package io.github.veronikapj.wiki.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

class LlmExpandClient(
    private val llmFn: suspend (String) -> String,
) {
    suspend fun enrichDocument(text: String): String {
        val prompt = """
            아래 문서의 핵심 키워드, 동의어, 영어 표현, 관련 질문 유형을 추출해서
            원문 아래에 덧붙여 반환하세요. 추가 설명 없이 결과만 출력.

            문서:
            ${text.take(2000)}
        """.trimIndent()
        return runCatching { llmFn(prompt) }.getOrDefault(text)
    }

    suspend fun expandQuery(query: String): String {
        val prompt = """
            아래 검색어의 동의어, 영어 표현, 관련 개념을 공백으로 구분해서 한 줄로 반환하세요.
            원래 검색어도 포함. 추가 설명 없이 단어/구절만 출력.

            검색어: $query
        """.trimIndent()
        return runCatching { "$query ${llmFn(prompt)}" }.getOrDefault(query)
    }
}

class GoogleEmbeddingClient(private val apiKey: String) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }
    private val model = "gemini-embedding-001"
    private val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent?key=$apiKey"

    suspend fun embed(text: String): List<Float> {
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(buildEmbedRequest(text, model))
        }.bodyAsText()
        val result = parseEmbedResponse(response)
        if (result == null) {
            log.warn("Embedding API response parse failed. response={}", response.take(500))
        }
        return result ?: error("Failed to parse embedding response")
    }

    internal fun buildEmbedRequest(text: String, modelName: String): String =
        """{"model":"models/$modelName","content":{"parts":[{"text":"${text.take(2048).escapeJson()}"}]}}"""

    internal fun parseEmbedResponse(json: String): List<Float>? {
        val valuesJson = Regex("\"values\"\\s*:\\s*\\[([^]]+)]").find(json)
            ?.groupValues?.get(1) ?: return null
        return valuesJson.split(",").mapNotNull { it.trim().toFloatOrNull() }
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(GoogleEmbeddingClient::class.java)
    }
}

private fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
