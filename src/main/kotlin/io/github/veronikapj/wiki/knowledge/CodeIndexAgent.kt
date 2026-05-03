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
    /** Step 3: BM25 키워드 검색 인덱스. null이면 벡터 검색만 사용. */
    private val bm25Index: BM25Index? = null,
) {

    /**
     * Step 2: 함수 단위 청킹 — Cursor 방식처럼 함수 하나가 하나의 ChromaDB 청크.
     * className이 빈 문자열이면 top-level 함수.
     */
    data class CodeChunk(
        val filePath: String,
        val className: String,      // "" = top-level function
        val functionName: String,
        val signature: String,      // "fun onBannerClick(bannerId: String): Unit"
        val body: String,           // 함수 바디, 최대 500자
        val packageName: String,
    )

    // ── 하위 호환 — extractClasses는 buildIndexDocument와 함께 유지 ─────────────
    data class KotlinClassInfo(
        val name: String,
        val kind: String,
        val packageName: String,
        val publicFunctions: List<String>,
        val firstLines: String,
    )

    // ── 인덱싱 진입점 ───────────────────────────────────────────────────────────

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

                        val chunks = extractFunctionChunks(content, path)
                        if (chunks.isEmpty()) return@forEach

                        chunks.forEach { chunk ->
                            val doc = buildChunkDocument(chunk)
                            val id = "$repo:${chunk.filePath}:${chunk.className}:${chunk.functionName}"
                            ids += id
                            docs += doc
                            embeddingFn?.let { fn ->
                                embeddings += runCatching { fn(doc) }.getOrElse { emptyList() }
                            }
                            metas += mapOf(
                                "repo" to repo,
                                "file_path" to chunk.filePath,
                                "class_name" to chunk.className,
                                "function_name" to chunk.functionName,
                                "branch" to branch,
                            )
                            bm25Index?.upsert(id, doc)  // Step 3: BM25 동시 저장
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

        log.info("Code index complete: {} function chunks", total)
        return total
    }

    /**
     * 증분 인덱싱: 마지막 인덱싱 커밋 이후 변경된 .kt 파일만 재처리합니다.
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

    // ── Step 2: 함수 단위 청크 추출 ────────────────────────────────────────────

    /**
     * 파일 내용에서 함수 단위 CodeChunk 목록을 추출합니다.
     *
     * - top-level과 클래스 내부 함수 모두 추출
     * - abstract/interface 선언 함수는 body = ""
     * - 함수 바디는 최대 500자로 잘라냄 (Cursor 기준)
     */
    internal fun extractFunctionChunks(content: String, filePath: String): List<CodeChunk> {
        val packageName = Regex("^package\\s+([\\w.]+)").find(content)?.groupValues?.get(1) ?: ""
        val chunks = mutableListOf<CodeChunk>()

        // 클래스 선언 위치 수집 — 함수가 어떤 클래스에 속하는지 판단용
        val classPattern = Regex(
            "^((?:data |sealed |abstract |open |enum )*(?:class|object|interface))\\s+(\\w+)",
            RegexOption.MULTILINE,
        )
        data class ClassPos(val name: String, val pos: Int)
        val classPositions = classPattern.findAll(content)
            .map { ClassPos(it.groupValues[2], it.range.first) }
            .toList()

        // 함수 시그니처 패턴
        // - 선택적 modifier: override/suspend/private/protected/internal/public/open/abstract/inline/operator
        // - 파라미터: 단일 라인만 지원 (Step 4 Tree-sitter에서 멀티라인 처리 예정)
        val funPattern = Regex(
            // (?:\w+\.)* = extension receiver 선택적 처리 (e.g. ViewModel.getString)
            """^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:override|suspend|private|protected|internal|public|open|abstract|final|inline|infix|operator|tailrec)\s+)*fun\s+(?:\w+\.)*(\w+)\s*(\([^)]*\))(?:\s*:\s*([^\n{=]+))?""",
            setOf(RegexOption.MULTILINE),
        )

        funPattern.findAll(content).forEach { match ->
            val functionName = match.groupValues[1]
            val params = match.groupValues[2]
            val returnType = match.groupValues[3].trim().trimEnd { it == '{' || it.isWhitespace() }

            val signature = buildString {
                append("fun ")
                append(functionName)
                append(params)
                if (returnType.isNotBlank()) {
                    append(": ")
                    append(returnType)
                }
            }

            // 이 함수보다 앞서 나온 클래스 선언 중 가장 마지막 = 이 함수가 속한 클래스
            val funcPos = match.range.first
            val className = classPositions.lastOrNull { it.pos <= funcPos }?.name ?: ""

            // 함수 바디 추출
            val afterSignature = content.substring(match.range.last + 1)
            val trimmed = afterSignature.trimStart(' ', '\t')
            val body = when {
                trimmed.startsWith("\n{") || trimmed.startsWith(" {") || trimmed.startsWith("{") -> {
                    val braceStart = trimmed.indexOf('{')
                    if (braceStart >= 0) extractBraceBlock(trimmed.substring(braceStart), maxChars = 500)
                    else ""
                }
                trimmed.trimStart('\n').startsWith("=") -> {
                    "= " + trimmed.trimStart('\n').removePrefix("=").trim().lines().first().take(498)
                }
                else -> "" // abstract 또는 interface 선언
            }

            chunks.add(
                CodeChunk(
                    filePath = filePath,
                    className = className,
                    functionName = functionName,
                    signature = signature,
                    body = body,
                    packageName = packageName,
                ),
            )
        }

        return chunks
    }

    /** 중괄호 블록 전체를 추출, maxChars 초과 시 잘라냄. */
    private fun extractBraceBlock(text: String, maxChars: Int): String {
        var depth = 0
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            if (sb.length >= maxChars) break
        }
        return sb.toString()
    }

    /** ChromaDB에 저장할 함수 단위 문서를 생성합니다. */
    internal fun buildChunkDocument(chunk: CodeChunk): String = buildString {
        appendLine("package ${chunk.packageName}")
        appendLine("file: ${chunk.filePath}")
        if (chunk.className.isNotBlank()) {
            appendLine("class: ${chunk.className}")
        }
        appendLine()
        if (chunk.body.isNotBlank()) {
            appendLine(chunk.signature)
            append(chunk.body)
            if (!chunk.body.trimEnd().endsWith("}") && !chunk.body.trimEnd().startsWith("=")) {
                appendLine()
            }
        } else {
            appendLine(chunk.signature) // abstract / interface 선언
        }
    }

    // ── 내부 유틸: 파일 목록 인덱싱 ───────────────────────────────────────────

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

                    val chunks = extractFunctionChunks(content, path)
                    if (chunks.isEmpty()) return@forEach

                    chunks.forEach { chunk ->
                        val doc = buildChunkDocument(chunk)
                        val id = "$repo:${chunk.filePath}:${chunk.className}:${chunk.functionName}"
                        ids += id
                        docs += doc
                        embeddingFn?.let { fn ->
                            embeddings += runCatching { fn(doc) }.getOrElse { emptyList() }
                        }
                        metas += mapOf(
                            "repo" to repo,
                            "file_path" to chunk.filePath,
                            "class_name" to chunk.className,
                            "function_name" to chunk.functionName,
                            "branch" to branch,
                        )
                        bm25Index?.upsert(id, doc)  // Step 3: BM25 동시 저장
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

    // ── 인덱스 상태 파일 ────────────────────────────────────────────────────────

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

    // ── 하위 호환 — 클래스 단위 추출 (이전 버전 / 단위 테스트 참조용) ──────────

    internal fun extractClasses(content: String): List<KotlinClassInfo> {
        val packageName = Regex("^package\\s+([\\w.]+)").find(content)?.groupValues?.get(1) ?: ""
        val classPattern = Regex(
            "^((?:data |sealed |abstract |open |enum )*(?:class|object|interface))\\s+(\\w+)",
            RegexOption.MULTILINE,
        )
        val funSignaturePattern = Regex("(?:suspend )?fun\\s+(\\w+\\([^)]*\\))(?:\\s*:\\s*(\\S+))?")
        val privateFunPattern = Regex("(?:private|protected)\\s+(?:suspend )?fun\\s+")

        return classPattern.findAll(content).map { match ->
            val kind = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val startIdx = match.range.first
            val classBlock = content.substring(startIdx).take(2000)

            val publicFunctions = classBlock.lines()
                .filter { line -> line.contains("fun ") && !privateFunPattern.containsMatchIn(line) }
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

    companion object {
        private val log = LoggerFactory.getLogger(CodeIndexAgent::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }
}
