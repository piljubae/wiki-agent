# 검색 의도 이해 강화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 복합 질문 의도를 분해·이해해 각 에이전트가 알맞은 결과를 리턴하게 만든다 — Koog 경로와 수동 루프 경로 양쪽에서.

**Architecture:** 의도 이해를 검색/tool 레이어의 공유 컴포넌트(`QueryUnderstanding`)로 두어, Koog 경로의 빈약한 Confluence tool 호출을 enrich(W1)하고, 수동 루프에는 `QueryDecomposer`로 복합질문을 sub-question으로 쪼개 기존 라우터를 재사용·병렬 호출 후 섹션별 통합 답변을 만든다(W2).

**Tech Stack:** Kotlin, Gradle 멀티모듈, Koog AIAgent, kotlinx.coroutines, JUnit5 + MockK + kotlinx-coroutines-test.

## Global Constraints

- 모듈 의존은 단방향: `:app → :search → :confluence …`. `:search`는 `:app`에 의존 불가 → `QueryUnderstanding`은 `:search`에, `QueryDecomposer`는 `:app`에 둔다.
- 신규 LLM 추상화는 기존 `fun interface LLMCaller`(`src/main/.../agent/QueryRewriter.kt`) 패턴을 따른다. `:search`에서는 `:app`의 `LLMCaller`를 못 쓰므로 `:search` 로컬 fun interface를 새로 정의한다.
- 모든 LLM 출력 파싱은 `runCatching`으로 감싸고 실패 시 입력 보존 폴백 — 기존 동작을 절대 깨지 않는다.
- 신규 컴포넌트는 작은 `routerModel`(Haiku/Flash)을 재사용한다.
- 테스트는 testing-strategy L1(Unit, mock LLM) 중심. 실제 네트워크/LLM 호출 금지.
- 루트(:app) 테스트: `./gradlew test`. 검색 모듈 테스트: `./gradlew :search:test`.
- 단일 질문(분해 결과 1개) 경로는 기존과 동일하게 동작해야 한다(회귀 0).

---

### Task 1: QueryUnderstanding 공유 컴포넌트 (:search)

bare query 하나로 정제쿼리 + synonyms + 날짜를 뽑는 enrich 프리미티브.

**Files:**
- Create: `search/src/main/kotlin/io/github/veronikapj/wiki/search/QueryUnderstanding.kt`
- Test: `search/src/test/kotlin/io/github/veronikapj/wiki/search/QueryUnderstandingTest.kt`

**Interfaces:**
- Produces:
  - `fun interface QueryLlm { suspend fun call(prompt: String): String }`
  - `data class UnderstoodQuery(val cleanedQuery: String, val synonyms: List<String>, val dateAfter: String?, val dateBefore: String?)`
  - `class QueryUnderstanding(private val llm: QueryLlm)` with `suspend fun understand(rawQuery: String): UnderstoodQuery`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.veronikapj.wiki.search

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryUnderstandingTest {

    @Test
    fun `parses query synonyms and dates from llm output`() = runTest {
        val llm = QueryLlm {
            """
            QUERY: iOS 배포 가이드
            SYNONYMS: iOS 릴리즈, iOS Release, 배포 프로세스
            DATE_AFTER: 2026-01-01
            DATE_BEFORE:
            """.trimIndent()
        }
        val result = QueryUnderstanding(llm).understand("iOS 배포 가이드 알려줘")

        assertEquals("iOS 배포 가이드", result.cleanedQuery)
        assertEquals(listOf("iOS 릴리즈", "iOS Release", "배포 프로세스"), result.synonyms)
        assertEquals("2026-01-01", result.dateAfter)
        assertEquals(null, result.dateBefore)
    }

    @Test
    fun `falls back to raw query when llm throws`() = runTest {
        val llm = QueryLlm { throw RuntimeException("boom") }
        val result = QueryUnderstanding(llm).understand("결제 흐름")

        assertEquals("결제 흐름", result.cleanedQuery)
        assertTrue(result.synonyms.isEmpty())
        assertEquals(null, result.dateAfter)
        assertEquals(null, result.dateBefore)
    }

    @Test
    fun `falls back when output has no recognizable fields`() = runTest {
        val llm = QueryLlm { "죄송하지만 도와드릴 수 없습니다." }
        val result = QueryUnderstanding(llm).understand("배포")

        assertEquals("배포", result.cleanedQuery)
        assertTrue(result.synonyms.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :search:test --tests "io.github.veronikapj.wiki.search.QueryUnderstandingTest"`
Expected: FAIL — `QueryUnderstanding` / `QueryLlm` / `UnderstoodQuery` 미정의 (compile error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.veronikapj.wiki.search

import org.slf4j.LoggerFactory

fun interface QueryLlm {
    suspend fun call(prompt: String): String
}

data class UnderstoodQuery(
    val cleanedQuery: String,
    val synonyms: List<String>,
    val dateAfter: String?,
    val dateBefore: String?,
)

/**
 * bare query 하나로 검색 품질을 끌어올리는 enrich 프리미티브.
 * Koog 경로의 confluenceSearch(query) 가 synonyms·날짜 없이 호출될 때 보완한다.
 */
class QueryUnderstanding(private val llm: QueryLlm) {

    suspend fun understand(rawQuery: String): UnderstoodQuery {
        return runCatching {
            val raw = llm.call(buildPrompt(rawQuery))
            val query = QUERY_RE.find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: rawQuery
            val synonyms = SYN_RE.find(raw)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val dateAfter = DATE_AFTER_RE.find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val dateBefore = DATE_BEFORE_RE.find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            UnderstoodQuery(query, synonyms, dateAfter, dateBefore)
        }.getOrElse {
            log.warn("QueryUnderstanding failed, fallback to raw: {}", it.message)
            UnderstoodQuery(rawQuery, emptyList(), null, null)
        }
    }

    private fun buildPrompt(query: String): String = """
        아래 검색어를 Confluence 위키 검색에 맞게 분석하세요. 지정 형식만 출력하고 다른 텍스트는 금지합니다.

        QUERY: [Confluence 제목에 들어갈 법한 핵심 용어. 대화형 접미사 제거, 팀명·플랫폼 수식어는 유지]
        SYNONYMS: [같은 개념의 다른 표현 3~6개, 쉼표 구분. 한국어↔영어 양방향, 약어 확장 포함]
        DATE_AFTER: [최신/최근/지난주 의도면 yyyy-MM-dd, 아니면 빈칸]
        DATE_BEFORE: [기간 상한이 있으면 yyyy-MM-dd, 아니면 빈칸]

        검색어: $query
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(QueryUnderstanding::class.java)
        private val QUERY_RE = Regex("QUERY:\\s*(.+)")
        private val SYN_RE = Regex("SYNONYMS:\\s*(.+)")
        private val DATE_AFTER_RE = Regex("DATE_AFTER:\\s*(\\S*)")
        private val DATE_BEFORE_RE = Regex("DATE_BEFORE:\\s*(\\S*)")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :search:test --tests "io.github.veronikapj.wiki.search.QueryUnderstandingTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add search/src/main/kotlin/io/github/veronikapj/wiki/search/QueryUnderstanding.kt \
        search/src/test/kotlin/io/github/veronikapj/wiki/search/QueryUnderstandingTest.kt
git commit -m "feat(search): QueryUnderstanding 공유 enrich 컴포넌트 추가"
```

---

### Task 2: ConfluenceTool enrich (W1) + Main 배선

Koog 경로의 `confluenceSearch(query)`가 `QueryUnderstanding`으로 enrich하도록.

**Files:**
- Modify: `search/src/main/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceTool.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt:152-160`
- Test: `search/src/test/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceToolTest.kt` (신규)

**Interfaces:**
- Consumes: `QueryUnderstanding`, `UnderstoodQuery` (Task 1)
- Produces: `ConfluenceTool(searchAgent, tracker, queryUnderstanding: QueryUnderstanding? = null)` — 생성자에 nullable 3번째 인자 추가. null이면 기존 bare-query 동작.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.QueryLlm
import io.github.veronikapj.wiki.search.QueryUnderstanding
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class ConfluenceToolTest {

    @Test
    fun `enriches query with synonyms and dates when QueryUnderstanding present`() = runBlocking {
        val searchAgent = mockk<ConfluenceSearchAgent>()
        coEvery { searchAgent.search(any(), any(), any(), any(), any(), any()) } returns "결과"

        val llm = QueryLlm {
            """
            QUERY: iOS 배포 가이드
            SYNONYMS: iOS 릴리즈, iOS Release
            DATE_AFTER: 2026-01-01
            DATE_BEFORE:
            """.trimIndent()
        }
        val tool = ConfluenceTool(searchAgent, null, QueryUnderstanding(llm))

        tool.confluenceSearch("iOS 배포 가이드 알려줘")

        coVerify {
            searchAgent.search(
                "iOS 배포 가이드",
                listOf("iOS 릴리즈", "iOS Release"),
                any(),
                "2026-01-01",
                null,
                "iOS 배포 가이드 알려줘",
            )
        }
    }

    @Test
    fun `uses bare query when QueryUnderstanding absent`() = runBlocking {
        val searchAgent = mockk<ConfluenceSearchAgent>()
        coEvery { searchAgent.search(any()) } returns "결과"

        val tool = ConfluenceTool(searchAgent, null, null)
        tool.confluenceSearch("결제 흐름")

        coVerify { searchAgent.search("결제 흐름") }
    }
}
```

참고: `ConfluenceSearchAgent.search`의 시그니처는
`search(query: String, synonyms: List<String> = emptyList(), topK: Int = 5, dateAfter: String? = null, dateBefore: String? = null, originalQuestion: String = "")`.
enrich 호출은 위치 인자 순서대로 `(cleanedQuery, synonyms, topK=5, dateAfter, dateBefore, originalQuestion=rawQuery)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :search:test --tests "io.github.veronikapj.wiki.search.tool.ConfluenceToolTest"`
Expected: FAIL — `ConfluenceTool` 생성자 3-인자 미지원 (compile error).

- [ ] **Step 3: Write minimal implementation**

`ConfluenceTool.kt` 를 아래로 교체:

```kotlin
package io.github.veronikapj.wiki.search.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.QueryUnderstanding
import kotlinx.coroutines.runBlocking

class ConfluenceTool(
    private val searchAgent: ConfluenceSearchAgent,
    private val tracker: SourceTracker? = null,
    private val queryUnderstanding: QueryUnderstanding? = null,
) {

    @Tool("confluenceSearch")
    @LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
    fun confluenceSearch(
        @LLMDescription("검색할 질문 또는 키워드 (한국어 가능)")
        query: String,
    ): String = runBlocking {
        tracker?.record("Confluence")
        val u = queryUnderstanding
        if (u == null) {
            searchAgent.search(query)
        } else {
            val understood = u.understand(query)
            searchAgent.search(
                understood.cleanedQuery,
                understood.synonyms,
                5,
                understood.dateAfter,
                understood.dateBefore,
                query,
            )
        }
    }

    suspend fun confluenceSearchSuspend(
        query: String, synonyms: List<String> = emptyList(),
        dateAfter: String? = null, dateBefore: String? = null,
        originalQuestion: String = "",
    ): String {
        tracker?.record("Confluence")
        return searchAgent.search(query, synonyms, dateAfter = dateAfter, dateBefore = dateBefore, originalQuestion = originalQuestion)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :search:test --tests "io.github.veronikapj.wiki.search.tool.ConfluenceToolTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire in Main.kt**

`Main.kt:152-160` 의 Confluence 구성 블록을 아래로 교체 (routerExecutor+routerModel 기반 `QueryUnderstanding` 주입):

```kotlin
    // Confluence 검색 에이전트
    var confluenceTool: ConfluenceTool? = null
    if (confluenceClient != null) {
        val confluenceSearchAgent = ConfluenceSearchAgent(
            confluenceClient = confluenceClient,
            spaces = config.confluence.spaces,
        )
        val queryUnderstanding = io.github.veronikapj.wiki.search.QueryUnderstanding { prompt ->
            routerExecutor.execute(prompt("qu") { user(prompt) }, routerModel).joinToString("") { it.content }
        }
        confluenceTool = ConfluenceTool(confluenceSearchAgent, sourceTracker, queryUnderstanding)
    }
```

주의: `prompt` 변수명이 Koog DSL 함수 `prompt(...)` 와 겹친다. 람다 파라미터를 `p`로 바꾸고
`prompt("qu") { user(p) }` 로 작성할 것. `routerExecutor`/`routerModel` 가 이 스코프에 정의돼
있는지 확인 (`Main.kt` 상단에서 선언됨). 없으면 `executor`/`model` 사용.

- [ ] **Step 6: Verify build + full search/app tests**

Run: `./gradlew :search:test test`
Expected: PASS (전체). 컴파일 통과 + 기존 테스트 회귀 없음.

- [ ] **Step 7: Commit**

```bash
git add search/src/main/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceTool.kt \
        search/src/test/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceToolTest.kt \
        src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat(search): Koog confluenceSearch tool을 QueryUnderstanding으로 enrich (W1)"
```

---

### Task 3: QueryDecomposer (:app)

복합 질문을 독립 sub-question으로 분해.

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/QueryDecomposer.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/QueryDecomposerTest.kt`

**Interfaces:**
- Consumes: `LLMCaller` (기존, `src/main/.../agent/QueryRewriter.kt`)
- Produces: `class QueryDecomposer(private val llm: LLMCaller)` with `suspend fun decompose(question: String, context: String = ""): List<String>`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.veronikapj.wiki.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryDecomposerTest {

    @Test
    fun `splits compound question into sub-questions`() = runTest {
        val llm = LLMCaller {
            """
            결제 흐름 코드
            결제 관련 위키 문서
            최근 결제 PR
            """.trimIndent()
        }
        val result = QueryDecomposer(llm).decompose("결제 흐름 코드랑 위키, 최근 PR 알려줘")

        assertEquals(
            listOf("결제 흐름 코드", "결제 관련 위키 문서", "최근 결제 PR"),
            result,
        )
    }

    @Test
    fun `returns single sub-question for simple question`() = runTest {
        val llm = LLMCaller { "iOS 배포 가이드" }
        val result = QueryDecomposer(llm).decompose("iOS 배포 가이드 알려줘")
        assertEquals(listOf("iOS 배포 가이드"), result)
    }

    @Test
    fun `strips bullet prefixes and blank lines`() = runTest {
        val llm = LLMCaller { "- A 질문\n\n* B 질문\n" }
        val result = QueryDecomposer(llm).decompose("A랑 B")
        assertEquals(listOf("A 질문", "B 질문"), result)
    }

    @Test
    fun `falls back to original question when llm throws`() = runTest {
        val llm = LLMCaller { throw RuntimeException("boom") }
        val result = QueryDecomposer(llm).decompose("원본 질문")
        assertEquals(listOf("원본 질문"), result)
    }

    @Test
    fun `falls back to original when llm returns blank`() = runTest {
        val llm = LLMCaller { "   \n  " }
        val result = QueryDecomposer(llm).decompose("원본 질문")
        assertEquals(listOf("원본 질문"), result)
    }

    @Test
    fun `caps at three sub-questions`() = runTest {
        val llm = LLMCaller { "a\nb\nc\nd\ne" }
        val result = QueryDecomposer(llm).decompose("많은 질문")
        assertEquals(listOf("a", "b", "c"), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.QueryDecomposerTest"`
Expected: FAIL — `QueryDecomposer` 미정의 (compile error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.veronikapj.wiki.agent

import org.slf4j.LoggerFactory

/**
 * 복합 질문을 독립적으로 검색 가능한 sub-question으로 분해한다.
 * 단순 질문이면 원본 1개를 그대로 반환 → 기존 단일 경로와 동일하게 동작.
 */
class QueryDecomposer(private val llm: LLMCaller) {

    suspend fun decompose(question: String, context: String = ""): List<String> {
        return runCatching {
            val raw = llm.call(buildPrompt(question, context))
            val parsed = raw.lines()
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_SUB_QUESTIONS)
            parsed.ifEmpty { listOf(question) }
        }.getOrElse {
            log.warn("QueryDecomposer failed, fallback to original: {}", it.message)
            listOf(question)
        }
    }

    private fun buildPrompt(question: String, context: String): String = buildString {
        appendLine("아래 질문을 독립적으로 검색 가능한 하위 질문으로 나누세요.")
        appendLine("- 복합 질문(서로 다른 주제·대상이 둘 이상)이면 하위 질문을 한 줄에 하나씩 출력.")
        appendLine("- 단순 질문이면 원래 질문을 한 줄로 그대로 출력.")
        appendLine("- 최대 3개. 설명·번호·불릿 없이 질문 문장만 출력.")
        if (context.isNotBlank()) {
            appendLine()
            appendLine("이전 대화(지시어 해소용):")
            appendLine(context)
        }
        appendLine()
        appendLine("질문: $question")
    }

    companion object {
        private val log = LoggerFactory.getLogger(QueryDecomposer::class.java)
        private const val MAX_SUB_QUESTIONS = 3
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.QueryDecomposerTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/QueryDecomposer.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/QueryDecomposerTest.kt
git commit -m "feat(agent): QueryDecomposer 복합질문 분해 컴포넌트 추가 (W2)"
```

---

### Task 4: StepResult + 섹션 요약 프롬프트 빌더 (순수 함수)

분해 집계·섹션 조립을 순수 함수로 분리해 TDD.

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/CompoundAnswer.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/CompoundAnswerTest.kt`

**Interfaces:**
- Produces:
  - `data class StepResult(val subQuestion: String, val toolName: String, val searchResult: String?)`
  - `fun buildSectionedResultBlock(steps: List<StepResult>): String` — 검색결과 블록(요약 프롬프트에 삽입할 본문). null searchResult는 "관련 문서를 찾지 못했습니다"로 표기.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.veronikapj.wiki.agent

import kotlin.test.Test
import kotlin.test.assertTrue

class CompoundAnswerTest {

    @Test
    fun `builds labeled sections for each step`() {
        val steps = listOf(
            StepResult("결제 흐름 코드", "codeSearch", "PaymentFlow.kt ..."),
            StepResult("결제 위키", "confluenceSearch", "컬리페이 설계 문서 ..."),
        )
        val block = buildSectionedResultBlock(steps)

        assertTrue(block.contains("결제 흐름 코드"))
        assertTrue(block.contains("PaymentFlow.kt"))
        assertTrue(block.contains("결제 위키"))
        assertTrue(block.contains("컬리페이 설계 문서"))
    }

    @Test
    fun `marks step with null result as not found`() {
        val steps = listOf(StepResult("없는 주제", "confluenceSearch", null))
        val block = buildSectionedResultBlock(steps)
        assertTrue(block.contains("없는 주제"))
        assertTrue(block.contains("찾지 못했습니다"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.CompoundAnswerTest"`
Expected: FAIL — `StepResult` / `buildSectionedResultBlock` 미정의.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.veronikapj.wiki.agent

data class StepResult(
    val subQuestion: String,
    val toolName: String,
    val searchResult: String?,
)

/** N개 StepResult를 sub-question 라벨 섹션으로 묶어 요약 프롬프트 본문을 만든다. */
fun buildSectionedResultBlock(steps: List<StepResult>): String = buildString {
    steps.forEachIndexed { i, step ->
        appendLine("[${i + 1}. ${step.subQuestion}]")
        appendLine(step.searchResult ?: "관련 문서를 찾지 못했습니다.")
        appendLine()
    }
}.trim()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.CompoundAnswerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/CompoundAnswer.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/CompoundAnswerTest.kt
git commit -m "feat(agent): StepResult + 섹션 요약 블록 빌더 추가 (W2)"
```

---

### Task 5: routeAndRetrieve 추출 (behavior-preserving 리팩터)

`answerWithManualLoop`에서 "질문 1개 → 검색결과 문자열" 부분을 재사용 가능한 private suspend 함수로 분리. **동작 불변** — 이 태스크는 기존 테스트가 계속 통과하는 게 합격 기준.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Interfaces:**
- Produces (private):
  ```kotlin
  private suspend fun routeAndRetrieve(
      subQuestion: String,
      contextHistory: List<Turn>,
      memory: String?,
      availableTools: List<String>,
      listener: SearchProgressListener?,
      forceTool: String?,
      forceAllTools: Boolean,
      userId: String?,
  ): StepResult
  ```

추출 범위: 현재 `answerWithManualLoop` 본문에서 **decisionPrompt 구성(`~127-250`) → 라우터 호출/파싱(`~252-323`) → tool 실행하여 `searchResult` 산출(`~401-454`)** 까지를 `routeAndRetrieve`로 옮긴다. 단:
- 특수 tool early-return(`none`/`progressAdvisor`/`onboarding`, `~326-399`)은 **추출하지 않는다**. 이들은 `answerWithManualLoop`에 그대로 남겨 단일질문 경로에서만 처리.
- `routeAndRetrieve`는 `toolName`이 특수 tool이면 `StepResult(subQuestion, toolName, null)`을 반환(검색 안 함). 단일질문 호출자는 이 경우를 만나지 않음(아래 Step 설명).
- 함수 끝에서 `question` 대신 `subQuestion`을 사용하도록 내부 참조를 모두 치환. (router prompt의 `질문: $question` → `질문: $subQuestion`, query 파싱 폴백 `?: question` → `?: subQuestion`, originalQuestion 인자 → `subQuestion`.)

- [ ] **Step 1: 추출 — routeAndRetrieve 함수 작성**

`answerWithManualLoop` 위 또는 아래에 새 private 함수를 만들고, 위 "추출 범위"의 코드를 이동한다. 반환은 `StepResult(subQuestion, toolName ?: "fallback", searchResult)`. 특수 tool 분기는 함수 진입 직후:

```kotlin
// routeAndRetrieve 내부, toolName 파싱 직후:
if (toolName in setOf("none", "progressAdvisor", "onboarding")) {
    return StepResult(subQuestion, toolName ?: "none", null)
}
```

기존 `executeParallel` 등 검색 실행부는 그대로 호출하되 `question` → `subQuestion`로 치환.

- [ ] **Step 2: answerWithManualLoop이 routeAndRetrieve를 호출하도록 변경**

`answerWithManualLoop`은 (a) 특수 tool 조기처리를 위해 라우터 결정을 한 번 보고, (b) 검색성이면 `routeAndRetrieve(question, …)` 한 번 호출해 `StepResult` 받아 그 `searchResult`로 기존 요약(`~457-518`)을 태운다.

이번 태스크에서는 **단일 호출만** — 분해 없이 `routeAndRetrieve(question, …)` 1회. 즉 동작은 기존과 동일해야 한다.

주의: 특수 tool(none/onboarding/progressAdvisor) 경로는 라우터 결정이 필요하다. 현재 구조상
라우터 호출이 `routeAndRetrieve` 안으로 들어갔으므로, 특수 tool 조기 반환을 유지하려면 라우터
결정을 한 번 수행해 toolName을 얻는 부분을 `answerWithManualLoop`에 남기거나, `routeAndRetrieve`가
특수 tool일 때 `StepResult(_, toolName, null)`을 반환하고 호출자가 `toolName`을 보고 기존 특수
분기(noneAnswer/onboarding/progressAdvisor 처리, `~326-399`)로 위임하도록 한다. **후자를 채택**:
`routeAndRetrieve` 결과의 `toolName`이 특수면 호출자가 해당 기존 블록으로 분기.

- [ ] **Step 3: Run existing tests (회귀 검증)**

Run: `./gradlew test`
Expected: PASS — 기존 `OrchestratorAgentTest`, `RouterSmokeTest` 등 전부 통과. 동작 불변 확인.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "refactor(agent): routeAndRetrieve 추출 (동작 불변, W2 준비)"
```

---

### Task 6: 분해→병렬 라우팅→섹션 통합 답변 배선 (W2) + Main

`QueryDecomposer`로 쪼개 `routeAndRetrieve`를 병렬 호출하고 섹션 요약으로 합친다.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt` (생성자 + answerWithManualLoop)
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt` (decomposer 주입)
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt` (보강)

**Interfaces:**
- Consumes: `QueryDecomposer`(Task 3), `StepResult`/`buildSectionedResultBlock`(Task 4), `routeAndRetrieve`(Task 5)
- Produces: `OrchestratorAgent(… , queryDecomposer: QueryDecomposer? = null)` 생성자 인자 추가.

- [ ] **Step 1: 생성자에 queryDecomposer 추가**

`OrchestratorAgent` 생성자 끝에 추가:

```kotlin
    private val queryDecomposer: QueryDecomposer? = null,
```

- [ ] **Step 2: answerWithManualLoop에 분해 분기 추가**

특수 tool 프리라우트 통과 후(검색성 질문), 다음 흐름으로 변경:

```kotlin
// 컨텍스트 문자열 (분해기 지시어 해소용)
val contextText = contextHistory.takeLast(3).joinToString("\n") { "Q: ${it.question} A: ${it.answer.take(120)}" }

val subQuestions = queryDecomposer
    ?.decompose(question, contextText)
    ?.distinct()
    ?: listOf(question)

val steps: List<StepResult> = if (subQuestions.size <= 1) {
    listOf(routeAndRetrieve(subQuestions.firstOrNull() ?: question,
        contextHistory, memory, availableTools, listener, forceTool, forceAllTools, userId))
} else {
    coroutineScope {
        subQuestions.map { sq ->
            async {
                runCatching {
                    routeAndRetrieve(sq, contextHistory, memory, availableTools, listener, forceTool, forceAllTools, userId)
                }.getOrElse { StepResult(sq, "error", null) }
            }
        }.map { it.await() }
    }
}
```

특수 tool 처리: `steps`가 단일이고 그 `toolName`이 `none`/`onboarding`/`progressAdvisor`이면 기존
특수 분기(`~326-399`)로 위임(Task 5 Step 2의 위임 구조 사용). 복합(size>1)에서는 특수 tool로
라우팅된 step은 `routeAndRetrieve`가 `searchResult=null`로 반환하므로 자연히 "못 찾음" 섹션이 됨.

검색결과 블록을 섹션으로 구성해 기존 요약 프롬프트의 `searchResult` 자리에 사용:

```kotlin
val searchResult: String? = run {
    val block = buildSectionedResultBlock(steps)
    block.takeIf { steps.any { s -> s.searchResult != null } }
}
```

복합일 때 요약 프롬프트에 병합 지시를 추가 (기존 `summaryPrompt` 빌드 내, 검색결과 출력 직후):

```kotlin
if (steps.size > 1) {
    appendLine("위 결과는 질문을 여러 하위 항목으로 나눠 검색한 것입니다.")
    appendLine("각 하위 항목을 소제목으로 구분해 답하고, 같은 문서가 여러 항목에 나오면 한 번만 인용하세요.")
}
```

- [ ] **Step 3: Write integration test (분해 동작)**

`OrchestratorAgentTest.kt`에 추가. mock executor가 라우터/요약 호출에 응답하도록 구성하기 복잡하므로,
여기서는 **분해기가 주입되면 복합 질문에 대해 routeAndRetrieve가 sub-question 수만큼 호출되는지**를
경량 검증한다. (executor는 relaxed mock; decomposer는 가짜 LLMCaller.)

```kotlin
@Test
fun `decomposes compound question into multiple sub-questions`() = runTest {
    val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>(relaxed = true))
    val decomposer = QueryDecomposer(LLMCaller { "A 질문\nB 질문" })

    val agent = OrchestratorAgent(
        confluenceTool = confluenceTool,
        executor = mockExecutor,
        useManualLoop = true,
        queryDecomposer = decomposer,
    )
    // 예외 없이 답변을 반환하면 통과 (분해→병렬 경로가 깨지지 않음)
    val result = agent.answer("A랑 B 알려줘")
    assertNotNull(result)
}
```

참고: 더 정밀한 호출횟수 검증이 필요하면 `routeAndRetrieve`를 `internal`로 노출하는 대신,
`SourceTracker` 또는 `SearchProgressListener` 콜백 횟수로 간접 검증할 수 있다(기존 listener 활용).

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.OrchestratorAgentTest"`
Expected: PASS (기존 + 신규).

- [ ] **Step 5: Wire decomposer in Main.kt**

`OrchestratorAgent(...)` 생성 지점(`Main.kt:~310-329`)에 추가:

```kotlin
        queryDecomposer = io.github.veronikapj.wiki.agent.QueryDecomposer(
            io.github.veronikapj.wiki.agent.LLMCaller { p ->
                routerExecutor.execute(prompt("decompose") { user(p) }, routerModel).joinToString("") { it.content }
            }
        ),
```

- [ ] **Step 6: Full build + test**

Run: `./gradlew test`
Expected: PASS (전체 회귀 없음).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/main/kotlin/io/github/veronikapj/wiki/Main.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat(agent): 복합질문 분해→병렬 라우팅→섹션 통합 답변 (W2)"
```

---

## 최종 검증

- [ ] `./gradlew test :search:test` 전체 통과
- [ ] 단일 질문이 기존과 동일하게 동작 (분해 1개 경로)
- [ ] Koog 경로(GOOGLE)에서 `confluenceSearch`가 synonyms·날짜로 enrich (로그로 확인)
- [ ] 수동 루프(*_CODE)에서 복합 질문이 섹션별로 분해 답변되는지 수동 점검

## 자체 검토 결과 (spec 대비)

- spec §3 QueryUnderstanding → Task 1 ✅
- spec §4 W1 Koog enrich → Task 2 ✅
- spec §5.2 QueryDecomposer → Task 3 ✅
- spec §5.3 routeAndRetrieve 추출 → Task 5 ✅
- spec §5.1 분해→병렬→집계→섹션 답변 → Task 4(빌더) + Task 6(배선) ✅
- spec §6 실패·폴백 → Task 1/3 폴백, Task 6 step async getOrElse ✅
- spec §7 테스트 → 각 Task의 L1 테스트 ✅
- 타입 일관성: `StepResult`(subQuestion/toolName/searchResult), `UnderstoodQuery`, `routeAndRetrieve` 시그니처 Task 간 일치 확인.
