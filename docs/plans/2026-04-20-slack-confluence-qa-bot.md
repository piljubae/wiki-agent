# Slack-Confluence Q&A Bot (wikiq-agent) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 슬랙에서 `@wikiq 질문` 하면 Confluence 위키를 CQL로 검색해 요약 + 링크를 스레드로 답변하는 봇을 만든다.

**Architecture:** SlackBotGateway(Bolt Socket Mode) → OrchestratorAgent → ConfluenceSearchAgent(CQL) → 슬랙 응답. autodoc-agent의 Koog + A2A 패턴을 재사용하고 Slack 레이어만 추가한다. Socket Mode를 사용해 공개 URL 없이 로컬에서도 실행 가능하다.

**Tech Stack:** Kotlin 2.3, Koog 0.8.0, Slack Bolt SDK 1.46, Ktor 3.1.2, Confluence REST API (CQL), kaml(YAML config)

---

## 사전 준비 (코드 작성 전 필독)

### Slack App 설정
1. https://api.slack.com/apps → "Create New App" → "From scratch"
2. "Socket Mode" 탭 → Enable Socket Mode → App-Level Token 생성 (`connections:write` scope) → `SLACK_APP_TOKEN` 메모
3. "OAuth & Permissions" → Bot Token Scopes 추가:
   - `app_mentions:read`, `chat:write`, `commands`, `channels:read`
4. "Slash Commands" → `/wikiq` 추가 (Request URL은 Socket Mode라 불필요)
5. "Event Subscriptions" → Enable Events → "Subscribe to bot events": `app_mention`
6. "Install App" → 워크스페이스에 설치 → `SLACK_BOT_TOKEN` 메모

### Confluence API 토큰
- https://id.atlassian.com/manage-profile/security/api-tokens → "Create API token"
- `email:token` 형식으로 Base64 인코딩: `echo -n "email@co.kr:token" | base64`

---

## Task 1: 새 레포 + Gradle 프로젝트 초기화

**Files:**
- Create: `/Users/piljubae/AndroidStudioProjects/wikiq-agent/` (새 디렉터리)
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/Main.kt`

**Step 1: 디렉터리 생성 및 git 초기화**

```bash
mkdir -p /Users/piljubae/AndroidStudioProjects/wikiq-agent
cd /Users/piljubae/AndroidStudioProjects/wikiq-agent
git init
mkdir -p src/main/kotlin/io/github/veronikapj/wikiq
mkdir -p src/test/kotlin/io/github/veronikapj/wikiq
mkdir -p .wikiq
```

**Step 2: `settings.gradle.kts` 작성**

```kotlin
rootProject.name = "wikiq-agent"
```

**Step 3: `build.gradle.kts` 작성**

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/koog/public")
}

dependencies {
    implementation("ai.koog:koog-agents-jvm:0.8.0")
    implementation("ai.koog:agents-features-a2a-server-jvm:0.8.0")
    implementation("ai.koog:agents-features-a2a-client-jvm:0.8.0")
    implementation("ai.koog:a2a-transport-server-jsonrpc-http-jvm:0.8.0")
    implementation("ai.koog:a2a-transport-client-jsonrpc-http-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-anthropic-client-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-google-client-jvm:0.8.0")
    implementation("com.slack.api:bolt:1.46.0")
    implementation("com.slack.api:bolt-socket-mode:1.46.0")
    implementation("com.neovisionaries:nv-websocket-client:2.14")
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("com.charleskorn.kaml:kaml:0.67.0")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.veronikapj.wikiq.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.test {
    useJUnitPlatform()
}

configurations.all {
    resolutionStrategy.force(
        "com.fasterxml.jackson.core:jackson-databind:2.15.2",
        "com.fasterxml.jackson.core:jackson-core:2.15.2",
        "com.fasterxml.jackson.core:jackson-annotations:2.15.2",
    )
}
```

**Step 4: `src/main/kotlin/io/github/veronikapj/wikiq/Main.kt` 작성 (플레이스홀더)**

```kotlin
package io.github.veronikapj.wikiq

fun main() {
    println("wikiq-agent starting...")
}
```

**Step 5: 빌드 확인**

```bash
cd /Users/piljubae/AndroidStudioProjects/wikiq-agent
./gradlew build
```
Expected: BUILD SUCCESSFUL

**Step 6: 커밋**

```bash
git add .
git commit -m "chore: Gradle 프로젝트 초기화"
```

---

## Task 2: WikiqConfig + ConfigLoader

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/config/WikiqConfig.kt`
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/config/ConfigLoader.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wikiq/config/ConfigLoaderTest.kt`
- Create: `.wikiq/config.yml` (샘플)

**Step 1: 테스트 먼저 작성**

`src/test/kotlin/io/github/veronikapj/wikiq/config/ConfigLoaderTest.kt`:

```kotlin
package io.github.veronikapj.wikiq.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigLoaderTest {

    @Test
    fun `loads CLAUDE_CODE provider`() {
        val yaml = """
            model:
              provider: CLAUDE_CODE
            confluence:
              baseUrl: https://example.atlassian.net
              token: mytoken
              spaces:
                - DEV
                - PM
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(ModelProvider.CLAUDE_CODE, config.model.provider)
        assertEquals(listOf("DEV", "PM"), config.confluence.spaces)
    }

    @Test
    fun `loads ANTHROPIC provider with model name`() {
        val yaml = """
            model:
              provider: ANTHROPIC
              name: claude-sonnet-4-6
              apiKey: sk-ant-test
            confluence:
              baseUrl: https://example.atlassian.net
              token: token
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(ModelProvider.ANTHROPIC, config.model.provider)
        assertEquals("claude-sonnet-4-6", config.model.name)
    }

    @Test
    fun `inline comment stripped from value`() {
        val yaml = """
            model:
              provider: GOOGLE # gemini
            confluence:
              baseUrl: https://example.atlassian.net
              token: tok
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(ModelProvider.GOOGLE, config.model.provider)
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.ConfigLoaderTest"
```
Expected: FAIL (클래스 없음)

**Step 3: `WikiqConfig.kt` 작성**

```kotlin
package io.github.veronikapj.wikiq.config

enum class ModelProvider { ANTHROPIC, GOOGLE, CLAUDE_CODE }

data class WikiqConfig(
    val model: ModelConfig = ModelConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
    val slack: SlackConfig = SlackConfig(),
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
```

**Step 4: `ConfigLoader.kt` 작성**

```kotlin
package io.github.veronikapj.wikiq.config

import java.io.File

object ConfigLoader {

    fun load(path: String = ".wikiq/config.yml"): WikiqConfig =
        fromString(File(path).readText())

    fun fromString(yaml: String): WikiqConfig {
        val lines = yaml.lines()
        var provider = ModelProvider.CLAUDE_CODE
        var modelName: String? = null
        var apiKey: String? = null
        var baseUrl = ""
        var token = ""
        val spaces = mutableListOf<String>()
        var botToken = ""
        var appToken = ""
        var inModel = false
        var inConfluence = false
        var inSpaces = false
        var inSlack = false

        for (raw in lines) {
            val line = raw.substringBefore("#").trimEnd()
            when {
                line == "model:" -> { inModel = true; inConfluence = false; inSlack = false; inSpaces = false }
                line == "confluence:" -> { inConfluence = true; inModel = false; inSlack = false; inSpaces = false }
                line == "slack:" -> { inSlack = true; inModel = false; inConfluence = false; inSpaces = false }
                inConfluence && line.trimStart().startsWith("spaces:") -> inSpaces = true
                inSpaces && line.trimStart().startsWith("- ") -> spaces.add(line.trimStart().removePrefix("- ").trim())
                !line.trimStart().startsWith("- ") && inSpaces && line.isNotBlank() -> inSpaces = false
            }
            val trimmed = line.trim()
            when {
                inModel && trimmed.startsWith("provider:") ->
                    provider = runCatching {
                        ModelProvider.valueOf(trimmed.substringAfter("provider:").trim().uppercase())
                    }.getOrDefault(ModelProvider.CLAUDE_CODE)
                inModel && trimmed.startsWith("name:") ->
                    modelName = trimmed.substringAfter("name:").trim().ifEmpty { null }
                inModel && trimmed.startsWith("apiKey:") ->
                    apiKey = trimmed.substringAfter("apiKey:").trim().ifEmpty { null }
                inConfluence && trimmed.startsWith("baseUrl:") ->
                    baseUrl = trimmed.substringAfter("baseUrl:").trim()
                inConfluence && trimmed.startsWith("token:") ->
                    token = trimmed.substringAfter("token:").trim()
                inSlack && trimmed.startsWith("botToken:") ->
                    botToken = trimmed.substringAfter("botToken:").trim()
                inSlack && trimmed.startsWith("appToken:") ->
                    appToken = trimmed.substringAfter("appToken:").trim()
            }
        }

        return WikiqConfig(
            model = ModelConfig(provider, modelName, apiKey),
            confluence = ConfluenceConfig(baseUrl, token, spaces),
            slack = SlackConfig(botToken, appToken),
        )
    }

    fun save(config: WikiqConfig, path: String = ".wikiq/config.yml") {
        val spaces = config.confluence.spaces.joinToString("\n") { "    - $it" }
        val yaml = buildString {
            appendLine("model:")
            appendLine("  provider: ${config.model.provider}")
            config.model.name?.let { appendLine("  name: $it") }
            config.model.apiKey?.let { appendLine("  apiKey: $it") }
            appendLine("confluence:")
            appendLine("  baseUrl: ${config.confluence.baseUrl}")
            appendLine("  token: ${config.confluence.token}")
            if (config.confluence.spaces.isNotEmpty()) {
                appendLine("  spaces:")
                appendLine(spaces)
            }
            appendLine("slack:")
            appendLine("  botToken: ${config.slack.botToken}")
            appendLine("  appToken: ${config.slack.appToken}")
        }
        File(path).writeText(yaml)
    }
}
```

**Step 5: `.wikiq/config.yml` 샘플 작성**

```yaml
model:
  provider: CLAUDE_CODE  # CLAUDE_CODE | ANTHROPIC | GOOGLE
  # name: claude-sonnet-4-6  # provider가 ANTHROPIC/GOOGLE일 때만 사용
  # apiKey: sk-ant-...       # provider가 ANTHROPIC일 때
  # apiKey: AIza...          # provider가 GOOGLE일 때
confluence:
  baseUrl: https://yourcompany.atlassian.net
  token: your-confluence-api-token-base64  # echo -n "email:token" | base64
  spaces:
    - DEV
    - PM
slack:
  botToken: xoxb-...
  appToken: xapp-...
```

**Step 6: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.ConfigLoaderTest"
```
Expected: PASS (3 tests)

**Step 7: 커밋**

```bash
git add .
git commit -m "feat: WikiqConfig + ConfigLoader 구현"
```

---

## Task 3: ClaudeCodeLLMClient + LLM executor 구성

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/llm/ClaudeCodeLLMClient.kt`
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/llm/LLMExecutorBuilder.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wikiq/llm/ClaudeCodeLLMClientTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wikiq.llm

import io.github.veronikapj.wikiq.config.ModelConfig
import io.github.veronikapj.wikiq.config.ModelProvider
import kotlin.test.Test
import kotlin.test.assertNotNull

class ClaudeCodeLLMClientTest {

    @Test
    fun `buildExecutor returns executor for CLAUDE_CODE`() {
        val config = ModelConfig(provider = ModelProvider.CLAUDE_CODE)
        val executor = LLMExecutorBuilder.build(config)
        assertNotNull(executor)
    }

    @Test
    fun `buildExecutor returns executor for ANTHROPIC`() {
        val config = ModelConfig(provider = ModelProvider.ANTHROPIC, apiKey = "sk-ant-test")
        val executor = LLMExecutorBuilder.build(config)
        assertNotNull(executor)
    }

    @Test
    fun `buildExecutor returns executor for GOOGLE`() {
        val config = ModelConfig(provider = ModelProvider.GOOGLE, apiKey = "AIza-test")
        val executor = LLMExecutorBuilder.build(config)
        assertNotNull(executor)
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.ClaudeCodeLLMClientTest"
```

**Step 3: `ClaudeCodeLLMClient.kt` 작성**

autodoc-agent의 `ClaudeCodeLLMClient`를 패키지명만 바꿔서 복사:

```kotlin
package io.github.veronikapj.wikiq.llm

// autodoc-agent/src/main/kotlin/.../llm/ClaudeCodeLLMClient.kt 와 동일
// 패키지명만 io.github.veronikapj.wikiq.llm 으로 변경
```

(전체 코드는 autodoc-agent의 `ClaudeCodeLLMClient.kt` 참조 — 내용 동일)

**Step 4: `LLMExecutorBuilder.kt` 작성**

```kotlin
@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wikiq.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import io.github.veronikapj.wikiq.config.ModelConfig
import io.github.veronikapj.wikiq.config.ModelProvider

object LLMExecutorBuilder {

    fun build(config: ModelConfig): MultiLLMPromptExecutor =
        when (config.provider) {
            ModelProvider.CLAUDE_CODE -> MultiLLMPromptExecutor(
                ClaudeCodeLLMClient()
            )
            ModelProvider.ANTHROPIC -> MultiLLMPromptExecutor(
                AnthropicLLMClient(
                    apiKey = requireNotNull(config.apiKey) { "ANTHROPIC apiKey required" }
                )
            )
            ModelProvider.GOOGLE -> MultiLLMPromptExecutor(
                GoogleLLMClient(
                    apiKey = requireNotNull(config.apiKey) { "GOOGLE apiKey required" }
                )
            )
        }

    fun defaultModel(config: ModelConfig): ai.koog.prompt.llm.LLModel =
        when (config.provider) {
            ModelProvider.CLAUDE_CODE -> AnthropicModels.Sonnet_4
            ModelProvider.ANTHROPIC -> config.name
                ?.let { name -> AnthropicModels.entries.firstOrNull { it.id == name } }
                ?: AnthropicModels.Sonnet_4
            ModelProvider.GOOGLE -> config.name
                ?.let { name -> GoogleModels.entries.firstOrNull { it.id == name } }
                ?: GoogleModels.GeminiFlash2_0
        }
}
```

**Step 5: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.ClaudeCodeLLMClientTest"
```
Expected: PASS

**Step 6: 커밋**

```bash
git add .
git commit -m "feat: LLM executor 구성 (ClaudeCode, Anthropic, Google)"
```

---

## Task 4: ConfluenceClient (CQL 검색 추가)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/confluence/ConfluenceClient.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wikiq/confluence/ConfluenceClientTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wikiq.confluence

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
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.ConfluenceClientTest"
```

**Step 3: `ConfluenceClient.kt` 작성**

```kotlin
package io.github.veronikapj.wikiq.confluence

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.net.URLEncoder
import org.slf4j.LoggerFactory

data class ConfluencePageRef(
    val id: String,
    val title: String,
    val webUrl: String,
)

data class ConfluencePage(
    val id: String,
    val title: String,
    val content: String,
    val webUrl: String,
)

class ConfluenceClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    fun buildCqlSearchUrl(query: String, spaces: List<String>, limit: Int = 5): String {
        val spaceCql = if (spaces.isNotEmpty())
            " AND space IN (${spaces.joinToString(",") { "\"$it\"" }})"
        else ""
        val cql = URLEncoder.encode("text ~ \"$query\"$spaceCql", "UTF-8")
        return "$baseUrl/wiki/rest/api/content/search?cql=$cql&limit=$limit&expand=body.storage"
    }

    fun buildPageUrl(pageId: String): String =
        "$baseUrl/wiki/rest/api/content/$pageId?expand=body.storage,version,title"

    suspend fun searchPages(query: String, spaces: List<String>, limit: Int = 5): List<ConfluencePageRef> {
        val url = buildCqlSearchUrl(query, spaces, limit)
        log.info("Confluence CQL search: {}", url)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parseSearchResults(response, baseUrl)
    }

    suspend fun fetchPageContent(pageId: String): ConfluencePage {
        val url = buildPageUrl(pageId)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parsePage(response, baseUrl)
    }

    fun parseSearchResults(json: String, baseUrlForLinks: String): List<ConfluencePageRef> {
        val results = mutableListOf<ConfluencePageRef>()
        val idPattern = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        val titlePattern = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"")
        val webUiPattern = Regex("\"webui\"\\s*:\\s*\"([^\"]+)\"")

        val ids = idPattern.findAll(json).map { it.groupValues[1] }.toList()
        val titles = titlePattern.findAll(json).map { it.groupValues[1] }.toList()
        val webUis = webUiPattern.findAll(json).map { it.groupValues[1] }.toList()

        ids.forEachIndexed { i, id ->
            results.add(
                ConfluencePageRef(
                    id = id,
                    title = titles.getOrElse(i) { "Untitled" },
                    webUrl = "$baseUrlForLinks${webUis.getOrElse(i) { "" }}",
                )
            )
        }
        return results
    }

    private fun parsePage(json: String, baseUrlForLinks: String): ConfluencePage {
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "unknown"
        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "Untitled"
        val body = Regex("\"value\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?: ""
        val webUi = Regex("\"webui\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        return ConfluencePage(id, title, convertHtmlToMarkdown(body), "$baseUrlForLinks$webUi")
    }

    fun convertHtmlToMarkdown(html: String): String =
        html
            .replace(Regex("<h1[^>]*>"), "# ").replace(Regex("</h1>"), "\n")
            .replace(Regex("<h2[^>]*>"), "## ").replace(Regex("</h2>"), "\n")
            .replace(Regex("<h3[^>]*>"), "### ").replace(Regex("</h3>"), "\n")
            .replace(Regex("<p[^>]*>"), "\n").replace("</p>", "\n")
            .replace(Regex("<br[^>]*/?>"), "\n")
            .replace(Regex("<strong[^>]*>|<b[^>]*>"), "**").replace(Regex("</strong>|</b>"), "**")
            .replace(Regex("<li[^>]*>"), "- ").replace("</li>", "\n")
            .replace(Regex("<code[^>]*>"), "`").replace("</code>", "`")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceClient::class.java)
    }
}
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.ConfluenceClientTest"
```
Expected: PASS (4 tests)

**Step 5: 커밋**

```bash
git add .
git commit -m "feat: ConfluenceClient CQL 검색 구현"
```

---

## Task 5: ConfluenceSearchAgent (Koog Agent)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/agent/ConfluenceSearchAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wikiq/agent/ConfluenceSearchAgentTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wikiq.agent

import io.github.veronikapj.wikiq.confluence.ConfluenceClient
import io.github.veronikapj.wikiq.confluence.ConfluencePageRef
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfluenceSearchAgentTest {

    @Test
    fun `search returns formatted markdown with links`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchPages("배포", listOf("DEV"), 5) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "https://co.atlassian.net/wiki/spaces/DEV/pages/1"),
        )
        coEvery { mockClient.fetchPageContent("1") } returns io.github.veronikapj.wikiq.confluence.ConfluencePage(
            id = "1",
            title = "배포 가이드",
            content = "## 배포 절차\n1. PR 머지\n2. 자동 배포",
            webUrl = "https://co.atlassian.net/wiki/spaces/DEV/pages/1",
        )

        val agent = ConfluenceSearchAgent(mockClient, spaces = listOf("DEV"))
        val result = agent.search("배포")

        assertTrue(result.contains("배포 가이드"))
        assertTrue(result.contains("https://"))
    }

    @Test
    fun `search returns no results message when empty`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        coEvery { mockClient.searchPages(any(), any(), any()) } returns emptyList()

        val agent = ConfluenceSearchAgent(mockClient, spaces = listOf("DEV"))
        val result = agent.search("존재하지않는쿼리xyz")

        assertTrue(result.contains("찾을 수 없"))
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.ConfluenceSearchAgentTest"
```

**Step 3: `ConfluenceSearchAgent.kt` 작성**

```kotlin
@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wikiq.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.veronikapj.wikiq.confluence.ConfluenceClient
import org.slf4j.LoggerFactory

class ConfluenceSearchAgent(
    private val confluenceClient: ConfluenceClient,
    private val spaces: List<String>,
) {
    suspend fun search(query: String, topK: Int = 5): String {
        log.info("Searching Confluence: query='{}', spaces={}", query, spaces)

        val pages = confluenceClient.searchPages(query, spaces, topK)
        if (pages.isEmpty()) {
            return "관련 문서를 찾을 수 없습니다. (query: $query)"
        }

        val sb = StringBuilder()
        sb.appendLine("*\"$query\"* 관련 Confluence 문서 (${pages.size}건):\n")

        pages.forEachIndexed { i, ref ->
            runCatching {
                val page = confluenceClient.fetchPageContent(ref.id)
                val snippet = page.content.lines().take(5).joinToString("\n").take(300)
                sb.appendLine("${i + 1}. *${ref.title}*")
                sb.appendLine("   <${ref.webUrl}|링크>")
                sb.appendLine("   > ${snippet.replace("\n", "\n   > ")}")
                sb.appendLine()
            }.onFailure {
                sb.appendLine("${i + 1}. *${ref.title}*  <${ref.webUrl}|링크>")
                sb.appendLine()
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceSearchAgent::class.java)
    }
}
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.ConfluenceSearchAgentTest"
```
Expected: PASS

**Step 5: 커밋**

```bash
git add .
git commit -m "feat: ConfluenceSearchAgent 구현"
```

---

## Task 6: SlackConfigHandler (슬래시 커맨드)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/slack/SlackConfigHandler.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wikiq/slack/SlackConfigHandlerTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wikiq.slack

import io.github.veronikapj.wikiq.config.ConfluenceConfig
import io.github.veronikapj.wikiq.config.ModelConfig
import io.github.veronikapj.wikiq.config.ModelProvider
import io.github.veronikapj.wikiq.config.SlackConfig
import io.github.veronikapj.wikiq.config.WikiqConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackConfigHandlerTest {

    private fun makeConfig(spaces: List<String> = emptyList()) = WikiqConfig(
        model = ModelConfig(ModelProvider.CLAUDE_CODE),
        confluence = ConfluenceConfig("https://co.atlassian.net", "tok", spaces),
        slack = SlackConfig("xoxb-test", "xapp-test"),
    )

    @Test
    fun `handle set spaces updates config`() {
        val handler = SlackConfigHandler(makeConfig())
        val result = handler.handle("/wikiq config space DEV,PM,HR")
        assertEquals(listOf("DEV", "PM", "HR"), handler.currentConfig().confluence.spaces)
        assertTrue(result.contains("DEV"))
    }

    @Test
    fun `handle show returns current spaces`() {
        val handler = SlackConfigHandler(makeConfig(listOf("DEV", "PM")))
        val result = handler.handle("/wikiq config space show")
        assertTrue(result.contains("DEV"))
        assertTrue(result.contains("PM"))
    }

    @Test
    fun `handle unknown command returns help message`() {
        val handler = SlackConfigHandler(makeConfig())
        val result = handler.handle("/wikiq unknown")
        assertTrue(result.contains("사용법") || result.contains("help") || result.contains("config"))
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
./gradlew test --tests "*.SlackConfigHandlerTest"
```

**Step 3: `SlackConfigHandler.kt` 작성**

```kotlin
package io.github.veronikapj.wikiq.slack

import io.github.veronikapj.wikiq.config.ConfigLoader
import io.github.veronikapj.wikiq.config.WikiqConfig
import org.slf4j.LoggerFactory

class SlackConfigHandler(
    private var config: WikiqConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
) {
    fun currentConfig(): WikiqConfig = config

    fun handle(command: String): String {
        val parts = command.trim().split(" ")
        return when {
            parts.size >= 4 && parts[2] == "config" && parts[3] == "space" -> {
                if (parts.getOrNull(4) == "show") showSpaces()
                else setSpaces(parts.drop(4).joinToString(" "))
            }
            parts.size >= 3 && parts[2] == "config" && parts.getOrNull(3) == "space" -> {
                val arg = parts.getOrNull(4)
                if (arg == "show" || arg == null) showSpaces()
                else setSpaces(arg)
            }
            else -> helpMessage()
        }
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
        • `/wikiq <질문>` — Confluence에서 검색
        • `/wikiq config space DEV,PM,HR` — 검색 스페이스 설정
        • `/wikiq config space show` — 현재 설정 확인
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(SlackConfigHandler::class.java)
    }
}
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.SlackConfigHandlerTest"
```
Expected: PASS

**Step 5: 커밋**

```bash
git add .
git commit -m "feat: SlackConfigHandler 슬래시 커맨드 처리"
```

---

## Task 7: SlackBotGateway (Bolt Socket Mode)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wikiq/slack/SlackBotGateway.kt`
- Create: `src/main/resources/logback.xml`

> 참고: Bolt App 자체는 Slack 서버 연결이 필요해 단위 테스트가 불가하다. 핵심 로직(멘션 텍스트 추출)만 테스트한다.

**Step 1: 텍스트 추출 로직 테스트 작성**

`src/test/kotlin/io/github/veronikapj/wikiq/slack/MentionParserTest.kt`:

```kotlin
package io.github.veronikapj.wikiq.slack

import kotlin.test.Test
import kotlin.test.assertEquals

class MentionParserTest {

    @Test
    fun `strips bot mention from text`() {
        val raw = "<@U12345> 배포 프로세스 알려줘"
        assertEquals("배포 프로세스 알려줘", SlackBotGateway.extractQuery(raw))
    }

    @Test
    fun `strips leading whitespace after mention`() {
        val raw = "<@UBOT>  질문입니다"
        assertEquals("질문입니다", SlackBotGateway.extractQuery(raw))
    }

    @Test
    fun `returns text as-is when no mention`() {
        val raw = "그냥 텍스트"
        assertEquals("그냥 텍스트", SlackBotGateway.extractQuery(raw))
    }
}
```

**Step 2: `SlackBotGateway.kt` 작성**

```kotlin
package io.github.veronikapj.wikiq.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import io.github.veronikapj.wikiq.agent.ConfluenceSearchAgent
import io.github.veronikapj.wikiq.config.SlackConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val searchAgent: ConfluenceSearchAgent,
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
            val result = runBlocking { searchAgent.search(query) }
            ctx.asyncClient().chatPostMessage { it
                .channel(payload.event.channel)
                .threadTs(payload.event.ts)
                .text(result)
            }
            ctx.ack()
        }
    }

    private fun registerSlashCommand() {
        app.command("/wikiq") { req, ctx ->
            val fullCommand = "/wikiq ${req.payload.text}"
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

**Step 3: `src/main/resources/logback.xml` 작성**

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

**Step 4: 테스트 PASS 확인**

```bash
./gradlew test --tests "*.MentionParserTest"
```
Expected: PASS (3 tests)

**Step 5: 커밋**

```bash
git add .
git commit -m "feat: SlackBotGateway Bolt Socket Mode 구현"
```

---

## Task 8: Main.kt 최종 연결

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wikiq/Main.kt`

**Step 1: `Main.kt` 작성**

```kotlin
@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wikiq

import io.github.veronikapj.wikiq.agent.ConfluenceSearchAgent
import io.github.veronikapj.wikiq.config.ConfigLoader
import io.github.veronikapj.wikiq.confluence.ConfluenceClient
import io.github.veronikapj.wikiq.llm.LLMExecutorBuilder
import io.github.veronikapj.wikiq.slack.SlackBotGateway
import io.github.veronikapj.wikiq.slack.SlackConfigHandler
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wikiq.Main")

fun main() {
    val config = ConfigLoader.load()
    log.info("Provider: {}, Spaces: {}", config.model.provider, config.confluence.spaces)

    val confluenceClient = ConfluenceClient(
        baseUrl = config.confluence.baseUrl,
        token = config.confluence.token,
    )

    val searchAgent = ConfluenceSearchAgent(
        confluenceClient = confluenceClient,
        spaces = config.confluence.spaces,
    )

    val configHandler = SlackConfigHandler(
        config = config,
        persistOnChange = true,
    )

    val gateway = SlackBotGateway(
        slackConfig = config.slack,
        searchAgent = searchAgent,
        configHandler = configHandler,
    )

    gateway.start()
}
```

**Step 2: 전체 빌드 확인**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

**Step 3: fat JAR 생성 확인**

```bash
./gradlew shadowJar
ls -lh build/libs/wikiq-agent-1.0.0-all.jar
```
Expected: JAR 파일 생성됨

**Step 4: 커밋**

```bash
git add .
git commit -m "feat: Main.kt 연결 및 전체 빌드 완료"
```

---

## Task 9: 로컬 실행 검증

**Step 1: `.wikiq/config.yml` 실제 값으로 채우기**

```yaml
model:
  provider: CLAUDE_CODE  # 로컬 테스트용 (API 키 불필요)
confluence:
  baseUrl: https://yourcompany.atlassian.net
  token: <base64 encoded email:api-token>
  spaces:
    - DEV
slack:
  botToken: xoxb-...
  appToken: xapp-...
```

**Step 2: 실행**

```bash
./gradlew run
# 또는
java -jar build/libs/wikiq-agent-1.0.0-all.jar
```
Expected: "Starting Slack bot (Socket Mode)..." 로그 출력

**Step 3: 슬랙에서 테스트**

```
@wikiq 배포 프로세스 알려줘
/wikiq config space DEV,PM
/wikiq config space show
```
Expected: 봇이 스레드로 Confluence 검색 결과 응답

---

## 배포 모드 전환 방법 요약

| 원하는 모드 | `config.yml` 변경 사항 |
|------------|----------------------|
| 로컬 (API 키 없음) | `provider: CLAUDE_CODE` |
| Claude API | `provider: ANTHROPIC` + `apiKey: sk-ant-...` + `name: claude-sonnet-4-6` |
| Gemini API | `provider: GOOGLE` + `apiKey: AIza...` + `name: gemini-2.0-flash` |
| 팀 서버 공용 운영 | 위 어느 provider든 + 팀 공용 Bot/App 토큰 |
