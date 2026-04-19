package io.github.veronikapj.wikiq.agent

import io.github.veronikapj.wikiq.confluence.ConfluenceClient
import io.github.veronikapj.wikiq.confluence.ConfluencePage
import io.github.veronikapj.wikiq.confluence.ConfluencePageRef
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfluenceSearchAgentTest {

    @Test
    fun `search returns formatted markdown with links`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchPages("배포", listOf("DEV"), 5) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "https://co.atlassian.net/wiki/spaces/DEV/pages/1"),
        )
        coEvery { mockClient.fetchPageContent("1") } returns ConfluencePage(
            id = "1",
            title = "배포 가이드",
            content = "## 배포 절차\n1. PR 머지\n2. 자동 배포",
            webUrl = "https://co.atlassian.net/wiki/spaces/DEV/pages/1",
        )

        val agent = ConfluenceSearchAgent(mockClient, spaces = listOf("DEV"))
        val result = agent.search("배포")

        assertTrue(result.contains("배포 가이드"))
        assertTrue(result.contains("https://"))
    }

    @Test
    fun `search returns no results message when empty`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchPages(any(), any(), any()) } returns emptyList()

        val agent = ConfluenceSearchAgent(mockClient, spaces = listOf("DEV"))
        val result = agent.search("존재하지않는쿼리xyz")

        assertTrue(result.contains("찾을 수 없"))
    }
}
