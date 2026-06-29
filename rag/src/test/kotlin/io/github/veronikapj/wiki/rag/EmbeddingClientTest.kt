package io.github.veronikapj.wiki.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `expandQuery caps runaway LLM output to word limit`() {
        // LLM이 "한 줄로"를 무시하고 수백 단어를 뱉는 폭주 케이스
        val runaway = (1..500).joinToString(" ") { "키워드$it" }
        val client = LlmExpandClient(llmFn = { runaway })
        val expanded = runBlocking { client.expandQuery("MarketingService") }
        val wordCount = expanded.split(Regex("\\s+")).count { it.isNotBlank() }
        // 원본 쿼리(1) + 상한(MAX_EXPANSION_WORDS) 이내여야 함
        assertTrue(wordCount <= 1 + LlmExpandClient.MAX_EXPANSION_WORDS,
            "expandQuery output not capped: $wordCount words")
        assertTrue(expanded.startsWith("MarketingService"))
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

    private val okBody = """{"embedding":{"values":[0.1,0.2,0.3]}}"""
    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `embed — 5xx 두 번 후 성공하면 재시도하여 결과 반환`() {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls < 3) respond("upstream error", HttpStatusCode.ServiceUnavailable, jsonHeaders())
            else respond(okBody, HttpStatusCode.OK, jsonHeaders())
        }
        val client = GoogleEmbeddingClient("k", HttpClient(engine), maxAttempts = 3, baseBackoffMs = 1L)

        val result = runBlocking { client.embed("hello") }

        assertEquals(3, calls)
        assertEquals(3, result.size)
    }

    @Test
    fun `embed — 429도 재시도 대상`() {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls < 2) respond("rate limited", HttpStatusCode.TooManyRequests, jsonHeaders())
            else respond(okBody, HttpStatusCode.OK, jsonHeaders())
        }
        val client = GoogleEmbeddingClient("k", HttpClient(engine), maxAttempts = 3, baseBackoffMs = 1L)

        val result = runBlocking { client.embed("hello") }

        assertEquals(2, calls)
        assertEquals(3, result.size)
    }

    @Test
    fun `embed — maxAttempts 모두 실패하면 예외`() {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("boom", HttpStatusCode.InternalServerError, jsonHeaders())
        }
        val client = GoogleEmbeddingClient("k", HttpClient(engine), maxAttempts = 3, baseBackoffMs = 1L)

        assertFailsWith<IllegalStateException> { runBlocking { client.embed("hello") } }
        assertEquals(3, calls)
    }

    @Test
    fun `embed — 2xx 파싱 실패는 재시도하지 않고 즉시 실패`() {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("not json", HttpStatusCode.OK, jsonHeaders())
        }
        val client = GoogleEmbeddingClient("k", HttpClient(engine), maxAttempts = 3, baseBackoffMs = 1L)

        assertFailsWith<IllegalStateException> { runBlocking { client.embed("hello") } }
        assertEquals(1, calls)
    }
}
