package io.github.veronikapj.wiki.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubWikiClientTest {

    private val client = GitHubWikiClient()

    @Test
    fun `buildRawUrl constructs wiki raw url`() {
        val url = client.buildRawUrl("owner/repo.wiki", "Deploy-Guide.md", isWiki = true)
        assertEquals("https://raw.githubusercontent.com/wiki/owner/repo/Deploy-Guide.md", url)
    }

    @Test
    fun `buildRawUrl constructs main repo raw url`() {
        val url = client.buildRawUrl("owner/repo", "docs/guide.md", isWiki = false)
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/docs/guide.md", url)
    }

    @Test
    fun `parseMdFilePaths extracts md paths from tree json`() {
        val json = """
            {"tree":[
              {"path":"README.md","type":"blob"},
              {"path":"src/Main.kt","type":"blob"},
              {"path":"docs/guide.md","type":"blob"}
            ]}
        """.trimIndent()
        val paths = client.parseMdFilePaths(json)
        assertEquals(listOf("README.md", "docs/guide.md"), paths)
    }

    @Test
    fun `parseMdFilePaths returns empty on no md files`() {
        val json = """{"tree":[{"path":"src/Main.kt","type":"blob"}]}"""
        assertTrue(client.parseMdFilePaths(json).isEmpty())
    }
}
