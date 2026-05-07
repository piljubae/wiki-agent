# 피드백 수집 + 🔄 LLM 재검색 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 👎/🔄 Slack 리액션으로 피드백을 수집하고, 🔄 트리거 시 LLM이 쿼리를 확장해 재검색한다.

**Architecture:** `FeedbackStore`가 (query, answer, usedTools)를 messageTs 키로 메모리+SQLite에 저장. 🔄 감지 시 `QueryRewriter`가 BM25용/벡터용 확장 쿼리와 추가 도구를 생성, `OrchestratorAgent`가 Stage 1(확장 쿼리)→Stage 2(전체 도구) 순으로 재검색.

**Tech Stack:** Kotlin, JUnit5, MockK, SQLite(java.sql.DriverManager), Koog Agent SDK

---

### Task 1: FeedbackStore — in-memory + SQLite 저장

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/slack/FeedbackStore.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/slack/FeedbackStoreTest.kt`

**Step 1: 실패 테스트 작성**

```kotlin
// src/test/kotlin/io/github/veronikapj/wiki/slack/FeedbackStoreTest.kt
package io.github.veronikapj.wiki.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeedbackStoreTest {

    private fun store() = FeedbackStore(dbPath = ":memory:")

    @Test
    fun `save and get entry by messageTs`() {
        val store = store()
        store.save("ts-001", FeedbackEntry(
            query = "회원가입 화면",
            answer = "SignUpActivity에서 구현",
            usedTools = listOf("confluence"),
            ts = "ts-001",
        ))
        val entry = store.get("ts-001")
        assertNotNull(entry)
        assertEquals("회원가입 화면", entry.query)
    }

    @Test
    fun `get returns null for unknown ts`() {
        val store = store()
        assertNull(store.get("unknown"))
    }

    @Test
    fun `saveReaction updates existing entry`() {
        val store = store()
        store.save("ts-002", FeedbackEntry(
            query = "push 알림", answer = "BrazeWrapper", usedTools = listOf("code_search"), ts = "ts-002",
        ))
        store.saveReaction("ts-002", "thumbsdown")
        val entry = store.get("ts-002")
        assertEquals("thumbsdown", entry?.reaction)
    }

    @Test
    fun `saveRequery updates requery fields and increments stage`() {
        val store = store()
        store.save("ts-003", FeedbackEntry(
            query = "로그아웃", answer = "AuthRepository", usedTools = listOf("confluence"), ts = "ts-003",
        ))
        store.saveRequery("ts-003", requeryBm25 = "logout signOut", requeryVec = "로그인 해제 동작", requeryAnswer = "새 답변", stage = 1)
        val entry = store.get("ts-003")
        assertEquals(1, entry?.stage)
        assertEquals("logout signOut", entry?.requeryBm25)
    }
}
```

**Step 2: 테스트 실행 → 실패 확인**

```bash
./gradlew test --tests "*.FeedbackStoreTest" 2>&1 | tail -10
```
Expected: FAIL (FeedbackStore 클래스 없음)

**Step 3: FeedbackStore 구현**

```kotlin
// src/main/kotlin/io/github/veronikapj/wiki/slack/FeedbackStore.kt
package io.github.veronikapj.wiki.slack

import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

data class FeedbackEntry(
    val query: String,
    val answer: String,
    val usedTools: List<String>,
    val ts: String,
    val reaction: String? = null,
    val requeryBm25: String? = null,
    val requeryVec: String? = null,
    val requeryAnswer: String? = null,
    val stage: Int = 0,
)

class FeedbackStore(dbPath: String = ".wiki/feedback.db") {

    private val cache = ConcurrentHashMap<String, FeedbackEntry>()
    private val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { createTable(it) }

    private fun createTable(c: java.sql.Connection) {
        c.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS feedback (
                ts TEXT PRIMARY KEY,
                query TEXT NOT NULL,
                answer TEXT NOT NULL,
                used_tools TEXT NOT NULL,
                reaction TEXT,
                requery_bm25 TEXT,
                requery_vec TEXT,
                requery_answer TEXT,
                stage INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    fun save(ts: String, entry: FeedbackEntry) {
        cache[ts] = entry
        conn.prepareStatement(
            "INSERT OR REPLACE INTO feedback(ts,query,answer,used_tools,created_at) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, ts)
            ps.setString(2, entry.query)
            ps.setString(3, entry.answer)
            ps.setString(4, entry.usedTools.joinToString(","))
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    fun get(ts: String): FeedbackEntry? = cache[ts]

    fun saveReaction(ts: String, reaction: String) {
        cache.computeIfPresent(ts) { _, e -> e.copy(reaction = reaction) }
        conn.prepareStatement("UPDATE feedback SET reaction=? WHERE ts=?").use {
            it.setString(1, reaction); it.setString(2, ts); it.executeUpdate()
        }
    }

    fun saveRequery(ts: String, requeryBm25: String, requeryVec: String, requeryAnswer: String, stage: Int) {
        cache.computeIfPresent(ts) { _, e ->
            e.copy(requeryBm25 = requeryBm25, requeryVec = requeryVec, requeryAnswer = requeryAnswer, stage = stage)
        }
        conn.prepareStatement(
            "UPDATE feedback SET requery_bm25=?,requery_vec=?,requery_answer=?,stage=? WHERE ts=?"
        ).use {
            it.setString(1, requeryBm25); it.setString(2, requeryVec)
            it.setString(3, requeryAnswer); it.setInt(4, stage); it.setString(5, ts)
            it.executeUpdate()
        }
    }
}
```

**Step 4: 테스트 실행 → 통과 확인**

```bash
./gradlew test --tests "*.FeedbackStoreTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 4 tests passed

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/FeedbackStore.kt \
        src/test/kotlin/io/github/veronikapj/wiki/slack/FeedbackStoreTest.kt
git commit -m "feat: FeedbackStore — messageTs→entry 메모리+SQLite 저장"
```

---

### Task 2: QueryRewriter — LLM 쿼리 확장

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/QueryRewriter.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/agent/QueryRewriterTest.kt`

**Step 1: 실패 테스트 작성**

```kotlin
// src/test/kotlin/io/github/veronikapj/wiki/agent/QueryRewriterTest.kt
package io.github.veronikapj.wiki.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class QueryRewriterTest {

    @Test
    fun `rewrite returns parsed RewrittenQuery`() = runTest {
        val llm = mockk<LLMCaller>()
        coEvery { llm.call(any()) } returns """
            BM25: signUp register join SignUpActivity SignUpViewModel
            VECTOR: 사용자가 앱에서 처음 계정을 만드는 화면의 구조
            TOOLS: code_search
        """.trimIndent()

        val rewriter = QueryRewriter(llm)
        val result = rewriter.rewrite(
            query = "회원가입 화면 어떻게 구현돼 있어?",
            usedTools = listOf("confluence"),
        )

        assertTrue(result.bm25.contains("SignUpActivity"))
        assertTrue(result.vector.contains("화면"))
        assertTrue(result.additionalTools.contains("code_search"))
    }

    @Test
    fun `rewrite with SAME tools returns empty additionalTools`() = runTest {
        val llm = mockk<LLMCaller>()
        coEvery { llm.call(any()) } returns """
            BM25: push notification FCM token
            VECTOR: 푸시 알림 발송 구현
            TOOLS: SAME
        """.trimIndent()

        val rewriter = QueryRewriter(llm)
        val result = rewriter.rewrite("푸시 알림 어떻게 보내?", listOf("code_search"))

        assertTrue(result.additionalTools.isEmpty())
    }
}
```

**Step 2: 테스트 실행 → 실패 확인**

```bash
./gradlew test --tests "*.QueryRewriterTest" 2>&1 | tail -10
```

**Step 3: QueryRewriter 구현**

```kotlin
// src/main/kotlin/io/github/veronikapj/wiki/agent/QueryRewriter.kt
package io.github.veronikapj.wiki.agent

import org.slf4j.LoggerFactory

data class RewrittenQuery(
    val bm25: String,
    val vector: String,
    val additionalTools: List<String>,
)

fun interface LLMCaller {
    suspend fun call(prompt: String): String
}

class QueryRewriter(private val llm: LLMCaller) {

    private val log = LoggerFactory.getLogger(QueryRewriter::class.java)

    suspend fun rewrite(query: String, usedTools: List<String>): RewrittenQuery {
        val prompt = """
            원래 질문: $query
            첫 검색에 사용한 도구: ${usedTools.joinToString(", ")}

            아래 3가지를 각각 한 줄로 출력하세요:
            BM25: [한국어 동의어 + 영문 클래스명/메서드명 패턴, 공백 구분]
            VECTOR: [같은 의미의 다른 표현으로 재작성한 자연어 문장]
            TOOLS: [추가로 시도할 도구 목록 (confluence/code_search/bm25 중), 없으면 SAME]
        """.trimIndent()

        val raw = llm.call(prompt)
        log.debug("QueryRewriter raw output: {}", raw)
        return parse(raw)
    }

    private fun parse(raw: String): RewrittenQuery {
        val lines = raw.lines().associate { line ->
            val colon = line.indexOf(':')
            if (colon < 0) return@associate "" to ""
            line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        val tools = lines["TOOLS"]
            ?.takeIf { it.isNotBlank() && it != "SAME" }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return RewrittenQuery(
            bm25 = lines["BM25"].orEmpty(),
            vector = lines["VECTOR"].orEmpty(),
            additionalTools = tools,
        )
    }
}
```

**Step 4: 테스트 실행 → 통과 확인**

```bash
./gradlew test --tests "*.QueryRewriterTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 2 tests passed

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/QueryRewriter.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/QueryRewriterTest.kt
git commit -m "feat: QueryRewriter — LLM 기반 BM25/벡터/도구 3갈래 쿼리 확장"
```

---

### Task 3: OrchestratorAgent — forceAllTools 파라미터 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Step 1: answer() 시그니처에 파라미터 추가**

`OrchestratorAgent.kt`의 `answer()` 함수를 다음으로 수정:

```kotlin
suspend fun answer(
    question: String,
    listener: SearchProgressListener? = null,
    sessionId: String? = null,
    forceAllTools: Boolean = false,      // 추가
): String {
    log.info("OrchestratorAgent answering: '{}' forceAllTools={}", question, forceAllTools)
    return if (useManualLoop) answerWithManualLoop(question, listener, sessionId, forceAllTools)
    else answerWithKoogAgent(question, listener, sessionId)
}
```

`answerWithManualLoop()` 내 `availableTools` 블록에 `forceAllTools` 분기 추가:

```kotlin
private suspend fun answerWithManualLoop(
    question: String,
    listener: SearchProgressListener? = null,
    sessionId: String? = null,
    forceAllTools: Boolean = false,      // 추가
): String {
    // ... 기존 코드 ...

    val availableTools = if (forceAllTools) {
        // 모든 도구 강제 활성화
        listOfNotNull(
            knowledgeTool?.let { "knowledgeSearch" },
            confluenceTool?.let { "confluenceSearch" },
            githubWikiTool?.let { "githubWikiSearch" },
            vectorSearchTool?.let { "vectorSearch" },
            prHistoryTool?.let { "prHistory" },
            codeSearchTool?.let { "codeSearch" },
        )
    } else {
        listOfNotNull(   // 기존 로직 그대로
            knowledgeTool?.let { "knowledgeSearch" },
            confluenceTool?.let { "confluenceSearch" },
            // ... 기존 코드 유지
        )
    }
    // ... 나머지 기존 코드 ...
}
```

**Step 2: 빌드 확인**

```bash
./gradlew compileKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

**Step 3: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: OrchestratorAgent forceAllTools 파라미터 추가"
```

---

### Task 4: SlackBotGateway — 피드백 저장 + 🔄 재검색 연동

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: FeedbackStore 주입 + 의존성 추가**

생성자에 `feedbackStore` 추가:

```kotlin
class SlackBotGateway(
    // ... 기존 파라미터 ...
    private val feedbackStore: FeedbackStore = FeedbackStore(),
    private val queryRewriter: QueryRewriter? = null,
)
```

**Step 2: handleQueryAsync — 응답 후 FeedbackStore 저장**

`sendResult.ts?.let { botMessageTimestamps.add(it) }` 라인을 다음으로 교체:

```kotlin
sendResult.ts?.let { ts ->
    val entry = FeedbackEntry(
        query = query,
        answer = result,
        usedTools = searchedTools.distinct(),
        ts = ts,
    )
    feedbackStore.save(ts, entry)
}
```

**Step 3: registerReactionHandler — 분기 처리**

```kotlin
private fun registerReactionHandler() {
    app.event(com.slack.api.model.event.ReactionAddedEvent::class.java) { payload, ctx ->
        val event = payload.event
        val reaction = event.reaction
        val messageTs = event.item.ts

        when {
            reaction in FEEDBACK_REACTIONS && feedbackStore.get(messageTs) != null -> {
                feedbackStore.saveReaction(messageTs, reaction)
                log.info("Feedback saved: reaction={}, ts={}", reaction, messageTs)
            }
            reaction in RETRY_REACTIONS && feedbackStore.get(messageTs) != null -> {
                val channel = event.item.channel
                val threadTs = messageTs
                messageExecutor.submit {
                    triggerRequery(messageTs, channel, threadTs)
                }
            }
        }
        ctx.ack()
    }
}
```

**Step 4: triggerRequery 함수 추가**

```kotlin
private fun triggerRequery(messageTs: String, channel: String, threadTs: String) {
    val entry = feedbackStore.get(messageTs) ?: run {
        log.warn("triggerRequery: entry not found for ts={}", messageTs)
        return
    }

    val stage = entry.stage + 1
    log.info("Requery stage={} for query='{}'", stage, entry.query)

    val forceAllTools = stage >= 2

    val (searchQuery, vectorQuery) = if (!forceAllTools && queryRewriter != null) {
        runBlocking {
            val rewritten = queryRewriter.rewrite(entry.query, entry.usedTools)
            rewritten.bm25 to rewritten.vector
        }
    } else {
        entry.query to entry.query
    }

    // BM25 + 벡터 모두 커버하는 합성 쿼리
    val combinedQuery = if (vectorQuery.isNotBlank() && vectorQuery != searchQuery)
        "$searchQuery\n$vectorQuery" else searchQuery

    val result = runBlocking {
        orchestrator.answer(combinedQuery, sessionId = "requery-$messageTs", forceAllTools = forceAllTools)
    }

    val reply = ":repeat: 다른 방식으로 찾아봤어요\n\n$result"
    slackClient.chatPostMessage { req ->
        req.channel(channel).threadTs(threadTs).text(reply)
    }

    feedbackStore.saveRequery(
        ts = messageTs,
        requeryBm25 = searchQuery,
        requeryVec = vectorQuery,
        requeryAnswer = result,
        stage = stage,
    )
}
```

**Step 5: FEEDBACK_GUIDE + RETRY_REACTIONS 상수 추가**

```kotlin
companion object {
    // ...
    const val FEEDBACK_GUIDE =
        ":thumbsup: 도움됐다면 | :thumbsdown: 아쉬웠다면 | :repeat: 다시 검색해드릴게요"
    val FEEDBACK_REACTIONS = listOf("+1", "-1", "thumbsup", "thumbsdown")
    val RETRY_REACTIONS = listOf("repeat", "arrows_counterclockwise")
    // ...
}
```

**Step 6: 빌드 확인**

```bash
./gradlew compileKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

**Step 7: 전체 테스트 실행**

```bash
./gradlew test 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL

**Step 8: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: SlackBotGateway 피드백 저장 + 🔄 2단계 LLM 재검색 연동"
```

---

### Task 5: Main.kt — QueryRewriter 초기화 연동

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

**Step 1: QueryRewriter 생성 후 SlackBotGateway에 주입**

Main.kt에서 `SlackBotGateway` 생성 직전에 추가:

```kotlin
// QueryRewriter: LLM executor 재사용
val queryRewriter = QueryRewriter { prompt ->
    // 기존 executor를 단순 호출로 감쌈 (Haiku 모델로 비용 절감)
    val result = executor.execute(
        model = AnthropicModels.Haiku_3_5,
        prompt = ai.koog.prompt.dsl.prompt { user(prompt) },
        params = AnthropicParams(maxTokens = 300),
    )
    result.content.firstOrNull()?.text ?: ""
}

val gateway = SlackBotGateway(
    // ... 기존 파라미터 ...
    queryRewriter = queryRewriter,
)
```

**Step 2: 빌드 확인**

```bash
./gradlew shadowJar 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

**Step 3: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat: Main.kt QueryRewriter 초기화 및 SlackBotGateway 연동"
```

---

## 수동 검증

1. 봇에 질문 후 답변 Footer에 `:repeat: 다시 검색해드릴게요` 확인
2. 👎 리액션 → 로그에 `Feedback saved: reaction=thumbsdown` 확인
3. 🔄 리액션 → 같은 스레드에 `:repeat: 다른 방식으로 찾아봤어요` 답변 확인
4. 🔄 한 번 더 → Stage 2 (forceAllTools) 동작 확인
5. `.wiki/feedback.db`에서 데이터 확인:
   ```bash
   sqlite3 .wiki/feedback.db "SELECT ts, query, reaction, stage FROM feedback LIMIT 10;"
   ```
