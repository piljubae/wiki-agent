# wiki-agent-workshop Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** `piljubae/wiki-agent-workshop` 레포를 생성한다 — Koog 에이전트 세션 실습용 스켈레톤으로, 참가자가 @LLMDescription과 system-prompt.txt만 수정해서 봇 동작이 바뀌는 7단계 + 심화 2단계(대화 히스토리, 내 Tool 만들기)를 체험한다.

**Architecture:** WorkshopAgent는 Koog AIAgent를 사용하지 않고 manual loop로 동작한다. @LLMDescription 텍스트를 런타임에 reflection으로 읽어 routing prompt에 주입 → LLM이 해당 설명을 보고 tool을 선택 → 이것이 "description = tool 선택 정확도"의 실제 메커니즘임을 보여준다. PersonaTool의 @LLMDescription은 별도로 읽어 final answer prompt의 스타일 지시로 주입한다.

**Tech Stack:** Kotlin 2.3.0, Koog 0.8.0 (koog-agents-jvm + prompt-executor-google-client-jvm), kaml 0.67.0, ktor-client-cio 3.1.2, logback

**Project path:** `/Users/pilju.bae/projects/wiki-agent-workshop/`  
**Package:** `io.github.piljubae.workshop`

---

### Task 1: Gradle 프로젝트 초기화

**Files:**
- Create: `/Users/pilju.bae/projects/wiki-agent-workshop/settings.gradle.kts`
- Create: `/Users/pilju.bae/projects/wiki-agent-workshop/build.gradle.kts`
- Create: `/Users/pilju.bae/projects/wiki-agent-workshop/.gitignore`

**Step 1: 디렉토리 생성 및 git 초기화**

```bash
mkdir -p /Users/pilju.bae/projects/wiki-agent-workshop
cd /Users/pilju.bae/projects/wiki-agent-workshop
git init
```

**Step 2: settings.gradle.kts 작성**

```kotlin
rootProject.name = "wiki-agent-workshop"
```

**Step 3: build.gradle.kts 작성**

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "io.github.piljubae"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/koog/public")
}

dependencies {
    implementation("ai.koog:koog-agents-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-google-client-jvm:0.8.0")
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("com.charleskorn.kaml:kaml:0.67.0")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.piljubae.workshop.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx1g")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
```

**Step 4: .gitignore 작성**

```
.gradle/
build/
.wiki/knowledge/
*.env
.env
logback.xml
```

**Step 5: 필수 디렉토리 생성**

```bash
mkdir -p src/main/kotlin/io/github/piljubae/workshop/config
mkdir -p src/main/kotlin/io/github/piljubae/workshop/agent/tool
mkdir -p src/main/kotlin/io/github/piljubae/workshop/knowledge
mkdir -p src/main/kotlin/io/github/piljubae/workshop/github
mkdir -p src/main/kotlin/io/github/piljubae/workshop/llm
mkdir -p src/main/resources
mkdir -p src/test/kotlin/io/github/piljubae/workshop
mkdir -p prompts
mkdir -p .wiki/knowledge
```

**Step 6: logback 설정 (src/main/resources/logback.xml)**

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss} [%level] %logger{20} — %msg%n</pattern>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

**Step 7: 빌드 확인**

```bash
cd /Users/pilju.bae/projects/wiki-agent-workshop
./gradlew build
```

Expected: BUILD SUCCESSFUL (main 클래스 없어서 실패할 수 있음 — 다음 태스크에서 추가)

**Step 8: 첫 커밋**

```bash
git add .
git commit -m "chore: initialize Gradle project"
```

---

### Task 2: Config 클래스

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/config/WorkshopConfig.kt`
- Create: `src/main/kotlin/io/github/piljubae/workshop/config/ConfigLoader.kt`
- Create: `config.yml`

**Step 1: WorkshopConfig.kt 작성**

```kotlin
package io.github.piljubae.workshop.config

import kotlinx.serialization.Serializable

enum class ModelProvider { GEMINI_CODE, GOOGLE, ANTHROPIC }

@Serializable
data class WorkshopConfig(
    val model: ModelConfig = ModelConfig(),
    val github: GithubConfig = GithubConfig(),
    val knowledge: KnowledgeConfig = KnowledgeConfig(),
)

@Serializable
data class ModelConfig(
    val provider: ModelProvider = ModelProvider.GEMINI_CODE,
    val apiKey: String? = null,
)

@Serializable
data class GithubConfig(
    val enabled: Boolean = true,
    val token: String = "",
    val repos: List<String> = listOf("piljubae/wiki-agent"),
)

@Serializable
data class KnowledgeConfig(
    val path: String = ".wiki/knowledge",
)
```

**Step 2: ConfigLoader.kt 작성**

wiki-agent의 ConfigLoader와 같은 패턴. config.yml 파일이 없으면 기본값 사용.

```kotlin
package io.github.piljubae.workshop.config

import com.charleskorn.kaml.Yaml
import java.io.File

object ConfigLoader {
    private const val CONFIG_PATH = "config.yml"

    fun load(): WorkshopConfig {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return WorkshopConfig()
        return Yaml.default.decodeFromString(WorkshopConfig.serializer(), file.readText())
    }
}
```

**Step 3: config.yml 작성**

```yaml
model:
  provider: GEMINI_CODE   # GEMINI_CODE | GOOGLE | ANTHROPIC
github:
  enabled: true
  repos:
    - piljubae/wiki-agent  # 기본 데이터소스 — 공개 레포, 토큰 불필요
knowledge:
  path: .wiki/knowledge
```

**Step 4: 테스트 작성 및 확인**

```kotlin
// src/test/kotlin/io/github/piljubae/workshop/ConfigLoaderTest.kt
package io.github.piljubae.workshop

import io.github.piljubae.workshop.config.ConfigLoader
import io.github.piljubae.workshop.config.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigLoaderTest {
    @Test
    fun `loads default config when no file`() {
        val config = ConfigLoader.load()
        assertEquals(ModelProvider.GEMINI_CODE, config.model.provider)
    }
}
```

```bash
./gradlew test
```

**Step 5: 커밋**

```bash
git add .
git commit -m "feat: add config classes"
```

---

### Task 3: KnowledgeStore

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/knowledge/KnowledgeStore.kt`

wiki-agent의 KnowledgeStore를 단순화. savePage/loadAll/pageExists만 유지.

**Step 1: KnowledgeStore.kt 작성**

```kotlin
package io.github.piljubae.workshop.knowledge

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class KnowledgeStore(val baseDir: String = ".wiki/knowledge") {

    private val lock = ReentrantLock()

    fun savePage(relativePath: String, content: String) = lock.withLock {
        val file = File("$baseDir/$relativePath")
        val root = File(baseDir).canonicalFile
        require(file.canonicalFile.startsWith(root)) { "Path traversal rejected: $relativePath" }
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun loadAll(): List<Pair<String, String>> = lock.withLock {
        val root = File(baseDir)
        if (!root.exists()) return@withLock emptyList()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(root).path to it.readText() }
            .toList()
    }

    fun pageExists(relativePath: String): Boolean = lock.withLock {
        val file = File("$baseDir/$relativePath")
        val root = File(baseDir).canonicalFile
        if (!file.canonicalFile.startsWith(root)) return@withLock false
        file.exists()
    }

    fun search(query: String, topK: Int = 3): String {
        val pages = loadAll()
        if (pages.isEmpty()) return "지식베이스가 비어있습니다."
        val keywords = query.lowercase().split(" ").filter { it.length > 1 }
        val relevant = pages.filter { (_, content) ->
            keywords.any { content.lowercase().contains(it) }
        }
        if (relevant.isEmpty()) return "관련 문서를 찾을 수 없습니다. (query: $query)"
        return relevant.take(topK).joinToString("\n\n---\n\n") { (path, content) ->
            "[$path]\n${content.take(600)}"
        }
    }
}
```

**Step 2: 커밋**

```bash
git add .
git commit -m "feat: add KnowledgeStore"
```

---

### Task 4: IngestAgent (단순화)

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/knowledge/IngestAgent.kt`

workshop용 IngestAgent는 LLM 컴파일 없이 URL → HTML 텍스트 추출 → .md 저장.

**Step 1: IngestAgent.kt 작성**

```kotlin
package io.github.piljubae.workshop.knowledge

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory

class IngestAgent(private val store: KnowledgeStore) {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    fun close() = httpClient.close()

    suspend fun ingestUrl(url: String): String {
        val sourceKey = urlToKey(url)
        val path = "sources/$sourceKey.md"
        if (store.pageExists(path)) return "이미 등록된 소스입니다: $url"

        log.info("Fetching URL: {}", url)
        val html = runCatching { httpClient.get(url).bodyAsText() }
            .getOrElse { return "URL 가져오기 실패: ${it.message}" }

        val text = extractText(html)
        val content = "# $url\n\n$text"
        store.savePage(path, content)
        log.info("Saved: {}", path)
        return "저장됨: $path"
    }

    private fun extractText(html: String): String =
        html.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(3000)

    private fun urlToKey(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .replace(Regex("[^a-zA-Z0-9가-힣]"), "-")
            .take(60)

    companion object {
        private val log = LoggerFactory.getLogger(IngestAgent::class.java)
    }
}
```

**Step 2: 커밋**

```bash
git add .
git commit -m "feat: add IngestAgent"
```

---

### Task 5: GitHubWikiClient

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/github/GitHubWikiClient.kt`

wiki-agent의 GitHubWikiClient 그대로 복사 (패키지명만 변경).

**Step 1: GitHubWikiClient.kt 작성**

wiki-agent의 `src/main/kotlin/io/github/veronikapj/wiki/github/GitHubWikiClient.kt` 내용을 복사하고:
- 첫 줄 `package`를 `package io.github.piljubae.workshop.github` 로 변경
- `data class GitHubWikiPage`, `class GitHubWikiClient` 그대로 유지

**Step 2: 커밋**

```bash
git add .
git commit -m "feat: add GitHubWikiClient"
```

---

### Task 6: Tool 스텁 3종

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`
- Create: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`
- Create: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/PersonaTool.kt`

핵심: `@LLMDescription("")` 비어있음 — 참가자가 Step 2에서 채운다.

**Step 1: KnowledgeTool.kt 작성**

```kotlin
package io.github.piljubae.workshop.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.piljubae.workshop.knowledge.KnowledgeStore

class KnowledgeTool(private val store: KnowledgeStore) {

    @Tool("knowledgeSearch")
    @LLMDescription("") // ← Step 2: 이 설명을 채우세요
    fun knowledgeSearch(
        @LLMDescription("검색할 질문 또는 키워드")
        query: String,
    ): String = store.search(query)
}
```

**Step 2: GitHubWikiTool.kt 작성**

```kotlin
package io.github.piljubae.workshop.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.piljubae.workshop.github.GitHubWikiClient
import kotlinx.coroutines.runBlocking

class GitHubWikiTool(
    private val client: GitHubWikiClient,
    private val repos: List<String>,
) {

    @Tool("githubWikiSearch")
    @LLMDescription("") // ← Step 2: 이 설명을 채우세요
    fun githubWikiSearch(
        @LLMDescription("검색할 질문 또는 키워드")
        query: String,
    ): String = runBlocking {
        val results = client.searchPages(query, repos)
        if (results.isEmpty()) return@runBlocking "관련 문서를 찾을 수 없습니다. (query: $query)"
        results.joinToString("\n\n") { page ->
            "제목: ${page.title}\n출처: ${page.htmlUrl}\n내용: ${page.snippet}"
        }
    }
}
```

**Step 3: PersonaTool.kt 작성**

```kotlin
package io.github.piljubae.workshop.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

class PersonaTool {

    @Tool("persona")
    @LLMDescription("") // ← 보너스: 페르소나 스타일 설명을 여기에 붙여넣으세요
    fun persona(
        @LLMDescription("사용자에게 전달할 답변 내용")
        content: String,
    ): String = content
}
```

**Step 4: 커밋**

```bash
git add .
git commit -m "feat: add tool stubs (KnowledgeTool, GitHubWikiTool, PersonaTool)"
```

---

### Task 7: GeminiCodeLLMClient + LLMExecutorBuilder

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/llm/GeminiCodeLLMClient.kt`
- Create: `src/main/kotlin/io/github/piljubae/workshop/llm/LLMExecutorBuilder.kt`

**Step 1: GeminiCodeLLMClient.kt 작성**

wiki-agent의 `GeminiCodeLLMClient.kt`를 복사하고 패키지명만 변경:
- `package io.github.piljubae.workshop.llm`

**Step 2: LLMExecutorBuilder.kt 작성**

```kotlin
package io.github.piljubae.workshop.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.piljubae.workshop.config.ModelConfig
import io.github.piljubae.workshop.config.ModelProvider

object LLMExecutorBuilder {

    fun build(config: ModelConfig): MultiLLMPromptExecutor = when (config.provider) {
        ModelProvider.GEMINI_CODE -> MultiLLMPromptExecutor(GeminiCodeLLMClient())
        ModelProvider.GOOGLE -> MultiLLMPromptExecutor(
            GoogleLLMClient(apiKey = requireNotNull(config.apiKey) { "GOOGLE apiKey required" })
        )
        ModelProvider.ANTHROPIC -> MultiLLMPromptExecutor(
            AnthropicLLMClient(apiKey = requireNotNull(config.apiKey) { "ANTHROPIC apiKey required" })
        )
    }

    fun defaultModel(config: ModelConfig): LLModel = when (config.provider) {
        ModelProvider.GEMINI_CODE -> GoogleModels.Gemini2_5Flash
        ModelProvider.GOOGLE -> GoogleModels.Gemini2_5Flash
        ModelProvider.ANTHROPIC -> AnthropicModels.Haiku_4_5
    }
}
```

**Step 3: 커밋**

```bash
git add .
git commit -m "feat: add LLM clients"
```

---

### Task 8: WorkshopAgent

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/agent/WorkshopAgent.kt`

핵심 설계:
1. `@LLMDescription` annotation을 reflection으로 읽어 routing prompt에 주입
2. 모든 description이 비어있으면 → LLM에게 직접 질문 (Step 1 동작)
3. description이 채워지면 → routing prompt에 tool 설명 포함 → LLM이 TOOL: 선택 (Step 2+)
4. PersonaTool의 description은 summary prompt의 스타일 지시로 주입 (보너스)

**Step 1: WorkshopAgent.kt 작성**

```kotlin
@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.piljubae.workshop.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.piljubae.workshop.agent.tool.GitHubWikiTool
import io.github.piljubae.workshop.agent.tool.KnowledgeTool
import io.github.piljubae.workshop.agent.tool.PersonaTool
import org.slf4j.LoggerFactory
import java.io.File

class WorkshopAgent(
    private val knowledgeTool: KnowledgeTool,
    private val githubWikiTool: GitHubWikiTool,
    private val personaTool: PersonaTool,
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
) {
    // @LLMDescription 텍스트를 reflection으로 읽기
    private fun toolDescription(tool: Any): String {
        return tool::class.java.declaredMethods
            .firstOrNull { it.isAnnotationPresent(Tool::class.java) }
            ?.getAnnotation(LLMDescription::class.java)?.value ?: ""
    }

    private fun systemPrompt(): String =
        File("prompts/system-prompt.txt").let { if (it.exists()) it.readText().trim() else "" }

    suspend fun answer(question: String): String {
        val knowledgeDesc = toolDescription(knowledgeTool)
        val githubDesc = toolDescription(githubWikiTool)
        val personaDesc = toolDescription(personaTool)

        val hasSearchTools = knowledgeDesc.isNotBlank() || githubDesc.isNotBlank()
        val systemPrompt = systemPrompt()

        log.info("knowledgeDesc='{}' githubDesc='{}' personaDesc='{}'",
            knowledgeDesc.take(40), githubDesc.take(40), personaDesc.take(40))

        if (!hasSearchTools) {
            // Step 1: Tool description 없음 → LLM이 직접 답변 (tool 미사용)
            log.info("No tool descriptions — answering directly")
            return executor.execute(prompt("direct") {
                if (systemPrompt.isNotBlank()) system(systemPrompt)
                user(question)
            }, model).joinToString("") { it.content }
        }

        // Step 2+: Routing — LLM이 tool 설명을 읽고 선택
        val routingPrompt = buildRoutingPrompt(question, knowledgeDesc, githubDesc)
        val decision = executor.execute(
            prompt("routing") { user(routingPrompt) }, model
        ).joinToString("") { it.content }.trim()

        log.info("Routing decision: {}", decision.take(100))

        val toolName = Regex("TOOL:\\s*(\\S+)").find(decision)?.groupValues?.get(1)?.trim()
        val query = Regex("QUERY:\\s*(.+)").find(decision)?.groupValues?.get(1)?.trim() ?: question

        log.info("Selected tool: {} query: {}", toolName, query)

        // Tool 실행
        val searchResult = when (toolName) {
            "githubWikiSearch" -> runCatching { githubWikiTool.githubWikiSearch(query) }.getOrElse { it.message ?: "검색 실패" }
            else -> runCatching { knowledgeTool.knowledgeSearch(query) }.getOrElse { it.message ?: "검색 실패" }
        }

        log.info("Search result: {}", searchResult.take(100))

        // Step 4+: 최종 답변 생성 (system-prompt.txt 반영)
        val summaryPrompt = buildSummaryPrompt(question, searchResult, systemPrompt, personaDesc)
        return executor.execute(
            prompt("summary") { user(summaryPrompt) }, model
        ).joinToString("") { it.content }
    }

    private fun buildRoutingPrompt(
        question: String,
        knowledgeDesc: String,
        githubDesc: String,
    ): String = buildString {
        appendLine("당신은 검색 라우터입니다. 아래 도구 중 하나를 선택해 사용자 질문을 검색하세요.")
        appendLine()
        appendLine("사용 가능한 도구:")
        if (knowledgeDesc.isNotBlank()) appendLine("- knowledgeSearch: $knowledgeDesc")
        if (githubDesc.isNotBlank()) appendLine("- githubWikiSearch: $githubDesc")
        appendLine()
        appendLine("출력 형식 (이 두 줄만 출력, 다른 텍스트 금지):")
        appendLine("TOOL: <도구 이름>")
        appendLine("QUERY: <검색어>")
        appendLine()
        appendLine("질문: $question")
    }

    private fun buildSummaryPrompt(
        question: String,
        searchResult: String,
        systemPrompt: String,
        personaDesc: String,
    ): String = buildString {
        if (systemPrompt.isNotBlank()) {
            appendLine(systemPrompt)
            appendLine()
        }
        if (personaDesc.isNotBlank()) {
            appendLine("답변 스타일: $personaDesc")
            appendLine()
        }
        appendLine("질문: $question")
        appendLine()
        appendLine("검색 결과:")
        appendLine(searchResult)
        appendLine()
        appendLine("위 검색 결과를 바탕으로 질문에 답변하세요.")
        appendLine("검색 결과에 없는 내용을 지어내지 마세요.")
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkshopAgent::class.java)
    }
}
```

**Step 2: 커밋**

```bash
git add .
git commit -m "feat: add WorkshopAgent with @LLMDescription reflection"
```

---

### Task 9: Main.kt (CLI 러너)

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/Main.kt`

**Step 1: Main.kt 작성**

```kotlin
package io.github.piljubae.workshop

import io.github.piljubae.workshop.agent.WorkshopAgent
import io.github.piljubae.workshop.agent.tool.GitHubWikiTool
import io.github.piljubae.workshop.agent.tool.KnowledgeTool
import io.github.piljubae.workshop.agent.tool.PersonaTool
import io.github.piljubae.workshop.config.ConfigLoader
import io.github.piljubae.workshop.github.GitHubWikiClient
import io.github.piljubae.workshop.knowledge.IngestAgent
import io.github.piljubae.workshop.knowledge.KnowledgeStore
import io.github.piljubae.workshop.llm.LLMExecutorBuilder
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("workshop.Main")

fun main() {
    val config = ConfigLoader.load()
    log.info("Provider: {}, repos: {}", config.model.provider, config.github.repos)

    val executor = LLMExecutorBuilder.build(config.model)
    val model = LLMExecutorBuilder.defaultModel(config.model)

    val store = KnowledgeStore(config.knowledge.path)
    val ingestAgent = IngestAgent(store)
    Runtime.getRuntime().addShutdownHook(Thread { ingestAgent.close() })

    val knowledgeTool = KnowledgeTool(store)
    val githubClient = GitHubWikiClient(config.github.token)
    val githubWikiTool = GitHubWikiTool(githubClient, config.github.repos)
    val personaTool = PersonaTool()

    val agent = WorkshopAgent(knowledgeTool, githubWikiTool, personaTool, executor, model)

    val divider = "─".repeat(60)
    println(divider)
    println("  wiki-agent-workshop  |  종료: q  |  ingest: /ingest <URL>")
    println(divider)

    while (true) {
        println()
        print("질문 > ")
        val input = readlnOrNull()?.trim() ?: break
        when {
            input == "q" -> break
            input.isBlank() -> continue
            input.startsWith("/ingest ") -> {
                val url = input.removePrefix("/ingest ").trim()
                println("ingest 중...")
                val result = runBlocking { ingestAgent.ingestUrl(url) }
                println(result)
            }
            else -> {
                println("검색 중...")
                val result = runBlocking { agent.answer(input) }
                println()
                println(result)
                println(divider)
            }
        }
    }
    println("종료합니다.")
}
```

**Step 2: 빌드 확인**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

**Step 3: 커밋**

```bash
git add .
git commit -m "feat: add Main CLI runner"
```

---

### Task 10: prompts/ 파일 작성

**Files:**
- Create: `prompts/system-prompt.txt`
- Create: `prompts/persona-guide.md`

**Step 1: system-prompt.txt — 비어있음 (Step 1 시작 상태)**

파일 내용 없음. 빈 파일로 저장.

**Step 2: persona-guide.md 작성**

```markdown
# PersonaTool 보너스 실습 가이드

`PersonaTool.kt`의 `@LLMDescription("")`에 페르소나 설명을 넣으면
봇이 그 스타일로 답변합니다.

---

## 페르소나 예시

아래에서 하나 골라도 되고, 직접 정의해도 됩니다.

| 이름 | 특징 |
|------|------|
| MZ 인턴 | 이모지 남발, "ㅋㅋ", "ㄹㅇ", 짧게 끊어 답변, 요즘 유행어 많이 사용 |
| 갓생러 | 모든 답변에 생산성 팁 추가. "이것도 루틴화하면 좋습니다" |
| 번아웃 5년차 | 귀찮음 최대. 최소한만. "...그냥 문서 보세요" |
| 너무 정중한 GPT | 매 답변마다 "물론이죠!", 과도한 면책 조항, 3단 들여쓰기 |
| 유튜버 편집장 | "자 오늘은!", 결론 먼저, "이거 레전드임", 썸네일 문체 |
| 시그마 개발자 | 감정 없음. 핵심만. 코드로 대답 가능하면 코드로. |
| 스타트업 대표 | "임팩트", "피봇", "그로스". 모든 걸 비즈니스 지표로 환산 |
| 중2병 현자 | 어둡고 철학적. "진정한 답은... 스스로 찾아야 합니다" |
| NPC 알바 | 정해진 멘트만. 범위 벗어나면 "그건 제 담당이 아닌데요" |
| K-직장선배 | "야", 반말, "그것도 모르냐", 핵심은 알려줌 |

---

## 직접 정의하기

아래 항목을 채워서 2단계로 넘어가세요:

- **호칭/말투**: (예: 반말 / 존댓말 / 이모지체 / 영어 섞기)
- **답변 길이**: (예: 한 줄 / 자세하게 / 번호 리스트)
- **성격 한 줄**: (예: 귀찮지만 틀린 말은 못 참음)
- **금지어**: (예: "물론이죠", "말씀드리겠습니다")
- **말버릇**: (예: 모든 문장 끝에 "ㅋㅋ" 붙이기)

---

## 2단계 — @LLMDescription 생성

아래를 Claude/Gemini에 붙여넣고 `[페르소나]` 자리만 채우세요:

```
다음 페르소나를 Koog @LLMDescription 텍스트로 변환해주세요.
@LLMDescription은 이 Tool이 호출될 때 LLM의 답변 스타일을 지정합니다.

페르소나: [위에서 고른 것 또는 직접 정의한 내용]

조건:
- 한국어 1-2문장
- "이 Tool을 통해 답변할 때는 ___처럼 말합니다" 형태로
- 말버릇, 금지어, 말투 특성을 구체적으로 명시

출력: @LLMDescription에 바로 붙여넣을 텍스트만
```

---

## 3단계 — 적용

1. `PersonaTool.kt` 열기
2. `@LLMDescription("여기에 생성된 텍스트 붙여넣기")`
3. `./gradlew run` 재실행
4. 질문하고 변화 확인!
```

**Step 3: 커밋**

```bash
git add prompts/
git commit -m "docs: add system-prompt.txt and persona-guide.md"
```

---

### Task 11: 실행 검증

**Step 1: 전체 빌드 + 실행 확인**

```bash
cd /Users/pilju.bae/projects/wiki-agent-workshop
./gradlew run
```

Expected:
```
질문 > Koog가 뭐야?
검색 중...
[직접 답변 — Step 1: tool description 없음]
```

로그에서 `knowledgeDesc='' githubDesc=''` 확인.

**Step 2: main 브랜치 = 스켈레톤 확정 커밋**

```bash
git add .
git commit -m "chore: skeleton complete — step 1 start state"
```

---

### Task 12: 단계별 브랜치 생성

각 브랜치는 해당 단계까지 완성된 상태. 항상 main에서 분기.

**Step 1: step-2 브랜치 — @LLMDescription 작성**

```bash
git checkout -b step-2
```

`KnowledgeTool.kt`의 `@LLMDescription` 채우기:
```kotlin
@LLMDescription("로컬 지식베이스에서 문서를 검색합니다. URL을 ingest해서 저장한 문서를 찾을 때 사용하세요.")
```

`GitHubWikiTool.kt`의 `@LLMDescription` 채우기:
```kotlin
@LLMDescription("GitHub Wiki에서 문서를 검색합니다. Koog 프레임워크, wiki-agent 구조, 기술 개념을 찾을 때 사용하세요.")
```

```bash
git add .
git commit -m "step-2: fill @LLMDescription for KnowledgeTool and GitHubWikiTool"
```

**Step 2: step-4 브랜치 — system-prompt.txt 역할 + 출력 형식**

```bash
git checkout main
git checkout -b step-4
```

`KnowledgeTool.kt`, `GitHubWikiTool.kt` @LLMDescription: step-2와 동일하게 채우기.

`prompts/system-prompt.txt` 작성:
```
당신은 Koog 프레임워크와 wiki-agent에 대해 답변하는 전문 봇입니다.
항상 검색 결과를 바탕으로 답변하세요.

답변 형식:
- 정의형: 한 줄 정의 + 부연 2-3문장
- 절차형: 번호 리스트 (1. 2. 3.)
- 기타: 핵심 먼저, 세부사항 아래
```

```bash
git add .
git commit -m "step-4: add role and output format to system-prompt.txt"
```

**Step 3: step-5 브랜치 — Tool 호출 강제**

```bash
git checkout main
git checkout -b step-5
```

step-4 내용 + `prompts/system-prompt.txt`에 추가:
```
검색 없이 직접 답변하지 마세요. 반드시 knowledgeSearch 또는 githubWikiSearch를 사용하세요.
```

```bash
git add .
git commit -m "step-5: enforce tool usage in system prompt"
```

**Step 4: step-6 브랜치 — 샘플 knowledge 파일**

```bash
git checkout main
git checkout -b step-6
```

step-5 내용 + `.wiki/knowledge/sources/sample-koog.md` 샘플 파일 추가:
```markdown
# Koog 프레임워크

Koog는 JetBrains incubator 프로젝트로 개발된 Kotlin Multiplatform 기반 AI 에이전트 프레임워크입니다.

## 핵심 개념
- @Tool: Tool로 등록할 함수에 붙이는 어노테이션
- @LLMDescription: LLM이 Tool을 선택할 때 읽는 설명
- AIAgent: LLM + Tool + Loop 실행 엔진
- ToolRegistry: Tool을 등록하고 관리하는 레지스트리
```

`.wiki/knowledge/` 디렉토리를 .gitignore에서 제거 (step-6 브랜치에서만 파일 포함).

```bash
git add .
git commit -m "step-6: add sample knowledge file"
```

**Step 5: step-7 브랜치 — config.yml 범위 조정 예시**

```bash
git checkout main
git checkout -b step-7
```

step-6 내용 + `config.yml`에 주석 추가:
```yaml
github:
  enabled: true
  repos:
    - piljubae/wiki-agent  # 줄이면 검색 범위 좁아짐 → 정확도 올라감
    # - 다른-레포/이름   # 추가하면 범위 넓어짐 → 노이즈 증가
```

```bash
git add .
git commit -m "step-7: add config.yml scope comments"
```

**Step 6: step-bonus 브랜치 — PersonaTool MZ 인턴 예시**

```bash
git checkout main
git checkout -b step-bonus
```

step-5 내용 + `PersonaTool.kt` @LLMDescription 채우기:
```kotlin
@LLMDescription("이 Tool을 통해 답변할 때는 MZ 인턴처럼 말합니다. 이모지를 2-3개 이상 쓰고, 'ㅋㅋ', 'ㄹㅇ', '레전드' 같은 유행어를 자연스럽게 섞어서 짧고 경쾌하게 답변합니다.")
```

```bash
git add .
git commit -m "step-bonus: add MZ intern persona to PersonaTool"
```

---

### Task 13: 심화 브랜치 — Step 8 대화 히스토리

**Files:**
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/WorkshopAgent.kt`

참가자가 직접 추가하는 코드를 브랜치로 제공. step-5 기반으로 분기.

**Step 1: step-8 브랜치 생성**

```bash
git checkout main
git checkout -b step-8
```

step-5 내용 적용 (@LLMDescription 채움, system-prompt.txt Tool 호출 강제 포함).

**Step 2: WorkshopAgent.kt에 히스토리 추가**

클래스 상단에 필드 추가:
```kotlin
private val history = ArrayDeque<Pair<String, String>>() // (질문, 답변) 최대 3턴
```

`buildSummaryPrompt` 파라미터에 `history: List<Pair<String,String>>` 추가 후 내부에서 사용:
```kotlin
private fun buildSummaryPrompt(
    question: String,
    searchResult: String,
    systemPrompt: String,
    personaDesc: String,
    history: List<Pair<String, String>> = emptyList(),
): String = buildString {
    if (systemPrompt.isNotBlank()) { appendLine(systemPrompt); appendLine() }
    if (history.isNotEmpty()) {
        appendLine("이전 대화 (참고용):")
        history.forEach { (q, a) -> appendLine("Q: $q\nA: ${a.take(200)}") }
        appendLine()
    }
    if (personaDesc.isNotBlank()) { appendLine("답변 스타일: $personaDesc"); appendLine() }
    appendLine("질문: $question")
    appendLine()
    appendLine("검색 결과:")
    appendLine(searchResult)
    appendLine()
    appendLine("위 검색 결과를 바탕으로 질문에 답변하세요. 이전 대화가 있으면 맥락을 반영하세요.")
    appendLine("검색 결과에 없는 내용을 지어내지 마세요.")
}
```

`answer()` 함수 마지막에 히스토리 저장:
```kotlin
// summary 생성 후
val result = executor.execute(prompt("summary") { user(summaryPrompt) }, model)
    .joinToString("") { it.content }

history.addLast(question to result)
if (history.size > 3) history.removeFirst()

return result
```

**Step 3: 동작 확인**

```bash
./gradlew run
```

테스트 시나리오:
```
질문 > wiki-agent 기획서 v1이 뭐야?
[답변 확인]

질문 > 그거 v2랑 뭐가 달라?   ← "그거"가 v1을 가리킴 — 맥락 유지 확인
[v1 → v2 변경사항 답변 확인]
```

**Step 4: 커밋**

```bash
git add .
git commit -m "step-8: add in-memory conversation history (3 turns)"
```

---

### Task 14: 심화 브랜치 — Step 9 내 Tool 만들기

**Files:**
- Create: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/MyTool.kt`
- Modify: `src/main/kotlin/io/github/piljubae/workshop/Main.kt`
- Create: `prompts/my-tool-guide.md`

참가자가 직접 Tool을 작성하는 단계. step-8 기반으로 분기.

**Step 1: step-9 브랜치 생성**

```bash
git checkout step-8
git checkout -b step-9
```

**Step 2: MyTool.kt 템플릿 작성**

참가자가 TODO를 채우도록 stub 제공:
```kotlin
package io.github.piljubae.workshop.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

/**
 * 심화 Step 9: 내 Tool 만들기
 *
 * 아래 @LLMDescription과 함수 본문을 채우고 Main.kt에 등록하세요.
 * Tool 하나 = 에이전트 능력 하나.
 */
class MyTool {

    @Tool("mySearch")
    @LLMDescription("") // ← 내 Tool이 하는 일을 설명하세요 (예: "날씨 정보를 검색합니다")
    fun mySearch(
        @LLMDescription("검색할 내용이나 키워드")
        query: String,
    ): String {
        // TODO: 내 로직 구현
        // 예시: 외부 API 호출, 파일 읽기, DB 조회 등
        return "MyTool 결과: $query (TODO: 실제 로직 구현)"
    }
}
```

**Step 3: WorkshopAgent 생성자에 MyTool 파라미터 추가**

`WorkshopAgent.kt`:
```kotlin
class WorkshopAgent(
    private val knowledgeTool: KnowledgeTool,
    private val githubWikiTool: GitHubWikiTool,
    private val personaTool: PersonaTool,
    private val myTool: MyTool? = null,   // ← 추가 (null이면 비활성)
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
)
```

routing prompt에 myTool 포함:
```kotlin
val myToolDesc = myTool?.let { toolDescription(it) } ?: ""

// buildRoutingPrompt에서
if (myToolDesc.isNotBlank()) appendLine("- mySearch: $myToolDesc")

// tool 실행 분기에서
"mySearch" -> runCatching { myTool?.mySearch(query) ?: "MyTool이 등록되지 않았습니다." }
    .getOrElse { it.message ?: "실패" }
```

**Step 4: Main.kt에 MyTool 등록**

```kotlin
val myTool = MyTool()

val agent = WorkshopAgent(
    knowledgeTool = knowledgeTool,
    githubWikiTool = githubWikiTool,
    personaTool = personaTool,
    myTool = myTool,   // ← 추가
    executor = executor,
    model = model,
)
```

**Step 5: my-tool-guide.md 작성**

```markdown
# Step 9 — 내 Tool 만들기

## 아이디어 예시

| Tool 이름 | 할 일 | 구현 힌트 |
|-----------|------|----------|
| 날씨 검색 | OpenWeatherMap API 호출 | ktor HttpClient GET |
| 계산기 | 수식 파싱 후 계산 | kotlin.math, ScriptEngine |
| 파일 읽기 | 로컬 .txt/.md 파일 내용 반환 | File("path").readText() |
| 번역 | DeepL/Google Translate API | HttpClient POST |
| 공지 조회 | 사내 REST API 호출 | 기존 HttpClient 재사용 |
| Git 로그 | git log 명령 실행 | ProcessBuilder |

## 작성 순서

1. `MyTool.kt` 열기
2. `@LLMDescription("이 Tool이 하는 일")` 채우기
3. `fun mySearch(query: String): String` 본문 구현
4. `./gradlew run` 실행
5. Tool이 선택되는지 확인: `"[내 Tool 이름]으로 검색할 수 있는 거 있어?"`

## 팁

- @LLMDescription이 비어있으면 LLM이 Tool을 선택하지 않습니다 (Step 2 복습)
- 함수가 예외를 던지면 에이전트가 멈춥니다 — runCatching으로 감싸세요
- Tool 이름은 영문 camelCase (`mySearch`, `weatherSearch`)
```

**Step 6: 빌드 확인**

```bash
./gradlew build
```

**Step 7: 커밋**

```bash
git add .
git commit -m "step-9: add MyTool template and guide for custom tool creation"
```

---

### Task 15: GitHub 레포 생성 및 푸시

**Step 1: GitHub 레포 생성**

```bash
cd /Users/pilju.bae/projects/wiki-agent-workshop
gh repo create piljubae/wiki-agent-workshop --public --description "Koog 에이전트 세션 실습용 스켈레톤"
```

**Step 2: main 브랜치 푸시**

```bash
git remote add origin https://github.com/piljubae/wiki-agent-workshop.git
git push -u origin main
```

**Step 3: 단계별 브랜치 모두 푸시**

```bash
git push origin step-2 step-4 step-5 step-6 step-7 step-bonus step-8 step-9
```

**Step 4: 확인**

브라우저에서 `github.com/piljubae/wiki-agent-workshop` 열어서:
- main 브랜치: KnowledgeTool @LLMDescription이 비어있는지 확인
- step-2 브랜치: @LLMDescription이 채워져 있는지 확인
