# Sliding Window 압축 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 대화가 10턴 초과 시 오래된 턴을 LLM으로 요약하여 토큰 절약하면서 맥락을 유지한다.

**Architecture:** `ConversationStore`에 `compress()` 메서드를 추가한다. 총 턴 수가 COMPRESS_THRESHOLD(10)을 초과하면 오래된 턴을 LLM으로 요약하여 `{sessionId}.summary.md`에 저장하고 JSONL에서 제거한다. `OrchestratorAgent`는 요약을 시스템 프롬프트에 주입한다.

**Tech Stack:** Kotlin, Koog prompt DSL, MultiLLMPromptExecutor

---

## Task 1: ConversationStore에 loadAll + loadSummary + compress 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/context/ConversationStore.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/context/ConversationStoreTest.kt`

**Step 1: 테스트 추가**

`ConversationStoreTest.kt`에 추가:

```kotlin
@Test
fun `loadAll returns all turns without limit`() {
    val store = createTempStore()
    repeat(12) { i -> store.append("session1", "질문$i", "답변$i") }
    val all = store.loadAll("session1")
    assertEquals(12, all.size)
}

@Test
fun `loadSummary returns null when no summary`() {
    val store = createTempStore()
    assertEquals(null, store.loadSummary("session1"))
}

@Test
fun `saveSummary and loadSummary round-trip`() {
    val store = createTempStore()
    store.saveSummary("session1", "이전 대화 요약 내용")
    assertEquals("이전 대화 요약 내용", store.loadSummary("session1"))
}

@Test
fun `trimOldTurns keeps only recent turns`() {
    val store = createTempStore()
    repeat(10) { i -> store.append("session1", "질문$i", "답변$i") }
    store.trimOldTurns("session1", keepRecent = 4)
    val remaining = store.loadAll("session1")
    assertEquals(4, remaining.size)
    assertEquals("질문6", remaining[0].question)
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.ConversationStoreTest" 2>&1 | tail -10
```

**Step 3: ConversationStore 구현 추가**

`ConversationStore.kt`에 메서드 추가:

```kotlin
fun loadAll(sessionId: String): List<Turn> {
    val file = File(sessionsDir, "$sessionId.jsonl")
    if (!file.exists()) return emptyList()

    val entries = file.readLines()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            runCatching {
                val obj = json.parseToJsonElement(line).jsonObject
                val role = obj["role"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                role to content
            }.getOrNull()
        }

    val turns = mutableListOf<Turn>()
    var i = 0
    while (i < entries.size - 1) {
        val (userRole, userContent) = entries[i]
        val (assistantRole, assistantContent) = entries[i + 1]
        if (userRole == "user" && assistantRole == "assistant") {
            turns.add(Turn(userContent, assistantContent))
            i += 2
        } else {
            i++
        }
    }
    return turns
}

fun loadSummary(sessionId: String): String? {
    val file = File(sessionsDir, "$sessionId.summary.md")
    if (!file.exists()) return null
    return file.readText().ifBlank { null }
}

fun saveSummary(sessionId: String, summary: String) {
    val dir = File(sessionsDir)
    if (!dir.exists()) dir.mkdirs()
    File(dir, "$sessionId.summary.md").writeText(summary)
}

fun trimOldTurns(sessionId: String, keepRecent: Int) {
    val allTurns = loadAll(sessionId)
    if (allTurns.size <= keepRecent) return
    val recent = allTurns.takeLast(keepRecent)
    // Rewrite JSONL with only recent turns
    val file = File(sessionsDir, "$sessionId.jsonl")
    file.writeText("") // clear
    for (turn in recent) {
        append(sessionId, turn.question, turn.answer)
    }
}
```

NOTE: `loadAll` is a copy of `load` without `takeLast`. Refactor `load` to use `loadAll`:

```kotlin
fun load(sessionId: String, maxTurns: Int = 5): List<Turn> = loadAll(sessionId).takeLast(maxTurns)
```

Replace the existing `load` body with this one-liner, keeping the existing `loadAll` as the full implementation.

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
git commit -m "feat: ConversationStore — loadAll, loadSummary, saveSummary, trimOldTurns"
```

---

## Task 2: ConversationStore.compress() 메서드

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/context/ConversationStore.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/context/ConversationStoreTest.kt`

**Step 1: 테스트 추가**

compress는 LLM 호출이 필요하므로 실제 LLM 없이 테스트하기 위해 `summarizer` 함수를 인자로 받는다:

```kotlin
@Test
fun `compress summarizes old turns and keeps recent`() {
    val store = createTempStore()
    repeat(12) { i -> store.append("session1", "질문$i", "답변$i") }

    // Mock summarizer
    val summarizer: suspend (String) -> String = { text -> "요약: ${text.lines().size}줄" }

    kotlinx.coroutines.runBlocking {
        store.compress("session1", summarizer)
    }

    // After compression: only KEEP_RECENT turns remain in JSONL
    val remaining = store.loadAll("session1")
    assertEquals(4, remaining.size)
    assertEquals("질문8", remaining[0].question)

    // Summary file should exist
    val summary = store.loadSummary("session1")
    assertNotNull(summary)
    assertTrue(summary!!.contains("요약"))
}

@Test
fun `compress does nothing below threshold`() {
    val store = createTempStore()
    repeat(5) { i -> store.append("session1", "질문$i", "답변$i") }

    val summarizer: suspend (String) -> String = { error("should not be called") }
    kotlinx.coroutines.runBlocking {
        store.compress("session1", summarizer)
    }

    assertEquals(5, store.loadAll("session1").size)
    assertEquals(null, store.loadSummary("session1"))
}
```

**Step 2: 테스트 실행 — FAIL 확인**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.ConversationStoreTest" 2>&1 | tail -10
```

**Step 3: compress() 구현**

`ConversationStore.kt`에 추가:

```kotlin
suspend fun compress(
    sessionId: String,
    summarizer: suspend (String) -> String,
    compressThreshold: Int = COMPRESS_THRESHOLD,
    keepRecent: Int = KEEP_RECENT,
) {
    val allTurns = loadAll(sessionId)
    if (allTurns.size <= compressThreshold) return

    val turnsToSummarize = allTurns.dropLast(keepRecent)

    val conversationText = buildString {
        loadSummary(sessionId)?.let {
            appendLine("이전 요약: $it")
            appendLine()
        }
        for (turn in turnsToSummarize) {
            appendLine("User: ${turn.question}")
            appendLine("Assistant: ${turn.answer}")
        }
    }

    val prompt = buildString {
        appendLine("다음은 Slack에서 사용자와 AI 어시스턴트의 대화입니다.")
        appendLine("핵심 내용을 3-5줄로 요약하세요. 검색한 문서명과 주요 답변 내용을 포함하세요.")
        appendLine()
        append(conversationText)
    }

    val summary = summarizer(prompt)
    saveSummary(sessionId, summary)
    trimOldTurns(sessionId, keepRecent)
}

companion object {
    const val COMPRESS_THRESHOLD = 10
    const val KEEP_RECENT = 4
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
git commit -m "feat: ConversationStore.compress() — Sliding Window 요약 압축"
```

---

## Task 3: OrchestratorAgent에서 compress 호출 + 요약 주입

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Step 1: answerWithKoogAgent에서 compress 호출**

`answerWithKoogAgent()`의 히스토리 로드 전에 compress 호출 추가:

```kotlin
private suspend fun answerWithKoogAgent(
    question: String,
    listener: SearchProgressListener? = null,
    sessionId: String? = null,
): String {
    val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)

    // Compress if needed
    if (sessionId != null && conversationStore != null) {
        val summarizer: suspend (String) -> String = { promptText ->
            executor.execute(
                prompt("summarize") { user(promptText) },
                AnthropicModels.Haiku_4_5,
            ).joinToString("") { it.content }
        }
        conversationStore.compress(sessionId, summarizer)
    }

    // Load history + summary
    val conversationHistory = if (sessionId != null && conversationStore != null) {
        conversationStore.load(sessionId)
    } else emptyList()

    val summary = if (sessionId != null && conversationStore != null) {
        conversationStore.loadSummary(sessionId)
    } else null

    for ((index, model) in fallbackModels.withIndex()) {
        val result = runCatching { buildAgent(model, listener, conversationHistory, summary).run(question) }
        // ... rest same, including saving to history on success
    }
    // ...
}
```

**Step 2: buildAgent에서 요약을 시스템 프롬프트에 주입**

```kotlin
private fun buildAgent(
    model: LLModel,
    listener: SearchProgressListener? = null,
    history: List<Turn> = emptyList(),
    summary: String? = null,  // NEW
): AIAgent<String, String> {
    val systemPrompt = buildString {
        // ... existing sources + rules code ...
        
        // Append summary if exists
        summary?.let {
            appendLine()
            appendLine("# 이전 대화 요약")
            appendLine(it)
        }
    }

    return AIAgent(
        // ... existing code, prompt DSL with history injection stays the same
    )
}
```

**Step 3: 컴파일 + 테스트**

```bash
cd /tmp/wiki-agent
./gradlew compileKotlin 2>&1 | tail -10
./gradlew test 2>&1 | tail -15
```

**Step 4: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: OrchestratorAgent — compress 호출 + 요약 시스템 프롬프트 주입"
```
