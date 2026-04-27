package io.github.veronikapj.wiki.confluence

import kotlin.test.Test
import kotlin.test.assertTrue

class ConfluenceClientSearchTest {

    private val client = ConfluenceClient(
        baseUrl = "https://test.atlassian.net",
        token = "dummy",
    )

    @Test
    fun `searchByTitle URL uses title CQL`() {
        val url = client.buildTitleCqlSearchUrl("배포", listOf("DEV"), emptyList(), 5)
        assertTrue(url.contains("title"))
    }

    @Test
    fun `searchByText URL uses text CQL`() {
        val url = client.buildTextCqlSearchUrl("배포 가이드", listOf("DEV"), emptyList(), 5)
        assertTrue(url.contains("text"))
    }

    @Test
    fun `buildTitleCqlSearchUrl includes synonyms in title OR clause`() {
        val url = client.buildTitleCqlSearchUrl("배포", listOf("DEV"), listOf("릴리즈", "출시"), 5)
        assertTrue(url.contains("title"))
    }
}
