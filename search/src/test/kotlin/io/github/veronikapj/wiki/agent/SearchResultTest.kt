package io.github.veronikapj.wiki.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchResultTest {

    @Test
    fun `SearchStage score ordering matches design`() {
        assertTrue(SearchStage.TITLE_MATCH.score > SearchStage.SPACE_EXPANSION.score)
        assertTrue(SearchStage.SPACE_EXPANSION.score > SearchStage.TEXT_MATCH.score)
        assertTrue(SearchStage.TEXT_MATCH.score > SearchStage.KEYWORD_AND.score)
        assertTrue(SearchStage.KEYWORD_AND.score > SearchStage.GLOBAL_FALLBACK.score)
    }

    @Test
    fun `SearchStage score values`() {
        assertEquals(1.0, SearchStage.TITLE_MATCH.score)
        assertEquals(0.8, SearchStage.SPACE_EXPANSION.score)
        assertEquals(0.6, SearchStage.TEXT_MATCH.score)
        assertEquals(0.55, SearchStage.KEYWORD_AND.score)
        assertEquals(0.4, SearchStage.GLOBAL_FALLBACK.score)
    }

    @Test
    fun `results sorted by stage score descending`() {
        val results = listOf(
            SearchResult("3", "C", "url3", "s3", SearchStage.KEYWORD_AND),
            SearchResult("1", "A", "url1", "s1", SearchStage.TITLE_MATCH),
            SearchResult("2", "B", "url2", "s2", SearchStage.TEXT_MATCH),
        )
        val sorted = results.sortedByDescending { it.stage.score }
        assertEquals("1", sorted[0].pageId)
        assertEquals("2", sorted[1].pageId)
        assertEquals("3", sorted[2].pageId)
    }

    @Test
    fun `formatForSlack shows numbered results`() {
        val results = listOf(
            SearchResult("1", "문서 A", "https://example.com/1", "요약 A", SearchStage.TITLE_MATCH),
        )
        val formatted = results.formatForSlack()
        assertTrue(formatted.contains("1. *문서 A*"))
        assertTrue(formatted.contains("<https://example.com/1|링크>"))
    }
}
