# 온보딩 위키 Android SSOT — 콘텐츠 오염 방지 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 온보딩 질문 경로의 Confluence 라이브 검색을 ProductApp 스페이스로 한정하고, 스페이스 내 iOS/공통 문서를 제목+스니펫 토큰으로 분류해 출처 라벨링함으로써 Android 온보딩 위키를 SSOT로 유지한다.

**Architecture:** `search` 모듈에 "스페이스 한정 + fallback 비활성"을 켜는 범용 옵션(`strictSpaces`)을 추가하고, `onboarding` 모듈(`ContentGatherer`)이 그 결과를 플랫폼 분류·라벨링한다. 온보딩 SSOT 스페이스는 `curriculum.yaml`에 선언한다. `search` 모듈은 "온보딩"을 모르며, 호출자가 파라미터로 동작을 결정한다.

**Tech Stack:** Kotlin, Gradle 멀티모듈, kotlinx.serialization + kaml(YAML), kotlinx.coroutines, 테스트는 kotlin.test + MockK (+ `runTest`).

## Global Constraints

- 일반 Q&A 검색 동작 불변: `ConfluenceSearchAgent`의 `strictSpaces` 기본값은 `null`이며 null일 때 기존 경로(space 확장·global fallback 포함)를 100% 보존한다.
- 가이드 경로(`ContentGatherer.gather`)·코드 검색(`codeContent`)은 변경하지 않는다.
- iOS 문서를 검색 결과에서 제외하지 않는다 — 라벨링만 한다. 분류 기본값은 `Platform.ANDROID`(Android 토큰을 요구하지 않음).
- 라벨 마커 문자열(verbatim): iOS = `[🍎 iOS 참조]`, 공통 = `[🔀 Android·iOS 공통]`, Android = 마커 없음.
- 온보딩 SSOT 스페이스 키(verbatim): `ProductApp`.
- 모든 신규 테스트는 L1 Unit. 빌드/테스트 명령은 워크트리 루트에서 `./gradlew` 사용.

---

### Task 1: `ConfluenceSearchAgent.searchStructured`에 `strictSpaces` 추가

**Files:**
- Modify: `search/src/main/kotlin/io/github/veronikapj/wiki/search/ConfluenceSearchAgent.kt`
- Test: `search/src/test/kotlin/io/github/veronikapj/wiki/search/ConfluenceSearchAgentTest.kt`

**Interfaces:**
- Produces: `suspend fun searchStructured(query, synonyms, topK, dateAfter, dateBefore, originalQuestion, strictSpaces: List<String>? = null): List<SearchResult>` — `strictSpaces != null`이면 그 스페이스로만 검색하고 space 확장·global fallback을 건너뛴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ConfluenceSearchAgentTest.kt`에 추가:

```kotlin
    @Test
    fun `strictSpaces는 스페이스를 한정하고 확장·global fallback을 건너뛴다`() = runTest {
        val mockClient = mockk<ConfluenceClient>()
        // ProductApp 제목 검색 1건 (threshold=3 미만) → 평소면 확장+fallback 트리거될 상황
        coEvery { mockClient.searchByTitle("질문", listOf("ProductApp"), any(), any()) } returns listOf(
            ConfluencePageRef("1", "온보딩 가이드", "url1", titleMatched = true),
        )
        coEvery { mockClient.searchByText("질문", listOf("ProductApp"), any(), any()) } returns emptyList()

        val agent = ConfluenceSearchAgent(mockClient, spaces = listOf("ProductApp", "PSD"), sufficientThreshold = 3)
        val results = agent.searchStructured("질문", strictSpaces = listOf("ProductApp"))

        assertTrue(results.any { it.pageId == "1" })
        // 확장 검색(emptyList 스페이스)·global fallback(emptyList 텍스트 검색)은 호출되지 않아야 함
        coVerify(exactly = 0) { mockClient.searchByTitle(any(), emptyList(), any(), any()) }
        coVerify(exactly = 0) { mockClient.searchByText(any(), emptyList(), any(), any()) }
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :search:test --tests "*ConfluenceSearchAgentTest*" --console=plain`
Expected: FAIL — `strictSpaces` 파라미터가 없어 컴파일 에러.

- [ ] **Step 3: 최소 구현**

`ConfluenceSearchAgent.kt`의 `searchStructured` 시그니처에 파라미터 추가:

```kotlin
    suspend fun searchStructured(
        query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
        originalQuestion: String = "",
        strictSpaces: List<String>? = null,
    ): List<SearchResult> {
        val cleaned = cleanQuery(query)
        val effectiveSpaces = strictSpaces ?: spaces
        // strictSpaces 분리: 스코프 검색 결과가 일반 검색 캐시와 섞이지 않도록 키에 포함
        val cacheKey = "$cleaned|${synonyms.sorted().joinToString(",")}|$topK|$dateAfter|$dateBefore|${strictSpaces?.joinToString(",") ?: "-"}"
```

이어서 함수 본문에서 `spaces`를 사용하던 검색 호출을 `effectiveSpaces`로 교체하고, 확장·fallback을 `strictSpaces == null`로 가드한다. 구체적으로:

제목 검색 (기존 `titleResults` 라인):
```kotlin
        val titleResults = confluenceClient.searchByTitle(cleaned, effectiveSpaces, synonyms, titleFetchLimit, dateAfter, dateBefore)
```

병렬 fallback 블록 (text + 확장):
```kotlin
        val (textResults, expandedResults) = coroutineScope {
            val textDeferred = async {
                runCatching { confluenceClient.searchByText(cleaned, effectiveSpaces, synonyms, topK, dateAfter, dateBefore) }.getOrElse { emptyList() }
            }
            val expandedDeferred = async {
                // strictSpaces 지정 시 전체 스페이스 확장 금지
                if (strictSpaces == null && spaces.isNotEmpty()) {
                    runCatching { confluenceClient.searchByTitle(cleaned, emptyList(), synonyms, topK, dateAfter, dateBefore) }.getOrElse { emptyList() }
                } else emptyList()
            }
            Pair(textDeferred.await(), expandedDeferred.await())
        }
```

keyword fallback의 두 `searchByKeywords` 호출도 `spaces` → `effectiveSpaces`:
```kotlin
                val andResults = runCatching {
                    confluenceClient.searchByKeywords(keywords, effectiveSpaces, topK, dateAfter, dateBefore, useOr = false)
                }.getOrElse { emptyList() }
```
```kotlin
                    runCatching {
                        confluenceClient.searchByKeywords(keywords, effectiveSpaces, topK, dateAfter, dateBefore, useOr = true)
                    }.getOrElse { emptyList() }
```

global fallback 게이트:
```kotlin
        val needsGlobalFallback = strictSpaces == null && spaces.isNotEmpty() &&
            (combined.isEmpty() || !anyResultRelevant(combined, originalQuestion, synonyms))
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :search:test --tests "*ConfluenceSearchAgentTest*" --console=plain`
Expected: PASS (신규 테스트 + 기존 회귀 테스트 모두 green — 기존 테스트는 `strictSpaces=null` 경로라 동작 불변).

- [ ] **Step 5: 커밋**

```bash
git add search/src/main/kotlin/io/github/veronikapj/wiki/search/ConfluenceSearchAgent.kt search/src/test/kotlin/io/github/veronikapj/wiki/search/ConfluenceSearchAgentTest.kt
git commit -m "feat(search): ConfluenceSearchAgent에 strictSpaces 스코프 검색 추가"
```

---

### Task 2: `ConfluenceTool.searchScopedStructured` 추가

**Files:**
- Modify: `search/src/main/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceTool.kt`
- Test: `search/src/test/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceToolTest.kt` (Create)

**Interfaces:**
- Consumes: `ConfluenceSearchAgent.searchStructured(..., strictSpaces)` (Task 1)
- Produces: `fun ConfluenceTool.searchScopedStructured(query: String, spaces: List<String>): List<SearchResult>`

- [ ] **Step 1: 실패하는 테스트 작성**

`ConfluenceToolTest.kt` 신규 생성:

```kotlin
package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.SearchResult
import io.github.veronikapj.wiki.search.SearchStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfluenceToolTest {

    @Test
    fun `searchScopedStructured는 strictSpaces로 searchStructured에 위임한다`() {
        val agent = mockk<ConfluenceSearchAgent>()
        coEvery { agent.searchStructured("q", strictSpaces = listOf("ProductApp")) } returns listOf(
            SearchResult("1", "온보딩", "url1", "본문", SearchStage.TITLE_MATCH),
        )
        val tool = ConfluenceTool(agent)

        val results = tool.searchScopedStructured("q", listOf("ProductApp"))

        assertEquals(1, results.size)
        assertEquals("1", results[0].pageId)
        coVerify { agent.searchStructured("q", strictSpaces = listOf("ProductApp")) }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :search:test --tests "*ConfluenceToolTest*" --console=plain`
Expected: FAIL — `searchScopedStructured` 미정의 컴파일 에러.

- [ ] **Step 3: 최소 구현**

`ConfluenceTool.kt`에 import 추가 후 메서드 추가:

```kotlin
import io.github.veronikapj.wiki.search.SearchResult
```

```kotlin
    /** 온보딩 등 스페이스 한정이 필요한 호출용 — 확장·global fallback 없이 구조화 결과 반환 */
    fun searchScopedStructured(query: String, spaces: List<String>): List<SearchResult> = runBlocking {
        tracker?.record("Confluence")
        searchAgent.searchStructured(query, strictSpaces = spaces)
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :search:test --tests "*ConfluenceToolTest*" --console=plain`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add search/src/main/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceTool.kt search/src/test/kotlin/io/github/veronikapj/wiki/search/tool/ConfluenceToolTest.kt
git commit -m "feat(search): ConfluenceTool.searchScopedStructured 구조화 스코프 검색 추가"
```

---

### Task 3: `OnboardingCurriculum.space` 필드 + curriculum.yaml 선언

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingCurriculum.kt`
- Modify: `.wiki/onboarding/curriculum.yaml`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingCurriculumTest.kt` (Create)

**Interfaces:**
- Produces: `OnboardingCurriculum.space: String?` (null 허용, 기본 null)

- [ ] **Step 1: 실패하는 테스트 작성**

`OnboardingCurriculumTest.kt` 신규 생성:

```kotlin
package io.github.veronikapj.wiki.onboarding

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingCurriculumTest {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    @Test
    fun `space 필드가 파싱된다`() {
        val cur = yaml.decodeFromString(OnboardingCurriculum.serializer(), """
            lastUpdated: "2026-06-05"
            space: "ProductApp"
            phases: []
        """.trimIndent())
        assertEquals("ProductApp", cur.space)
    }

    @Test
    fun `space 누락 시 null이다`() {
        val cur = yaml.decodeFromString(OnboardingCurriculum.serializer(), """
            lastUpdated: "2026-06-05"
            phases: []
        """.trimIndent())
        assertNull(cur.space)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :onboarding:test --tests "*OnboardingCurriculumTest*" --console=plain`
Expected: FAIL — `space` 프로퍼티 미존재 컴파일 에러.

- [ ] **Step 3: 최소 구현**

`OnboardingCurriculum.kt`의 데이터 클래스에 필드 추가:

```kotlin
@Serializable
data class OnboardingCurriculum(
    val lastUpdated: String,
    val space: String? = null,
    val phases: List<CurriculumStep>,
)
```

`.wiki/onboarding/curriculum.yaml` 최상위(`lastUpdated` 아래, `phases` 위)에 추가:

```yaml
lastUpdated: "2026-06-05"
space: "ProductApp"

# Single Source of Truth: Confluence 위키 페이지
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :onboarding:test --tests "*OnboardingCurriculumTest*" --console=plain`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingCurriculum.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingCurriculumTest.kt .wiki/onboarding/curriculum.yaml
git commit -m "feat(onboarding): curriculum에 SSOT space 필드 추가 (ProductApp)"
```

---

### Task 4: `ContentGatherer` 플랫폼 분류 + 라벨 렌더링

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt`

**Interfaces:**
- Produces:
  - `enum class ContentGatherer.Platform { ANDROID, IOS, SHARED }`
  - `ContentGatherer.GatheredContent`에 `platform: Platform = Platform.ANDROID` 필드
  - `ContentGatherer.classifyPlatform(title: String, snippet: String): Platform` (companion)
  - `formatBlocks`가 IOS/SHARED에 마커 부여

- [ ] **Step 1: 실패하는 테스트 작성**

`ContentGathererTest.kt`에 추가:

```kotlin
    @Test
    fun `classifyPlatform - 제목에 iOS만 있으면 IOS`() {
        assertEquals(ContentGatherer.Platform.IOS,
            ContentGatherer.classifyPlatform("[iOS] App Intent 기술검토", ""))
        assertEquals(ContentGatherer.Platform.IOS,
            ContentGatherer.classifyPlatform("2026.06.22 (iOS)", ""))
    }

    @Test
    fun `classifyPlatform - Android와 iOS 둘 다면 SHARED`() {
        assertEquals(ContentGatherer.Platform.SHARED,
            ContentGatherer.classifyPlatform("v3.78.0 Release Note Android/iOS", ""))
    }

    @Test
    fun `classifyPlatform - 토큰 없으면 기본 ANDROID`() {
        assertEquals(ContentGatherer.Platform.ANDROID,
            ContentGatherer.classifyPlatform("프로젝트 온보딩 가이드", "환경 셋업과 모듈 맵"))
    }

    @Test
    fun `classifyPlatform - 제목엔 없고 스니펫에 iOS 토큰이면 IOS`() {
        assertEquals(ContentGatherer.Platform.IOS,
            ContentGatherer.classifyPlatform("상품상세 장애 보고서", "원인은 kurly-ios 아이폰 빌드의 UICollectionView 조정"))
    }

    @Test
    fun `classifyPlatform - kiosk는 ios 단어경계 오탐이 아니다`() {
        assertEquals(ContentGatherer.Platform.ANDROID,
            ContentGatherer.classifyPlatform("kiosk 결제 플로우", ""))
    }

    @Test
    fun `formatBlocks는 플랫폼 마커를 붙인다`() {
        val block = ContentGatherer.formatBlocks(listOf(
            ContentGatherer.GatheredContent("Android 문서", ContentGatherer.Provenance.CONFLUENCE, "본문", ContentGatherer.Platform.ANDROID),
            ContentGatherer.GatheredContent("iOS 문서", ContentGatherer.Provenance.CONFLUENCE, "본문", ContentGatherer.Platform.IOS),
            ContentGatherer.GatheredContent("공용 문서", ContentGatherer.Provenance.CONFLUENCE, "본문", ContentGatherer.Platform.SHARED),
        ))
        assertTrue(block.contains("[🍎 iOS 참조]"))
        assertTrue(block.contains("[🔀 Android·iOS 공통]"))
        // ANDROID 항목 헤더엔 마커 없음
        assertTrue(block.contains("연관문서: Android 문서"))
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :onboarding:test --tests "*ContentGathererTest*" --console=plain`
Expected: FAIL — `Platform`/`classifyPlatform`/`platform` 미정의 컴파일 에러.

- [ ] **Step 3: 최소 구현**

`ContentGatherer.kt`에서:

(a) `Provenance` enum 아래에 `Platform` enum 추가:
```kotlin
    enum class Platform { ANDROID, IOS, SHARED }
```

(b) `GatheredContent`에 `platform` 필드 추가:
```kotlin
    data class GatheredContent(
        val label: String,
        val provenance: Provenance,
        val text: String,
        val platform: Platform = Platform.ANDROID,
    )
```

(c) companion object에 토큰 정규식 + 분류 함수 추가:
```kotlin
        // 라벨이 없어 제목+스니펫 토큰으로 플랫폼 분류. ANDROID가 기본값(android 토큰 불필요).
        private val IOS_TOKENS = Regex(
            """\b(ios|swift|swiftui|uikit|xcode|cocoapods|testflight|kurly-ios)\b|아이폰""",
            RegexOption.IGNORE_CASE)
        private val ANDROID_TOKENS = Regex(
            """\b(android|kotlin|compose|gradle|hilt|jetpack|kurly-android)\b|안드로이드""",
            RegexOption.IGNORE_CASE)

        fun classifyPlatform(title: String, snippet: String): Platform {
            val text = "$title\n$snippet"
            val ios = IOS_TOKENS.containsMatchIn(text)
            val android = ANDROID_TOKENS.containsMatchIn(text)
            return when {
                ios && android -> Platform.SHARED
                ios -> Platform.IOS
                else -> Platform.ANDROID
            }
        }
```

(d) `formatBlocks`에 마커 부여:
```kotlin
        fun formatBlocks(items: List<GatheredContent>): String =
            items.joinToString("\n\n") { gc ->
                val marker = when (gc.platform) {
                    Platform.IOS -> " [🍎 iOS 참조]"
                    Platform.SHARED -> " [🔀 Android·iOS 공통]"
                    Platform.ANDROID -> ""
                }
                "=== ${gc.provenance.emoji} ${gc.provenance.display}$marker: ${gc.label} ===\n${gc.text}"
            }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :onboarding:test --tests "*ContentGathererTest*" --console=plain`
Expected: PASS (신규 + 기존 `formatBlocks는 provenance 헤더를 붙인다` 회귀 포함).

- [ ] **Step 5: 커밋**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt
git commit -m "feat(onboarding): 콘텐츠 플랫폼 분류(classifyPlatform) + 라벨 렌더링"
```

---

### Task 5: `gatherForQuestion` 스코프 검색 분기 + 라벨링

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt`

**Interfaces:**
- Consumes: `ConfluenceTool.searchScopedStructured` (Task 2), `classifyPlatform` (Task 4)
- Produces: `ContentGatherer` 생성자에 `onboardingSpace: String? = null` 추가. `onboardingSpace != null`이면 질문 경로 Confluence 수집이 스코프 검색 + 결과별 라벨링.

- [ ] **Step 1: 실패하는 테스트 작성**

먼저 `ContentGathererTest.kt`의 `gatherer(...)` 헬퍼에 `onboardingSpace` 파라미터 추가:

```kotlin
    private fun gatherer(
        confluenceClient: ConfluenceClient? = mockk(relaxed = true),
        confluenceTool: ConfluenceTool = mockk(relaxed = true),
        codeSearchTool: CodeSearchTool = mockk(relaxed = true),
        codeClient: GitHubCodeClient? = mockk(relaxed = true),
        codeRepo: String? = "kurly/kurly-android",
        wikiPageId: String? = "5912232879",
        onboardingSpace: String? = null,
    ) = ContentGatherer(
        confluenceClient = confluenceClient,
        confluenceTool = confluenceTool,
        codeSearchTool = codeSearchTool,
        codeClient = codeClient,
        codeRepo = codeRepo,
        codeBranch = "develop",
        wikiPageId = wikiPageId,
        onboardingSpace = onboardingSpace,
    )
```

import 추가:
```kotlin
import io.github.veronikapj.wiki.search.SearchResult
import io.github.veronikapj.wiki.search.SearchStage
```

테스트 추가:

```kotlin
    @Test
    fun `gatherForQuestion은 onboardingSpace가 있으면 스코프 검색 후 플랫폼 라벨을 붙인다`() {
        val confluenceTool = mockk<ConfluenceTool>()
        val codeSearchTool = mockk<CodeSearchTool>()
        every { codeSearchTool.codeSearch(any()) } returns ""  // 코드 결과 없음 → confluence만 검증
        every { confluenceTool.searchScopedStructured("배포 절차", listOf("ProductApp")) } returns listOf(
            SearchResult("1", "QA 및 배포 프로세스", "url1", "develop 머지 후 QA", SearchStage.TITLE_MATCH),
            SearchResult("2", "v3.78 Release Note Android/iOS", "url2", "정기 배포", SearchStage.TITLE_MATCH),
            SearchResult("3", "컬리앱(iOS) 장애 보고서", "url3", "상품상세 스크롤", SearchStage.TEXT_MATCH),
        )
        val g = gatherer(confluenceTool = confluenceTool, codeSearchTool = codeSearchTool, onboardingSpace = "ProductApp")

        val result = g.gatherForQuestion("배포 절차", step = null)

        verify { confluenceTool.searchScopedStructured("배포 절차", listOf("ProductApp")) }
        val byLabel = result.associateBy { it.label }
        assertEquals(ContentGatherer.Platform.ANDROID, byLabel["QA 및 배포 프로세스"]!!.platform)
        assertEquals(ContentGatherer.Platform.SHARED, byLabel["v3.78 Release Note Android/iOS"]!!.platform)
        assertEquals(ContentGatherer.Platform.IOS, byLabel["컬리앱(iOS) 장애 보고서"]!!.platform)
    }

    @Test
    fun `gatherForQuestion은 onboardingSpace가 null이면 기존 confluenceSearch 경로를 쓴다`() {
        val confluenceTool = mockk<ConfluenceTool>()
        val codeSearchTool = mockk<CodeSearchTool>()
        every { codeSearchTool.codeSearch(any()) } returns ""
        every { confluenceTool.confluenceSearch("질문") } returns "위키 결과"
        val g = gatherer(confluenceTool = confluenceTool, codeSearchTool = codeSearchTool, onboardingSpace = null)

        val result = g.gatherForQuestion("질문", step = null)

        verify { confluenceTool.confluenceSearch("질문") }
        assertEquals(1, result.size)
        assertEquals(ContentGatherer.Provenance.CONFLUENCE, result[0].provenance)
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :onboarding:test --tests "*ContentGathererTest*" --console=plain`
Expected: FAIL — `onboardingSpace` 생성자 파라미터 미존재 컴파일 에러.

- [ ] **Step 3: 최소 구현**

`ContentGatherer.kt` 생성자에 파라미터 추가 (`wikiPageId` 다음):
```kotlin
    private val wikiPageId: String?,
    private val onboardingSpace: String? = null,
) {
```

질문 경로 Confluence 수집 함수 추가 (`confluenceContent` 근처):
```kotlin
    /** 질문 경로 전용: onboardingSpace가 있으면 스코프 검색 + 플랫폼 라벨링, 없으면 기존 전체 검색 */
    private fun confluenceQuestionContent(query: String): List<GatheredContent> {
        val q = query.takeIf { it.isNotBlank() } ?: return emptyList()
        val space = onboardingSpace
            ?: return confluenceContent(q)?.let { listOf(it) } ?: emptyList()
        return confluenceTool.searchScopedStructured(q, listOf(space)).map { r ->
            GatheredContent(
                label = r.title,
                provenance = Provenance.CONFLUENCE,
                text = buildString {
                    if (r.snippet.isNotBlank()) appendLine(r.snippet)
                    append("(${r.url})")
                }.truncated(),
                platform = classifyPlatform(r.title, r.snippet),
            )
        }
    }
```

`gatherForQuestion`의 confluence 수집부를 교체:
```kotlin
        runBlocking {
            val codeDeferred = async(Dispatchers.IO) {
                runCatching { codeContent(question) }.onFailure { log.warn("question codeSearch failed: {}", it.message) }.getOrNull()
            }
            val confDeferred = async(Dispatchers.IO) {
                runCatching { confluenceQuestionContent(question) }.onFailure { log.warn("question confluenceSearch failed: {}", it.message) }.getOrDefault(emptyList())
            }
            codeDeferred.await()?.let { out += it }
            out += confDeferred.await()
        }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :onboarding:test --tests "*ContentGathererTest*" --console=plain`
Expected: PASS. (기존 `gatherForQuestion은 질문으로 codeSearch와 confluenceSearch를 호출한다` 등은 `onboardingSpace=null` 기본값으로 기존 경로를 타므로 회귀 없음.)

- [ ] **Step 5: 커밋**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt
git commit -m "feat(onboarding): 질문 경로 Confluence 검색 스페이스 스코프 + 플랫폼 라벨링"
```

---

### Task 6: `OnboardingTool` 와이어링 + 질문 프롬프트 규칙

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt`

**Interfaces:**
- Consumes: `OnboardingCurriculum.space` (Task 3), `ContentGatherer(onboardingSpace=...)` (Task 5)
- Produces: `OnboardingTool.IOS_REFERENCE_RULE` 상수 (companion). 질문 프롬프트에 SSOT/참조 규칙 포함.

- [ ] **Step 1: 실패하는 테스트 작성**

`OnboardingToolTest.kt`에 추가:

```kotlin
    @Test
    fun `IOS_REFERENCE_RULE은 마커와 SSOT 안내를 포함한다`() {
        val rule = OnboardingTool.IOS_REFERENCE_RULE
        assertTrue(rule.contains("🍎 iOS 참조"))
        assertTrue(rule.contains("🔀 Android·iOS 공통"))
        assertTrue(rule.contains("SSOT"))
    }
```

(파일 상단에 `import kotlin.test.assertTrue`가 없으면 추가.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :onboarding:test --tests "*OnboardingToolTest*" --console=plain`
Expected: FAIL — `IOS_REFERENCE_RULE` 미정의 컴파일 에러.

- [ ] **Step 3: 최소 구현**

`OnboardingTool.kt`에서:

(a) `wikiPageId` lazy 아래에 `onboardingSpace` lazy 추가:
```kotlin
    private val onboardingSpace: String? by lazy { curriculum?.space }
```

(b) `gatherer` lazy 블록의 `ContentGatherer(...)` 호출에 인자 추가:
```kotlin
            wikiPageId = wikiPageId,
            onboardingSpace = onboardingSpace,
        )
```

(c) companion object에 규칙 상수 추가:
```kotlin
        const val IOS_REFERENCE_RULE =
            "- [🍎 iOS 참조]·[🔀 Android·iOS 공통] 표시 자료는 iOS 또는 공통 플랫폼 내용입니다. " +
            "Android 온보딩 답변의 권위 있는 출처로 쓰지 말고, 필요 시 \"(iOS 참조)\"로만 인용하세요. SSOT는 📄 위키 자료입니다."
```

(d) `handleQuestion`의 `questionPrompt` buildString에서 "모르는 내용은 모른다고..." 라인 다음에 규칙 삽입:
```kotlin
            appendLine("모르는 내용은 모른다고 하고, 관련 문서나 담당자를 안내하세요.")
            appendLine(IOS_REFERENCE_RULE)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :onboarding:test --tests "*OnboardingToolTest*" --console=plain`
Expected: PASS.

- [ ] **Step 5: 전체 모듈 회귀 빌드**

Run: `./gradlew :search:test :onboarding:test --console=plain`
Expected: BUILD SUCCESSFUL — 두 모듈 전체 테스트 green.

- [ ] **Step 6: 커밋**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt
git commit -m "feat(onboarding): OnboardingTool에 SSOT space 와이어링 + iOS 참조 프롬프트 규칙"
```

---

## Self-Review

**Spec coverage:**
- §4.1 `strictSpaces` → Task 1 ✅
- §4.2 `searchScopedStructured` → Task 2 ✅
- §4.3 `OnboardingCurriculum.space` + yaml → Task 3 ✅
- §4.4 `OnboardingTool` 와이어링 + 프롬프트 → Task 6 ✅
- §4.5 `gatherForQuestion` 분기 → Task 5 ✅
- §4.6 `classifyPlatform` 토큰 스코어링 → Task 4 ✅
- §4.7 렌더링 마커 + LLM 규칙 → Task 4(마커) + Task 6(규칙) ✅
- §6 테스트 전략 → 각 Task의 테스트 단계로 분산 ✅

**Type consistency:** `searchStructured(..., strictSpaces)`(T1) → `searchScopedStructured`(T2)가 호출 → `confluenceQuestionContent`(T5)가 사용. `classifyPlatform(title, snippet)`(T4) → T5에서 동일 시그니처로 호출. `GatheredContent.platform`(T4) → T5에서 설정, formatBlocks(T4)에서 소비. `OnboardingCurriculum.space`(T3) → `onboardingSpace`(T6) → `ContentGatherer(onboardingSpace)`(T5). 일관됨 ✅

**Placeholder scan:** 모든 코드 단계에 실제 코드/명령/기대 출력 포함. 누락 없음 ✅
