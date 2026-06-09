package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.GitHubWikiSearchAgent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolTest {

    @Test
    fun `ConfluenceTool delegates to ConfluenceSearchAgent`() = runTest {
        val mockAgent = mockk<ConfluenceSearchAgent>()
        coEvery { mockAgent.search("배포", any(), any(), any(), any()) } returns "배포 가이드 결과"
        val tool = ConfluenceTool(mockAgent)
        val result = tool.confluenceSearch("배포")
        assertTrue(result.contains("배포"))
    }

    @Test
    fun `confluenceSearchSuspend delegates to searchAgent`() = runTest {
        val searchAgent = mockk<ConfluenceSearchAgent>()
        coEvery { searchAgent.search(any(), any(), any(), any(), any()) } returns "Confluence 결과"
        val tool = ConfluenceTool(searchAgent)
        val result = tool.confluenceSearchSuspend("배포 프로세스")
        assertEquals("Confluence 결과", result)
    }

    @Test
    fun `GitHubWikiTool delegates to GitHubWikiSearchAgent`() = runTest {
        val mockAgent = mockk<GitHubWikiSearchAgent>()
        coEvery { mockAgent.search("배포") } returns "GitHub Wiki 결과"
        val tool = GitHubWikiTool(mockAgent)
        val result = tool.githubWikiSearch("배포")
        assertTrue(result.contains("GitHub Wiki"))
    }
}
