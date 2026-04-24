package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

class ConfluenceSearchAgent(
    private val confluenceClient: ConfluenceClient,
    private val spaces: List<String>,
    private val vectorSearchAgent: VectorSearchAgent? = null,
    private val sufficientThreshold: Int = 3,
) {
    suspend fun searchStructured(
        query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
    ): List<SearchResult> {
        log.info("Searching: query='{}', synonyms={}, spaces={}", query, synonyms, spaces)

        // 1단계: 설정 스페이스에서 제목 검색
        val titleResults = confluenceClient.searchByTitle(query, spaces, synonyms, topK)
        log.info("Title search: {} results", titleResults.size)

        // Early return: 제목 매칭 충분하면 추가 검색 안 함
        if (titleResults.size >= sufficientThreshold) {
            log.info("Sufficient title matches ({}>={}), early return", titleResults.size, sufficientThreshold)
            return titleResults.take(topK).map { it.toSearchResult(SearchStage.TITLE_MATCH) }
        }

        // 2단계: 부족 → 병렬로 text + 스페이스 확장 + RAG
        log.info("Insufficient title matches ({}<{}), parallel fallback", titleResults.size, sufficientThreshold)
        val (textResults, expandedResults, ragResults) = coroutineScope {
            val textDeferred = async {
                runCatching { confluenceClient.searchByText(query, spaces, synonyms, topK) }.getOrElse { emptyList() }
            }
            val expandedDeferred = async {
                if (spaces.isNotEmpty()) {
                    runCatching { confluenceClient.searchByTitle(query, emptyList(), synonyms, topK) }.getOrElse { emptyList() }
                } else emptyList()
            }
            val ragDeferred = async {
                if (vectorSearchAgent != null) {
                    withTimeoutOrNull(5000) {
                        runCatching { vectorSearchAgent.searchStructured(query, topK) }.getOrElse { emptyList() }
                    } ?: emptyList()
                } else emptyList()
            }
            Triple(textDeferred.await(), expandedDeferred.await(), ragDeferred.await())
        }

        // 3단계: 합산 + 중복 제거 + 랭킹
        return combineAndRank(titleResults, textResults, expandedResults, ragResults, topK)
    }

    private fun combineAndRank(
        titleResults: List<ConfluencePageRef>,
        textResults: List<ConfluencePageRef>,
        expandedResults: List<ConfluencePageRef>,
        ragResults: List<SearchResult>,
        topK: Int,
    ): List<SearchResult> {
        val seen = mutableSetOf<String>()
        val scored = mutableListOf<SearchResult>()

        titleResults.forEach { if (seen.add(it.id)) scored.add(it.toSearchResult(SearchStage.TITLE_MATCH)) }
        expandedResults.forEach { if (seen.add(it.id)) scored.add(it.toSearchResult(SearchStage.SPACE_EXPANSION)) }
        textResults.forEach { if (seen.add(it.id)) scored.add(it.toSearchResult(SearchStage.TEXT_MATCH)) }
        ragResults.forEach { if (seen.add(it.pageId)) scored.add(it) }

        return scored.sortedByDescending { it.stage.score }.take(topK)
    }

    private fun ConfluencePageRef.toSearchResult(stage: SearchStage) = SearchResult(
        pageId = id, title = title, url = webUrl, snippet = excerpt, stage = stage,
    )

    suspend fun search(query: String, synonyms: List<String> = emptyList(), topK: Int = 5): String {
        val results = searchStructured(query, synonyms, topK)
        if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (query: $query)"
        return results.formatForSlack()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceSearchAgent::class.java)
    }
}
