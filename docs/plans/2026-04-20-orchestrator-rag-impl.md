# OrchestratorAgent + RAG Phase 2 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** OrchestratorAgent(Koog AIAgent + Tool)로 슬랙 질문을 라우팅하고, ChromaDB 기반 RAG(LLM_EXPAND / GOOGLE_EMBEDDING 두 모드)를 Phase 2로 추가한다.

**Architecture:** SlackBotGateway → OrchestratorAgent(AIAgent) → ConfluenceTool(CQL) / VectorSearchTool(RAG). 시크릿은 env var → .env → config.yml 순서로 폴백. ChromaDB는 Docker로 로컬 실행, LLM_EXPAND 모드는 ChromaDB 내장 sentence-transformers + LLM 쿼리 확장, GOOGLE_EMBEDDING 모드는 text-embedding-004 + 명시적 벡터.

**Tech Stack:** Kotlin 2.3, Koog 0.8.0 (`@Tool`/`ToolRegistry`/`AIAgent`), Ktor 3.1.2 (ChromaDB HTTP), ChromaDB Docker

---

## Task 1: RagConfig + WikiConfig 확장

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/ConfigLoader.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/config/ConfigLoaderTest.kt`

**Step 1: 테스트 추가 (`ConfigLoaderTest.kt` 에 아래 케이스 추가)**

```kotlin
@Test
fun `loads rag config`() {
    val yaml = """
        model:
          provider: CLAUDE_CODE
        confluence:
          baseUrl: https://example.atlassian.net
          token: tok
        rag:
          enabled: true
          chromaUrl: http://localhost:8000
          embeddingMode: GOOGLE_EMBEDDING
    """.trimIndent()
    val config = ConfigLoader.fromString(yaml)
    assertEquals(true, config.rag.enabled)
    assertEquals("http://localhost:8000", config.rag.chromaUrl)
    assertEquals(EmbeddingMode.GOOGLE_EMBEDDING, config.rag.embeddingMode)
}

@Test
fun `rag defaults to disabled`() {
    val yaml = """
        model:
          provider: CLAUDE_CODE
        confluence:
          baseUrl: https://example.atlassian.net
          token: tok
    """.trimIndent()
    val config = ConfigLoader.fromString(yaml)
    assertEquals(false, config.rag.enabled)
    assertEquals(EmbeddingMode.LLM_EXPAND, config.rag.embeddingMode)
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
cd /Users/piljubae/AndroidStudioProjects/wikiq-agent
./gradlew test --tests "*.ConfigLoaderTest" 2>&1 | tail -5
```

**Step 3: `WikiConfig.kt` 에 RagConfig 추가**

```kotlin
package io.github.veronikapj.wiki.config

enum class ModelProvider { ANTHROPIC, GOOGLE, CLAUDE_CODE }
enum class EmbeddingMode { LLM_EXPAND, GOOGLE_EMBEDDING }

data class WikiConfig(
    val model: ModelConfig = ModelConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
    val slack: SlackConfig = SlackConfig(),
    val rag: RagConfig = RagConfig(),
)

data class ModelConfig(
    val provider: ModelProvider = ModelProvider.CLAUDE_CODE,
    val name: String? = null,
    val apiKey: String? = null,
)

data class ConfluenceConfig(
    val baseUrl: String = "",
    val token: String = "",
    val spaces: List<String> = emptyList(),
)

data class SlackConfig(
    val botToken: String = "",
    val appToken: String = "",
)

data class RagConfig(
    val enabled: Boolean = false,
    val chromaUrl: String = "http://localhost:8000",
    val embeddingMode: EmbeddingMode = EmbeddingMode.LLM_EXPAND,
    val googleApiKey: String? = null,
)
```

**Step 4: `ConfigLoader.kt` 에 rag 파싱 추가**

`fromString` 함수 내 파싱 로직에 아래 섹션 추가:

```kotlin
// 기존 변수 선언부에 추가
var ragEnabled = false
var chromaUrl = "http://localhost:8000"
var embeddingMode = EmbeddingMode.LLM_EXPAND
var ragGoogleApiKey: String? = null
var inRag = false

// 섹션 감지 when 블록에 추가
line == "rag:" -> { inRag = true; inModel = false; inConfluence = false; inSlack = false; inSpaces = false }

// 값 파싱 when 블록에 추가
inRag && trimmed.startsWith("enabled:") ->
    ragEnabled = trimmed.substringAfter("enabled:").trim() == "true"
inRag && trimmed.startsWith("chromaUrl:") ->
    chromaUrl = trimmed.substringAfter("chromaUrl:").trim()
inRag && trimmed.startsWith("embeddingMode:") ->
    embeddingMode = runCatching {
        EmbeddingMode.valueOf(trimmed.substringAfter("embeddingMode:").trim().uppercase())
    }.getOrDefault(EmbeddingMode.LLM_EXPAND)
inRag && trimmed.startsWith("googleApiKey:") ->
    ragGoogleApiKey = trimmed.substringAfter("googleApiKey:").trim().ifEmpty { null }
```

`return WikiConfig(...)` 에 `rag` 필드 추가:

```kotlin
return WikiConfig(
    model = ModelConfig(provider, modelName, apiKey),
    confluence = ConfluenceConfig(baseUrl, token, spaces),
    slack = SlackConfig(botToken, appToken),
    rag = RagConfig(ragEnabled, chromaUrl, embeddingMode, ragGoogleApiKey),
)
```

**Step 5: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.ConfigLoaderTest" 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL (기존 3개 + 신규 2개 = 5 tests)

**Step 6: 커밋**

```bash
git add src
git commit -m "feat: RagConfig 추가 및 ConfigLoader rag 섹션 파싱"
```

---

## Task 2: SecretLoader (env → .env → config 폴백)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/config/SecretLoader.kt`
- Create: `.env.example`
- Modify: `.gitignore`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/config/SecretLoaderTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SecretLoaderTest {

    @Test
    fun `resolve returns config value when no env or dotenv`() {
        val result = SecretLoader.resolve("NONEXISTENT_KEY_XYZ_12345", "fallback-value")
        assertEquals("fallback-value", result)
    }

    @Test
    fun `parseDotEnv parses KEY=VALUE lines`() {
        val content = """
            SLACK_BOT_TOKEN=xoxb-test
            # comment line
            CONFLUENCE_TOKEN=mytoken
            EMPTY_LINE=
        """.trimIndent()
        val map = SecretLoader.parseDotEnv(content)
        assertEquals("xoxb-test", map["SLACK_BOT_TOKEN"])
        assertEquals("mytoken", map["CONFLUENCE_TOKEN"])
        assertEquals(null, map["# comment line"])
    }

    @Test
    fun `parseDotEnv ignores comments and blank lines`() {
        val content = "# this is a comment\n\nKEY=value"
        val map = SecretLoader.parseDotEnv(content)
        assertEquals(1, map.size)
        assertEquals("value", map["KEY"])
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.SecretLoaderTest" 2>&1 | tail -5
```

**Step 3: `SecretLoader.kt` 작성**

```kotlin
package io.github.veronikapj.wiki.config

import java.io.File
import org.slf4j.LoggerFactory

object SecretLoader {
    private val log = LoggerFactory.getLogger(SecretLoader::class.java)
    private val dotEnvCache: Map<String, String> by lazy { loadDotEnv() }

    fun resolve(envKey: String, configValue: String): String =
        System.getenv(envKey)
            ?: dotEnvCache[envKey]
            ?: configValue

    fun resolveNullable(envKey: String, configValue: String?): String? =
        System.getenv(envKey)
            ?: dotEnvCache[envKey]
            ?: configValue

    internal fun parseDotEnv(content: String): Map<String, String> =
        content.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 1) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()

    private fun loadDotEnv(): Map<String, String> {
        val file = File(".env")
        if (!file.exists()) return emptyMap()
        log.info("Loading secrets from .env")
        return parseDotEnv(file.readText())
    }
}
```

**Step 4: `.env.example` 작성**

```
# Slack
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...

# Confluence
CONFLUENCE_TOKEN=<base64 email:api-token>

# LLM (provider=ANTHROPIC 시)
ANTHROPIC_API_KEY=sk-ant-...

# Google (provider=GOOGLE 또는 rag.embeddingMode=GOOGLE_EMBEDDING 시)
GOOGLE_API_KEY=AIza...
```

**Step 5: `.gitignore` 에 `.env` 추가**

`.gitignore` 에 아래 라인 추가:
```
.env
```

**Step 6: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.SecretLoaderTest" 2>&1 | tail -5
```
Expected: PASS (3 tests)

**Step 7: 커밋**

```bash
git add src .env.example .gitignore
git commit -m "feat: SecretLoader env→.env→config 폴백 + .env.example"
```

---

## Task 3: ChromaClient (HTTP REST)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/rag/ChromaClient.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/rag/ChromaClientTest.kt`

**Step 1: 테스트 작성**

```kotlin
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
    fun `parseQueryResults extracts documents`() {
        val response = """{"ids":[["id1","id2"]],"documents":[["doc1 content","doc2 content"]],"metadatas":[[{"title":"Page1"},{"title":"Page2"}]],"distances":[[0.1,0.2]]}"""
        val results = client.parseQueryResults(response)
        assertEquals(2, results.size)
        assertEquals("doc1 content", results[0].document)
        assertEquals("Page1", results[0].metadata["title"])
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.ChromaClientTest" 2>&1 | tail -5
```

**Step 3: `ChromaClient.kt` 작성**

```kotlin
package io.github.veronikapj.wiki.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

data class ChromaQueryResult(
    val id: String,
    val document: String,
    val metadata: Map<String, String>,
    val distance: Float,
)

class ChromaClient(private val baseUrl: String) {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    suspend fun getOrCreateCollection(name: String): String {
        val body = """{"name":"$name","get_or_create":true}"""
        val response = httpClient.post("$baseUrl/api/v1/collections") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)
            ?: error("Cannot parse collection id from: $response")
    }

    suspend fun addDocuments(
        collectionId: String,
        ids: List<String>,
        documents: List<String>,
        embeddings: List<List<Float>>? = null,
        metadatas: List<Map<String, String>> = emptyList(),
    ) {
        val body = buildAddBody(ids, documents, embeddings, metadatas)
        log.debug("addDocuments to {}: {} docs", collectionId, ids.size)
        httpClient.post("$baseUrl/api/v1/collections/$collectionId/add") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun query(
        collectionId: String,
        queryTexts: List<String>? = null,
        queryEmbeddings: List<List<Float>>? = null,
        nResults: Int = 5,
    ): List<ChromaQueryResult> {
        val body = buildQueryBody(queryTexts, queryEmbeddings, nResults)
        val response = httpClient.post("$baseUrl/api/v1/collections/$collectionId/query") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        return parseQueryResults(response)
    }

    internal fun buildAddBody(
        ids: List<String>,
        documents: List<String>,
        embeddings: List<List<Float>>?,
        metadatas: List<Map<String, String>>,
    ): String = buildString {
        append("{")
        append("\"ids\":[${ids.joinToString(",") { "\"$it\"" }}]")
        append(",\"documents\":[${documents.joinToString(",") { "\"${it.escapeJson()}\"" }}]")
        if (embeddings != null) {
            append(",\"embeddings\":[${embeddings.joinToString(",") { vec -> "[${vec.joinToString(",")}]" }}]")
        }
        if (metadatas.isNotEmpty()) {
            append(",\"metadatas\":[${metadatas.joinToString(",") { meta ->
                "{${meta.entries.joinToString(",") { (k, v) -> "\"$k\":\"${v.escapeJson()}\"" }}}"
            }}]")
        }
        append("}")
    }

    internal fun buildQueryBody(
        queryTexts: List<String>?,
        queryEmbeddings: List<List<Float>>?,
        nResults: Int,
    ): String = buildString {
        append("{")
        if (queryTexts != null) {
            append("\"query_texts\":[${queryTexts.joinToString(",") { "\"${it.escapeJson()}\"" }}]")
        }
        if (queryEmbeddings != null) {
            val sep = if (queryTexts != null) "," else ""
            append("${sep}\"query_embeddings\":[${queryEmbeddings.joinToString(",") { vec -> "[${vec.joinToString(",")}]" }}]")
        }
        val sep = if (queryTexts != null || queryEmbeddings != null) "," else ""
        append("${sep}\"n_results\":$nResults")
        append("}")
    }

    internal fun parseQueryResults(json: String): List<ChromaQueryResult> {
        val ids = Regex("\"ids\"\\s*:\\s*\\[\\[([^]]+)]]").find(json)
            ?.groupValues?.get(1)?.split(",")
            ?.map { it.trim().trim('"') } ?: return emptyList()
        val docs = Regex("\"documents\"\\s*:\\s*\\[\\[([^]]+)]]").find(json)
            ?.groupValues?.get(1)?.split(Regex(",(?=\")"))
            ?.map { it.trim().trim('"') } ?: emptyList()
        val distances = Regex("\"distances\"\\s*:\\s*\\[\\[([^]]+)]]").find(json)
            ?.groupValues?.get(1)?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()

        val metaBlock = Regex("\"metadatas\"\\s*:\\s*\\[\\[(.+?)]]", RegexOption.DOT_MATCHES_ALL).find(json)
            ?.groupValues?.get(1) ?: ""
        val metas = Regex("\\{([^}]*)\\}").findAll(metaBlock).map { m ->
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(m.value)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.toList()

        return ids.mapIndexed { i, id ->
            ChromaQueryResult(
                id = id,
                document = docs.getOrElse(i) { "" },
                metadata = metas.getOrElse(i) { emptyMap() },
                distance = distances.getOrElse(i) { 1f },
            )
        }
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(ChromaClient::class.java)
    }
}

private fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.ChromaClientTest" 2>&1 | tail -5
```
Expected: PASS (4 tests)

**Step 5: 커밋**

```bash
git add src
git commit -m "feat: ChromaClient HTTP REST 클라이언트 구현"
```

---

## Task 4: EmbeddingClient (LLM_EXPAND + GOOGLE_EMBEDDING)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/rag/EmbeddingClient.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/rag/EmbeddingClientTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.config.EmbeddingMode
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmbeddingClientTest {

    @Test
    fun `LlmExpandClient returns non-blank enriched text`() {
        val client = LlmExpandClient(llmFn = { text -> "키워드: 배포, deploy, release\n관련질문: $text" })
        val result = client.enrichDocument("배포 프로세스")
        assertTrue(result.contains("배포"))
    }

    @Test
    fun `LlmExpandClient expands query with synonyms`() {
        val client = LlmExpandClient(llmFn = { text -> "$text deploy release" })
        val expanded = client.expandQuery("배포")
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
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.EmbeddingClientTest" 2>&1 | tail -5
```

**Step 3: `EmbeddingClient.kt` 작성**

```kotlin
package io.github.veronikapj.wiki.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

// LLM_EXPAND 모드: LLM으로 문서 enrichment + 쿼리 확장
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

// GOOGLE_EMBEDDING 모드: text-embedding-004 API
class GoogleEmbeddingClient(private val apiKey: String) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }
    private val model = "text-embedding-004"
    private val endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent?key=$apiKey"

    suspend fun embed(text: String): List<Float> {
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(buildEmbedRequest(text, model))
        }.bodyAsText()
        return parseEmbedResponse(response) ?: error("Failed to parse embedding response")
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
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.EmbeddingClientTest" 2>&1 | tail -5
```
Expected: PASS (4 tests)

**Step 5: 커밋**

```bash
git add src
git commit -m "feat: EmbeddingClient LLM_EXPAND + GOOGLE_EMBEDDING 구현"
```

---

## Task 5: VectorIndexAgent

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/rag/VectorIndexAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/rag/VectorIndexAgentTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePage
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorIndexAgentTest {

    private val mockChroma = mockk<ChromaClient>(relaxed = true)
    private val mockConfluence = mockk<ConfluenceClient>()
    private val llmExpand = LlmExpandClient(llmFn = { text -> "keyword: $text" })

    @Test
    fun `index fetches pages and adds to chroma`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"
        coEvery { mockConfluence.searchPages(any(), any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "https://co.atlassian.net/wiki/1")
        )
        coEvery { mockConfluence.fetchPageContent("1") } returns ConfluencePage(
            "1", "배포 가이드", "배포 절차 내용", "https://co.atlassian.net/wiki/1"
        )

        val agent = VectorIndexAgent(mockConfluence, mockChroma, llmExpand, null, config, listOf("DEV"))
        val count = agent.indexAll()

        assertEquals(1, count)
        coVerify { mockChroma.addDocuments(any(), any(), any(), null, any()) }
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.VectorIndexAgentTest" 2>&1 | tail -5
```

**Step 3: `VectorIndexAgent.kt` 작성**

```kotlin
package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import org.slf4j.LoggerFactory

class VectorIndexAgent(
    private val confluenceClient: ConfluenceClient,
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val googleEmbeddingClient: GoogleEmbeddingClient?,
    private val config: RagConfig,
    private val spaces: List<String>,
    private val collectionName: String = "wiki-pages",
    private val topK: Int = 100,
) {
    suspend fun indexAll(): Int {
        val collectionId = chromaClient.getOrCreateCollection(collectionName)
        val pages = confluenceClient.searchPages("", spaces, topK)
        log.info("Indexing {} pages into ChromaDB (mode={})", pages.size, config.embeddingMode)

        var indexed = 0
        pages.chunked(10).forEach { batch ->
            val ids = mutableListOf<String>()
            val docs = mutableListOf<String>()
            val embeddings = mutableListOf<List<Float>>()
            val metas = mutableListOf<Map<String, String>>()

            batch.forEach { ref ->
                runCatching {
                    val page = confluenceClient.fetchPageContent(ref.id)
                    val text = when (config.embeddingMode) {
                        EmbeddingMode.LLM_EXPAND ->
                            requireNotNull(llmExpandClient).enrichDocument("${page.title}\n${page.content}")
                        EmbeddingMode.GOOGLE_EMBEDDING ->
                            "${page.title}\n${page.content}"
                    }
                    ids += ref.id
                    docs += text
                    if (config.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING) {
                        embeddings += requireNotNull(googleEmbeddingClient).embed(text)
                    }
                    metas += mapOf("title" to page.title, "url" to page.webUrl)
                    indexed++
                }.onFailure { log.warn("Failed to index page {}: {}", ref.id, it.message) }
            }

            if (ids.isNotEmpty()) {
                chromaClient.addDocuments(
                    collectionId = collectionId,
                    ids = ids,
                    documents = docs,
                    embeddings = if (embeddings.isNotEmpty()) embeddings else null,
                    metadatas = metas,
                )
            }
        }
        log.info("Indexed {} pages", indexed)
        return indexed
    }

    companion object {
        private val log = LoggerFactory.getLogger(VectorIndexAgent::class.java)
    }
}
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.VectorIndexAgentTest" 2>&1 | tail -5
```
Expected: PASS

**Step 5: 커밋**

```bash
git add src
git commit -m "feat: VectorIndexAgent Confluence → ChromaDB 인덱싱"
```

---

## Task 6: VectorSearchAgent

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/rag/VectorSearchAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/rag/VectorSearchAgentTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class VectorSearchAgentTest {

    private val mockChroma = mockk<ChromaClient>(relaxed = true)
    private val llmExpand = LlmExpandClient(llmFn = { query -> "$query deploy release" })

    @Test
    fun `search LLM_EXPAND returns formatted results`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"
        coEvery { mockChroma.query("col-id", any(), null, any()) } returns listOf(
            ChromaQueryResult("1", "배포 절차 내용", mapOf("title" to "배포 가이드", "url" to "https://co.atlassian.net/1"), 0.2f)
        )

        val agent = VectorSearchAgent(mockChroma, llmExpand, null, config)
        val result = agent.search("배포")

        assertTrue(result.contains("배포 가이드"))
        assertTrue(result.contains("https://"))
    }

    @Test
    fun `search returns no results message when empty`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"
        coEvery { mockChroma.query(any(), any(), any(), any()) } returns emptyList()

        val agent = VectorSearchAgent(mockChroma, llmExpand, null, config)
        val result = agent.search("존재하지않는것")

        assertTrue(result.contains("찾을 수 없"))
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.VectorSearchAgentTest" 2>&1 | tail -5
```

**Step 3: `VectorSearchAgent.kt` 작성**

```kotlin
package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import org.slf4j.LoggerFactory

class VectorSearchAgent(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val googleEmbeddingClient: GoogleEmbeddingClient?,
    private val config: RagConfig,
    private val collectionName: String = "wiki-pages",
) {
    suspend fun search(query: String, nResults: Int = 5): String {
        val collectionId = chromaClient.getOrCreateCollection(collectionName)

        val results = when (config.embeddingMode) {
            EmbeddingMode.LLM_EXPAND -> {
                val expanded = requireNotNull(llmExpandClient).expandQuery(query)
                log.info("LLM_EXPAND query: '{}' → '{}'", query, expanded.take(100))
                chromaClient.query(collectionId, queryTexts = listOf(expanded), nResults = nResults)
            }
            EmbeddingMode.GOOGLE_EMBEDDING -> {
                val embedding = requireNotNull(googleEmbeddingClient).embed(query)
                chromaClient.query(collectionId, queryEmbeddings = listOf(embedding), nResults = nResults)
            }
        }

        if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (RAG query: $query)"

        return buildString {
            appendLine("*\"$query\"* 관련 문서 (RAG, ${results.size}건):\n")
            results.forEachIndexed { i, r ->
                val title = r.metadata["title"] ?: "Untitled"
                val url = r.metadata["url"] ?: ""
                val snippet = r.document.lines().take(3).joinToString(" ").take(200)
                appendLine("${i + 1}. *$title*")
                if (url.isNotBlank()) appendLine("   <$url|링크>")
                appendLine("   > $snippet")
                appendLine()
            }
        }.trim()
    }

    companion object {
        private val log = LoggerFactory.getLogger(VectorSearchAgent::class.java)
    }
}
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.VectorSearchAgentTest" 2>&1 | tail -5
```
Expected: PASS (2 tests)

**Step 5: 커밋**

```bash
git add src
git commit -m "feat: VectorSearchAgent ChromaDB 검색 (LLM_EXPAND + GOOGLE_EMBEDDING)"
```

---

## Task 7: Koog Tool 정의 (ConfluenceTool + VectorSearchTool)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt`
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/VectorSearchTool.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/agent/tool/ToolTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.agent.tool

import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolTest {

    @Test
    fun `ConfluenceTool delegates to ConfluenceSearchAgent`() = runTest {
        val mockAgent = mockk<ConfluenceSearchAgent>()
        coEvery { mockAgent.search("배포") } returns "배포 가이드 결과"
        val tool = ConfluenceTool(mockAgent)
        val result = tool.confluenceSearch("배포")
        assertTrue(result.contains("배포"))
    }

    @Test
    fun `VectorSearchTool delegates to VectorSearchAgent`() = runTest {
        val mockAgent = mockk<VectorSearchAgent>()
        coEvery { mockAgent.search("배포") } returns "RAG 결과"
        val tool = VectorSearchTool(mockAgent)
        val result = tool.vectorSearch("배포")
        assertTrue(result.contains("RAG"))
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.ToolTest" 2>&1 | tail -5
```

**Step 3: `ConfluenceTool.kt` 작성**

```kotlin
package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import kotlinx.coroutines.runBlocking

class ConfluenceTool(private val searchAgent: ConfluenceSearchAgent) {

    @Tool("confluenceSearch")
    @LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
    fun confluenceSearch(
        @LLMDescription("검색할 질문 또는 키워드 (한국어 가능)")
        query: String,
    ): String = runBlocking { searchAgent.search(query) }
}
```

**Step 4: `VectorSearchTool.kt` 작성**

```kotlin
package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import kotlinx.coroutines.runBlocking

class VectorSearchTool(private val searchAgent: VectorSearchAgent) {

    @Tool("vectorSearch")
    @LLMDescription("RAG(의미 기반 벡터 검색)으로 Confluence 문서를 검색합니다. CQL 검색 결과가 부족할 때 사용하세요.")
    fun vectorSearch(
        @LLMDescription("검색할 질문 또는 키워드")
        query: String,
    ): String = runBlocking { searchAgent.search(query) }
}
```

**Step 5: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.ToolTest" 2>&1 | tail -5
```
Expected: PASS (2 tests)

**Step 6: 커밋**

```bash
git add src
git commit -m "feat: Koog Tool 정의 (ConfluenceTool, VectorSearchTool)"
```

---

## Task 8: OrchestratorAgent (Koog AIAgent)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class OrchestratorAgentTest {

    @Test
    fun `build creates agent with confluenceTool only when rag disabled`() {
        val mockSearchAgent = mockk<ConfluenceSearchAgent>()
        val confluenceTool = ConfluenceTool(mockSearchAgent)
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            vectorSearchTool = null,
            executor = io.github.veronikapj.wiki.llm.LLMExecutorBuilder.build(
                io.github.veronikapj.wiki.config.ModelConfig()
            ),
        )
        assertNotNull(agent)
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.OrchestratorAgentTest" 2>&1 | tail -5
```

**Step 3: `OrchestratorAgent.kt` 작성**

```kotlin
@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import org.slf4j.LoggerFactory

class OrchestratorAgent(
    private val confluenceTool: ConfluenceTool,
    private val vectorSearchTool: VectorSearchTool?,
    private val executor: MultiLLMPromptExecutor,
) {
    suspend fun answer(question: String): String {
        log.info("OrchestratorAgent answering: '{}'", question)
        val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)
        for ((index, model) in fallbackModels.withIndex()) {
            val result = runCatching { buildAgent(model).run(question) }
            val ex = result.exceptionOrNull()
            if (ex == null) return result.getOrThrow() ?: "답변을 생성하지 못했습니다."
            if (index < fallbackModels.lastIndex) {
                log.warn("Retrying with {}", fallbackModels[index + 1].id)
                continue
            }
            log.error("All models failed: {}", ex.message)
            return "검색 중 오류가 발생했습니다: ${ex.message}"
        }
        error("unreachable")
    }

    private fun buildAgent(model: LLModel): AIAgent<String, String> {
        val systemPrompt = buildString {
            appendLine("당신은 Confluence 위키 검색 전문가입니다.")
            appendLine("사용자의 질문에 답하기 위해 반드시 제공된 Tool을 사용해 검색하세요.")
            appendLine("검색 없이 직접 답변하지 마세요.")
            if (vectorSearchTool != null) {
                appendLine("confluenceSearch로 먼저 검색하고, 결과가 부족하면 vectorSearch도 사용하세요.")
            }
            appendLine("검색 결과를 바탕으로 요약과 링크를 함께 제공하세요.")
        }

        return AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
                    system(systemPrompt)
                },
                model = model,
                maxAgentIterations = 10,
            ),
            toolRegistry = ToolRegistry {
                tool(confluenceTool::confluenceSearch)
                if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
            },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrchestratorAgent::class.java)
    }
}
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.OrchestratorAgentTest" 2>&1 | tail -5
```
Expected: PASS

**Step 5: 커밋**

```bash
git add src
git commit -m "feat: OrchestratorAgent Koog AIAgent 구현"
```

---

## Task 9: SlackConfigHandler + SlackBotGateway 업데이트

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandlerTest.kt`

**Step 1: SlackConfigHandlerTest 에 reindex 케이스 추가**

```kotlin
@Test
fun `handle reindex calls indexer`() {
    var indexCalled = false
    val handler = SlackConfigHandler(makeConfig(), onReindex = { indexCalled = true; 42 })
    val result = handler.handle("/wiki reindex")
    assertTrue(indexCalled)
    assertTrue(result.contains("42"))
}

@Test
fun `handle reindex status returns last index info`() {
    val handler = SlackConfigHandler(makeConfig(), onReindex = { 0 })
    handler.handle("/wiki reindex")
    val result = handler.handle("/wiki reindex status")
    assertTrue(result.contains("마지막") || result.contains("문서"))
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.SlackConfigHandlerTest" 2>&1 | tail -5
```

**Step 3: `SlackConfigHandler.kt` 업데이트**

```kotlin
package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.WikiConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SlackConfigHandler(
    private var config: WikiConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
    private val onReindex: (suspend () -> Int)? = null,
) {
    private var lastIndexTime: LocalDateTime? = null
    private var lastIndexCount: Int = 0

    fun currentConfig(): WikiConfig = config

    fun handle(command: String): String {
        val parts = command.trim().split(" ")
        return when {
            parts.size >= 3 && parts[1] == "config" && parts[2] == "space" -> {
                val arg = parts.getOrNull(3)
                if (arg == "show" || arg == null) showSpaces()
                else setSpaces(parts.drop(3).joinToString(" "))
            }
            parts.size >= 2 && parts[1] == "reindex" && parts.getOrNull(2) == "status" ->
                reindexStatus()
            parts.size >= 2 && parts[1] == "reindex" ->
                triggerReindex()
            else -> helpMessage()
        }
    }

    private fun triggerReindex(): String {
        val indexer = onReindex ?: return "RAG가 비활성화 상태입니다. config.yml에서 rag.enabled=true로 설정하세요."
        return runCatching {
            val count = runBlocking { indexer() }
            lastIndexCount = count
            lastIndexTime = LocalDateTime.now()
            "$count 개 문서 인덱싱 완료"
        }.getOrElse { "인덱싱 실패: ${it.message}" }
    }

    private fun reindexStatus(): String {
        val time = lastIndexTime?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            ?: "아직 인덱싱하지 않았습니다"
        return "마지막 인덱싱: $time / 문서 수: $lastIndexCount"
    }

    private fun setSpaces(spacesArg: String): String {
        val newSpaces = spacesArg.split(",").map { it.trim() }.filter { it.isNotBlank() }
        config = config.copy(confluence = config.confluence.copy(spaces = newSpaces))
        if (persistOnChange) ConfigLoader.save(config, configPath)
        log.info("Confluence spaces updated: {}", newSpaces)
        return "검색 범위 업데이트: ${newSpaces.joinToString(", ")}"
    }

    private fun showSpaces(): String {
        val spaces = config.confluence.spaces
        return if (spaces.isEmpty()) "현재 설정된 스페이스가 없습니다."
        else "현재 검색 스페이스: ${spaces.joinToString(", ")}"
    }

    private fun helpMessage() = """
        사용법:
        • `/wiki <질문>` — Confluence에서 검색
        • `/wiki config space DEV,PM,HR` — 검색 스페이스 설정
        • `/wiki config space show` — 현재 설정 확인
        • `/wiki reindex` — RAG 재인덱싱
        • `/wiki reindex status` — 마지막 인덱싱 정보
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(SlackConfigHandler::class.java)
    }
}
```

**Step 4: `SlackBotGateway.kt` — OrchestratorAgent 사용하도록 업데이트**

`ConfluenceSearchAgent` → `OrchestratorAgent` 로 교체:

```kotlin
package io.github.veronikapj.wiki.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.config.SlackConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
) {
    private val app = App()

    fun start() {
        registerMentionHandler()
        registerSlashCommand()
        log.info("Starting Slack bot (Socket Mode)...")
        SocketModeApp(slackConfig.appToken, app).start()
    }

    private fun registerMentionHandler() {
        app.event(com.slack.api.model.event.AppMentionEvent::class.java) { payload, ctx ->
            val query = extractQuery(payload.event.text)
            log.info("Mention received: '{}'", query)
            ctx.asyncClient().chatPostMessage { it
                .channel(payload.event.channel)
                .threadTs(payload.event.ts)
                .text(":mag: 검색 중...")
            }
            val result = runBlocking { orchestrator.answer(query) }
            ctx.asyncClient().chatPostMessage { it
                .channel(payload.event.channel)
                .threadTs(payload.event.ts)
                .text(result)
            }
            ctx.ack()
        }
    }

    private fun registerSlashCommand() {
        app.command("/wiki") { req, ctx ->
            val fullCommand = "/wiki ${req.payload.text}"
            val result = configHandler.handle(fullCommand)
            ctx.ack(result)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackBotGateway::class.java)

        fun extractQuery(text: String): String =
            text.replace(Regex("<@[A-Z0-9]+>"), "").trim()
    }
}
```

**Step 5: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.SlackConfigHandlerTest" 2>&1 | tail -5
```
Expected: PASS (5 tests)

**Step 6: 커밋**

```bash
git add src
git commit -m "feat: SlackConfigHandler reindex 커맨드 + SlackBotGateway OrchestratorAgent 연결"
```

---

## Task 10: Main.kt 업데이트 + 전체 빌드

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`
- Modify: `.wikiq/config.yml`

**Step 1: `Main.kt` 작성**

```kotlin
@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki

import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.EmbeddingMode as RagEmbeddingMode
import io.github.veronikapj.wiki.rag.GoogleEmbeddingClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import io.github.veronikapj.wiki.rag.VectorIndexAgent
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.github.veronikapj.wiki.slack.SlackBotGateway
import io.github.veronikapj.wiki.slack.SlackConfigHandler
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wiki.Main")

fun main() {
    val config = ConfigLoader.load()
    log.info("Provider: {}, Spaces: {}, RAG: {}", config.model.provider, config.confluence.spaces, config.rag.enabled)

    // 시크릿 로드 (env → .env → config 폴백)
    val confluenceToken = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
    val slackBotToken = SecretLoader.resolve("SLACK_BOT_TOKEN", config.slack.botToken)
    val slackAppToken = SecretLoader.resolve("SLACK_APP_TOKEN", config.slack.appToken)

    val confluenceClient = ConfluenceClient(
        baseUrl = config.confluence.baseUrl,
        token = confluenceToken,
    )

    val executor = LLMExecutorBuilder.build(config.model)
    val model = LLMExecutorBuilder.defaultModel(config.model)

    // CQL 검색 에이전트
    val confluenceSearchAgent = ConfluenceSearchAgent(
        confluenceClient = confluenceClient,
        spaces = config.confluence.spaces,
    )
    val confluenceTool = ConfluenceTool(confluenceSearchAgent)

    // RAG 에이전트 (rag.enabled=true 시만 생성)
    var vectorSearchTool: VectorSearchTool? = null
    var vectorIndexAgent: VectorIndexAgent? = null

    if (config.rag.enabled) {
        val chromaClient = ChromaClient(config.rag.chromaUrl)
        val googleApiKey = SecretLoader.resolveNullable("GOOGLE_API_KEY", config.rag.googleApiKey)
        val llmFn: suspend (String) -> String = { prompt ->
            executor.execute(
                ai.koog.prompt.dsl.prompt("llm") { user(prompt) }, model
            ).joinToString("") { it.content }
        }
        val llmExpandClient = LlmExpandClient(llmFn)
        val googleEmbeddingClient = if (config.rag.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING)
            GoogleEmbeddingClient(requireNotNull(googleApiKey) { "GOOGLE_API_KEY required for GOOGLE_EMBEDDING mode" })
        else null

        val vectorSearchAgent = VectorSearchAgent(chromaClient, llmExpandClient, googleEmbeddingClient, config.rag)
        vectorSearchTool = VectorSearchTool(vectorSearchAgent)
        vectorIndexAgent = VectorIndexAgent(confluenceClient, chromaClient, llmExpandClient, googleEmbeddingClient, config.rag, config.confluence.spaces)
        log.info("RAG enabled (mode={})", config.rag.embeddingMode)
    }

    val orchestrator = OrchestratorAgent(confluenceTool, vectorSearchTool, executor)

    val configHandler = SlackConfigHandler(
        config = config,
        persistOnChange = true,
        onReindex = vectorIndexAgent?.let { agent -> { agent.indexAll() } },
    )

    val gateway = SlackBotGateway(
        slackConfig = config.slack.copy(botToken = slackBotToken, appToken = slackAppToken),
        orchestrator = orchestrator,
        configHandler = configHandler,
    )

    gateway.start()
}
```

**Step 2: `.wikiq/config.yml` rag 섹션 추가**

```yaml
model:
  provider: CLAUDE_CODE
confluence:
  baseUrl: https://yourcompany.atlassian.net
  spaces:
    - DEV
    - PM
slack: {}
rag:
  enabled: false
  chromaUrl: http://localhost:8000
  embeddingMode: LLM_EXPAND
```

**Step 3: 전체 빌드 확인**

```bash
./gradlew build 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

**Step 4: 커밋**

```bash
git add src .wikiq/config.yml
git commit -m "feat: Main.kt OrchestratorAgent + RAG 연결 완료"
```
