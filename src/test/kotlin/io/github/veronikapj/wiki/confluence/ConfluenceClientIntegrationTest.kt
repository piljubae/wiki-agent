package io.github.veronikapj.wiki.confluence

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@Tag("eval")
class ConfluenceClientIntegrationTest {

    private val client = ConfluenceClient(
        baseUrl = EXAMPLE_BASE_URL,
        token = "test",
    )

    @Test
    fun `parseSearchResults handles search API response`() {
        val json = this::class.java.getResourceAsStream("/search-response-sample.json")
            ?.reader()?.readText() ?: error("search-response-sample.json not found")
        val results = client.parseSearchResults(json, EXAMPLE_BASE_URL)
        assertTrue(results.size >= 2, "Should parse at least 2 results, got ${results.size}")
        assertTrue(results.any { it.id == "1000000002" }, "Should find 신규 입사자 온보딩 page")
        assertTrue(results.any { it.title.contains("온보딩") }, "Title should contain 온보딩")
        results.forEach { ref ->
            assertTrue(ref.webUrl.startsWith("$EXAMPLE_BASE_URL/wiki/"), "URL should start with base/wiki: ${ref.webUrl}")
            assertTrue(ref.excerpt.isNotBlank(), "Excerpt should not be blank for ${ref.id}")
        }
    }

    companion object {
        const val EXAMPLE_BASE_URL = "https://example.atlassian.net"
    }
}
