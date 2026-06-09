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
    fun `buildTextCqlSearchUrl encodes query and spaces`() {
        val url = client.buildTextCqlSearchUrl("배포 프로세스", listOf("DEV", "PM"), limit = 5)
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
    fun `parseSearchResults extracts pages from search API JSON`() {
        val json = """
            {
              "results": [
                {"content": {"id": "1", "type": "page", "title": "배포 가이드", "_links": {"webui": "/spaces/DEV/pages/1"}}, "title": "배포 가이드"},
                {"content": {"id": "2", "type": "page", "title": "온보딩", "_links": {"webui": "/spaces/HR/pages/2"}}, "title": "온보딩"}
              ]
            }
        """.trimIndent()
        val pages = client.parseSearchResults(json, "https://example.atlassian.net")
        assertEquals(2, pages.size)
        assertEquals("1", pages[0].id)
        assertEquals("배포 가이드", pages[0].title)
        assertEquals("https://example.atlassian.net/wiki/spaces/DEV/pages/1", pages[0].webUrl)
        assertEquals("2", pages[1].id)
    }

    @Test
    fun `convertHtmlToSlackMrkdwn strips tags and converts to mrkdwn`() {
        val html = "<h1>제목</h1><p>내용</p>"
        val result = client.convertHtmlToSlackMrkdwn(html)
        assertTrue(result.contains("*제목*"), "h1 should become *bold*")
        assertTrue(result.contains("내용"))
    }

    @Test
    fun `convertHtmlToSlackMrkdwn converts headings to bold`() {
        val html = "<h1>제목1</h1><h2>제목2</h2><h3>제목3</h3>"
        val result = client.convertHtmlToSlackMrkdwn(html)
        assertTrue(result.contains("*제목1*"), "h1 should become *bold*")
        assertTrue(result.contains("*제목2*"), "h2 should become *bold*")
        assertTrue(!result.contains("# "), "Should not contain Markdown heading")
        assertTrue(!result.contains("## "), "Should not contain Markdown heading")
    }

    @Test
    fun `convertHtmlToSlackMrkdwn converts strong to single asterisk`() {
        val html = "<strong>굵게</strong> and <b>볼드</b>"
        val result = client.convertHtmlToSlackMrkdwn(html)
        assertTrue(result.contains("*굵게*"), "strong should become *bold*")
        assertTrue(result.contains("*볼드*"), "b should become *bold*")
        assertTrue(!result.contains("**"), "Should not contain Markdown bold **")
    }

    @Test
    fun `convertHtmlToSlackMrkdwn converts list items to bullet`() {
        val html = "<li>항목1</li><li>항목2</li>"
        val result = client.convertHtmlToSlackMrkdwn(html)
        assertTrue(result.contains("• 항목1"), "li should become • bullet")
        assertTrue(!result.contains("- 항목"), "Should not contain Markdown dash bullet")
    }

    @Test
    fun `buildTextCqlSearchUrl escapes CQL special characters`() {
        val url = client.buildTextCqlSearchUrl("배포(긴급) 프로세스", listOf("DEV"), limit = 5)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        // Parentheses in user input must be backslash-escaped in CQL
        assertTrue(decoded.contains("\\(") && decoded.contains("\\)"),
            "Parentheses in user input should be backslash-escaped, got: $decoded")
        assertTrue(url.contains("cql="))
    }

    @Test
    fun `buildTextCqlSearchUrl uses full phrase including short keywords like UI and QA`() {
        // Full phrase search — short keywords are included as part of the phrase
        val url = client.buildTextCqlSearchUrl("A UI QA 가이드", listOf("DEV"), limit = 5)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("text ~ \"A UI QA 가이드\""), "Full phrase should be used as a single text clause")
        assertTrue(decoded.contains("UI"), "Short keyword 'UI' should appear in the phrase")
        assertTrue(decoded.contains("QA"), "Short keyword 'QA' should appear in the phrase")
    }

    @Test
    fun `buildTextCqlSearchUrl filters Korean stopwords`() {
        val url = client.buildTextCqlSearchUrl("배포는 어떻게 하는가", listOf("DEV"), limit = 5)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        // "배포" should remain, stopwords should be filtered
        assertTrue(decoded.contains("배포"), "Content word '배포' should be present")
        assertTrue(!decoded.contains("text ~ \"는\""), "Stopword '는' should be filtered")
        assertTrue(!decoded.contains("text ~ \"어떻게\""), "Stopword '어떻게' should be filtered")
        assertTrue(!decoded.contains("text ~ \"하는가\""), "Stopword '하는가' should be filtered")
    }

    @Test
    fun `buildTextCqlSearchUrl with synonyms combines into single OR clause`() {
        val url = client.buildTextCqlSearchUrl(
            query = "신입 온보딩",
            spaces = listOf("DEV"),
            synonyms = listOf("신규 입사자", "입사 가이드"),
        )
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("신규 입사자"), "Should include synonym '신규 입사자'")
        assertTrue(decoded.contains("입사 가이드"), "Should include synonym '입사 가이드'")
    }

    @Test
    fun `buildTextCqlSearchUrl limits OR clauses to prevent explosion`() {
        val synonyms = (1..10).map { "동의어$it" }
        val url = client.buildTextCqlSearchUrl("테스트", listOf("DEV"), synonyms = synonyms)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        // Total OR terms (query words + synonyms) should be capped
        val orCount = Regex("text ~").findAll(decoded).count()
        assertTrue(orCount <= 7, "OR clauses should be capped at MAX_TEXT_CLAUSES(7), got $orCount")
    }

    @Test
    fun `parseSearchResults extracts lastModified`() {
        val json = """
            {
              "results": [
                {
                  "title": "테스트 페이지",
                  "url": "/wiki/pages/123",
                  "lastModified": "2026-05-01T12:00:00.000Z",
                  "content": {
                    "id": "page-123",
                    "title": "테스트 페이지",
                    "_links": { "webui": "/spaces/TEST/pages/123" }
                  },
                  "excerpt": "요약"
                }
              ]
            }
        """.trimIndent()

        val results = client.parseSearchResults(json, "https://example.atlassian.net")
        assertEquals(1, results.size)
        assertEquals("page-123", results[0].id)
        assertEquals("2026-05-01T12:00:00.000Z", results[0].lastModified)
    }

    @Test
    fun `parseSearchResults handles missing lastModified gracefully`() {
        val json = """
            {
              "results": [
                {
                  "title": "페이지",
                  "content": { "id": "page-456", "_links": { "webui": "/pages/456" } }
                }
              ]
            }
        """.trimIndent()

        val results = client.parseSearchResults(json, "https://example.atlassian.net")
        assertEquals(1, results.size)
        assertEquals("", results[0].lastModified)
    }

    @Test
    fun `buildTitleCqlSearchUrl searches title with synonyms`() {
        val url = client.buildTitleCqlSearchUrl(
            query = "신입 온보딩",
            spaces = listOf("DEV"),
            synonyms = listOf("신규 입사자"),
        )
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("title ~ \"신입 온보딩\""), "Should search original query in title")
        assertTrue(decoded.contains("title ~ \"신규 입사자\""), "Should search synonym in title")
        assertTrue(decoded.contains("type = page"), "Should filter to pages only")
        assertTrue(!decoded.contains("text ~"), "Title search should not include text clauses")
    }
}
