# Search Quality Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** "클라이언트 위클리" 질문 시 물류 위클리가 아닌 클라이언트 위클리가 상위에 오도록 검색 품질을 개선한다.

**Architecture:** (A) OrchestratorAgent 라우터 프롬프트에서 팀명 제거 지시를 삭제 → 검색어에 "클라이언트" 보존. (C) ConfluenceSearchAgent에 `originalQuestion` 파라미터 추가 → `combineAndRank()` 후 원래 질문 키워드로 제목을 재정렬.

**Tech Stack:** Kotlin, MockK, JUnit (kotlin.test), kotlinx-coroutines-test

---

## Context

핵심 파일:
- `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt` — QUERY 프롬프트 155-157번째 줄
- `src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt` — `searchStructured()`, `search()`, `combineAndRank()`
- `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt` — `confluenceSearchSuspend()`
- `src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt` — 기존 테스트 (패턴 참고)

현재 문제 위치 (`OrchestratorAgent.kt:153-157`):
```kotlin
appendLine("QUERY 작성 원칙:")
appendLine("- Confluence 페이지 제목에 들어갈 법한 핵심 용어로 추출하세요.")
appendLine("- 플랫폼(안드로이드/iOS), 팀명 등 수식어보다 문서 이름 자체를 우선하세요.")  // ← 팀명 드롭 지시
appendLine("- 예: \"안드로이드 tech talk 위키 찾아줘\" → QUERY: tech talk")
appendLine("- 예: \"iOS 배포 프로세스 어떻게 돼?\" → QUERY: 배포 프로세스")
```

`executeParallel()` 호출부 (`OrchestratorAgent.kt:272`):
```kotlin
runCatching { executeParallel(query, synonyms, dateAfter, dateBefore) }.getOrNull()
```

---

## Task 1: OrchestratorAgent QUERY 프롬프트 수정

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt:153-157`

### Step 1: 프롬프트 원칙 교체

`OrchestratorAgent.kt:153-157`에서 아래 5줄을:
```kotlin
appendLine("QUERY 작성 원칙:")
appendLine("- Confluence 페이지 제목에 들어갈 법한 핵심 용어로 추출하세요.")
appendLine("- 플랫폼(안드로이드/iOS), 팀명 등 수식어보다 문서 이름 자체를 우선하세요.")
appendLine("- 예: \"안드로이드 tech talk 위키 찾아줘\" → QUERY: tech talk")
appendLine("- 예: \"iOS 배포 프로세스 어떻게 돼?\" → QUERY: 배포 프로세스")
```

아래로 교체:
```kotlin
appendLine("QUERY 작성 원칙:")
appendLine("- Confluence 페이지 제목에 들어갈 법한 핵심 용어로 추출하세요.")
appendLine("- 검색 범위를 좁히는 수식어(팀명·부서명·플랫폼)는 반드시 QUERY에 포함하세요.")
appendLine("  좋은 예: \"클라이언트 위클리\", \"iOS 배포 가이드\", \"프론트엔드 온보딩\"")
appendLine("  나쁜 예: \"위클리\" (팀명 제거 → 물류·서버·디자인 팀 위클리까지 혼입)")
appendLine("- 플랫폼/언어가 수식어에 불과하고 동종 문서가 하나뿐인 경우에만 제거하세요.")
appendLine("  예: \"안드로이드 tech talk\" → QUERY: tech talk (안드로이드팀 내 유일한 tech talk)")
appendLine("  예: \"iOS 배포 프로세스\" → QUERY: 배포 프로세스 (iOS·안드로이드 통합 문서인 경우)")
```

### Step 2: 컴파일 확인

```bash
cd /Users/pilju.bae/projects/wiki-agent
./gradlew compileKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

### Step 3: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "fix: preserve team names in router QUERY prompt"
```

---

## Task 2: ConfluenceSearchAgent post-retrieval re-ranking

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt`

### Step 1: 실패 테스트 먼저 작성

`ConfluenceSearchAgentTest.kt` 하단 `}` 직전에 3개 테스트 추가:

```kotlin
@Test
fun `re-ranking moves originalQuestion keyword-matching title to top`() = runTest {
    val mockClient = mockk<ConfluenceClient>()
    // 1개 title match → threshold=3 미만 → 병렬 fallback
    coEvery { mockClient.searchByTitle("위클리", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("1", "물류 위클리 2026-W18", "url1", titleMatched = true),
    )
    coEvery { mockClient.searchByText("위클리", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("2", "클라이언트 위클리 2026-W18", "url2"),
        ConfluencePageRef("3", "서버 위클리 2026-W18", "url3"),
    )
    coEvery { mockClient.searchByTitle("위클리", emptyList(), any(), any()) } returns emptyList()

    val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
    val results = agent.searchStructured("위클리", originalQuestion = "클라이언트 위클리 알려줘")

    // 재정렬 전: [물류 위클리(TITLE), 클라이언트 위클리(TEXT), 서버 위클리(TEXT)]
    // 재정렬 후: 클라이언트 위클리가 "클라이언트" + "위클리" 2 hits → 1위
    assertEquals("2", results[0].pageId)
}

@Test
fun `re-ranking also applies to early-return path`() = runTest {
    val mockClient = mockk<ConfluenceClient>()
    // 3개 title match → early return 발동
    coEvery { mockClient.searchByTitle("위클리", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("1", "물류 위클리 2026-W18", "url1", titleMatched = true),
        ConfluencePageRef("2", "클라이언트 위클리 2026-W18", "url2", titleMatched = true),
        ConfluencePageRef("3", "서버 위클리 2026-W18", "url3", titleMatched = true),
    )

    val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
    val results = agent.searchStructured("위클리", originalQuestion = "클라이언트 위클리 알려줘")

    assertEquals("2", results[0].pageId)
}

@Test
fun `no re-ranking when originalQuestion is blank`() = runTest {
    val mockClient = mockk<ConfluenceClient>()
    coEvery { mockClient.searchByTitle("위클리", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("1", "물류 위클리", "url1", titleMatched = true),
        ConfluencePageRef("2", "클라이언트 위클리", "url2", titleMatched = true),
        ConfluencePageRef("3", "서버 위클리", "url3", titleMatched = true),
    )

    val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
    val results = agent.searchStructured("위클리")  // originalQuestion 미지정 → 기존 순서 유지

    assertEquals("1", results[0].pageId)  // 물류 위클리가 원래 순서 1위
}
```

### Step 2: 테스트 실패 확인

```bash
./gradlew test --tests "*.ConfluenceSearchAgentTest.re-ranking*" 2>&1 | tail -15
./gradlew test --tests "*.ConfluenceSearchAgentTest.no re-ranking*" 2>&1 | tail -10
```
Expected: FAIL — `searchStructured` 에 `originalQuestion` 파라미터 없음

### Step 3: reRankByOriginalQuestion 헬퍼 추가

`ConfluenceSearchAgent.kt`에서 `combineAndRank` 메서드 바로 앞에 추가:

```kotlin
private fun reRankByOriginalQuestion(results: List<SearchResult>, originalQuestion: String): List<SearchResult> {
    if (originalQuestion.isBlank()) return results
    val keywords = extractSignificantKeywords(cleanQuery(originalQuestion))
    if (keywords.isEmpty()) return results
    return results.sortedByDescending { page ->
        keywords.count { kw -> page.title.lowercase().contains(kw.lowercase()) }
    }
}
```

### Step 4: searchStructured 파라미터 + 재정렬 적용

`ConfluenceSearchAgent.kt:17-20` 시그니처에 `originalQuestion` 추가:
```kotlin
suspend fun searchStructured(
    query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
    dateAfter: String? = null, dateBefore: String? = null,
    originalQuestion: String = "",
): List<SearchResult> {
```

Early return 블록 (현재 30-33번째 줄) 수정:
```kotlin
// Before:
if (titleResults.size >= sufficientThreshold) {
    log.info("Sufficient title matches ({}>={}), early return", titleResults.size, sufficientThreshold)
    return titleResults.take(topK).map { it.toSearchResult(SearchStage.TITLE_MATCH) }
}

// After:
if (titleResults.size >= sufficientThreshold) {
    log.info("Sufficient title matches ({}>={}), early return", titleResults.size, sufficientThreshold)
    val earlyResults = titleResults.take(topK).map { it.toSearchResult(SearchStage.TITLE_MATCH) }
    return reRankByOriginalQuestion(earlyResults, originalQuestion)
}
```

마지막 return (현재 72번째 줄) 수정:
```kotlin
// Before:
return combineAndRank(titleResults, textResults, expandedResults, ragResults, keywordResults, topK)

// After:
return reRankByOriginalQuestion(
    combineAndRank(titleResults, textResults, expandedResults, ragResults, keywordResults, topK),
    originalQuestion,
)
```

### Step 5: search() 메서드 시그니처 + 전달

`ConfluenceSearchAgent.kt:99-106` (search 메서드) 수정:
```kotlin
// Before:
suspend fun search(
    query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
    dateAfter: String? = null, dateBefore: String? = null,
): String {
    val results = searchStructured(query, synonyms, topK, dateAfter, dateBefore)

// After:
suspend fun search(
    query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
    dateAfter: String? = null, dateBefore: String? = null,
    originalQuestion: String = "",
): String {
    val results = searchStructured(query, synonyms, topK, dateAfter, dateBefore, originalQuestion)
```

### Step 6: 테스트 통과 확인

```bash
./gradlew test --tests "*.ConfluenceSearchAgentTest" 2>&1 | tail -20
```
Expected: 기존 테스트 + 새 3개 = 모두 PASS

### Step 7: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt
git commit -m "feat: post-retrieval re-ranking by original question keywords"
```

---

## Task 3: originalQuestion 호출 체인 연결

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

### Step 1: ConfluenceTool.confluenceSearchSuspend 파라미터 추가

`ConfluenceTool.kt:23-29`에서:
```kotlin
// Before:
suspend fun confluenceSearchSuspend(
    query: String, synonyms: List<String> = emptyList(),
    dateAfter: String? = null, dateBefore: String? = null,
): String {
    tracker?.record("Confluence")
    return searchAgent.search(query, synonyms, dateAfter = dateAfter, dateBefore = dateBefore)
}

// After:
suspend fun confluenceSearchSuspend(
    query: String, synonyms: List<String> = emptyList(),
    dateAfter: String? = null, dateBefore: String? = null,
    originalQuestion: String = "",
): String {
    tracker?.record("Confluence")
    return searchAgent.search(query, synonyms, dateAfter = dateAfter, dateBefore = dateBefore, originalQuestion = originalQuestion)
}
```

### Step 2: OrchestratorAgent.executeParallel 파라미터 추가

`OrchestratorAgent.kt:340-341` (executeParallel 시그니처):
```kotlin
// Before:
internal suspend fun executeParallel(
    query: String, synonyms: List<String> = emptyList(),
    dateAfter: String? = null, dateBefore: String? = null,
): String? {

// After:
internal suspend fun executeParallel(
    query: String, synonyms: List<String> = emptyList(),
    dateAfter: String? = null, dateBefore: String? = null,
    originalQuestion: String = "",
): String? {
```

같은 메서드 안에서 `confluenceTool.confluenceSearchSuspend()` 호출부 수정
(`OrchestratorAgent.kt:352` 부근):
```kotlin
// Before:
runCatching { confluenceTool.confluenceSearchSuspend(query, synonyms, dateAfter, dateBefore) }.getOrNull()

// After:
runCatching { confluenceTool.confluenceSearchSuspend(query, synonyms, dateAfter, dateBefore, originalQuestion) }.getOrNull()
```

### Step 3: executeParallel 호출부에 question 전달

`OrchestratorAgent.kt:272` 부근 (else 브랜치):
```kotlin
// Before:
runCatching { executeParallel(query, synonyms, dateAfter, dateBefore) }.getOrNull()

// After:
runCatching { executeParallel(query, synonyms, dateAfter, dateBefore, question) }.getOrNull()
```

`question`은 `answerWithManualLoop(question: String, ...)` 의 파라미터 — 원래 사용자 질문.

### Step 4: 컴파일 + 전체 테스트

```bash
./gradlew compileKotlin 2>&1 | tail -5
./gradlew test 2>&1 | tail -20
```
Expected: 두 명령 모두 BUILD SUCCESSFUL

### Step 5: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: thread originalQuestion through to ConfluenceSearchAgent"
```

---

## 완료 기준

- [ ] `ConfluenceSearchAgentTest` 기존 + 새 3개 = 모두 PASS
- [ ] 전체 `./gradlew test` PASS
- [ ] 봇 재시작 후 "클라이언트 위클리" 질문 → 클라이언트팀 위클리 문서 최상위
- [ ] "물류 위클리" 질문 → 물류팀 위클리 문서 최상위 (회귀 없음)
