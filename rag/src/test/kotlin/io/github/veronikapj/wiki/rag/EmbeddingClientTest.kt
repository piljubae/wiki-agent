package io.github.veronikapj.wiki.rag

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmbeddingClientTest {

    @Test
    fun `LlmExpandClient returns non-blank enriched text`() {
        val client = LlmExpandClient(llmFn = { text -> "키워드: 배포, deploy, release\n관련질문: $text" })
        val result = runBlocking { client.enrichDocument("배포 프로세스") }
        assertTrue(result.contains("배포"))
    }

    @Test
    fun `LlmExpandClient expands query with synonyms`() {
        val client = LlmExpandClient(llmFn = { text -> "$text deploy release" })
        val expanded = runBlocking { client.expandQuery("배포") }
        assertTrue(expanded.contains("배포"))
        assertTrue(expanded.contains("deploy"))
    }

    @Test
    fun `GoogleEmbeddingClient buildEmbedRequest formats JSON correctly`() {
        val client = GoogleEmbeddingClient(apiKey = "test-key")
        val json = client.buildEmbedRequest("hello world", "text-embedding-004")
        assertTrue(json.contains("hello world"))
        assertTrue(json.contains("text-embedding-004"))
    }

    @Test
    fun `GoogleEmbeddingClient parseEmbedResponse extracts values`() {
        val client = GoogleEmbeddingClient(apiKey = "test-key")
        val response = """{"embedding":{"values":[0.1,0.2,0.3]}}"""
        val embedding = client.parseEmbedResponse(response)
        assertNotNull(embedding)
        assertTrue(embedding.size == 3)
        assertTrue(embedding[0] in 0.09f..0.11f)
    }
}
