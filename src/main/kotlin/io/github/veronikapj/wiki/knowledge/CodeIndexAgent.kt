package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.rag.ChromaClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

class CodeIndexAgent(
    private val codeClient: GitHubCodeClient,
    private val llmFn: suspend (String) -> String,
    private val chromaClient: ChromaClient,
    private val repos: List<String>,
    private val branch: String = "develop",
    private val collectionName: String = "code_index",
    private val embeddingFn: (suspend (String) -> List<Float>)? = null,
    /** 설정 시 GitHub API 대신 로컬 체크아웃에서 파일을 읽습니다. */
    private val localRepoPath: String? = null,
    private val localRepoSync: LocalRepoSync? = null,
    private val indexStateFile: String = ".wiki/code-index-state.json",
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
        val localRoot = localRepoPath?.let { File(it) }

        for (repo in repos) {
            val filePaths: List<String> = if (localRoot != null) {
                localRoot.walk()
                    .filter {
                        it.extension == "kt"
                            && !it.path.contains("/build/")
                            && !it.path.contains("/generated/")
                            && !it.name.contains("Test")
                    }
                    .map { it.relativeTo(localRoot).path }
                    .toList()
            } else {
                codeClient.fetchKotlinFilePaths(repo, branch).also { paths ->
                    if (paths.size > 500) log.warn(
                        "Large repo {}: {} files — significant GitHub API quota will be used", repo, paths.size,
                    )
                }
            }

            log.info(
                "Indexing {} Kotlin files from {} (source={})",
                filePaths.size, repo, if (localRoot != null) "local:$localRepoPath" else "github-api",
            )

            filePaths.chunked(5).forEach { batch ->
                val ids = mutableListOf<String>()
                val docs = mutableListOf<String>()
                val embeddings = mutableListOf<List<Float>>()
                val metas = mutableListOf<Map<String, String>>()

                batch.forEach { path ->
                    runCatching {
                        val content = if (localRoot != null)
                            File(localRoot, path).takeIf { it.exists() }?.readText()
                        else
                            codeClient.fetchFileContent(repo, path, branch)
                        content ?: return@forEach

                        val classes = extractClasses(content)
                        if (classes.isEmpty()) return@forEach

                        classes.forEach { cls ->
                            val baseDoc = buildIndexDocument(path, cls)
                            // embeddingFn が ある場合は LLM enrichment をスキップ (コスト削減)
                            val doc = if (embeddingFn != null) baseDoc
                                      else runCatching { llmFn(buildEnrichPrompt(path, cls)) }.getOrDefault(baseDoc)
                            ids += "$repo:$path:${cls.name}"
                            docs += doc
                            embeddingFn?.let { fn ->
                                embeddings += runCatching { fn(doc) }.getOrElse { emptyList() }
                            }
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
                    val embeds = embeddings.takeIf { it.size == ids.size }
                    chromaClient.upsertDocuments(collectionId, ids, docs, embeddings = embeds, metadatas = metas)
                }
                if (localRoot == null) kotlinx.coroutines.delay(100) // GitHub API rate-limit protection
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

    /**
     * 증분 인덱싱: 마지막 인덱싱 커밋 이후 변경된 .kt 파일만 재처리합니다.
     * 이전 상태가 없으면 indexAll()로 폴백합니다.
     * localRepoSync가 null이면 즉시 0을 반환합니다.
     */
    suspend fun syncAndIndexChanged(repo: String, branch: String = this.branch): Int {
        if (localRepoSync == null) {
            log.warn("syncAndIndexChanged called but localRepoSync is null — skipping")
            return 0
        }

        val state = loadIndexState()
        val lastCommit = state["lastCommit"]

        if (lastCommit == null) {
            log.info("No previous index state — running full index")
            val count = indexAll()
            saveIndexState(localRepoSync.currentCommit() ?: return count)
            return count
        }

        val changedFiles = localRepoSync.changedKtFiles(lastCommit)
        if (changedFiles.isEmpty()) {
            log.info("No changed .kt files since {} — skipping", lastCommit.take(8))
            return 0
        }

        log.info("Incremental index: {} changed files since {}", changedFiles.size, lastCommit.take(8))
        val count = indexFiles(changedFiles, repo)
        saveIndexState(localRepoSync.currentCommit() ?: lastCommit)
        return count
    }

    /**
     * 지정한 파일 경로 목록만 인덱싱합니다 (localRepoPath 기준 상대 경로).
     * 로컬 파일시스템에서 읽으므로 API rate-limit delay 없음.
     */
    private suspend fun indexFiles(filePaths: List<String>, repo: String): Int {
        val localRoot = localRepoPath?.let { File(it) }
            ?: run { log.warn("indexFiles called but localRepoPath is null"); return 0 }
        val collectionId = chromaClient.getOrCreateCollection(collectionName)
        var total = 0

        filePaths.chunked(5).forEach { batch ->
            val ids = mutableListOf<String>()
            val docs = mutableListOf<String>()
            val embeddings = mutableListOf<List<Float>>()
            val metas = mutableListOf<Map<String, String>>()

            batch.forEach { path ->
                runCatching {
                    val content = File(localRoot, path).takeIf { it.exists() }?.readText()
                        ?: return@forEach

                    val classes = extractClasses(content)
                    if (classes.isEmpty()) return@forEach

                    classes.forEach { cls ->
                        val baseDoc = buildIndexDocument(path, cls)
                        val doc = if (embeddingFn != null) baseDoc
                                  else runCatching { llmFn(buildEnrichPrompt(path, cls)) }.getOrDefault(baseDoc)
                        ids += "$repo:$path:${cls.name}"
                        docs += doc
                        embeddingFn?.let { fn ->
                            embeddings += runCatching { fn(doc) }.getOrElse { emptyList() }
                        }
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
                val embeds = embeddings.takeIf { it.size == ids.size }
                chromaClient.upsertDocuments(collectionId, ids, docs, embeddings = embeds, metadatas = metas)
            }
        }

        return total
    }

    private fun loadIndexState(): Map<String, String> {
        val file = File(indexStateFile)
        if (!file.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(file.readText())
        }.getOrDefault(emptyMap())
    }

    private fun saveIndexState(commitSha: String) {
        val file = File(indexStateFile)
        file.parentFile?.mkdirs()
        val tmp = File("$indexStateFile.tmp")
        tmp.writeText(Json.encodeToString(mapOf("lastCommit" to commitSha)))
        tmp.renameTo(file)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CodeIndexAgent::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }
}
