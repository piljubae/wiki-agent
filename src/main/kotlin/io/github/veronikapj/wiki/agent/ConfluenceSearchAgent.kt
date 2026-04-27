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
        val cleaned = cleanQuery(query)
        log.info("Searching: query='{}' → cleaned='{}', synonyms={}, spaces={}", query, cleaned, synonyms, spaces)

        // 1단계: 설정 스페이스에서 제목 검색
        val titleResults = confluenceClient.searchByTitle(cleaned, spaces, synonyms, topK)
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
                runCatching { confluenceClient.searchByText(cleaned, spaces, synonyms, topK) }.getOrElse { emptyList() }
            }
            val expandedDeferred = async {
                if (spaces.isNotEmpty()) {
                    runCatching { confluenceClient.searchByTitle(cleaned, emptyList(), synonyms, topK) }.getOrElse { emptyList() }
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

        // 대화형 접미사 제거
        private val SUFFIXES = listOf(
            "알려줘", "알려주세요", "알려 줘", "알려 주세요",
            "어디서 봐?", "어디서 봐", "어디 있어?", "어디 있어",
            "어떻게 돼?", "어떻게 돼", "뭐야?", "뭐야",
            "찾아줘", "보여줘", "설명해줘", "정리해줘",
        )

        /** CQL 검색 전 쿼리 정제: 특수문자 제거 + 대화형 접미사 제거 */
        internal fun cleanQuery(query: String): String {
            var q = query
            // 대화형 접미사 제거
            for (s in SUFFIXES) {
                if (q.endsWith(s)) {
                    q = q.removeSuffix(s).trimEnd()
                    break
                }
            }
            // CQL을 깨뜨리는 특수문자 제거 (내용은 유지)
            q = q.replace(Regex("[\\[\\]|~{}()]"), " ")
            // 언더스코어 → 공백 (제목에서 구분자로 사용)
            q = q.replace('_', ' ')
            // 연속 공백 정리
            q = q.replace(Regex("\\s+"), " ").trim()
            return q.ifBlank { query.trim() }
        }
    }
}
