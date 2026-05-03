package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.knowledge.BM25Index
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import kotlinx.coroutines.runBlocking

class CodeSearchTool(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val codeClient: GitHubCodeClient,
    private val codeRepos: List<String>,
    private val branch: String = "develop",
    private val tracker: SourceTracker? = null,
    private val collectionName: String = "code_index",
    /** Step 3: BM25 키워드 검색 인덱스. null이면 벡터 검색만 사용. */
    private val bm25Index: BM25Index? = null,
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
        runCatching {
            val collectionId = chromaClient.getOrCreateCollection(collectionName)
            val expandedQuery = llmExpandClient?.expandQuery(query) ?: query

            // Step 3: 하이브리드 검색 — BM25 + 벡터, RRF로 순위 병합
            val orderedIds: List<String> = if (bm25Index != null) {
                val vectorResults = chromaClient.query(collectionId, queryTexts = listOf(expandedQuery), nResults = 10)
                val vectorIds = vectorResults.map { r ->
                    val repo = r.metadata["repo"] ?: ""
                    val path = r.metadata["file_path"] ?: ""
                    val cls = r.metadata["class_name"] ?: ""
                    val fn = r.metadata["function_name"] ?: ""
                    "$repo:$path:$cls:$fn"
                }
                val bm25Ids = bm25Index.search(query, limit = 10)
                BM25Index.mergeRRF(vectorIds, bm25Ids)
            } else {
                // BM25 없으면 벡터만 사용
                chromaClient.query(collectionId, queryTexts = listOf(expandedQuery), nResults = 10)
                    .map { r ->
                        val repo = r.metadata["repo"] ?: ""
                        val path = r.metadata["file_path"] ?: ""
                        val cls = r.metadata["class_name"] ?: ""
                        val fn = r.metadata["function_name"] ?: ""
                        "$repo:$path:$cls:$fn"
                    }
            }

            // id 기준으로 ChromaDB 결과 메타데이터 조회
            val vectorResults = chromaClient.query(collectionId, queryTexts = listOf(expandedQuery), nResults = 10)
            val metaById = vectorResults.associateBy { r ->
                val repo = r.metadata["repo"] ?: ""
                val path = r.metadata["file_path"] ?: ""
                val cls = r.metadata["class_name"] ?: ""
                val fn = r.metadata["function_name"] ?: ""
                "$repo:$path:$cls:$fn"
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
                        val label = if (functionName.isNotBlank()) "$className.$functionName()" else className
                        appendLine("${i + 1}. `$label` — $filePath")
                        if (repo.isNotBlank() && filePath.isNotBlank()) {
                            appendLine("   <https://github.com/$repo/blob/$branch/$filePath|소스 보기>")
                        }
                        r?.let { appendLine("   > ${it.document.lines().take(2).joinToString(" ").take(200)}") }
                        appendLine()
                    }
                }.trim()
            } else null

            // ChromaDB + BM25 모두 결과 없으면 GitHub Code Search API fallback
            if (chromaResult != null) chromaResult
            else {
                val apiResults = codeRepos.flatMap { repo ->
                    runCatching { codeClient.searchCode(repo, query, branch) }.getOrDefault(emptyList())
                }.take(5)

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
        }.getOrElse { "코드 검색 중 오류: ${it.message}" }
    }
}
