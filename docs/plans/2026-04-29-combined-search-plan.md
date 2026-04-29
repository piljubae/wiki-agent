# Combined Search Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** `answerWithManualLoop()`에서 지식베이스와 Confluence를 항상 병렬 검색하고 결과를 합산한다.

**Architecture:** `ConfluenceTool`에 suspend 버전 추가 → `OrchestratorAgent`에 `executeParallel()` 추가 → manual loop에서 githubWikiSearch 외 모든 질문은 병렬 검색 경로로 처리.

**Tech Stack:** Kotlin 2.3, kotlinx-coroutines, MockK, kotlinx-coroutines-test

---

### Task 1: `ConfluenceTool.kt` — suspend 검색 함수 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt`

`confluenceSearch()`는 내부에서 `runBlocking`을 쓰므로 coroutine 병렬 실행 컨텍스트에서 deadlock 위험이 있다.
기존 함수는 Koog tool 등록용으로 그대로 두고, suspend 버전을 추가한다.

**Step 1: 테스트 먼저 작성**

`src/test/kotlin/io/github/veronikapj/wiki/agent/tool/ToolTest.kt`에 추가:

```kotlin
import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@Test
fun `confluenceSearchSuspend delegates to searchAgent`() = runTest {
    val searchAgent = mockk<ConfluenceSearchAgent>()
    coEvery { searchAgent.search(any()) } returns "Confluence 결과"
    val tool = ConfluenceTool(searchAgent)
    val result = tool.confluenceSearchSuspend("배포 프로세스")
    assertEquals("Confluence 결과", result)
}
```

**Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "*.ToolTest.confluenceSearchSuspend*" 2>&1 | tail -20
```
Expected: 컴파일 오류 (`confluenceSearchSuspend` 없음)

**Step 3: 구현**

`ConfluenceTool.kt`에 추가:

```kotlin
suspend fun confluenceSearchSuspend(query: String): String {
    tracker?.record("Confluence")
    return searchAgent.search(query)
}
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "*.ToolTest.confluenceSearchSuspend*" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/tool/ToolTest.kt
git commit -m "feat: ConfluenceTool에 confluenceSearchSuspend() 추가"
```

---

### Task 2: `OrchestratorAgent.kt` — `executeParallel()` 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt`

**Step 1: 테스트 먼저 작성**

`OrchestratorAgentTest.kt`에 추가:

```kotlin
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.knowledge.KnowledgeTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.assertContains
import kotlin.test.assertNull

@Test
fun `executeParallel combines knowledge and confluence when both return results`() = runTest {
    val knowledgeTool = mockk<KnowledgeTool>()
    every { knowledgeTool.knowledgeSearch(any()) } returns "지식베이스 결과"

    val confluenceTool = mockk<ConfluenceTool>()
    coEvery { confluenceTool.confluenceSearchSuspend(any()) } returns "Confluence 결과"

    val agent = OrchestratorAgent(
        knowledgeTool = knowledgeTool,
        confluenceTool = confluenceTool,
        executor = LLMExecutorBuilder.build(ModelConfig()),
    )
    val result = agent.executeParallel("배포 프로세스", emptyList())
    assertContains(result!!, "[지식베이스]")
    assertContains(result, "[Confluence]")
    assertContains(result, "지식베이스 결과")
    assertContains(result, "Confluence 결과")
}

@Test
fun `executeParallel returns knowledge only when confluence finds nothing`() = runTest {
    val knowledgeTool = mockk<KnowledgeTool>()
    every { knowledgeTool.knowledgeSearch(any()) } returns "지식베이스 결과"

    val confluenceTool = mockk<ConfluenceTool>()
    coEvery { confluenceTool.confluenceSearchSuspend(any()) } returns "관련 문서를 찾을 수 없습니다."

    val agent = OrchestratorAgent(
        knowledgeTool = knowledgeTool,
        confluenceTool = confluenceTool,
        executor = LLMExecutorBuilder.build(ModelConfig()),
    )
    val result = agent.executeParallel("없는내용", emptyList())
    assertContains(result!!, "지식베이스 결과")
    assert(!result.contains("[Confluence]"))
}

@Test
fun `executeParallel returns null when both find nothing`() = runTest {
    val knowledgeTool = mockk<KnowledgeTool>()
    every { knowledgeTool.knowledgeSearch(any()) } returns "지식베이스에서 관련 내용을 찾을 수 없습니다."

    val confluenceTool = mockk<ConfluenceTool>()
    coEvery { confluenceTool.confluenceSearchSuspend(any()) } returns "관련 문서를 찾을 수 없습니다."

    val agent = OrchestratorAgent(
        knowledgeTool = knowledgeTool,
        confluenceTool = confluenceTool,
        executor = LLMExecutorBuilder.build(ModelConfig()),
    )
    val result = agent.executeParallel("없는내용", emptyList())
    assertNull(result)
}
```

**Step 2: 테스트 실패 확인**

```bash
./gradlew test --tests "*.OrchestratorAgentTest.executeParallel*" 2>&1 | tail -20
```
Expected: 컴파일 오류 (`executeParallel` 없음)

**Step 3: 구현**

`OrchestratorAgent.kt`에서 `executeFromDecision()` 아래에 추가:

```kotlin
internal suspend fun executeParallel(query: String, synonyms: List<String>): String? {
    val (knowledgeResult, confluenceResult) = coroutineScope {
        val kDeferred = async {
            if (knowledgeTool != null)
                runCatching { knowledgeTool.knowledgeSearch(query) }.getOrNull()
            else null
        }
        val cDeferred = async {
            if (confluenceTool != null)
                runCatching { confluenceTool.confluenceSearchSuspend(query) }.getOrNull()
            else null
        }
        kDeferred.await() to cDeferred.await()
    }

    val kValid = knowledgeResult?.takeIf { result ->
        !result.contains("찾을 수 없습니다") && !result.contains("비어있습니다")
    }
    val cValid = confluenceResult?.takeIf { result ->
        !result.contains("찾을 수 없습니다")
    }

    return when {
        kValid != null && cValid != null -> "[지식베이스]\n$kValid\n\n---\n\n[Confluence]\n$cValid"
        kValid != null -> kValid
        cValid != null -> cValid
        else -> null
    }
}
```

필요한 import 추가:
```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
```

**Step 4: 테스트 통과 확인**

```bash
./gradlew test --tests "*.OrchestratorAgentTest.executeParallel*" 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`, 3개 테스트 PASS

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat: OrchestratorAgent에 executeParallel() 추가"
```

---

### Task 3: `answerWithManualLoop()` — 병렬 검색 경로로 전환

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Step 1: 현재 검색 실행 블록 파악**

`answerWithManualLoop()` 내 아래 블록을 수정한다 (대략 line 116~120):

```kotlin
// 현재 코드
val toolName = Regex("TOOL:\\s*(\\S+)").find(decision)?.groupValues?.get(1)?.trim()
if (toolName != null) listener?.onSearchStarted(toolName)
var searchResult = runCatching { executeFromDecision(decision) }.getOrNull()
if (toolName != null) listener?.onSearchCompleted(toolName)
```

**Step 2: 수정 — 병렬 검색 우선, githubWikiSearch만 단독 실행**

```kotlin
val toolName = Regex("TOOL:\\s*(\\S+)").find(decision)?.groupValues?.get(1)?.trim()
val query = Regex("QUERY:\\s*(.+)").find(decision)?.groupValues?.get(1)?.trim() ?: question
val synonyms = Regex("SYNONYMS:\\s*(.+)").find(decision)?.groupValues?.get(1)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

val searchLabel = if (toolName == "githubWikiSearch") "githubWikiSearch" else "combinedSearch"
listener?.onSearchStarted(searchLabel)

var searchResult = if (toolName == "githubWikiSearch" && githubWikiTool != null) {
    runCatching { githubWikiTool.githubWikiSearch(query) }.getOrNull()
        ?.takeIf { !it.contains("찾을 수 없습니다") }
} else {
    runCatching { executeParallel(query, synonyms) }.getOrNull()
}

listener?.onSearchCompleted(searchLabel)
```

**Step 3: 전체 테스트 실행**

```bash
./gradlew test 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`

**Step 4: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: manual loop에서 지식베이스+Confluence 병렬 검색으로 전환"
```

---

### Task 4: 라우터 프롬프트 업데이트

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

라우터 프롬프트의 TOOL 안내를 현실에 맞게 수정한다. `githubWikiSearch`가 없을 때는 TOOL 라인을 아예 생략한다.

**Step 1: `decisionPrompt` 블록 수정**

`answerWithManualLoop()`의 `decisionPrompt` 내 아래 부분을 변경:

```kotlin
// 현재
appendLine("출력 형식 (이 세 줄만 출력, 다른 텍스트 금지):")
appendLine("TOOL: <도구이름>")
appendLine("QUERY: <핵심 검색어>")
appendLine("SYNONYMS: <동의어/유사 표현 2-3개, 쉼표 구분>")
appendLine()
appendLine("규칙:")
appendLine("- 어떤 질문이든 반드시 검색해야 합니다.")
appendLine("- QUERY는 핵심 키워드만 간결하게.")
appendLine("- SYNONYMS에 같은 의미의 다른 표현을 포함하세요. ...")
appendLine("- TOOL, QUERY, SYNONYMS 세 줄만 출력하세요.")
```

```kotlin
// 수정 후
if (githubWikiTool != null) {
    appendLine("출력 형식 (이 세 줄만 출력, 다른 텍스트 금지):")
    appendLine("TOOL: githubWikiSearch (코드/API/기술구현 질문) 또는 confluenceSearch (그 외)")
    appendLine("QUERY: <핵심 검색어>")
    appendLine("SYNONYMS: <동의어/유사 표현 2-3개, 쉼표 구분>")
    appendLine()
    appendLine("규칙:")
    appendLine("- githubWikiSearch: 코드, API, 기술 구현 질문에만 선택하세요.")
    appendLine("- confluenceSearch: 프로세스, 가이드, 팀 문서 질문 시 선택 (지식베이스+Confluence 병렬 검색).")
} else {
    appendLine("출력 형식 (두 줄만 출력, 다른 텍스트 금지):")
    appendLine("QUERY: <핵심 검색어>")
    appendLine("SYNONYMS: <동의어/유사 표현 2-3개, 쉼표 구분>")
    appendLine()
    appendLine("규칙:")
}
appendLine("- QUERY는 핵심 키워드만 간결하게.")
appendLine("- SYNONYMS에 같은 의미의 다른 표현을 포함하세요. 예: 신입 온보딩 → 신규 입사자, 입사 가이드, 온보딩 체크리스트")
```

**Step 2: 전체 테스트 실행**

```bash
./gradlew test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

**Step 3: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "refactor: 라우터 프롬프트를 병렬 검색 구조에 맞게 정리"
```

---

### Task 5: 최종 검증

**Step 1: 전체 테스트**

```bash
./gradlew test 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`, 모든 테스트 PASS

**Step 2: 컴파일**

```bash
./gradlew compileKotlin 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

**Step 3: 동작 확인 (CLI 모드)**

`.env`에 Slack 토큰이 없으면 CLI 모드로 실행된다:

```bash
./gradlew run 2>&1 &
# 질문 입력: "배포 프로세스가 어떻게 돼?"
# 출처에 "KnowledgeBase + Confluence" 또는 둘 중 하나가 표시되어야 함
```
