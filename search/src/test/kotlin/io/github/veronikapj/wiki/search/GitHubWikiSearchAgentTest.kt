package io.github.veronikapj.wiki.search

import io.github.veronikapj.wiki.github.GitHubWikiClient
import io.github.veronikapj.wiki.github.GitHubWikiPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class GitHubWikiSearchAgentTest {

    private val mockClient = mockk<GitHubWikiClient>()

    @Test
    fun `search returns formatted results`() = runTest {
        coEvery { mockClient.searchPages("배포", any()) } returns listOf(
            GitHubWikiPage("배포 가이드", "owner/repo", "Deploy-Guide.md", "https://github.com/owner/repo/wiki/Deploy-Guide", "")
        )
        every { mockClient.buildRawUrl(any(), any(), any()) } returns "https://raw.githubusercontent.com/wiki/owner/repo/Deploy-Guide.md"
        coEvery { mockClient.fetchContent(any()) } returns "배포 절차 설명"

        val agent = GitHubWikiSearchAgent(mockClient, listOf("owner/repo"))
        val result = agent.search("배포")

        assertTrue(result.contains("배포 가이드"))
        assertTrue(result.contains("owner/repo"))
    }

    @Test
    fun `search returns no results message when empty`() = runTest {
        coEvery { mockClient.searchPages(any(), any()) } returns emptyList()

        val agent = GitHubWikiSearchAgent(mockClient, listOf("owner/repo"))
        val result = agent.search("없는것")

        assertTrue(result.contains("찾을 수 없"))
    }
}
