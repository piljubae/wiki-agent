# 대화 기록 영속화 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Slack 스레드 단위로 대화를 JSONL 파일에 저장하고, koogAgent 모드에서 이전 대화를 Koog prompt DSL(user/assistant 메시지)로 주입하여 맥락을 유지한다.

**Architecture:** `ConversationStore`가 `.wiki/sessions/{sessionId}.jsonl` 파일을 읽고 쓴다. `OrchestratorAgent.answerWithKoogAgent()`가 히스토리를 로드하여 `buildAgent()`의 prompt DSL에 `user()`/`assistant()` 메시지로 주입한다. SlackBotGateway가 `threadTs`를 sessionId로 전달한다.

**Tech Stack:** Kotlin, kotlinx.serialization (JSON), Koog prompt DSL (`user()`/`assistant()`)

---

## Task 1: ConversationStore + Turn 데이터 클래스

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/context/ConversationStore.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/context/ConversationStoreTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.context

import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File

class ConversationStoreTest {

    private fun createTempStore(): ConversationStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "wiki-test-sessions-${System.nanoTime()}")
        return ConversationStore(dir.absolutePath)
    }

    @Test
    fun `append and load returns turns`() {
        val store = createTempStore()
        store.append("session1", "질문1", "답변1")
        store.append("session1", "질문2", "답변2")
        val turns = store.load("session1")
        assertEquals(2, turns.size)
        assertEquals("질문1", turns[0].question)
        assertEquals("답변2", turns[1].answer)
    }

    @Test
    fun `load returns empty for nonexistent session`() {
        val store = createTempStore()
        assertEquals(emptyList(), store.load("nonexistent"))
    }

    @Test
    fun `load returns only last maxTurns`() {
        val store = createTempStore()
        repeat(8) { i -> store.append("session1", "질문$i", "답변$i") }
        val turns = store.load("session1", maxTurns = 5)
        assertEquals(5, turns.size)
        assertEquals("질문3", turns[0].question)
        assertEquals("질문7", turns[4].question)
    }

    @Test
    fun `different sessions are isolated`() {
        val store = createTempStore()
        store.append("session1", "Q1", "A1")
        store.append("session2", "Q2", "A2")
        assertEquals(1, store.load("session1").size)
        assertEquals("Q2", store.load("session2")[0].question)
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.ConversationStoreTest" 2>&1 | tail -10
```

**Step 3: 구현**

```kotlin
package io.github.veronikapj.wiki.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ConversationEntry(
    val ts: String,
    val role: String,
    val content: String,
)

data class Turn(val question: String, val answer: String)

class ConversationStore(private val sessionsDir: String = ".wiki/sessions") {

    private val json = Json { ignoreUnknownKeys = true }

    fun append(sessionId: String, question: String, answer: String) {
        val dir = File(sessionsDir)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$sessionId.jsonl")
        val ts = java.time.Instant.now().toString()
        val userEntry = json.encodeToString(ConversationEntry.serializer(), ConversationEntry(ts, "user", question))
        val assistantEntry = json.encodeToString(ConversationEntry.serializer(), ConversationEntry(ts, "assistant", answer))
        file.appendText("$userEntry\n$assistantEntry\n")
    }

    fun load(sessionId: String, maxTurns: Int = 5): List<Turn> {
        val file = File(sessionsDir, "$sessionId.jsonl")
        if (!file.exists()) return emptyList()

        val entries = file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString(ConversationEntry.serializer(), line) }.getOrNull()
            }

        // pair consecutive user+assistant entries into Turns
        val turns = mutableListOf<Turn>()
        var i = 0
        while (i < entries.size - 1) {
            val userEntry = entries[i]
            val assistantEntry = entries[i + 1]
            if (userEntry.role == "user" && assistantEntry.role == "assistant") {
                turns.add(Turn(userEntry.content, assistantEntry.content))
                i += 2
            } else {
                i++
            }
        }

        return turns.takeLast(maxTurns)
    }
}
```

**Step 4: 테스트 실행 — PASS 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.ConversationStoreTest" 2>&1 | tail -10
```

**Step 5: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/context/ConversationStore.kt \
        src/test/kotlin/io/github/veronikapj/wiki/context/ConversationStoreTest.kt
git commit -m "feat: ConversationStore — JSONL 기반 스레드별 대화 기록"
```

---

## Task 2: OrchestratorAgent에 히스토리 주입

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt`

**Step 1: 테스트 추가**

```kotlin
@Test
fun `answer method accepts sessionId parameter`() {
    val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
    val store = io.github.veronikapj.wiki.context.ConversationStore(
        java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-${System.nanoTime()}").absolutePath
    )
    val agent = OrchestratorAgent(
        confluenceTool = confluenceTool,
        executor = LLMExecutorBuilder.build(ModelConfig()),
        conversationStore = store,
    )
    assertNotNull(agent)
}
```

**Step 2: 테스트 실행 — FAIL**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.OrchestratorAgentTest" 2>&1 | tail -10
```

**Step 3: OrchestratorAgent 수정**

생성자에 `conversationStore` 추가:

```kotlin
class OrchestratorAgent(
    private val confluenceTool: ConfluenceTool? = null,
    private val githubWikiTool: GitHubWikiTool? = null,
    private val vectorSearchTool: VectorSearchTool? = null,
    private val executor: MultiLLMPromptExecutor,
    private val useManualLoop: Boolean = false,
    private val conversationStore: ConversationStore? = null,
)
```

`answer()` 시그니처에 `sessionId` 추가:

```kotlin
suspend fun answer(
    question: String,
    progressListener: SearchProgressListener? = null,
    sessionId: String? = null,
): String {
    log.info("OrchestratorAgent answering: '{}' session={}", question, sessionId)
    return if (useManualLoop) answerWithManualLoop(question)
    else answerWithKoogAgent(question, progressListener, sessionId)
}
```

`answerWithKoogAgent`에 sessionId 전달:

```kotlin
private suspend fun answerWithKoogAgent(
    question: String,
    listener: SearchProgressListener? = null,
    sessionId: String? = null,
): String {
    val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)
    // 히스토리 로드
    val history = if (sessionId != null && conversationStore != null) {
        conversationStore.load(sessionId)
    } else emptyList()

    for ((index, model) in fallbackModels.withIndex()) {
        val result = runCatching { buildAgent(model, listener, history).run(question) }
        val ex = result.exceptionOrNull()
        if (ex == null) {
            val answer = result.getOrThrow()
            // 히스토리 저장
            if (sessionId != null && conversationStore != null) {
                conversationStore.append(sessionId, question, answer)
            }
            return answer
        }
        if (index < fallbackModels.lastIndex) {
            log.warn("Retrying with {}", fallbackModels[index + 1].id)
            continue
        }
        log.error("All models failed: {}", ex.message)
        return "검색 중 오류가 발생했습니다: ${ex.message}"
    }
    error("unreachable")
}
```

`buildAgent`에서 히스토리를 prompt DSL로 주입:

```kotlin
import io.github.veronikapj.wiki.context.Turn

private fun buildAgent(
    model: LLModel,
    listener: SearchProgressListener? = null,
    history: List<Turn> = emptyList(),
): AIAgent<String, String> {
    val systemPrompt = buildString { /* 기존 코드 동일 */ }

    return AIAgent(
        promptExecutor = executor,
        agentConfig = AIAgentConfig(
            prompt = prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
                system(systemPrompt)
                for (turn in history) {
                    user(turn.question)
                    assistant(turn.answer)
                }
            },
            model = model,
            maxAgentIterations = 10,
        ),
        toolRegistry = ToolRegistry {
            if (confluenceTool != null) tool(confluenceTool::confluenceSearch)
            if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
            if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
        },
    ) {
        if (listener != null) {
            install(EventHandler.Feature) {
                onToolCallStarting { context ->
                    kotlinx.coroutines.runBlocking { listener.onSearchStarted(context.toolName) }
                }
                onToolCallCompleted { context ->
                    kotlinx.coroutines.runBlocking { listener.onSearchCompleted(context.toolName) }
                }
            }
        }
    }
}
```

**Step 4: 테스트 실행 — PASS 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.OrchestratorAgentTest" 2>&1 | tail -10
```

**Step 5: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat: OrchestratorAgent에 대화 히스토리 주입 (prompt DSL user/assistant)"
```

---

## Task 3: SlackBotGateway + Main.kt 와이어링

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

**Step 1: SlackBotGateway에서 threadTs를 sessionId로 전달**

`registerMentionHandler()` 안에서 `orchestrator.answer()` 호출 부분 수정:

```kotlin
// 기존:
val result = runBlocking { orchestrator.answer(query, listener) }

// 변경:
val result = runBlocking { orchestrator.answer(query, listener, sessionId = threadTs) }
```

이것 한 줄 변경이 전부.

**Step 2: Main.kt에서 ConversationStore 생성 + OrchestratorAgent에 주입**

```kotlin
import io.github.veronikapj.wiki.context.ConversationStore

// sourceTracker 생성 후에 추가:
val conversationStore = ConversationStore()

// OrchestratorAgent 생성 시:
val orchestrator = OrchestratorAgent(
    confluenceTool = confluenceTool,
    githubWikiTool = githubWikiTool,
    vectorSearchTool = vectorSearchTool,
    executor = executor,
    useManualLoop = config.model.provider == io.github.veronikapj.wiki.config.ModelProvider.CLAUDE_CODE,
    conversationStore = conversationStore,
)
```

**Step 3: .gitignore에 세션 디렉터리 추가**

`.wiki/sessions/` 디렉터리가 git에 포함되지 않도록:

```bash
echo ".wiki/sessions/" >> .gitignore
```

**Step 4: 컴파일 + 테스트 확인**

```bash
cd /tmp/wiki-agent
./gradlew compileKotlin 2>&1 | tail -10
./gradlew test 2>&1 | tail -15
```

**Step 5: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt \
        src/main/kotlin/io/github/veronikapj/wiki/Main.kt \
        .gitignore
git commit -m "feat: SlackBotGateway threadTs → sessionId 전달 + Main.kt 와이어링"
```
