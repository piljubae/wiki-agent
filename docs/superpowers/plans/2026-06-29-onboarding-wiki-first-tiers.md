# 온보딩 위키-우선 2-tier 응답 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 온보딩 중 질문을 기본 위키 SSOT만으로(Tier 1) 답하고, 키워드 신호가 있을 때만 코드+PR+추가 Confluence를 연결(Tier 2)하며, `온보딩 초기화`는 세션을 지우고 즉시 재시작한다.

**Architecture:** `OnboardingTool.handleQuestion`이 `wantsDeepDive(message)` 키워드 판정으로 `ContentGatherer.gatherForQuestion(..., includeDeep)`을 호출. Tier 1은 위키 SSOT 섹션만, Tier 2는 코드/PR/Confluence를 추가. `classifyIntent`에 RESET을 더하고 `OnboardingSessionStore.delete`로 세션을 비운 뒤 `handleStart`로 재진입.

**Tech Stack:** Kotlin, JUnit5 + MockK, Gradle (멀티모듈: `:onboarding`이 `:search`/`:confluence`/`:github`에 의존).

## Global Constraints

- 단계 가이드(`generateGuide` / `ContentGatherer.gather(step)`)는 변경하지 않는다 (이미 위키 SSOT only).
- `prHistoryTool`은 nullable — null이면 PR 수집은 graceful skip.
- 테스트는 `:onboarding` 모듈. 세션 파일은 실제 `.wiki/onboarding/sessions/`에 쓰이므로 `U_TEST_` 접두사 + `@AfterEach` 정리 규칙을 따른다 (기존 테스트와 동일).
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- 작업 브랜치: `fix/onboarding-forcetool-koog-path` (이어서 커밋).

---

### Task 1: `OnboardingSessionStore.delete()`

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingSession.kt` (object `OnboardingSessionStore`, `exists` 아래)
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingSessionStoreTest.kt`

**Interfaces:**
- Produces: `fun OnboardingSessionStore.delete(userId: String): Boolean` — 세션 파일 삭제, 없으면 false.

- [ ] **Step 1: Write the failing test**

`OnboardingSessionStoreTest.kt`에 추가:

```kotlin
    @Test
    fun `delete는 세션 파일을 제거하고 이후 load는 null이다`() {
        val userId = uniqueUserId()
        OnboardingSessionStore.create(userId, testLevel, oneStep())
        assertTrue(OnboardingSessionStore.exists(userId))

        val deleted = OnboardingSessionStore.delete(userId)

        assertTrue(deleted)
        assertFalse(OnboardingSessionStore.exists(userId))
        assertNull(OnboardingSessionStore.load(userId))
    }

    @Test
    fun `delete는 세션이 없으면 false를 반환한다`() {
        assertFalse(OnboardingSessionStore.delete(uniqueUserId()))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.OnboardingSessionStoreTest"`
Expected: FAIL — `delete` 미해결 (compile error: unresolved reference: delete)

- [ ] **Step 3: Write minimal implementation**

`OnboardingSession.kt`의 `exists` 함수 바로 아래에 추가:

```kotlin
    fun delete(userId: String): Boolean {
        val file = sessionFile(userId)
        if (!file.exists()) return false
        return runCatching { file.delete() }
            .onFailure { e -> log.error("Failed to delete session for {}: {}", userId, e.message) }
            .getOrDefault(false)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.OnboardingSessionStoreTest"`
Expected: PASS (전체 OnboardingSessionStoreTest green)

- [ ] **Step 5: Commit**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingSession.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingSessionStoreTest.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): OnboardingSessionStore.delete 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `ContentGatherer` — includeDeep tier + PR 수집

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt`

**Interfaces:**
- Consumes: `io.github.veronikapj.wiki.search.tool.PrHistoryTool.prHistory(query: String): String`
- Produces:
  - `ContentGatherer` 생성자에 `prHistoryTool: PrHistoryTool? = null` (마지막 파라미터로 추가)
  - `fun gatherForQuestion(question: String, step: CurriculumStep?, includeDeep: Boolean): List<GatheredContent>` (시그니처 변경 — 기존 2-arg 제거)
  - `Provenance.PR` enum 값 추가

- [ ] **Step 1: Write the failing tests**

먼저 `ContentGathererTest.kt`의 `gatherer(...)` 헬퍼를 prHistoryTool 받도록 교체:

```kotlin
    private fun gatherer(
        confluenceClient: ConfluenceClient? = mockk(relaxed = true),
        confluenceTool: ConfluenceTool = mockk(relaxed = true),
        codeSearchTool: CodeSearchTool = mockk(relaxed = true),
        codeClient: GitHubCodeClient? = mockk(relaxed = true),
        codeRepo: String? = "kurly/kurly-android",
        wikiPageId: String? = "5912232879",
        prHistoryTool: io.github.veronikapj.wiki.search.tool.PrHistoryTool? = mockk(relaxed = true),
    ) = ContentGatherer(
        confluenceClient = confluenceClient,
        confluenceTool = confluenceTool,
        codeSearchTool = codeSearchTool,
        codeClient = codeClient,
        codeRepo = codeRepo,
        codeBranch = "develop",
        wikiPageId = wikiPageId,
        prHistoryTool = prHistoryTool,
    )
```

기존 테스트 2건을 새 시그니처로 갱신(동작 보존: Tier 2 = includeDeep=true):

```kotlin
    @Test
    fun `gatherForQuestion(deep)은 질문으로 codeSearch와 confluenceSearch를 호출한다`() {
        val codeSearchTool = mockk<CodeSearchTool>()
        val confluenceTool = mockk<ConfluenceTool>()
        every { codeSearchTool.codeSearch("UseCase 어디있어") } returns "domain layer"
        every { confluenceTool.confluenceSearch("UseCase 어디있어") } returns "아키텍처 문서"
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool)

        val result = g.gatherForQuestion("UseCase 어디있어", step = null, includeDeep = true)

        verify { codeSearchTool.codeSearch("UseCase 어디있어") }
        verify { confluenceTool.confluenceSearch("UseCase 어디있어") }
        assertTrue(result.any { it.provenance == ContentGatherer.Provenance.CODE })
    }

    @Test
    fun `gatherForQuestion(deep)에서 한 도구가 예외를 던져도 나머지는 수집된다`() {
        val codeSearchTool = mockk<CodeSearchTool>()
        val confluenceTool = mockk<ConfluenceTool>()
        every { codeSearchTool.codeSearch(any()) } throws RuntimeException("fail")
        every { confluenceTool.confluenceSearch(any()) } returns "위키 결과"
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool,
            prHistoryTool = mockk(relaxed = true).also {
                every { it.prHistory(any()) } returns ""
            })

        val result = g.gatherForQuestion("q", step = null, includeDeep = true)

        assertEquals(1, result.size)
        assertEquals(ContentGatherer.Provenance.CONFLUENCE, result[0].provenance)
    }
```

새 Tier 동작 테스트 추가:

```kotlin
    @Test
    fun `gatherForQuestion(tier1)은 codeSearch와 confluenceSearch를 호출하지 않는다`() {
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true)
        val prHistoryTool = mockk<io.github.veronikapj.wiki.search.tool.PrHistoryTool>(relaxed = true)
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool, prHistoryTool = prHistoryTool)

        g.gatherForQuestion("UseCase 어디있어", step = null, includeDeep = false)

        verify(exactly = 0) { codeSearchTool.codeSearch(any()) }
        verify(exactly = 0) { confluenceTool.confluenceSearch(any()) }
        verify(exactly = 0) { prHistoryTool.prHistory(any()) }
    }

    @Test
    fun `gatherForQuestion(deep)은 prHistory를 호출해 PR provenance로 수집한다`() {
        val prHistoryTool = mockk<io.github.veronikapj.wiki.search.tool.PrHistoryTool>()
        every { prHistoryTool.prHistory("배너 클릭 이벤트") } returns "PR #1234: 배너 클릭 이벤트 추가"
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true).also { every { it.codeSearch(any()) } returns "" }
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true).also { every { it.confluenceSearch(any()) } returns "" }
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool, prHistoryTool = prHistoryTool)

        val result = g.gatherForQuestion("배너 클릭 이벤트", step = null, includeDeep = true)

        verify { prHistoryTool.prHistory("배너 클릭 이벤트") }
        assertEquals(ContentGatherer.Provenance.PR, result.single().provenance)
    }

    @Test
    fun `gatherForQuestion(deep)은 prHistoryTool이 null이면 PR 없이 수집한다`() {
        val codeSearchTool = mockk<CodeSearchTool>().also { every { it.codeSearch(any()) } returns "코드" }
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true).also { every { it.confluenceSearch(any()) } returns "" }
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool, prHistoryTool = null)

        val result = g.gatherForQuestion("q", step = null, includeDeep = true)

        assertTrue(result.none { it.provenance == ContentGatherer.Provenance.PR })
        assertTrue(result.any { it.provenance == ContentGatherer.Provenance.CODE })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.ContentGathererTest"`
Expected: FAIL (compile error: `gatherForQuestion`의 3-arg 미존재, `Provenance.PR` 미존재, 생성자 `prHistoryTool` 미존재)

- [ ] **Step 3: Write minimal implementation**

`ContentGatherer.kt` 상단 import에 추가:

```kotlin
import io.github.veronikapj.wiki.search.tool.PrHistoryTool
```

생성자 마지막에 파라미터 추가:

```kotlin
internal class ContentGatherer(
    private val confluenceClient: ConfluenceClient?,
    private val confluenceTool: ConfluenceTool,
    private val codeSearchTool: CodeSearchTool,
    private val codeClient: GitHubCodeClient?,
    private val codeRepo: String?,
    private val codeBranch: String,
    private val wikiPageId: String?,
    private val prHistoryTool: PrHistoryTool? = null,
) {
```

`Provenance` enum에 PR 추가:

```kotlin
    enum class Provenance(val emoji: String, val display: String) {
        WIKI("📄", "위키"),
        CODE("💻", "코드"),
        PR("🔀", "PR 이력"),
        CONFLUENCE("🔗", "연관문서"),
        GITHUB_FILE("📁", "소스파일"),
    }
```

기존 `gatherForQuestion`(2-arg) 전체를 아래로 교체:

```kotlin
    fun gatherForQuestion(question: String, step: CurriculumStep?, includeDeep: Boolean): List<GatheredContent> {
        val out = mutableListOf<GatheredContent>()

        // Tier 1: 현재 단계의 위키 섹션 (맥락)
        if (step != null) {
            step.sources.firstOrNull { it.type == SourceType.CONFLUENCE_PAGE }?.let { src ->
                runCatching { wikiSection(src) }.getOrNull()?.let { out += it }
            }
        }

        // Tier 2: 코드 + PR + 추가 Confluence (사용자가 더 파고들 때만)
        if (includeDeep) {
            runBlocking {
                val codeDeferred = async(Dispatchers.IO) {
                    runCatching { codeContent(question) }.onFailure { log.warn("question codeSearch failed: {}", it.message) }.getOrNull()
                }
                val prDeferred = async(Dispatchers.IO) {
                    runCatching { prContent(question) }.onFailure { log.warn("question prHistory failed: {}", it.message) }.getOrNull()
                }
                val confDeferred = async(Dispatchers.IO) {
                    runCatching { confluenceContent(question) }.onFailure { log.warn("question confluenceSearch failed: {}", it.message) }.getOrNull()
                }
                codeDeferred.await()?.let { out += it }
                prDeferred.await()?.let { out += it }
                confDeferred.await()?.let { out += it }
            }
        }
        return out
    }

    private fun prContent(query: String?): GatheredContent? {
        val tool = prHistoryTool ?: return null
        val q = query?.takeIf { it.isNotBlank() } ?: return null
        val text = tool.prHistory(q)
        if (text.isBlank()) return null
        return GatheredContent(q, Provenance.PR, text.truncated())
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.ContentGathererTest"`
Expected: PASS (전체 ContentGathererTest green)

- [ ] **Step 5: Commit**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): gatherForQuestion includeDeep tier + PR 수집

Tier1(includeDeep=false)은 위키 섹션만, Tier2는 코드+PR+Confluence.
prHistoryTool null이면 PR graceful skip.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `ContentGatherer` — Tier 1 질문 키워드 매칭 SSOT 섹션

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt`

**Interfaces:**
- Consumes: Task 2의 `gatherForQuestion(question, step, includeDeep)`, 기존 `loadWikiSections()`
- Produces: Tier 1에서도 질문 키워드와 제목이 매칭되는 SSOT H2 섹션(최대 3개)을 포함.

- [ ] **Step 1: Write the failing test**

`ContentGathererTest.kt`에 추가:

```kotlin
    @Test
    fun `gatherForQuestion(tier1)은 질문 키워드와 매칭되는 SSOT 섹션을 위키로 수집한다`() {
        val confluenceClient = mockk<ConfluenceClient>()
        coEvery { confluenceClient.fetchPageRawHtml("5912232879") } returns
            "<h2>브랜치 네이밍</h2><p>feature/KMA-xxxx 규칙</p>" +
            "<h2>코드 리뷰</h2><p>리뷰 기준</p>"
        val g = gatherer(confluenceClient = confluenceClient)

        val result = g.gatherForQuestion("브랜치 네이밍 규칙 알려줘", step = null, includeDeep = false)

        assertEquals(1, result.size)
        assertEquals(ContentGatherer.Provenance.WIKI, result[0].provenance)
        assertEquals("브랜치 네이밍", result[0].label)
    }

    @Test
    fun `gatherForQuestion(tier1)은 매칭 SSOT 섹션을 최대 3개로 제한한다`() {
        val confluenceClient = mockk<ConfluenceClient>()
        coEvery { confluenceClient.fetchPageRawHtml("5912232879") } returns
            "<h2>가이드 1</h2><p>a</p><h2>가이드 2</h2><p>b</p>" +
            "<h2>가이드 3</h2><p>c</p><h2>가이드 4</h2><p>d</p>"
        val g = gatherer(confluenceClient = confluenceClient)

        val result = g.gatherForQuestion("가이드", step = null, includeDeep = false)

        assertEquals(3, result.size)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.ContentGathererTest"`
Expected: FAIL — Tier 1이 빈 리스트 반환 (`expected:<1> but was:<0>` / `expected:<3> but was:<0>`)

- [ ] **Step 3: Write minimal implementation**

`gatherForQuestion`의 Tier 1 블록(현재 단계 섹션 직후, `if (includeDeep)` 직전)에 한 줄 추가:

```kotlin
        // Tier 1: 질문 키워드와 매칭되는 SSOT 섹션 (현재 단계 밖 주제도 위키로 답)
        out += wikiSectionsForQuestion(question, out.map { it.label }.toSet())
```

그리고 `prContent` 아래(또는 `wikiSection` 근처)에 private 메서드 추가:

```kotlin
    private fun wikiSectionsForQuestion(question: String, exclude: Set<String>): List<GatheredContent> {
        val tokens = question.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        return loadWikiSections()
            .filter { sec -> sec.title !in exclude && tokens.any { sec.title.contains(it, ignoreCase = true) } }
            .take(MAX_QUESTION_SECTIONS)
            .map { GatheredContent(it.title, Provenance.WIKI, it.content.truncated()) }
    }
```

`companion object`의 상수에 추가:

```kotlin
        private const val MAX_QUESTION_SECTIONS = 3
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.ContentGathererTest"`
Expected: PASS (전체 ContentGathererTest green — Tier1 미발화 테스트도 여전히 통과: 매칭 섹션이 없으면 빈 리스트)

- [ ] **Step 5: Commit**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): Tier1 질문 키워드 매칭 SSOT 섹션 수집(최대 3개)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `OnboardingTool` — RESET 의도 + handleReset

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt`

**Interfaces:**
- Consumes: Task 1 `OnboardingSessionStore.delete(userId)`, 기존 `handleStart(userId)`, 기존 `LEVEL_CHECK_MESSAGE`
- Produces: `classifyIntent`가 "초기화"/"리셋" 포함 메시지에 `Intent.RESET` 반환; `handleReset(userId)`가 세션 삭제 후 레벨 체크 메시지 반환.

- [ ] **Step 1: Write the failing test**

`OnboardingToolTest.kt`에 추가:

```kotlin
    @Test
    fun `온보딩 초기화 시 세션이 삭제되고 레벨 체크부터 다시 시작한다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // 진행 중 세션 생성 (B,A,A → step-1)
        tool.handle(userId, "B, A, A")
        assertTrue(OnboardingSessionStore.exists(userId))

        val result = tool.handle(userId, "온보딩 초기화")

        // 세션 삭제됨
        assertFalse(OnboardingSessionStore.exists(userId))
        // 레벨 체크 메시지로 재시작
        assertTrue(result.contains("경험 수준"), "초기화 후 레벨 체크가 표시되어야 합니다. 실제: $result")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.OnboardingToolTest"`
Expected: FAIL — "온보딩 초기화"가 QUESTION으로 분류되어 세션이 남아있음 (`assertFalse(exists)` 실패)

- [ ] **Step 3: Write minimal implementation**

`enum class Intent`에 `RESET` 추가:

```kotlin
    private enum class Intent {
        START, LEVEL_RESPONSE, NEXT, SKIP, PROGRESS, JUMP, RESET, QUESTION
    }
```

`classifyIntent`의 `// START:` 블록 바로 위(LEVEL/NEXT/SKIP 판정 다음)에 추가:

```kotlin
        // RESET: "초기화" 또는 "리셋" 포함 (안내문의 "온보딩 초기화"와 일치)
        if (trimmed.contains("초기화") || trimmed.contains("리셋")) {
            return Intent.RESET
        }
```

`handle`의 `when (intent)`에 분기 추가:

```kotlin
            Intent.RESET -> handleReset(userId)
```

`handleStart` 위(또는 인접)에 핸들러 추가:

```kotlin
    private fun handleReset(userId: String): String {
        OnboardingSessionStore.delete(userId)
        return handleStart(userId)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.OnboardingToolTest"`
Expected: PASS (신규 테스트 + 기존 OnboardingToolTest green)

- [ ] **Step 5: Commit**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): RESET 의도 추가 — 초기화 시 세션 삭제 후 즉시 재시작

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `OnboardingTool` 2-tier 질문 + 배선

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt` (OnboardingTool 생성, 약 295–309행)
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt`

**Interfaces:**
- Consumes: Task 2 `gatherForQuestion(question, step, includeDeep)`, `PrHistoryTool`
- Produces:
  - `OnboardingTool` 생성자에 `prHistoryTool: PrHistoryTool? = null`
  - `handleQuestion`이 `wantsDeepDive(message)`로 tier 결정 → Tier1은 코드/PR/Confluence 미발화.

- [ ] **Step 1: Write/Update the failing tests**

기존 테스트 `질문 시 codeSearch 라이브 검색이 호출되고 메모가 기록된다`(deep 의도로 명확화)를 아래로 교체 — 질문에 "코드" 키워드 추가:

```kotlin
    @Test
    fun `심화 키워드 질문 시 codeSearch가 호출되고 메모가 기록된다`() {
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        every { codeSearchTool.codeSearch(any()) } returns "ProductViewModel 위치: feature/product"
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true)
        every { confluenceTool.confluenceSearch(any()) } returns ""

        val tool = OnboardingTool(
            curriculumPath = curriculumPath,
            executor = createMockExecutor("코드는 feature/product 모듈에 있습니다."),
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = confluenceTool,
            codeSearchTool = codeSearchTool,
        )
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A")

        val result = tool.handle(userId, "ProductViewModel 코드 어디있어?")

        assertTrue(result.contains("feature/product"), "LLM 답변이 반환되어야 합니다. 실제: $result")
        verify { codeSearchTool.codeSearch("ProductViewModel 코드 어디있어?") }

        val session = OnboardingSessionStore.load(userId)!!
        assertTrue(session.memos.any { it.contains("ProductViewModel") }, "질문이 메모로 기록되어야 합니다. 실제: ${session.memos}")
    }
```

새 Tier 1 테스트 추가:

```kotlin
    @Test
    fun `심화 키워드 없는 질문은 codeSearch를 호출하지 않는다`() {
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true)
        val tool = OnboardingTool(
            curriculumPath = curriculumPath,
            executor = createMockExecutor("위키 기반 답변입니다."),
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = confluenceTool,
            codeSearchTool = codeSearchTool,
        )
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A")

        tool.handle(userId, "이 단계가 뭐야?")

        verify(exactly = 0) { codeSearchTool.codeSearch(any()) }
        verify(exactly = 0) { confluenceTool.confluenceSearch(any()) }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.OnboardingToolTest"`
Expected: FAIL — Tier1 테스트에서 codeSearch가 호출됨 (`verify(exactly=0)` 실패: 현재는 항상 호출)

- [ ] **Step 3: Write minimal implementation**

`OnboardingTool.kt` import에 추가:

```kotlin
import io.github.veronikapj.wiki.search.tool.PrHistoryTool
```

생성자에 파라미터 추가 (`tracker` 다음 줄):

```kotlin
    private val tracker: SourceTracker? = null,
    private val prHistoryTool: PrHistoryTool? = null,
```

`gatherer` lazy 초기화에 전달:

```kotlin
    private val gatherer: ContentGatherer by lazy {
        ContentGatherer(
            confluenceClient = confluenceClient,
            confluenceTool = confluenceTool,
            codeSearchTool = codeSearchTool,
            codeClient = codeClient,
            codeRepo = codeRepo,
            codeBranch = codeBranch,
            wikiPageId = wikiPageId,
            prHistoryTool = prHistoryTool,
        )
    }
```

`wantsDeepDive` 헬퍼 추가 (`classifyIntent` 근처):

```kotlin
    private fun wantsDeepDive(message: String): Boolean =
        DEEP_DIVE_KEYWORDS.any { message.contains(it, ignoreCase = true) }
```

`companion object`에 키워드 추가:

```kotlin
        private val DEEP_DIVE_KEYWORDS = listOf(
            "코드", "소스", "구현", "예시", "예제", "샘플", "PR", "풀리퀘", "커밋",
            "더 자세히", "자세히", "실제로", "동작 방식", "어떻게 동작", "깊이",
        )
```

`handleQuestion`에서 gather 호출과 프롬프트를 tier로 분기 — 함수 본문을 아래로 교체:

```kotlin
    private fun handleQuestion(userId: String, message: String, conversationContext: String): String {
        val session = OnboardingSessionStore.load(userId)
        val cur = curriculum
        val currentStep = if (session?.currentStepId != null && cur != null) {
            cur.phases.firstOrNull { it.id == session.currentStepId }
        } else null

        val deep = wantsDeepDive(message)
        val gathered = gatherer.gatherForQuestion(message, currentStep, includeDeep = deep)
        val contentBlock = ContentGatherer.formatBlocks(gathered)

        val contextBlock = buildString {
            if (currentStep != null) {
                appendLine("현재 온보딩 단계: ${currentStep.name} (Phase ${currentStep.phase}, ${currentStep.day})")
            }
            if (contentBlock.isNotBlank()) {
                appendLine()
                appendLine("=== 질문 관련 자료 (출처별) ===")
                appendLine(contentBlock)
                appendLine("=== 끝 ===")
            }
            if (conversationContext.isNotBlank()) {
                appendLine()
                appendLine("=== 대화 히스토리 ===")
                appendLine(conversationContext)
                appendLine("=== 끝 ===")
            }
        }

        val questionPrompt = buildString {
            appendLine(SLACK_FORMAT_RULE)
            appendLine()
            appendLine("당신은 컬리(Kurly) $projectName 프로젝트의 신규 입사자 온보딩을 도와주는 멘토입니다.")
            appendLine("컬리는 한국의 신선식품 이커머스 플랫폼이며, 프로젝트명은 '$projectName'입니다.")
            appendLine("온보딩 대상은 $projectName (Android 앱) 코드베이스입니다. 이 온보딩 도구 자체(wiki-agent)의 구조나 파일을 설명하지 마세요.")
            appendLine("아래 자료를 바탕으로 질문에 친절하고 정확하게 답변하세요. 자료에 없는 파일 경로·클래스명은 추측하지 마세요.")
            if (!deep) {
                appendLine("이번 답변은 온보딩 위키 자료 중심입니다. 코드·PR 세부 구현은 포함하지 마세요.")
            }
            appendLine("모르는 내용은 모른다고 하고, 관련 문서나 담당자를 안내하세요.")
            if (contextBlock.isNotBlank()) {
                appendLine()
                appendLine(contextBlock)
            }
            appendLine()
            appendLine("사용자 질문: $message")
            if (!deep) {
                appendLine()
                appendLine("답변 맨 끝에 다음 안내를 한 줄 덧붙이세요: \"_코드·PR까지 보려면 '코드 보여줘'처럼 다시 물어봐 주세요._\"")
            }
        }

        val answer = callLLM(questionPrompt)
        if (!answer.startsWith("가이드 생성 중 오류")) {
            OnboardingSessionStore.addMemo(userId, "질문: ${message.take(80)}")
        }
        return answer
    }
```

`Main.kt`의 `OnboardingTool(...)` 호출에 인자 추가 (`tracker = sourceTracker,` 다음 줄):

```kotlin
            tracker = sourceTracker,
            prHistoryTool = prHistoryTool,
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :onboarding:test --tests "io.github.veronikapj.wiki.onboarding.OnboardingToolTest"`
Expected: PASS (전체 OnboardingToolTest green)

- [ ] **Step 5: Full build + commit**

Run: `./gradlew compileKotlin :onboarding:test :test`
Expected: BUILD SUCCESSFUL (Main.kt 포함 전체 컴파일, onboarding·root 테스트 통과)

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt src/main/kotlin/io/github/veronikapj/wiki/Main.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt
git commit -m "$(cat <<'EOF'
feat(onboarding): 질문 2-tier 분기 + prHistoryTool 배선

심화 키워드 없으면 위키 SSOT만(Tier1), 있으면 코드+PR+Confluence(Tier2).
Main.kt에서 prHistoryTool 주입.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- 목표 "온보딩 질문 기본 위키만" → Task 5 (wantsDeepDive Tier1) + Task 3 (Tier1 SSOT 섹션). ✓
- 목표 "키워드 신호 시 코드+PR+추가위키" → Task 2 (includeDeep Tier2 + PR) + Task 5 (wantsDeepDive). ✓
- 목표 "초기화 시 세션 삭제 후 즉시 재시작" → Task 1 (delete) + Task 4 (RESET/handleReset). ✓
- 설계 4 "항상 현재 step 섹션 + 키워드 매칭 SSOT 섹션" → Task 2(현재 step) + Task 3(키워드 매칭). ✓
- 설계 5 배선 → Task 2(ContentGatherer 생성자) + Task 5(OnboardingTool 생성자 + Main.kt). ✓
- 비목표 "generateGuide 변경 없음" → 어떤 Task도 `gather(step)`/`generateGuide` 미수정. ✓

**Placeholder scan:** 모든 step에 실제 코드/명령/기대출력 존재. TBD 없음. ✓

**Type consistency:**
- `gatherForQuestion(question, step, includeDeep)` — Task 2 정의, Task 3·5에서 동일 시그니처 사용. ✓
- `Provenance.PR` — Task 2 정의, Task 2 테스트에서 사용. ✓
- `prHistoryTool: PrHistoryTool?` — Task 2(ContentGatherer)·Task 5(OnboardingTool) 동일 타입. ✓
- `delete(userId): Boolean` — Task 1 정의, Task 4에서 사용. ✓
- `wantsDeepDive(message): Boolean` — Task 5 정의·사용. ✓

**리스크:** Task 2가 기존 ContentGathererTest 2건의 시그니처를 바꾸므로 동일 커밋에서 갱신(Step 1에 포함). Task 5가 기존 OnboardingToolTest 1건을 deep 키워드로 갱신(Step 1에 포함).
