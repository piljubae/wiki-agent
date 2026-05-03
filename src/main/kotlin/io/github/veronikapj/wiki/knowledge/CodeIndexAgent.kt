package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.rag.ChromaClient
import org.slf4j.LoggerFactory

class CodeIndexAgent(
    private val codeClient: GitHubCodeClient,
    private val llmFn: suspend (String) -> String,
    private val chromaClient: ChromaClient,
    private val repos: List<String>,
    private val branch: String = "develop",
    private val collectionName: String = "code_index",
) {

    data class KotlinClassInfo(
        val name: String,
        val kind: String,           // "class", "data class", "object", "interface", "sealed class", etc.
        val packageName: String,
        val publicFunctions: List<String>,
        val firstLines: String,
    )

    suspend fun indexAll(): Int {
        val collectionId = chromaClient.getOrCreateCollection(collectionName)
        var total = 0

        for (repo in repos) {
            val filePaths = codeClient.fetchKotlinFilePaths(repo, branch)
            if (filePaths.size > 500) {
                log.warn(
                    "Large repo {}: {} files to index — this will consume significant GitHub API quota",
                    repo,
                    filePaths.size,
                )
            }
            log.info("Indexing {} Kotlin files from {}", filePaths.size, repo)

            filePaths.chunked(5).forEach { batch ->
                val ids = mutableListOf<String>()
                val docs = mutableListOf<String>()
                val metas = mutableListOf<Map<String, String>>()

                batch.forEach { path ->
                    runCatching {
                        val content = codeClient.fetchFileContent(repo, path, branch) ?: return@forEach
                        val classes = extractClasses(content)
                        if (classes.isEmpty()) return@forEach

                        classes.forEach { cls ->
                            val baseDoc = buildIndexDocument(path, cls)
                            val enriched = runCatching { llmFn(buildEnrichPrompt(path, cls)) }.getOrDefault(baseDoc)
                            ids += "$repo:$path:${cls.name}"
                            docs += enriched
                            metas += mapOf(
                                "repo" to repo,
                                "file_path" to path,
                                "class_name" to cls.name,
                                "branch" to branch,
                            )
                            total++
                        }
                    }.onFailure { log.warn("Failed to index {}/{}: {}", repo, path, it.message) }
                }

                if (ids.isNotEmpty()) {
                    chromaClient.upsertDocuments(collectionId, ids, docs, metadatas = metas)
                }
                kotlinx.coroutines.delay(100) // rate-limit protection
            }
        }

        log.info("Code index complete: {} class entries", total)
        return total
    }

    internal fun extractClasses(content: String): List<KotlinClassInfo> {
        val packageName = Regex("^package\\s+([\\w.]+)").find(content)?.groupValues?.get(1) ?: ""
        // Matches top-level declarations only: line must start with the modifiers (no leading spaces)
        // (data |sealed |abstract |open )*(class|object|interface) Name
        val classPattern = Regex(
            "^((?:data |sealed |abstract |open |enum )*(?:class|object|interface))\\s+(\\w+)",
            RegexOption.MULTILINE,
        )
        // Matches public/suspend fun — captures name + params + optional return type
        val funSignaturePattern = Regex("(?:suspend )?fun\\s+(\\w+\\([^)]*\\))(?:\\s*:\\s*(\\S+))?")
        val privateFunPattern = Regex("(?:private|protected)\\s+(?:suspend )?fun\\s+")

        return classPattern.findAll(content).map { match ->
            val kind = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val startIdx = match.range.first
            val classBlock = content.substring(startIdx).take(2000)

            // Extract fun signatures, skip private/protected
            val publicFunctions = classBlock.lines()
                .filter { line ->
                    line.contains("fun ") && !privateFunPattern.containsMatchIn(line)
                }
                .mapNotNull { line ->
                    val m = funSignaturePattern.find(line) ?: return@mapNotNull null
                    val nameAndParams = m.groupValues[1]
                    val returnType = m.groupValues[2].trimEnd { it == '{' || it.isWhitespace() }
                    if (returnType.isNotBlank()) "$nameAndParams: $returnType" else nameAndParams
                }
                .filter { it.isNotBlank() }
                .take(10)
                .toList()

            KotlinClassInfo(
                name = name,
                kind = kind,
                packageName = packageName,
                publicFunctions = publicFunctions,
                firstLines = classBlock.lines().take(3).joinToString("\n"),
            )
        }.toList()
    }

    internal fun buildIndexDocument(filePath: String, cls: KotlinClassInfo): String = buildString {
        appendLine("package ${cls.packageName}")
        appendLine("file: $filePath")
        appendLine()
        appendLine("${cls.kind} ${cls.name}")
        if (cls.publicFunctions.isNotEmpty()) {
            appendLine("public functions:")
            cls.publicFunctions.forEach { appendLine("  fun $it") }
        }
    }

    private fun buildEnrichPrompt(filePath: String, cls: KotlinClassInfo): String = buildString {
        appendLine("Kotlin 클래스를 검색 최적화된 한국어 설명으로 변환하세요. 50자 이내.")
        appendLine("클래스: ${cls.name} (${cls.kind})")
        appendLine("파일: $filePath")
        appendLine("패키지: ${cls.packageName}")
        if (cls.publicFunctions.isNotEmpty()) {
            appendLine("주요 함수: ${cls.publicFunctions.take(5).joinToString(", ")}")
        }
        appendLine("출력: 한 문단, 한국어, 이 클래스가 무엇을 하는지 + 어디서 쓰이는지")
    }

    companion object {
        private val log = LoggerFactory.getLogger(CodeIndexAgent::class.java)
    }
}
