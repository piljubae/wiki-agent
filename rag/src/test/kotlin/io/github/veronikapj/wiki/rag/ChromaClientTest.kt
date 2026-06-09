package io.github.veronikapj.wiki.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChromaClientTest {

    private val client = ChromaClient("http://localhost:8000")

    @Test
    fun `buildAddBody creates correct JSON without embeddings`() {
        val json = client.buildAddBody(
            ids = listOf("id1", "id2"),
            documents = listOf("doc1", "doc2"),
            embeddings = null,
            metadatas = listOf(mapOf("source" to "page1"), mapOf("source" to "page2")),
        )
        assertTrue(json.contains("\"id1\""))
        assertTrue(json.contains("\"doc1\""))
        assertTrue(json.contains("\"source\""))
        assertTrue(!json.contains("embeddings"))
    }

    @Test
    fun `buildAddBody includes embeddings when provided`() {
        val json = client.buildAddBody(
            ids = listOf("id1"),
            documents = listOf("doc1"),
            embeddings = listOf(listOf(0.1f, 0.2f, 0.3f)),
            metadatas = emptyList(),
        )
        assertTrue(json.contains("embeddings"))
        assertTrue(json.contains("0.1"))
    }

    @Test
    fun `buildQueryBody uses query_texts when no embeddings`() {
        val json = client.buildQueryBody(queryTexts = listOf("질문"), queryEmbeddings = null, nResults = 3)
        assertTrue(json.contains("query_texts"))
        assertTrue(json.contains("질문"))
        assertTrue(json.contains("\"n_results\":3"))
    }

    @Test
    fun `buildQueryBody uses query_embeddings only — queryTexts 없이 동작`() {
        val json = client.buildQueryBody(
            queryTexts = null,
            queryEmbeddings = listOf(listOf(0.1f, 0.2f, 0.3f)),
            nResults = 10,
        )
        assertTrue(json.contains("query_embeddings"))
        assertTrue(!json.contains("query_texts"), "queryTexts가 없어야 함")
        assertTrue(json.contains("\"n_results\":10"))
    }

    @Test
    fun `buildQueryBody — queryTexts와 queryEmbeddings 모두 null이면 n_results만 포함`() {
        val json = client.buildQueryBody(queryTexts = null, queryEmbeddings = null, nResults = 5)
        assertEquals("{\"n_results\":5}", json)
        assertTrue(!json.contains("query_texts"))
        assertTrue(!json.contains("query_embeddings"))
    }

    @Test
    fun `parseQueryResults extracts documents`() {
        val response = """{"ids":[["id1","id2"]],"documents":[["doc1 content","doc2 content"]],"metadatas":[[{"title":"Page1"},{"title":"Page2"}]],"distances":[[0.1,0.2]]}"""
        val results = client.parseQueryResults(response)
        assertEquals(2, results.size)
        assertEquals("doc1 content", results[0].document)
        assertEquals("Page1", results[0].metadata["title"])
    }

    @Test
    fun `parseGetIdsResponse extracts id list from get response`() {
        val json = """{"ids":[["id1","id2","id3"]],"documents":[[]],"metadatas":[[]],"embeddings":null}"""
        val result = ChromaClient("http://localhost").parseGetIdsResponse(json)
        assertEquals(listOf("id1", "id2", "id3"), result)
    }

    @Test
    fun `parseGetIdsResponse returns empty list when ids array is empty`() {
        val json = """{"ids":[[]],"documents":[[]],"metadatas":[[]]}"""
        val result = ChromaClient("http://localhost").parseGetIdsResponse(json)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `parseIdsWithLastModified returns id to lastModified map`() {
        val json = """
            {
              "ids": ["page-123", "page-456"],
              "metadatas": [
                {"title": "페이지1", "url": "https://...", "lastModified": "2026-05-01T12:00:00.000Z"},
                {"title": "페이지2", "url": "https://...", "lastModified": "2026-04-15T09:00:00.000Z"}
              ]
            }
        """.trimIndent()

        val result = client.parseIdsWithLastModified(json)

        assertEquals(2, result.size)
        assertEquals("2026-05-01T12:00:00.000Z", result["page-123"])
        assertEquals("2026-04-15T09:00:00.000Z", result["page-456"])
    }

    @Test
    fun `parseIdsWithLastModified skips entry without lastModified`() {
        val json = """
            {
              "ids": ["page-123", "page-456"],
              "metadatas": [
                {"title": "페이지1", "lastModified": "2026-05-01T12:00:00.000Z"},
                {"title": "페이지2"}
              ]
            }
        """.trimIndent()

        val result = client.parseIdsWithLastModified(json)

        assertEquals(1, result.size)
        assertEquals("2026-05-01T12:00:00.000Z", result["page-123"])
    }

    @Test
    fun `parseIdsWithLastModified returns empty map for empty collection`() {
        val json = """{"ids": [], "metadatas": []}"""
        val result = client.parseIdsWithLastModified(json)
        assertTrue(result.isEmpty())
    }
}
