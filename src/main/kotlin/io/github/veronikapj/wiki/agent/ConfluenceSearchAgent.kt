package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import org.slf4j.LoggerFactory

class ConfluenceSearchAgent(
    private val confluenceClient: ConfluenceClient,
    private val spaces: List<String>,
) {
    suspend fun searchStructured(query: String, synonyms: List<String> = emptyList(), topK: Int = 5): List<SearchResult> {
        log.info("Searching Confluence: query='{}', synonyms={}, spaces={}", query, synonyms, spaces)

        val pages = confluenceClient.searchPages(query, spaces, synonyms, topK)

        // 설정 스페이스에서 제목 매칭 결과가 없으면 전체 스페이스로 확장
        val finalPages = if (pages.none { it.titleMatched } && spaces.isNotEmpty()) {
            log.info("No title matches in configured spaces, expanding to all spaces")
            val allPages = confluenceClient.searchPages(query, emptyList(), synonyms, topK)
            val existingIds = pages.map { it.id }.toSet()
            val expanded = allPages.filter { it.id !in existingIds }
            if (expanded.isNotEmpty()) expanded else pages // 확장 결과 있으면 교체, 없으면 유지
        } else pages

        return finalPages.take(topK).map { ref ->
            SearchResult(
                pageId = ref.id,
                title = ref.title,
                url = ref.webUrl,
                snippet = ref.excerpt,
                source = Source.CQL,
            )
        }
    }

    suspend fun search(query: String, synonyms: List<String> = emptyList(), topK: Int = 5): String {
        val results = searchStructured(query, synonyms, topK)
        if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (query: $query)"
        return results.formatForSlack()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceSearchAgent::class.java)
    }
}
