package io.github.veronikapj.wiki.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

    /**
     * Kotlin/Android 코드에서 grep할 패턴을 LLM에게 직접 요청.
     * 쿼리 의도에 맞는 클래스명, 함수명, 어노테이션 등 코드 식별자만 반환.
     */
    suspend fun extractGrepPatterns(query: String): List<String> {
        val prompt = """
            아래 질문의 의도에 맞는 Kotlin/Android 코드를 grep으로 찾으려 합니다.
            실제 코드에서 검색해야 할 클래스명, 함수명, 어노테이션, 인터페이스명을 줄바꿈으로 구분해 반환하세요.

            규칙:
            - 실제 Kotlin/Android 코드에 존재할 법한 식별자만 (PascalCase, camelCase, snake_case)
            - 너무 일반적인 단어(Type, Content, Custom, User, Agent 등)는 제외
            - 최대 10개
            - 설명 없이 식별자만 출력

            질문: $query
        """.trimIndent()
        return runCatching {
            llmFn(prompt).lines()
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotBlank() && it.length >= 3 && !it.contains(" ") }
                .take(10)
        }.getOrDefault(emptyList())
    }
}

class GoogleEmbeddingClient(
    private val apiKey: String,
    // 테스트에서 MockEngine 주입용. 기본은 CIO + 15s 타임아웃.
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    },
    private val maxAttempts: Int = 3,
    private val baseBackoffMs: Long = 500L,
) {
    private val model = "gemini-embedding-001"
    private val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent?key=$apiKey"

    /**
     * 임베딩 생성. 일시적 실패(네트워크/타임아웃, HTTP 429·5xx)는 지수 백오프로 [maxAttempts]회까지 재시도.
     * 2xx 응답인데 파싱 실패하면 재시도해도 무의미하므로 즉시 실패.
     */
    suspend fun embed(text: String): List<Float> {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            val body: String? = try {
                val response = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(buildEmbedRequest(text, model))
                }
                val status = response.status.value
                val text = response.bodyAsText()
                if (status == 429 || status >= 500) {
                    lastError = IllegalStateException("Embedding API HTTP $status: ${text.take(200)}")
                    null // 재시도 대상
                } else {
                    text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e // 네트워크/타임아웃 등 → 재시도
                null
            }

            if (body != null) {
                // 성공 응답: 파싱 실패는 재시도 무의미하므로 즉시 throw
                return parseEmbedResponse(body) ?: run {
                    log.warn("Embedding API response parse failed. response={}", body.take(500))
                    error("Failed to parse embedding response")
                }
            }

            if (attempt < maxAttempts - 1) {
                val backoffMs = baseBackoffMs shl attempt // 500, 1000, 2000...
                log.warn(
                    "Embedding 재시도 {}/{} (사유: {}), {}ms 후",
                    attempt + 1, maxAttempts, lastError?.message?.take(120), backoffMs,
                )
                delay(backoffMs)
            }
        }
        throw lastError ?: IllegalStateException("Embedding ${maxAttempts}회 시도 모두 실패")
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
