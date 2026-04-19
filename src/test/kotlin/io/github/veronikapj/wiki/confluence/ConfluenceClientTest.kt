package io.github.veronikapj.wiki.confluence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfluenceClientTest {

    private val client = ConfluenceClient(
        baseUrl = "https://example.atlassian.net",
        token = "dGVzdDp0b2tlbg==",
    )

    @Test
    fun `buildCqlSearchUrl encodes query and spaces`() {
        val url = client.buildCqlSearchUrl("배포 프로세스", listOf("DEV", "PM"), limit = 5)
        assertTrue(url.contains("cql="))
        assertTrue(url.contains("DEV"))
        assertTrue(url.contains("limit=5"))
    }

    @Test
    fun `buildPageUrl returns correct path`() {
        val url = client.buildPageUrl("12345")
        assertEquals(
            "https://example.atlassian.net/wiki/rest/api/content/12345?expand=body.storage,version,title",
            url
        )
    }

    @Test
    fun `parseSearchResults extracts pages from JSON`() {
        val json = """
            {
              "results": [
                {"id": "1", "title": "배포 가이드", "_links": {"webui": "/wiki/spaces/DEV/pages/1"}},
                {"id": "2", "title": "온보딩", "_links": {"webui": "/wiki/spaces/HR/pages/2"}}
              ]
            }
        """.trimIndent()
        val pages = client.parseSearchResults(json, "https://example.atlassian.net")
        assertEquals(2, pages.size)
        assertEquals("배포 가이드", pages[0].title)
        assertTrue(pages[0].webUrl.contains("example.atlassian.net"))
    }

    @Test
    fun `convertHtmlToMarkdown strips tags`() {
        val html = "<h1>제목</h1><p>내용</p>"
        val md = client.convertHtmlToMarkdown(html)
        assertTrue(md.contains("# 제목"))
        assertTrue(md.contains("내용"))
    }
}
