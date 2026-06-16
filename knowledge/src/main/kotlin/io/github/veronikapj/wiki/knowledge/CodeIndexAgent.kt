package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.search.BM25Index
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val className: String,
        val functionName: String,
        val signature: String,
        val body: String,
        val packageName: String,
        val chunkType: String = "function",
        val qualifiedName: String, // 추가
    )

    private data class ChunkEntry(
        val id: String,
        val doc: String,
        val meta: Map<String, String>,
    )

    // ... 내부에 extractFunctionChunks에서 qualifiedName 생성 로직 추가 필요 ...

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
            val filePaths: List<String> = if (localRoot != null && localRepoSync != null) {
                // git ls-files로 tracked 파일만 (.gitignore 자동 적용)
                localRepoSync.allKtFiles()
            } else if (localRoot != null) {
                localRoot.walk()
                    .filter {
                        it.extension == "kt"
                            && !it.path.contains("/build/")
                            && !it.path.contains("/generated/")
                            && !it.path.contains("/.gradle/")
                            && !it.path.contains("/kpds-compose/")
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

            filePaths.chunked(50).forEachIndexed { batchIdx, batch ->
                // Phase 1: 청크 수집 (순차, 빠름)
                val entries = mutableListOf<ChunkEntry>()
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
                            val id = chunkId(repo, chunk)
                            val sigHash = chunk.signature.hashCode().and(0xFFFFFF).toString(16)
                            entries += ChunkEntry(
                                id = id,
                                doc = doc,
                                meta = mapOf(
                                    "repo" to repo,
                                    "file_path" to chunk.filePath,
                                    "class_name" to chunk.className,
                                    "function_name" to chunk.functionName,
                                    "sig_hash" to sigHash,
                                    "branch" to branch,
                                    "chunk_type" to chunk.chunkType,
                                ),
                            )
                            bm25Index?.upsert(id, doc, chunk.filePath)
                            total++
                        }
                    }.onFailure { log.warn("Failed to read {}/{}: {}", repo, path, it.message) }
                }

                if (entries.isEmpty()) return@forEachIndexed

                // Phase 2: 이미 인덱싱된 ID 확인 → 새 것만 임베딩
                val existingIds = chromaClient.getExistingIds(collectionId, entries.map { it.id })
                val newEntries = entries.filter { it.id !in existingIds }
                log.debug("Batch {}: {} total, {} cached, {} new", batchIdx, entries.size, existingIds.size, newEntries.size)

                val embeddings: List<List<Float>> = if (embeddingFn != null && newEntries.isNotEmpty()) {
                    coroutineScope {
                        newEntries.map { entry ->
                            async {
                                embeddingSemaphore.withPermit {
                                    runCatching { embeddingFn(entry.doc) }.getOrElse { emptyList() }
                                }
                            }
                        }.awaitAll()
                    }
                } else emptyList()

                // Phase 3: ChromaDB upsert (새 항목 + 빈 임베딩 제외)
                val validPairs = if (embeddings.size == newEntries.size) {
                    newEntries.zip(embeddings).filter { (_, emb) -> emb.isNotEmpty() }
                } else emptyList()

                if (validPairs.isEmpty()) {
                    if (batchIdx % 10 == 9) log.info(
                        "Progress: ~{}/{} files", minOf((batchIdx + 1) * 50, filePaths.size), filePaths.size,
                    )
                    return@forEachIndexed
                }
                if (validPairs.size < newEntries.size) {
                    log.warn("Skipped {} chunks with empty embeddings (batch {})", newEntries.size - validPairs.size, batchIdx)
                }
                chromaClient.upsertDocuments(
                    collectionId,
                    validPairs.map { it.first.id },
                    validPairs.map { it.first.doc },
                    embeddings = validPairs.map { it.second },
                    metadatas = validPairs.map { it.first.meta },
                )
                if (localRoot == null) kotlinx.coroutines.delay(100)
                if (batchIdx % 10 == 9) log.info(
                    "Progress: ~{}/{} files", minOf((batchIdx + 1) * 50, filePaths.size), filePaths.size,
                )
            }
        }

        log.info("Code index complete: {} function chunks", total)
        return total
    }

    /**
     * 증분 인덱싱: 마지막 인덱싱 커밋 이후 변경된 .kt 파일만 재처리합니다.
     * 삭제된 파일의 청크는 ChromaDB와 BM25에서 제거하고,
     * 수정된 파일은 stale 청크 삭제 후 재인덱싱합니다.
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

        val diff = localRepoSync.diffKtFiles(lastCommit)
        if (diff.modified.isEmpty() && diff.deleted.isEmpty()) {
            log.info("No changed .kt files since {} — skipping", lastCommit.take(8))
            return 0
        }

        val collectionId = chromaClient.getOrCreateCollection(collectionName)

        // 1. 삭제된 파일 — ChromaDB + BM25에서 청크 제거
        if (diff.deleted.isNotEmpty()) {
            log.info("Removing chunks for {} deleted files", diff.deleted.size)
            diff.deleted.forEach { filePath ->
                val ids = chromaClient.getIdsByFilePath(collectionId, repo, filePath)
                if (ids.isNotEmpty()) {
                    chromaClient.deleteByIds(collectionId, ids)
                    log.info("Deleted {} chunks for removed file: {}", ids.size, filePath)
                }
                bm25Index?.deleteByFilePath(filePath)
            }
        }

        // 2. 수정/추가된 파일 — stale 청크 제거 후 재인덱싱
        val count = if (diff.modified.isNotEmpty()) {
            log.info("Incremental index: {} modified files since {}", diff.modified.size, lastCommit.take(8))
            indexFilesWithStaleDeletion(diff.modified, repo, collectionId)
        } else 0

        saveIndexState(localRepoSync.currentCommit() ?: lastCommit)
        return count
    }

    // ── Step 4: 라인 기반 파서 (멀티라인 파라미터 + 중첩 클래스 스택) ──────────

    /**
     * 파일 내용에서 함수 단위 CodeChunk 목록을 추출합니다.
     *
     * Step 4 개선 사항 (Tree-sitter 등가, 순수 JVM):
     * - 중괄호 깊이 스택으로 정확한 className 추적 (companion object, 중첩 클래스)
     * - 괄호 깊이 카운팅으로 멀티라인 파라미터 지원
     * - extension receiver 처리 (ViewModel.getString 등)
     * - abstract/interface 선언 함수 (body = "")
     * - 함수 바디 최대 500자
     */
    internal fun extractFunctionChunks(content: String, filePath: String): List<CodeChunk> {
        val packageName = Regex("^package\\s+([\\w.]+)").find(content)?.groupValues?.get(1) ?: ""
        val chunks = mutableListOf<CodeChunk>()
        val lines = content.lines()

        // 클래스 선언 감지: class/object/interface/companion object
        val classLinePattern = Regex(
            """^[ \t]*(?:(?:data|sealed|abstract|open|enum|inner|private|internal)\s+)*(?:class|object|interface)\s+(\w+)""",
        )
        val companionPattern = Regex("""^[ \t]*companion\s+object(?:\s+\w+)?""")

        // 함수 선언 시작 감지 (파라미터 열린 괄호까지)
        val funStartPattern = Regex(
            """^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:override|suspend|private|protected|internal|public|open|abstract|final|inline|infix|operator|tailrec)\s+)*fun\s+(?:\w+\.)*(\w+)\s*\(""",
        )

        // const val (이름 무관) 또는 val SCREAMING_SNAKE_CASE 프로퍼티 감지
        // const val: 딥링크 스킴, API 경로, 상수값 검색용
        val propertyPattern = Regex(
            """^[ \t]*(?:(?:private|protected|internal|public|override|actual|expect|open|abstract|final)\s+)*(?:(const)\s+)?val\s+([A-Za-z_]\w*)\b""",
        )

        // className 스택: Pair(name, braceDepth) — 진입 시점의 누적 중괄호 깊이를 기록
        data class ClassFrame(val name: String, val entryDepth: Int)
        val classStack = ArrayDeque<ClassFrame>()
        var braceDepth = 0
        var pendingDoc = "" // 직전 KDoc 주석 (/** ... */), 다음 fun에 첨부

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // KDoc 주석 수집 (/** ... */) — @return, @param 등 스킴/파라미터 정보 캡처
            if (trimmed.startsWith("/**")) {
                val docLines = mutableListOf<String>()
                docLines.add(trimmed)
                i++
                while (i < lines.size) {
                    val docLine = lines[i].trimStart()
                    docLines.add(docLine)
                    if (docLine.contains("*/")) { i++; break }
                    i++
                }
                pendingDoc = docLines.joinToString("\n")
                    .let { if (it.length > 400) it.take(397) + "..." else it }
                continue
            }

            // 일반 주석 라인 스킵 (// 또는 KDoc 내부 *)
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                braceDepth += line.count { it == '{' } - line.count { it == '}' }
                i++; continue
            }

            // companion object 우선 체크 (class 패턴보다 먼저)
            if (companionPattern.containsMatchIn(line)) {
                pendingDoc = ""
                val openCount = line.count { it == '{' }
                braceDepth += openCount
                classStack.addLast(ClassFrame("(companion)", braceDepth))
                braceDepth -= line.count { it == '}' }
                // 닫힌 프레임 정리
                while (classStack.isNotEmpty() && braceDepth < classStack.last().entryDepth) {
                    classStack.removeLast()
                }
                i++; continue
            }

            // class/object/interface 선언
            val classMatch = classLinePattern.find(line)
            if (classMatch != null) {
                pendingDoc = ""
                val openCount = line.count { it == '{' }
                braceDepth += openCount
                classStack.addLast(ClassFrame(classMatch.groupValues[1], braceDepth))
                braceDepth -= line.count { it == '}' }
                while (classStack.isNotEmpty() && braceDepth < classStack.last().entryDepth) {
                    classStack.removeLast()
                }
                i++; continue
            }

            // 함수 선언 시작
            val funMatch = funStartPattern.find(line)
            if (funMatch != null) {
                val functionName = funMatch.groupValues[1]

                // 멀티라인 파라미터 수집: ')' 가 닫힐 때까지 라인 병합
                val sigLines = mutableListOf<String>()
                var j = i
                var parenDepth = 0
                while (j < lines.size) {
                    val sigLine = lines[j]
                    sigLines.add(if (j == i) sigLine.trimStart() else sigLine.trim())
                    parenDepth += sigLine.count { it == '(' } - sigLine.count { it == ')' }
                    if (parenDepth <= 0) break
                    j++
                }

                // 반환 타입 — 파라미터 다음 줄에 ": Type" 형태로 올 수 있음
                var returnType = ""
                val sigJoined = sigLines.joinToString(" ")
                val afterParen = sigJoined.substringAfter(")", "")
                val rtMatch = Regex("""^\s*:\s*([^\n{=]+)""").find(afterParen)
                if (rtMatch != null) {
                    returnType = rtMatch.groupValues[1].trim().trimEnd { it == '{' || it.isWhitespace() }
                } else if (j + 1 < lines.size) {
                    // 반환 타입이 다음 줄에 있는 경우: ): ReturnType {
                    val nextLine = lines[j + 1].trim()
                    val rtNext = Regex("""^:\s*([^\n{=]+)""").find(nextLine)
                    if (rtNext != null) {
                        returnType = rtNext.groupValues[1].trim().trimEnd { it == '{' || it.isWhitespace() }
                        j++
                    }
                }

                val signature = buildString {
                    // 파라미터만 단일 라인으로 요약
                    val paramsRaw = sigJoined.substringAfter("(").substringBefore(")")
                    val paramsShort = if (paramsRaw.length > 120) paramsRaw.lines()
                        .joinToString(", ") { it.trim() }.take(120) + "…"
                    else paramsRaw
                    append("fun $functionName($paramsShort)")
                    if (returnType.isNotBlank()) append(": $returnType")
                }

                // className: 스택 최상위 (companion은 내부용 이름)
                val className = classStack.lastOrNull { it.name != "(companion)" }?.name
                    ?: classStack.lastOrNull()?.name ?: ""
                
                val qualifiedName = if (packageName.isNotBlank()) "$packageName.${className.takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()}$functionName"
                    else "${className.takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()}$functionName"

                // 바디 추출 — j 이후 줄에서 시작
                val afterSigOffset = lines.take(j + 1).sumOf { it.length + 1 }.coerceAtMost(content.length)
                val afterSig = content.substring(afterSigOffset).trimStart(' ', '\t', '\n', '\r')
                val rawBody = when {
                    afterSig.startsWith("{") -> extractBraceBlock(afterSig, maxChars = BODY_MAX_CHARS)
                    afterSig.startsWith("=") -> "= " + afterSig.removePrefix("=").trim().take(BODY_MAX_CHARS - 2)
                    else -> ""
                }
                // KDoc 주석이 있으면 body 앞에 첨부 (스킴, 파라미터 설명 등 포함)
                val body = if (pendingDoc.isNotBlank()) "$pendingDoc\n$rawBody" else rawBody
                pendingDoc = ""

                chunks.add(
                    CodeChunk(
                        filePath = filePath,
                        className = className,
                        functionName = functionName,
                        signature = signature,
                        body = body,
                        packageName = packageName,
                        qualifiedName = qualifiedName,
                    ),
                )

                // 중괄호 깊이 업데이트 (시그니처 범위만큼)
                for (k in i..j) {
                    braceDepth += lines[k].count { it == '{' } - lines[k].count { it == '}' }
                }
                while (classStack.isNotEmpty() && braceDepth < classStack.last().entryDepth) {
                    classStack.removeLast()
                }
                i = j + 1
                continue
            }

            // 상수 프로퍼티 추출: const val (이름 무관) 또는 val SCREAMING_SNAKE_CASE
            val propMatch = propertyPattern.find(line)
            if (propMatch != null) {
                val isConst = propMatch.groupValues[1].isNotBlank()
                val propName = propMatch.groupValues[2]
                val isScreamingCase = propName.matches(Regex("[A-Z][A-Z0-9_]+"))
                // const val은 이름 무관 항상 인덱싱, 일반 val은 SCREAMING_CASE만
                if (isConst || isScreamingCase) {
                    val valueStartIdx = line.indexOf('=')
                    val rawValue = if (valueStartIdx >= 0) {
                        line.substring(valueStartIdx + 1).trim()
                            .let { if (it.length > 200) it.take(197) + "..." else it }
                    } else ""
                    val typeMatch = Regex("""val\s+\w+\s*:\s*([^\s=,<>]+(?:<[^>]+>)?)""").find(line)
                    val typeAnnotation = typeMatch?.groupValues?.get(1)
                    val signature = buildString {
                        if (isConst) append("const ")
                        append("val $propName")
                        if (!typeAnnotation.isNullOrBlank()) append(": $typeAnnotation")
                        if (rawValue.isNotBlank()) append(" = $rawValue")
                    }
                    val className = classStack.lastOrNull { it.name != "(companion)" }?.name
                        ?: classStack.lastOrNull()?.name ?: ""
                    
                    val qualifiedName = if (packageName.isNotBlank()) "$packageName.${className.takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()}$propName"
                        else "${className.takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()}$propName"

                    chunks.add(CodeChunk(
                        filePath = filePath,
                        className = className,
                        functionName = propName,
                        signature = signature,
                        body = "",
                        packageName = packageName,
                        chunkType = "property",
                        qualifiedName = qualifiedName,
                    ))
                }
            }

            // 일반 라인: 중괄호 깊이만 업데이트
            braceDepth += line.count { it == '{' } - line.count { it == '}' }
            while (classStack.isNotEmpty() && braceDepth < classStack.last().entryDepth) {
                classStack.removeLast()
            }
            i++
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

    /**
     * 수정된 파일 인덱싱 — 파일 내 삭제된 함수의 stale 청크를 먼저 제거 후 upsert.
     * 변경 없는 함수는 getExistingIds cache hit으로 재임베딩 없음.
     */
    private suspend fun indexFilesWithStaleDeletion(
        filePaths: List<String>,
        repo: String,
        collectionId: String,
    ): Int {
        val localRoot = localRepoPath?.let { File(it) }
            ?: run { log.warn("indexFilesWithStaleDeletion called but localRepoPath is null"); return 0 }
        var total = 0

        filePaths.chunked(50).forEach { batch ->
            // Phase 1: 청크 수집 + stale ID 계산
            val entries = mutableListOf<ChunkEntry>()
            batch.forEach { path ->
                runCatching {
                    val content = File(localRoot, path).takeIf { it.exists() }?.readText()
                        ?: run {
                            bm25Index?.deleteByFilePath(path)
                            return@forEach
                        }

                    val chunks = extractFunctionChunks(content, path)
                    if (chunks.isEmpty()) return@forEach

                    // stale 청크 제거: 현재 ChromaDB IDs - 새 파싱 IDs
                    val newIds = chunks.map { chunkId(repo, it) }.toSet()
                    val existingIds = chromaClient.getIdsByFilePath(collectionId, repo, path)
                    val staleIds = existingIds.filter { it !in newIds }
                    if (staleIds.isNotEmpty()) {
                        chromaClient.deleteByIds(collectionId, staleIds)
                        staleIds.forEach { bm25Index?.delete(it) }
                        log.info("Removed {} stale chunks from {}", staleIds.size, path)
                    }

                    chunks.forEach { chunk ->
                        val doc = buildChunkDocument(chunk)
                        val id = chunkId(repo, chunk)
                        val sigHash = chunk.signature.hashCode().and(0xFFFFFF).toString(16)
                        entries += ChunkEntry(
                            id = id,
                            doc = doc,
                            meta = mapOf(
                                "repo" to repo,
                                "file_path" to chunk.filePath,
                                "class_name" to chunk.className,
                                "function_name" to chunk.functionName,
                                "sig_hash" to sigHash,
                                "branch" to branch,
                                "chunk_type" to chunk.chunkType,
                            ),
                        )
                        bm25Index?.upsert(id, doc, chunk.filePath)
                        total++
                    }
                }.onFailure { log.warn("Failed to read {}/{}: {}", repo, path, it.message) }
            }

            if (entries.isEmpty()) return@forEach

            // Phase 2: 이미 인덱싱된 ID 확인 → 새 것만 임베딩 (재임베딩 없음)
            val existingIds = chromaClient.getExistingIds(collectionId, entries.map { it.id })
            val newEntries = entries.filter { it.id !in existingIds }

            val embeddings: List<List<Float>> = if (embeddingFn != null && newEntries.isNotEmpty()) {
                coroutineScope {
                    newEntries.map { entry ->
                        async {
                            embeddingSemaphore.withPermit {
                                runCatching { embeddingFn(entry.doc) }.getOrElse { emptyList() }
                            }
                        }
                    }.awaitAll()
                }
            } else emptyList()

            val validPairs = if (embeddings.size == newEntries.size) {
                newEntries.zip(embeddings).filter { (_, emb) -> emb.isNotEmpty() }
            } else emptyList()

            if (validPairs.isEmpty()) return@forEach
            chromaClient.upsertDocuments(
                collectionId,
                validPairs.map { it.first.id },
                validPairs.map { it.first.doc },
                embeddings = validPairs.map { it.second },
                metadatas = validPairs.map { it.first.meta },
            )
        }

        return total
    }

    private suspend fun indexFiles(filePaths: List<String>, repo: String): Int {
        val localRoot = localRepoPath?.let { File(it) }
            ?: run { log.warn("indexFiles called but localRepoPath is null"); return 0 }
        val collectionId = chromaClient.getOrCreateCollection(collectionName)
        var total = 0

        filePaths.chunked(50).forEach { batch ->
            // Phase 1: 청크 수집
            val entries = mutableListOf<ChunkEntry>()
            batch.forEach { path ->
                runCatching {
                    val content = File(localRoot, path).takeIf { it.exists() }?.readText()
                        ?: return@forEach

                    val chunks = extractFunctionChunks(content, path)
                    if (chunks.isEmpty()) return@forEach

                    chunks.forEach { chunk ->
                        val doc = buildChunkDocument(chunk)
                        val id = chunkId(repo, chunk)
                        val sigHash = chunk.signature.hashCode().and(0xFFFFFF).toString(16)
                        entries += ChunkEntry(
                            id = id,
                            doc = doc,
                            meta = mapOf(
                                "repo" to repo,
                                "file_path" to chunk.filePath,
                                "class_name" to chunk.className,
                                "function_name" to chunk.functionName,
                                "sig_hash" to sigHash,
                                "branch" to branch,
                                "chunk_type" to chunk.chunkType,
                            ),
                        )
                        bm25Index?.upsert(id, doc, chunk.filePath)
                        total++
                    }
                }.onFailure { log.warn("Failed to read {}/{}: {}", repo, path, it.message) }
            }

            if (entries.isEmpty()) return@forEach

            // Phase 2: 이미 인덱싱된 ID 확인 → 새 것만 임베딩
            val existingIds = chromaClient.getExistingIds(collectionId, entries.map { it.id })
            val newEntries = entries.filter { it.id !in existingIds }

            val embeddings: List<List<Float>> = if (embeddingFn != null && newEntries.isNotEmpty()) {
                coroutineScope {
                    newEntries.map { entry ->
                        async {
                            embeddingSemaphore.withPermit {
                                runCatching { embeddingFn(entry.doc) }.getOrElse { emptyList() }
                            }
                        }
                    }.awaitAll()
                }
            } else emptyList()

            // Phase 3: ChromaDB upsert (새 항목 + 빈 임베딩 제외)
            val validPairs = if (embeddings.size == newEntries.size) {
                newEntries.zip(embeddings).filter { (_, emb) -> emb.isNotEmpty() }
            } else emptyList()

            if (validPairs.isEmpty()) return@forEach
            if (validPairs.size < newEntries.size) {
                log.warn("Skipped {} chunks with empty embeddings", newEntries.size - validPairs.size)
            }
            chromaClient.upsertDocuments(
                collectionId,
                validPairs.map { it.first.id },
                validPairs.map { it.first.doc },
                embeddings = validPairs.map { it.second },
                metadatas = validPairs.map { it.first.meta },
            )
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

        // 인덱싱 시 청크에 저장할 함수 본문 최대 글자 수.
        // CodeSearchTool.TOP_BODY_LIMIT(노출 상한)과 맞춰 본문이 잘리지 않게 한다.
        // 변경 시 전체 재인덱싱 필요(기존 청크는 옛 상한으로 저장돼 있음).
        internal const val BODY_MAX_CHARS = 3000
        private val embeddingSemaphore = Semaphore(15) // Gemini 무료 1500RPM 기준: 15 × ~70req/s 이내

        /**
         * 청크 ID — 오버로드 함수 구별을 위해 시그니처 해시 6자리를 suffix로 붙임.
         * 예: "thefarmersfront/kurly-android:path/Foo.kt:FooViewModel:load:1a2b3c"
         *
         * CodeSearchTool.resultToId()와 포맷을 맞춰야 합니다.
         */
        fun chunkId(repo: String, chunk: CodeChunk): String {
            val sigHash = chunk.signature.hashCode().and(0xFFFFFF).toString(16)
            return "$repo:${chunk.filePath}:${chunk.className}:${chunk.functionName}:$sigHash"
        }
    }
}
