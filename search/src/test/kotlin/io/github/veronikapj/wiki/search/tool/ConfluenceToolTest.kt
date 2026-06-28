package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.SearchResult
import io.github.veronikapj.wiki.search.SearchStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfluenceToolTest {

    @Test
    fun `searchScopedStructured는 strictSpaces로 searchStructured에 위임한다`() {
        val agent = mockk<ConfluenceSearchAgent>()
        coEvery { agent.searchStructured("q", originalQuestion = "q", strictSpaces = listOf("ProductApp")) } returns listOf(
            SearchResult("1", "온보딩", "url1", "본문", SearchStage.TITLE_MATCH),
        )
        val tool = ConfluenceTool(agent)

        val results = tool.searchScopedStructured("q", listOf("ProductApp"))

        assertEquals(1, results.size)
        assertEquals("1", results[0].pageId)
        coVerify { agent.searchStructured("q", originalQuestion = "q", strictSpaces = listOf("ProductApp")) }
    }
}
