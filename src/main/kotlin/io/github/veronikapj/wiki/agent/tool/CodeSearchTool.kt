package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.github.GithubCodeResult
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.knowledge.BM25Index
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

class CodeSearchTool(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val codeClient: GitHubCodeClient,
    private val codeRepos: List<String>,
    private val branch: String = "develop",
    private val tracker: SourceTracker? = null,
    private val collectionName: String = "code_index",
    private val bm25Index: BM25Index? = null,
    private val embeddingFn: (suspend (String) -> List<Float>)? = null,
    private val localRepoPath: String? = null,
) {

    @Tool("codeSearch")
    @LLMDescription(
        "Kurly Android 소스코드에서 클래스, 함수, 구현 위치를 검색합니다. " +
        "'ProductViewModel 어디있어?', 'panelCode 어디서 쓰여?', '배너 클릭 이벤트 구현 방법' 같은 질문에 사용하세요. " +
        "PR 변경 이력이나 작업 내용은 prHistory를 사용하세요."
    )
    fun codeSearch(
        @LLMDescription("검색할 클래스명, 함수명, 또는 기능 설명. 예: BannerViewModel, panelCode, 배너 클릭 이벤트")
        query: String,
    ): String = runBlocking {
        tracker?.record("CodeSearch")

        val expandedQuery = runCatching { llmExpandClient?.expandQuery(query) }.getOrNull() ?: query
        log.info("codeSearch: query='{}', expandedQuery='{}'", query.take(80), expandedQuery.take(200))
        log.info("codeSearch: embeddingFn={}, bm25Index={}, localRepoPath={}",
            if (embeddingFn != null) "available" else "null",
            if (bm25Index != null) "available" else "null",
            localRepoPath ?: "null")

        // 1차: ChromaDB 벡터 + BM25 + grep
        val localResult = runCatching {
            val collectionId = chromaClient.getOrCreateCollection(collectionName)

            // 벡터 검색 — 이 컬렉션은 서버 내장 임베딩 함수 없음, 반드시 queryEmbeddings 필요
            val vectorResults = if (embeddingFn != null) {
                runCatching {
                    val embedding = embeddingFn(expandedQuery)
                    chromaClient.query(collectionId, queryEmbeddings = listOf(embedding), nResults = 10)
                }.getOrElse { e ->
                    log.warn("Embedding failed, skipping vector search — BM25+grep will handle: {}", e.message)
                    emptyList()
                }
            } else {
                log.warn("embeddingFn is null — vector search disabled for collection '{}'", collectionName)
                emptyList()
            }

            fun resultToId(r: io.github.veronikapj.wiki.rag.ChromaQueryResult): String {
                val repo    = r.metadata["repo"] ?: ""
                val path    = r.metadata["file_path"] ?: ""
                val cls     = r.metadata["class_name"] ?: ""
                val fn      = r.metadata["function_name"] ?: ""
                val sigHash = r.metadata["sig_hash"] ?: ""
                return "$repo:$path:$cls:$fn:$sigHash"
            }

            val vectorIds = vectorResults.map { resultToId(it) }
            val metaById  = vectorResults.associateBy { resultToId(it) }

            // BM25 키워드 검색 + RRF 병합 (벡터 결과 없으면 BM25 단독 사용)
            val orderedIds = if (bm25Index != null) {
                val bm25Ids = bm25Index.search(query, limit = 10)
                log.info("BM25: {} results (vector: {})", bm25Ids.size, vectorIds.size)
                if (vectorIds.isEmpty()) bm25Ids  // embedding 실패 시 BM25 단독
                else BM25Index.mergeRRF(vectorIds, bm25Ids)
            } else {
                log.info("BM25: index null, using vector only ({} results)", vectorIds.size)
                vectorIds
            }

            val topIds = orderedIds.take(5)

            val chromaResult = if (topIds.isNotEmpty()) {
                buildString {
                    val searchType = if (bm25Index != null) "하이브리드(벡터+BM25)" else "벡터"
                    appendLine("*\"$query\"* 관련 코드 [$searchType, ${topIds.size}건]:\n")
                    topIds.forEachIndexed { i, id ->
                        val r = metaById[id]
                        val repo = r?.metadata?.get("repo") ?: id.substringBefore(":")
                        val filePath = r?.metadata?.get("file_path") ?: ""
                        val className = r?.metadata?.get("class_name") ?: ""
                        val functionName = r?.metadata?.get("function_name") ?: ""
                        val chunkType = r?.metadata?.get("chunk_type") ?: "function"
                        val label = when {
                            functionName.isBlank() -> className
                            chunkType == "property" ->
                                if (className.isNotBlank()) "$className.$functionName" else functionName
                            else ->
                                if (className.isNotBlank()) "$className.$functionName()" else "$functionName()"
                        }
                        appendLine("${i + 1}. `$label` — $filePath")
                        if (repo.isNotBlank() && filePath.isNotBlank()) {
                            appendLine("   <https://github.com/$repo/blob/$branch/$filePath|소스 보기>")
                        }
                        r?.let { appendLine("   > ${it.document.lines().take(8).joinToString(" ").take(500)}") }
                        appendLine()
                    }
                }.trim()
            } else null

            // 로컬 grep 보완: expandedQuery에서 식별자 추출 → 한글 질문도 영문 코드 용어로 grep 가능
            val grepPatterns = buildGrepPatterns(expandedQuery)
            log.info("grep patterns from expandedQuery: {}", grepPatterns.take(10))
            val grepSection = localRepoPath
                ?.takeIf { grepPatterns.isNotEmpty() }
                ?.let { repoPath ->
                    val keywords = extractQueryKeywords(expandedQuery)
                    log.info("grep: localRepoPath={}, keywords={}, patterns={}", repoPath, keywords, grepPatterns)
                    val hits = grepLocalRepo(
                        repoDir = File(repoPath),
                        patterns = grepPatterns,
                        priorityKeywords = keywords,
                        maxHits = 5,
                    )
                    log.info("grep hits: {}", hits.size)
                    if (hits.isNotEmpty()) buildString {
                        appendLine("*직접 grep 결과 [${hits.size}건]:*\n")
                        hits.forEachIndexed { i, (path, lineNo, content) ->
                            val isTest = path.contains("/test/")
                            val label = if (isTest) "$path:$lineNo _(테스트)_" else "$path:$lineNo"
                            appendLine("${i + 1}. `$label`")
                            appendLine("   > `${content.take(200)}`")
                            appendLine()
                        }
                    }.trim() else null
                }

            // ChromaDB + grep 결과 병합 (둘 다 있으면 함께, 하나만 있으면 그것만)
            log.info("localResult: chromaResult={}, grepSection={}",
                if (chromaResult != null) "${chromaResult.length}chars" else "null",
                if (grepSection != null) "${grepSection.length}chars" else "null")
            listOfNotNull(chromaResult, grepSection).joinToString("\n\n").takeIf { it.isNotBlank() }
        }.onFailure { e ->
            log.warn("ChromaDB/grep search failed, falling back to GitHub API: {}", e.message)
        }.getOrNull()

        if (!localResult.isNullOrBlank()) return@runBlocking localResult
        log.info("localResult empty → falling back to GitHub Code Search")

        // 2차: GitHub Code Search API fallback — expandedQuery에서 영문 식별자만 추출
        val codeSearchKeywords = extractEnglishKeywords(expandedQuery)
        if (codeSearchKeywords.isBlank()) return@runBlocking "관련 코드를 찾을 수 없습니다."

        // 1차: 전체 AND → 0건이면 3개씩 청크 분할 재시도 (순서 유지 = 의미 클러스터)
        val apiResults = searchGitHubWithChunkedFallback(codeSearchKeywords, codeRepos, branch)

        if (apiResults.isEmpty()) return@runBlocking "관련 코드를 찾을 수 없습니다."

        buildString {
            appendLine("*\"$query\"* 관련 코드 (GitHub Search):\n")
            apiResults.forEachIndexed { i, r ->
                appendLine("${i + 1}. `${r.filePath}`")
                appendLine("   <${r.htmlUrl}|소스 보기>")
                appendLine()
            }
        }.trim()
    }

    companion object {
        private val log = LoggerFactory.getLogger(CodeSearchTool::class.java)

        private val GITHUB_SEARCH_STOPWORDS = setOf(
            "query", "search", "find", "where", "what", "how", "list", "show",
            "display", "get", "set", "add", "remove", "update", "delete",
            "configure", "enumerate", "fetch",
        )

        /** expandedQuery에서 영문 키워드만 추출 → GitHub Code Search용 */
        internal fun extractEnglishKeywords(expandedQuery: String): String {
            return expandedQuery.split("\\s+".toRegex())
                .filter { it.matches(Regex("[a-zA-Z][a-zA-Z0-9._-]*")) && it.length >= 3 }
                .filterNot { it.lowercase() in GITHUB_SEARCH_STOPWORDS }
                .distinct()
                .take(8)
                .joinToString(" ")
        }
    }

    /**
     * GitHub Code Search: 전체 AND → 0건이면 3개씩 청크 분할 재시도.
     * 키워드 순서 유지 = expandQuery의 자연 순서가 의미 클러스터를 형성.
     */
    private suspend fun searchGitHubWithChunkedFallback(
        keywords: String,
        repos: List<String>,
        branch: String,
    ): List<GithubCodeResult> {
        // 1차: 전체 키워드 AND
        log.info("GitHub Code Search [full]: {}", keywords)
        val fullResults = repos.flatMap { repo ->
            runCatching { codeClient.searchCode(repo, keywords, branch) }
                .onFailure { e -> log.warn("GitHub Code Search failed for {}: {}", repo, e.message) }
                .getOrDefault(emptyList())
        }
        if (fullResults.isNotEmpty()) {
            log.info("GitHub Code Search [full]: {} results", fullResults.size)
            return fullResults.take(5)
        }

        // 2차: 3개씩 청크 분할 → 각각 검색 후 병합 (최대 3청크)
        val words = keywords.split(" ")
        if (words.size <= 3) return emptyList()  // 이미 3개 이하면 재시도 무의미

        val chunks = words.chunked(3).take(3).map { it.joinToString(" ") }
        log.info("GitHub Code Search [chunked]: {}", chunks)

        val seen = mutableSetOf<String>()
        val merged = mutableListOf<GithubCodeResult>()
        for (chunk in chunks) {
            val chunkResults = repos.flatMap { repo ->
                runCatching { codeClient.searchCode(repo, chunk, branch) }
                    .onFailure { e -> log.warn("GitHub Code Search chunk failed for {}: {}", repo, e.message) }
                    .getOrDefault(emptyList())
            }
            for (r in chunkResults) {
                if (seen.add(r.filePath)) merged.add(r)
            }
        }
        log.info("GitHub Code Search [chunked]: {} results (merged)", merged.size)
        return merged.take(5)
    }

    private val schemeKeywords = setOf("스킴", "scheme", "딥링크", "deeplink", "deep link", "딥 링크")

    private fun isSchemeQuery(query: String) =
        schemeKeywords.any { query.contains(it, ignoreCase = true) }

    /**
     * 쿼리 타입에 따라 grep 패턴 목록 동적 생성.
     * - 스킴/딥링크 → URL 패턴
     * - PascalCase  → 클래스·인터페이스명 직접 매칭
     * - camelCase   → 함수·변수명 직접 매칭
     * - snake_case  → 식별자 직접 매칭
     * - @Annotation → 어노테이션 패턴
     * - SCREAMING_CASE → 상수명
     */
    private fun buildGrepPatterns(query: String): List<String> {
        val patterns = mutableListOf<String>()

        if (isSchemeQuery(query)) {
            patterns += "@return[^\\n]*://"
            patterns += "kurly://"
        }

        // PascalCase 식별자 (대문자 시작, 3자 이상)
        Regex("[A-Z][a-zA-Z0-9]{2,}").findAll(query).map { it.value }.forEach { patterns += it }

        // camelCase 식별자 (소문자 시작, 중간 대문자 포함)
        Regex("[a-z][a-z0-9]*[A-Z][a-zA-Z0-9]+").findAll(query).map { it.value }.forEach { patterns += it }

        // snake_case 식별자 (소문자+언더스코어, 3자 이상)
        Regex("[a-z0-9_]{2,}_[a-z0-9_]+").findAll(query).map { it.value }.forEach { patterns += it }

        // 어노테이션 (@Xxx)
        Regex("@[A-Z][a-zA-Z0-9]+").findAll(query).map { it.value }.forEach { patterns += it }

        // SCREAMING_CASE 상수 (대문자+언더스코어, 3자 이상)
        Regex("[A-Z][A-Z0-9_]{2,}").findAll(query).map { it.value }.forEach { patterns += it }

        // 단일 단어가 식별자처럼 보일 때 (영문+숫자 4자 이상)
        if (patterns.isEmpty()) {
            Regex("[a-zA-Z][a-zA-Z0-9]{3,}").findAll(query).map { it.value }
                .filterNot { it.lowercase() in setOf("query", "search", "find", "where", "what", "how", "list") }
                .forEach { patterns += it }
        }

        return patterns.distinct().filter { it.length >= 3 }
    }

    /**
     * 쿼리 키워드 추출 — CamelCase 분리 + 공백 분리, 스킴 관련 공용어 제거
     * 예) "ProductDetailScheme" → ["Product", "Detail"]
     *     "상품상세 스킴"       → ["상품상세"]
     */
    private fun extractQueryKeywords(query: String): List<String> {
        val camelSplit = query.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return camelSplit.split(" ", "_", "-")
            .map { it.trim() }
            .filter { it.length > 2 }
            .filterNot { schemeKeywords.contains(it.lowercase()) }
    }

    /**
     * 로컬 repo에서 정규식 패턴 grep — (relativePath, lineNo, content) 리스트 반환.
     * 모든 패턴을 | 로 합산해 단일 grep 호출로 처리 (성능).
     * priorityKeywords가 있으면 해당 키워드를 포함한 결과를 먼저 반환.
     */
    private fun grepLocalRepo(
        repoDir: File,
        patterns: List<String>,
        priorityKeywords: List<String> = emptyList(),
        maxHits: Int = 5,
    ): List<Triple<String, Int, String>> {
        if (patterns.isEmpty()) return emptyList()

        // 모든 패턴을 하나의 grep 호출로 합산
        val combinedPattern = patterns.joinToString("|") { "(?:$it)" }
        val lines = runCatching {
            ProcessBuilder("grep", "-rn", "--include=*.kt", "-E", combinedPattern, repoDir.absolutePath)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readLines()
        }.getOrDefault(emptyList())

        val allHits = mutableListOf<Triple<String, Int, String>>()
        for (raw in lines) {
            val firstColon = raw.indexOf(':')
            if (firstColon < 0) continue
            val afterPath = raw.substring(firstColon + 1)
            val secondColon = afterPath.indexOf(':')
            if (secondColon < 0) continue
            val absPath = raw.substring(0, firstColon)
            val lineNo = afterPath.substring(0, secondColon).toIntOrNull() ?: continue
            val content = afterPath.substring(secondColon + 1).trim()
            if (absPath.contains("/build/") || absPath.contains("/generated/")) continue
            val relPath = absPath.removePrefix(repoDir.absolutePath + "/")
            if (allHits.none { it.first == relPath && it.second == lineNo }) {
                allHits.add(Triple(relPath, lineNo, content))
            }
        }

        return if (priorityKeywords.isNotEmpty()) {
            val (priority, rest) = allHits.partition { (_, _, content) ->
                priorityKeywords.any { kw -> content.contains(kw, ignoreCase = true) }
            }
            (priority + rest).take(maxHits)
        } else {
            allHits.take(maxHits)
        }
    }

    @Tool("codeStats")
    @LLMDescription(
        "Kurly Android 코드베이스의 파일 통계를 조회합니다. " +
        "'테스트 파일 몇 개야?', '유닛테스트 코드 카운트', 'ViewModel 목록 알려줘' 같은 질문에 사용하세요."
    )
    fun codeStats(
        @LLMDescription("파일 패턴. 예: Test, ViewModel, Repository, UseCase. 전체 조회는 빈 문자열.")
        pattern: String,
    ): String = runBlocking {
        tracker?.record("CodeStats")
        runCatching {
            // localRepoPath 있으면 파일시스템 직접 스캔 (정확한 카운트)
            val localRoot = localRepoPath?.let { File(it) }
            if (localRoot != null && localRoot.exists()) {
                val files = localRoot.walk()
                    .filter { file ->
                        file.isFile &&
                        file.extension == "kt" &&
                        !file.path.contains("/build/") &&
                        !file.path.contains("/generated/") &&
                        (pattern.isBlank() || file.name.contains(pattern, ignoreCase = true))
                    }
                    .map { it.relativeTo(localRoot).path }
                    .sorted()
                    .toList()

                if (files.isEmpty()) return@runBlocking "패턴 `$pattern`에 해당하는 파일을 찾지 못했습니다."
                buildString {
                    val label = if (pattern.isBlank()) "전체" else "\"$pattern\""
                    appendLine("*$label 파일 [${files.size}개]:*\n")
                    files.take(20).forEachIndexed { i, path ->
                        appendLine("${i + 1}. `$path`")
                    }
                    if (files.size > 20) appendLine("\n_... 외 ${files.size - 20}개_")
                }.trim()
            } else {
                // BM25 fallback (localRepoPath 미설정 시)
                val index = bm25Index ?: return@runBlocking "코드 통계 기능이 비활성화 상태입니다. (localRepoPath 또는 BM25 인덱스 필요)"
                val files = index.listFilesByPattern(pattern)
                if (files.isEmpty()) return@runBlocking "패턴 `$pattern`에 해당하는 파일을 찾지 못했습니다."
                buildString {
                    val label = if (pattern.isBlank()) "전체" else "\"$pattern\""
                    appendLine("*$label 파일 [${files.size}개]:*\n")
                    files.take(20).forEachIndexed { i, path ->
                        appendLine("${i + 1}. `$path`")
                    }
                    if (files.size > 20) appendLine("\n_... 외 ${files.size - 20}개_")
                }.trim()
            }
        }.getOrElse { "코드 통계 조회 중 오류: ${it.message}" }
    }
}
