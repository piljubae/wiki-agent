package io.github.veronikapj.wiki.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubWikiClientTest {

    private val client = GitHubWikiClient()

    @Test
    fun `buildSearchUrl includes repo wiki suffix`() {
        val url = client.buildSearchUrl("배포", listOf("owner/repo"))
        assertTrue(url.contains("owner%2Frepo.wiki") || url.contains("owner/repo.wiki"))
    }

    @Test
    fun `buildRawUrl constructs correct raw github url`() {
        val url = client.buildRawUrl("owner/repo", "Deploy-Guide.md")
        assertEquals("https://raw.githubusercontent.com/wiki/owner/repo/Deploy-Guide.md", url)
    }

    @Test
    fun `parseSearchResults extracts pages`() {
        val json = """
            {"total_count":1,"items":[{"name":"Deploy-Guide.md","path":"Deploy-Guide.md","html_url":"https://github.com/owner/repo/wiki/Deploy-Guide","repository":{"full_name":"owner/repo"}}]}
        """.trimIndent()
        val results = client.parseSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("Deploy Guide", results[0].title)
        assertEquals("owner/repo", results[0].repoFullName)
    }

    @Test
    fun `parseSearchResults returns empty on no items`() {
        val json = """{"total_count":0,"items":[]}"""
        val results = client.parseSearchResults(json)
        assertTrue(results.isEmpty())
    }
}
