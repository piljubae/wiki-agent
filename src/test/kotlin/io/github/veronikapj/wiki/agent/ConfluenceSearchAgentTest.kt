package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePage
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfluenceSearchAgentTest {

    @Test
    fun `search returns formatted markdown with links`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle("배포", listOf("DEV"), emptyList(), 5) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "https://co.atlassian.net/wiki/spaces/DEV/pages/1", titleMatched = true),
            ConfluencePageRef("2", "배포 절차", "https://co.atlassian.net/wiki/spaces/DEV/pages/2", titleMatched = true),
            ConfluencePageRef("3", "배포 체크리스트", "https://co.atlassian.net/wiki/spaces/DEV/pages/3", titleMatched = true),
        )

        val agent = ConfluenceSearchAgent(mockClient, spaces = listOf("DEV"))
        val result = agent.search("배포")

        assertTrue(result.contains("배포 가이드"))
        assertTrue(result.contains("https://"))
    }

    @Test
    fun `search returns no results message when empty`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockClient.searchByText(any(), any(), any(), any()) } returns emptyList()

        val agent = ConfluenceSearchAgent(mockClient, spaces = listOf("DEV"))
        val result = agent.search("존재하지않는쿼리xyz")

        assertTrue(result.contains("찾을 수 없"))
    }

    @Test
    fun `searchStructured returns list of SearchResult`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "https://example.com/wiki/1", titleMatched = true),
            ConfluencePageRef("2", "배포 절차", "https://example.com/wiki/2", titleMatched = true),
            ConfluencePageRef("3", "배포 체크리스트", "https://example.com/wiki/3", titleMatched = true),
        )
        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"))
        val results = agent.searchStructured("배포")
        assertEquals(3, results.size)
        assertEquals("1", results[0].pageId)
        assertEquals(SearchStage.TITLE_MATCH, results[0].stage)
    }

    @Test
    fun `early return when title matches are sufficient`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "url1", titleMatched = true),
            ConfluencePageRef("2", "배포 절차", "url2", titleMatched = true),
            ConfluencePageRef("3", "배포 체크리스트", "url3", titleMatched = true),
        )
        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
        val results = agent.searchStructured("배포")
        assertEquals(3, results.size)
        assertTrue(results.all { it.stage == SearchStage.TITLE_MATCH })
        coVerify(exactly = 0) { mockClient.searchByText(any(), any(), any(), any()) }
    }

    @Test
    fun `parallel fallback when title matches insufficient`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "url1", titleMatched = true),
        )
        coEvery { mockClient.searchByText("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("2", "릴리즈 노트", "url2"),
        )
        coEvery { mockClient.searchByTitle("배포", emptyList(), any(), any()) } returns listOf(
            ConfluencePageRef("3", "다른팀 배포", "url3", titleMatched = true),
        )
        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
        val results = agent.searchStructured("배포")
        assertTrue(results.size >= 2)
        assertEquals(SearchStage.TITLE_MATCH, results[0].stage)
    }

    @Test
    fun `query preprocessing strips suffixes and special chars`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        // "iOS Daily - 26.04.27 알려줘" → 전처리 후 "iOS Daily 26.04.27" 로 검색
        coEvery { mockClient.searchByTitle(any(), listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "iOS Daily - 26.04.27", "url1", titleMatched = true),
            ConfluencePageRef("2", "iOS Daily - 26.04.26", "url2", titleMatched = true),
            ConfluencePageRef("3", "iOS Daily - 26.04.25", "url3", titleMatched = true),
        )
        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"))
        val results = agent.searchStructured("iOS Daily - 26.04.27 알려줘")
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `query preprocessing handles brackets and pipes`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        // "[XP/핀테크트라이브]주간보고_260417 알려줘" → 전처리
        coEvery { mockClient.searchByTitle(any(), listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "[XP/핀테크트라이브]주간보고", "url1", titleMatched = true),
            ConfluencePageRef("2", "주간보고 2", "url2", titleMatched = true),
            ConfluencePageRef("3", "주간보고 3", "url3", titleMatched = true),
        )
        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"))
        val results = agent.searchStructured("[XP/핀테크트라이브]주간보고_260417 알려줘")
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `results deduplicated by pageId`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "url1", titleMatched = true),
        )
        coEvery { mockClient.searchByText("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "url1"),
            ConfluencePageRef("2", "새 문서", "url2"),
        )
        coEvery { mockClient.searchByTitle("배포", emptyList(), any(), any()) } returns emptyList()
        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
        val results = agent.searchStructured("배포")
        assertEquals(1, results.count { it.pageId == "1" })
    }

    @Test
    fun `RAG result with same pageId as Confluence result is deduplicated`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("42", "배포 가이드", "url1", titleMatched = true),
        )
        coEvery { mockClient.searchByText("배포", listOf("DEV"), any(), any()) } returns emptyList()
        coEvery { mockClient.searchByTitle("배포", emptyList(), any(), any()) } returns emptyList()

        val mockVectorAgent = mockk<VectorSearchAgent>()
        coEvery { mockVectorAgent.searchStructured(any(), any()) } returns listOf(
            SearchResult("42", "배포 가이드 (RAG)", "url2", "snippet", SearchStage.RAG),
        )

        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), mockVectorAgent, sufficientThreshold = 3)
        val results = agent.searchStructured("배포")
        // Confluence result wins (higher stage score); RAG duplicate suppressed
        assertEquals(1, results.count { it.pageId == "42" })
        assertEquals(SearchStage.TITLE_MATCH, results.first { it.pageId == "42" }.stage)
    }

    @Test
    fun `RAG timeout returns empty list gracefully`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "url1", titleMatched = true),
        )
        coEvery { mockClient.searchByText("배포", listOf("DEV"), any(), any()) } returns emptyList()
        coEvery { mockClient.searchByTitle("배포", emptyList(), any(), any()) } returns emptyList()

        val slowVectorAgent = mockk<VectorSearchAgent>()
        coEvery { slowVectorAgent.searchStructured(any(), any()) } coAnswers {
            delay(10_000) // far exceeds RAG_TIMEOUT_MS=5000
            emptyList()
        }

        val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), slowVectorAgent, sufficientThreshold = 3)
        // Should not throw, should return Confluence results without RAG
        val results = agent.searchStructured("배포")
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.stage != SearchStage.RAG })
    }
}
