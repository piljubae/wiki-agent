# EventHandler ReAct 시각화 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Koog AIAgent 모드에서 Tool 호출 과정을 Slack 스레드에 실시간 중간 메시지로 보여주고, 완료 시 삭제 후 최종 답변에 출처 footer를 추가한다.

**Architecture:** `SearchProgressListener` 콜백 인터페이스를 만들어 OrchestratorAgent와 SlackBotGateway를 연결한다. OrchestratorAgent는 Koog EventHandler로 Tool 이벤트를 감지하고 리스너를 호출한다. SlackBotGateway는 리스너를 구현하여 임시 메시지 전송/삭제 + footer 부착을 처리한다.

**Tech Stack:** Kotlin, Koog 0.8.0 (EventHandler feature), Slack Bolt SDK (chat.postMessage, chat.delete)

---

## Task 1: SearchProgressListener 인터페이스 + SourceTracker 건수 추적

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/SearchProgressListener.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/SourceTracker.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/tool/SourceTrackerTest.kt`

**Step 1: SourceTracker 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.agent.tool

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceTrackerTest {

    @Test
    fun `record increments count for same source`() {
        val tracker = SourceTracker()
        tracker.record("Confluence")
        tracker.record("Confluence")
        tracker.record("GitHub Wiki")
        assertEquals(2, tracker.countOf("Confluence"))
        assertEquals(1, tracker.countOf("GitHub Wiki"))
    }

    @Test
    fun `formatFooter returns formatted string`() {
        val tracker = SourceTracker()
        tracker.record("Confluence")
        tracker.record("Confluence")
        tracker.record("Confluence")
        tracker.record("GitHub Wiki")
        assertEquals("📋 Confluence 3건 · GitHub Wiki 1건", tracker.formatFooter())
    }

    @Test
    fun `formatFooter returns empty when no sources`() {
        val tracker = SourceTracker()
        assertEquals("", tracker.formatFooter())
    }

    @Test
    fun `reset clears all counts`() {
        val tracker = SourceTracker()
        tracker.record("Confluence")
        tracker.reset()
        assertEquals("", tracker.formatFooter())
    }
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.SourceTrackerTest" 2>&1 | tail -10
```

Expected: FAIL — `countOf`, `formatFooter` 메서드 없음

**Step 3: SourceTracker 수정 — 건수 추적 + footer 포맷**

```kotlin
package io.github.veronikapj.wiki.agent.tool

class SourceTracker {
    private val _counts = mutableMapOf<String, Int>()
    val sources: List<String> get() = _counts.keys.toList()

    fun reset() = _counts.clear()

    fun record(source: String) {
        _counts[source] = (_counts[source] ?: 0) + 1
    }

    fun countOf(source: String): Int = _counts[source] ?: 0

    fun formatFooter(): String {
        if (_counts.isEmpty()) return ""
        return "📋 " + _counts.entries.joinToString(" · ") { "${it.key} ${it.value}건" }
    }
}
```

**Step 4: SearchProgressListener 인터페이스 생성**

```kotlin
package io.github.veronikapj.wiki.agent

interface SearchProgressListener {
    suspend fun onSearchStarted(toolName: String)
    suspend fun onSearchCompleted(toolName: String)
}
```

**Step 5: 테스트 실행 — PASS 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.SourceTrackerTest" 2>&1 | tail -10
```

Expected: PASS

**Step 6: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/agent/SearchProgressListener.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/tool/SourceTracker.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/tool/SourceTrackerTest.kt
git commit -m "feat: SearchProgressListener 인터페이스 + SourceTracker 건수 추적"
```

---

## Task 2: OrchestratorAgent에 EventHandler + 리스너 연결

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt:154-188` (buildAgent 메서드)
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt`

**Step 1: OrchestratorAgentTest에 리스너 테스트 추가**

```kotlin
@Test
fun `accepts progressListener parameter`() {
    val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
    val listener = object : SearchProgressListener {
        override suspend fun onSearchStarted(toolName: String) {}
        override suspend fun onSearchCompleted(toolName: String) {}
    }
    val agent = OrchestratorAgent(
        confluenceTool = confluenceTool,
        executor = LLMExecutorBuilder.build(ModelConfig()),
        progressListener = listener,
    )
    assertNotNull(agent)
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.OrchestratorAgentTest" 2>&1 | tail -10
```

Expected: FAIL — `progressListener` 파라미터 없음

**Step 3: OrchestratorAgent 수정**

생성자에 `progressListener` 파라미터 추가:

```kotlin
class OrchestratorAgent(
    private val confluenceTool: ConfluenceTool? = null,
    private val githubWikiTool: GitHubWikiTool? = null,
    private val vectorSearchTool: VectorSearchTool? = null,
    private val executor: MultiLLMPromptExecutor,
    private val useManualLoop: Boolean = false,
    private val progressListener: SearchProgressListener? = null,
)
```

`buildAgent()` 메서드에 `installFeatures` 추가:

```kotlin
import ai.koog.agents.features.eventHandler.feature.EventHandler

private fun buildAgent(model: LLModel): AIAgent<String, String> {
    val systemPrompt = buildString { /* 기존 코드 동일 */ }

    val listener = progressListener

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
            if (confluenceTool != null) tool(confluenceTool::confluenceSearch)
            if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
            if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
        },
        installFeatures = if (listener != null) {
            {
                install(EventHandler) {
                    onToolCallStarting { toolCall ->
                        kotlinx.coroutines.runBlocking { listener.onSearchStarted(toolCall.toolName) }
                        toolCall.toolName
                    }
                    onToolCallCompleted { toolCall ->
                        kotlinx.coroutines.runBlocking { listener.onSearchCompleted(toolCall.toolName) }
                        toolCall.toolName
                    }
                }
            }
        } else null,
    )
}
```

> 참고: EventHandler의 정확한 API 시그니처는 Koog 0.8.0 소스에 따라 다를 수 있음. `onToolCallStarting`/`onToolCallCompleted` 콜백의 파라미터 타입을 확인할 것. 코드랩 예시 기준으로 작성.

**Step 4: 테스트 실행 — PASS 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.OrchestratorAgentTest" 2>&1 | tail -10
```

Expected: PASS

**Step 5: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat: OrchestratorAgent에 EventHandler + progressListener 연결"
```

---

## Task 3: SlackBotGateway에 중간 메시지 + 삭제 + footer 구현

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: SlackBotGateway 수정**

Tool 이름 → 한국어 매핑:

```kotlin
private val toolDisplayNames = mapOf(
    "confluenceSearch" to "Confluence",
    "githubWikiSearch" to "GitHub Wiki",
    "vectorSearch" to "RAG(ChromaDB)",
)
```

`registerMentionHandler()`를 수정하여:
1. SearchProgressListener 구현 (중간 메시지 전송/업데이트)
2. 최종 답변 시 중간 메시지 삭제
3. footer 부착

```kotlin
private fun registerMentionHandler() {
    app.event(com.slack.api.model.event.AppMentionEvent::class.java) { payload, ctx ->
        val query = extractQuery(payload.event.text)
        val channel = payload.event.channel
        val threadTs = payload.event.ts
        log.info("Mention received: '{}'", query)

        // 중간 메시지 ts 저장
        var progressMessageTs: String? = null

        val listener = object : io.github.veronikapj.wiki.agent.SearchProgressListener {
            override suspend fun onSearchStarted(toolName: String) {
                val displayName = toolDisplayNames[toolName] ?: toolName
                val msg = "🔍 ${displayName} 검색 중..."
                if (progressMessageTs == null) {
                    val response = ctx.client().chatPostMessage { it
                        .channel(channel)
                        .threadTs(threadTs)
                        .text(msg)
                    }
                    progressMessageTs = response.ts
                } else {
                    ctx.client().chatUpdate { it
                        .channel(channel)
                        .ts(progressMessageTs)
                        .text(msg)
                    }
                }
            }

            override suspend fun onSearchCompleted(toolName: String) {
                // SourceTracker가 기록 처리 — 여기서는 별도 작업 불필요
            }
        }

        orchestrator.setProgressListener(listener)
        sourceTracker.reset()
        val result = runBlocking { orchestrator.answer(query) }

        // 중간 메시지 삭제
        progressMessageTs?.let { ts ->
            runCatching {
                ctx.client().chatDelete { it.channel(channel).ts(ts) }
            }
        }

        // 최종 답변 + footer
        val footer = sourceTracker.formatFooter()
        val finalText = if (footer.isNotEmpty()) "$result\n\n$footer" else result

        ctx.client().chatPostMessage { it
            .channel(channel)
            .threadTs(threadTs)
            .text(finalText)
        }
        ctx.ack()
    }
}
```

> 참고: `orchestrator.setProgressListener(listener)` 대신 요청별로 listener를 전달하는 방식도 가능. `answer(question, listener)` 시그니처로 변경하는 게 thread-safe. 이 결정은 구현 시 확인할 것.

**Step 2: 컴파일 확인**

```bash
cd /tmp/wiki-agent
./gradlew compileKotlin 2>&1 | tail -10
```

**Step 3: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: SlackBotGateway 중간 메시지 + 삭제 + 출처 footer"
```

---

## Task 4: Main.kt 와이어링 + SourceTracker 전달

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt:106-112`

**Step 1: Main.kt에서 OrchestratorAgent 생성 시 변경사항 반영**

SlackBotGateway에 sourceTracker를 전달할 수 있도록 생성자 수정이 필요할 수 있음.

현재 `SlackBotGateway`는 `orchestrator`를 받지만 `sourceTracker`는 안 받음. 추가:

```kotlin
class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
    private val sourceTracker: SourceTracker,
)
```

Main.kt의 `SlackBotGateway` 생성 부분:

```kotlin
val gateway = SlackBotGateway(
    slackConfig = config.slack.copy(botToken = slackBotToken, appToken = slackAppToken),
    orchestrator = orchestrator,
    configHandler = configHandler,
    sourceTracker = sourceTracker,
)
```

**Step 2: 전체 테스트 실행**

```bash
cd /tmp/wiki-agent
./gradlew test 2>&1 | tail -15
```

**Step 3: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt \
        src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: Main.kt 와이어링 — SourceTracker를 SlackBotGateway에 전달"
```

---

## Task 5: thread-safe 리스너 전달 방식 결정 + 리팩터링

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: answer() 메서드에 리스너를 매 호출마다 전달하도록 변경**

Slack에서는 동시에 여러 멘션이 올 수 있으므로, 클래스 필드 대신 메서드 파라미터로 전달:

```kotlin
suspend fun answer(question: String, progressListener: SearchProgressListener? = null): String {
    log.info("OrchestratorAgent answering: '{}'", question)
    return if (useManualLoop) answerWithManualLoop(question)
    else answerWithKoogAgent(question, progressListener)
}

private suspend fun answerWithKoogAgent(question: String, listener: SearchProgressListener? = null): String {
    // buildAgent에 listener 전달
}
```

생성자의 `progressListener` 파라미터를 제거하고 `answer()` 파라미터로 이동.

**Step 2: SlackBotGateway에서 호출 방식 변경**

```kotlin
val result = runBlocking { orchestrator.answer(query, listener) }
```

**Step 3: 테스트 업데이트 + 실행**

```bash
cd /tmp/wiki-agent
./gradlew test 2>&1 | tail -15
```

**Step 4: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "refactor: progressListener를 answer() 파라미터로 이동 (thread-safe)"
```
