# Onboarding 콘텐츠 고도화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 온보딩 콘텐츠 수집을 단일 위키 페이지에서 멀티소스(위키+코드+confluence+github-file)로 확장하고, 레벨 기반 깊이 조정과 질문 기반 라이브 검색을 더해 적응형 가이드를 만든다.

**Architecture:** `:onboarding` 모듈에 결정적 멀티소스 수집기 `ContentGatherer`를 신설해 `OnboardingTool`에 흩어진 위키 파싱/수집 책임을 모은다. `OnboardingTool.generateGuide`/`handleQuestion`은 ContentGatherer 결과를 provenance 헤더와 함께 프롬프트에 주입하고, `UserLevel`로 설명 깊이를 조정한다. manual loop·비-Koog·테스트 가능 철학 유지.

**Tech Stack:** Kotlin/JVM, Koog(`MultiLLMPromptExecutor`), kaml(curriculum.yaml), JUnit5 + MockK, Gradle 멀티모듈.

설계 문서: [2026-06-28-onboarding-content-enhancement-design.md](2026-06-28-onboarding-content-enhancement-design.md)

## Global Constraints

- 테스트 실행: `./gradlew :onboarding:test` (JUnit Platform).
- 검색 도구는 동기 호출: `CodeSearchTool.codeSearch(query: String): String`, `ConfluenceTool.confluenceSearch(query: String): String`.
- suspend 호출은 `runBlocking`으로 감싼다: `ConfluenceClient.fetchPageRawHtml(pageId): String`, `GitHubCodeClient.fetchFileContent(repo, path, branch): String?`.
- `STATIC` 소스는 **처리 금지** — 무시하고 `log.warn`만. (위키 SSOT 유지)
- 소스당 텍스트 상한 `MAX_CHARS = 4000`, 단계당 소스 수 상한 `MAX_SOURCES = 6`.
- 개별 소스 수집 실패는 graceful — 해당 소스만 건너뛰고 로그, 나머지는 진행.
- `OnboardingTool` 생성자 시그니처·`Main.kt` 호출부는 **변경 금지** (이미 도구 주입 중).
- 패키지: `io.github.veronikapj.wiki.onboarding`. `ContentGatherer`는 `internal`.
- 커밋은 각 Task 끝에서. 브랜치: `feat/onboarding-content-enhancement` (이미 생성됨).

---

### Task 1: ContentGatherer — 멀티소스 수집기 신설

위키 파싱을 포함한 모든 소스 타입 수집을 한 클래스로 모은다. 이 Task에서는 `ContentGatherer`를 **독립적으로** 만들고 단위 테스트한다. `OnboardingTool`은 아직 건드리지 않는다(다음 Task에서 위임 전환). 위키 파싱 로직은 `OnboardingTool`에도 잠시 중복 존재하지만 각 Task가 컴파일·통과하며, Task 2에서 `OnboardingTool` 쪽을 제거한다.

**Files:**
- Create: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt`

**Interfaces:**
- Consumes: `ConfluenceClient.fetchPageRawHtml(pageId): String` (suspend), `ConfluenceTool.confluenceSearch(query): String`, `CodeSearchTool.codeSearch(query): String`, `GitHubCodeClient.fetchFileContent(repo, path, branch): String?` (suspend), 기존 `CurriculumStep`/`ContentSource`/`SourceType`.
- Produces:
  - `ContentGatherer.GatheredContent(label: String, provenance: Provenance, text: String)`
  - `ContentGatherer.Provenance` enum: `WIKI("📄","위키")`, `CODE("💻","코드")`, `CONFLUENCE("🔗","연관문서")`, `GITHUB_FILE("📁","소스파일")` — 각 `emoji: String`, `display: String` 프로퍼티.
  - `fun gather(step: CurriculumStep): List<GatheredContent>`
  - `fun gatherForQuestion(question: String, step: CurriculumStep?): List<GatheredContent>`
  - `companion object { fun formatBlocks(items: List<GatheredContent>): String }`

- [ ] **Step 1: 실패하는 테스트 작성**

`onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt`:

```kotlin
package io.github.veronikapj.wiki.onboarding

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.search.tool.CodeSearchTool
import io.github.veronikapj.wiki.search.tool.ConfluenceTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ContentGathererTest {

    private fun gatherer(
        confluenceClient: ConfluenceClient? = mockk(relaxed = true),
        confluenceTool: ConfluenceTool = mockk(relaxed = true),
        codeSearchTool: CodeSearchTool = mockk(relaxed = true),
        codeClient: GitHubCodeClient? = mockk(relaxed = true),
        codeRepo: String? = "kurly/kurly-android",
        wikiPageId: String? = "5912232879",
    ) = ContentGatherer(
        confluenceClient = confluenceClient,
        confluenceTool = confluenceTool,
        codeSearchTool = codeSearchTool,
        codeClient = codeClient,
        codeRepo = codeRepo,
        codeBranch = "develop",
        wikiPageId = wikiPageId,
    )

    private fun step(vararg sources: ContentSource) = CurriculumStep(
        id = "s", name = "n", phase = 1, day = "Day 1", sources = sources.toList(),
    )

    @Test
    fun `CODE 소스는 codeSearch를 호출하고 CODE provenance로 수집된다`() {
        val codeSearchTool = mockk<CodeSearchTool>()
        every { codeSearchTool.codeSearch("ProductViewModel") } returns "class ProductViewModel { ... }"
        val g = gatherer(codeSearchTool = codeSearchTool)

        val result = g.gather(step(ContentSource(type = SourceType.CODE, query = "ProductViewModel")))

        assertEquals(1, result.size)
        assertEquals(ContentGatherer.Provenance.CODE, result[0].provenance)
        assertTrue(result[0].text.contains("ProductViewModel"))
        verify { codeSearchTool.codeSearch("ProductViewModel") }
    }

    @Test
    fun `CONFLUENCE 소스는 confluenceSearch를 호출한다`() {
        val confluenceTool = mockk<ConfluenceTool>()
        every { confluenceTool.confluenceSearch("브랜치 전략") } returns "git flow 기반..."
        val g = gatherer(confluenceTool = confluenceTool)

        val result = g.gather(step(ContentSource(type = SourceType.CONFLUENCE, query = "브랜치 전략")))

        assertEquals(ContentGatherer.Provenance.CONFLUENCE, result[0].provenance)
        verify { confluenceTool.confluenceSearch("브랜치 전략") }
    }

    @Test
    fun `GITHUB_FILE 소스는 fetchFileContent를 호출한다`() {
        val codeClient = mockk<GitHubCodeClient>()
        coEvery { codeClient.fetchFileContent("kurly/kurly-android", "build.gradle.kts", "develop") } returns "plugins { }"
        val g = gatherer(codeClient = codeClient)

        val result = g.gather(step(ContentSource(type = SourceType.GITHUB_FILE, path = "build.gradle.kts")))

        assertEquals(ContentGatherer.Provenance.GITHUB_FILE, result[0].provenance)
        assertTrue(result[0].text.contains("plugins"))
    }

    @Test
    fun `STATIC 소스는 무시되어 결과에 포함되지 않는다`() {
        val g = gatherer()
        val result = g.gather(step(ContentSource(type = SourceType.STATIC, path = "steps/x.md")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `한 소스가 예외를 던져도 나머지는 수집된다`() {
        val codeSearchTool = mockk<CodeSearchTool>()
        every { codeSearchTool.codeSearch("boom") } throws RuntimeException("fail")
        every { codeSearchTool.codeSearch("ok") } returns "fine"
        val g = gatherer(codeSearchTool = codeSearchTool)

        val result = g.gather(step(
            ContentSource(type = SourceType.CODE, query = "boom"),
            ContentSource(type = SourceType.CODE, query = "ok"),
        ))

        assertEquals(1, result.size)
        assertEquals("fine", result[0].text)
    }

    @Test
    fun `MAX_CHARS를 넘는 텍스트는 잘린다`() {
        val codeSearchTool = mockk<CodeSearchTool>()
        every { codeSearchTool.codeSearch(any()) } returns "x".repeat(10_000)
        val g = gatherer(codeSearchTool = codeSearchTool)

        val result = g.gather(step(ContentSource(type = SourceType.CODE, query = "q")))

        assertTrue(result[0].text.length < 10_000)
        assertTrue(result[0].text.contains("생략"))
    }

    @Test
    fun `gatherForQuestion은 질문으로 codeSearch와 confluenceSearch를 호출한다`() {
        val codeSearchTool = mockk<CodeSearchTool>()
        val confluenceTool = mockk<ConfluenceTool>()
        every { codeSearchTool.codeSearch("UseCase 어디있어") } returns "domain layer"
        every { confluenceTool.confluenceSearch("UseCase 어디있어") } returns "아키텍처 문서"
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool)

        val result = g.gatherForQuestion("UseCase 어디있어", step = null)

        assertEquals(2, result.size)
        verify { codeSearchTool.codeSearch("UseCase 어디있어") }
        verify { confluenceTool.confluenceSearch("UseCase 어디있어") }
    }

    @Test
    fun `formatBlocks는 provenance 헤더를 붙인다`() {
        val block = ContentGatherer.formatBlocks(listOf(
            ContentGatherer.GatheredContent("ProductViewModel", ContentGatherer.Provenance.CODE, "class P"),
        ))
        assertTrue(block.contains("💻"))
        assertTrue(block.contains("코드"))
        assertTrue(block.contains("ProductViewModel"))
        assertTrue(block.contains("class P"))
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :onboarding:test --tests "*ContentGathererTest*"`
Expected: 컴파일 실패 (`ContentGatherer` 미정의).

- [ ] **Step 3: ContentGatherer 구현**

`onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt`:

```kotlin
package io.github.veronikapj.wiki.onboarding

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.search.tool.CodeSearchTool
import io.github.veronikapj.wiki.search.tool.ConfluenceTool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 온보딩 단계/질문에 대한 콘텐츠를 결정적으로 멀티소스에서 수집한다.
 * LLM 자율 호출 없이 step.sources / 질문에서 쿼리를 도출한다.
 */
internal class ContentGatherer(
    private val confluenceClient: ConfluenceClient?,
    private val confluenceTool: ConfluenceTool,
    private val codeSearchTool: CodeSearchTool,
    private val codeClient: GitHubCodeClient?,
    private val codeRepo: String?,
    private val codeBranch: String,
    private val wikiPageId: String?,
) {
    private val log = LoggerFactory.getLogger(ContentGatherer::class.java)

    enum class Provenance(val emoji: String, val display: String) {
        WIKI("📄", "위키"),
        CODE("💻", "코드"),
        CONFLUENCE("🔗", "연관문서"),
        GITHUB_FILE("📁", "소스파일"),
    }

    data class GatheredContent(
        val label: String,
        val provenance: Provenance,
        val text: String,
    )

    // ── 단계 콘텐츠 ──

    fun gather(step: CurriculumStep): List<GatheredContent> {
        val out = mutableListOf<GatheredContent>()
        for (source in step.sources.take(MAX_SOURCES)) {
            runCatching {
                when (source.type) {
                    SourceType.CONFLUENCE_PAGE -> wikiSection(source)?.let { out += it }
                    SourceType.CODE -> codeContent(source.query)?.let { out += it }
                    SourceType.CONFLUENCE -> confluenceContent(source.query)?.let { out += it }
                    SourceType.GITHUB_FILE -> fileContent(source)?.let { out += it }
                    SourceType.STATIC -> log.warn("STATIC source ignored (wiki SSOT): {}", source.path)
                }
            }.onFailure { log.warn("gather source {} failed: {}", source.type, it.message) }
        }
        return out
    }

    // ── 질문 라이브 검색 ──

    fun gatherForQuestion(question: String, step: CurriculumStep?): List<GatheredContent> {
        val out = mutableListOf<GatheredContent>()

        // 현재 단계의 위키 섹션(맥락)
        if (step != null) {
            step.sources.firstOrNull { it.type == SourceType.CONFLUENCE_PAGE }?.let { src ->
                runCatching { wikiSection(src) }.getOrNull()?.let { out += it }
            }
        }
        codeContent(question)?.let { out += it }
        confluenceContent(question)?.let { out += it }
        return out
    }

    // ── 소스별 수집 ──

    private fun codeContent(query: String?): GatheredContent? {
        val q = query?.takeIf { it.isNotBlank() } ?: return null
        val text = codeSearchTool.codeSearch(q)
        if (text.isBlank()) return null
        return GatheredContent(q, Provenance.CODE, text.truncated())
    }

    private fun confluenceContent(query: String?): GatheredContent? {
        val q = query?.takeIf { it.isNotBlank() } ?: return null
        val text = confluenceTool.confluenceSearch(q)
        if (text.isBlank()) return null
        return GatheredContent(q, Provenance.CONFLUENCE, text.truncated())
    }

    private fun fileContent(source: ContentSource): GatheredContent? {
        val client = codeClient ?: return null
        val path = source.path?.takeIf { it.isNotBlank() } ?: return null
        val repo = source.repo ?: codeRepo ?: return null
        val text = runBlocking { client.fetchFileContent(repo, path, codeBranch) }
        if (text.isNullOrBlank()) return null
        return GatheredContent(path, Provenance.GITHUB_FILE, text.truncated())
    }

    // ── 위키 섹션 (H2 파싱 + 캐시) ──

    private data class WikiSection(val title: String, val content: String)

    @Volatile
    private var wikiSectionsCache: List<WikiSection>? = null

    private fun wikiSection(source: ContentSource): GatheredContent? {
        val keyword = source.section?.takeIf { it.isNotBlank() } ?: return null
        val sections = loadWikiSections()
        val matched = sections.firstOrNull { it.title.contains(keyword, ignoreCase = true) }
        if (matched == null) {
            log.warn("Wiki section not found for '{}', available: {}", keyword, sections.map { it.title })
            return null
        }
        return GatheredContent(matched.title, Provenance.WIKI, matched.content.truncated())
    }

    private fun loadWikiSections(): List<WikiSection> {
        wikiSectionsCache?.let { return it }
        val client = confluenceClient ?: run { log.warn("confluenceClient null"); return emptyList() }
        val pageId = wikiPageId ?: run { log.warn("wikiPageId null"); return emptyList() }

        val html = runCatching { runBlocking { client.fetchPageRawHtml(pageId) } }
            .onFailure { log.error("Failed to fetch wiki page {}: {}", pageId, it.message) }
            .getOrDefault("")
        if (html.isBlank()) return emptyList()

        val sections = parseHtmlToSections(html)
        log.info("Loaded {} H2 sections from wiki page {}", sections.size, pageId)
        wikiSectionsCache = sections
        return sections
    }

    private fun parseHtmlToSections(html: String): List<WikiSection> {
        val h2Pattern = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
        val h1h2Pattern = Regex("<h[12][^>]*>", RegexOption.DOT_MATCHES_ALL)
        val h2Matches = h2Pattern.findAll(html).toList()
        if (h2Matches.isEmpty()) {
            log.warn("No H2 headings in wiki HTML (length={})", html.length)
            return emptyList()
        }
        return h2Matches.map { match ->
            val title = match.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            val sectionStart = match.range.last + 1
            val sectionEnd = h1h2Pattern.findAll(html)
                .firstOrNull { it.range.first > match.range.last }?.range?.first ?: html.length
            val sectionHtml = html.substring(sectionStart, sectionEnd)
            val plainText = sectionHtml
                .replace(Regex("<pre><code[^>]*>"), "\n```\n")
                .replace(Regex("</code></pre>"), "\n```\n")
                .replace(Regex("<code>"), "`").replace("</code>", "`")
                .replace(Regex("<strong>"), "*").replace("</strong>", "*")
                .replace(Regex("<h3[^>]*>"), "\n### ").replace(Regex("</h3>"), "\n")
                .replace(Regex("<li[^>]*>"), "\n• ").replace("</li>", "")
                .replace(Regex("<p[^>]*>"), "\n").replace("</p>", "\n")
                .replace(Regex("<br[^>]*/?>"), "\n")
                .replace(Regex("<tr>"), "\n").replace(Regex("<th[^>]*>"), "| ").replace(Regex("<td[^>]*>"), "| ")
                .replace(Regex("</t[hd]>"), " ")
                .replace(Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>")) { m -> "${m.groupValues[2]} (${m.groupValues[1]})" }
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("&amp;"), "&").replace(Regex("&lt;"), "<").replace(Regex("&gt;"), ">")
                .replace(Regex("&#039;"), "'").replace(Regex("&quot;"), "\"")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            WikiSection(title, plainText)
        }
    }

    private fun String.truncated(): String =
        if (length <= MAX_CHARS) this else take(MAX_CHARS) + "\n…(이하 생략)"

    companion object {
        private const val MAX_SOURCES = 6
        private const val MAX_CHARS = 4000

        fun formatBlocks(items: List<GatheredContent>): String =
            items.joinToString("\n\n") { gc ->
                "=== ${gc.provenance.emoji} ${gc.provenance.display}: ${gc.label} ===\n${gc.text}"
            }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :onboarding:test --tests "*ContentGathererTest*"`
Expected: PASS (8 테스트).

- [ ] **Step 5: 커밋**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/ContentGatherer.kt \
        onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/ContentGathererTest.kt
git commit -m "feat(onboarding): 멀티소스 수집기 ContentGatherer 신설

위키 섹션·코드검색·confluence검색·github-file 결정적 수집. STATIC 무시.
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: generateGuide를 ContentGatherer로 전환 + 레벨 깊이 + provenance 프롬프트

`OnboardingTool`이 `ContentGatherer`를 내부 생성해 `generateGuide`에서 멀티소스를 수집하고, 레벨 기반 깊이 지시와 provenance 블록을 프롬프트에 반영한다. `OnboardingTool`의 기존 위키 파싱 메서드(`WikiSection`/`loadWikiSections`/`parseHtmlToSections`/`getWikiContentForStep`/`wikiSectionsCache`/`wikiPageId`)를 제거한다(ContentGatherer로 이전 완료).

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt` (레벨 깊이 테스트 추가는 Task 4. 여기선 기존 테스트가 깨지지 않게 픽스처만 최소 조정)

**Interfaces:**
- Consumes: `ContentGatherer.gather(step)`, `ContentGatherer.formatBlocks(items)`, `ContentGatherer.Provenance`.
- Produces: `OnboardingTool`가 내부에 `private val gatherer: ContentGatherer` 보유. `private fun depthInstruction(level: UserLevel?): String`.

- [ ] **Step 1: ContentGatherer 인스턴스 추가 + wikiPageId 보존**

`OnboardingTool.kt`에서 기존 `wikiPageId` lazy는 ContentGatherer 생성에 필요하므로 유지하고, 그 아래에 gatherer를 추가한다. (기존 `WikiSection` data class, `wikiSectionsCache`, `loadWikiSections`, `parseHtmlToSections`, `getWikiContentForStep`는 Step 3에서 제거)

`curriculum` lazy 정의 바로 아래(기존 `wikiPageId` 블록 유지)에 추가:

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
        )
    }
```

- [ ] **Step 2: depthInstruction 헬퍼 + generateGuide 재작성**

`generateGuide` 전체를 아래로 교체:

```kotlin
    private fun generateGuide(userId: String, step: CurriculumStep, session: OnboardingSession): String {
        val gathered = gatherer.gather(step)
        val contentBlock = ContentGatherer.formatBlocks(gathered)

        val phaseSteps = session.steps.filter { it.phase == step.phase }
        val stepIndex = phaseSteps.indexOfFirst { it.id == step.id } + 1
        val phaseTotal = phaseSteps.size
        val header = ":books: *[Phase ${step.phase}: $stepIndex/$phaseTotal] ${step.name}* (${step.day})"

        log.info("Generating guide for step={}, sources={}", step.id, gathered.size)

        val guidePrompt = buildString {
            appendLine(SLACK_FORMAT_RULE)
            appendLine()
            appendLine("당신은 컬리(Kurly) $projectName 프로젝트의 신규 입사자 온보딩 멘토입니다.")
            appendLine("컬리는 한국의 신선식품 이커머스 플랫폼이며, 프로젝트명은 '$projectName'입니다.")
            appendLine("온보딩 대상은 $projectName (Android 앱) 코드베이스입니다. 이 온보딩 도구 자체(wiki-agent)의 구조나 파일을 설명하지 마세요.")
            appendLine()
            appendLine("단계 정보:")
            appendLine("- 이름: ${step.name}")
            appendLine("- Phase: ${step.phase}")
            appendLine("- 예상 소요 기간: ${step.day}")
            appendLine()
            appendLine(depthInstruction(session.level))
            appendLine()
            if (gathered.isNotEmpty()) {
                appendLine("=== 참고 자료 (출처별로 구분됨) ===")
                appendLine(contentBlock)
                appendLine("=== 끝 ===")
                appendLine()
                appendLine("절대 규칙:")
                appendLine("- 위 참고 자료에 있는 내용만 안내하세요. 자료에 없는 내용을 추가하거나 추측하지 마세요.")
                appendLine("- 파일 경로, 클래스명, 모듈명은 참고 자료(💻 코드 / 📁 소스파일 포함)에 명시된 것만 사용하세요.")
                appendLine("- 참고 자료의 테이블/목록은 그대로 Slack mrkdwn 형식으로 변환하세요.")
            } else {
                appendLine("참고 자료를 수집하지 못했습니다.")
                appendLine("\"현재 이 단계의 참고 자료를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.\"라고만 안내하세요.")
                appendLine("절대로 자체적으로 내용을 생성하지 마세요.")
            }
            appendLine()
            appendLine("가이드 작성 규칙 (이 구조로 작성):")
            appendLine("1. *핵심 요약* — 이 단계에서 알아야 할 것을 2~3줄로.")
            appendLine("2. *상세* — 참고 자료의 핵심을 불릿으로 정리. 코드 출처가 있으면 실제 클래스/경로를 인용.")
            appendLine("3. *실습 액션* — 참고 자료에 있는 액션 아이템이 있으면 제시.")
            appendLine("4. 컨벤션/규칙 단계는 :white_check_mark: DO / :x: DON'T 형식으로 정리.")
            appendLine("5. 마지막에 `다음`을 입력하면 다음 단계로, 궁금하면 질문하라고 안내.")
        }

        val guideBody = callLLM(guidePrompt)
        return "$header\n\n$guideBody"
    }

    private fun depthInstruction(level: UserLevel?): String = when (level?.android) {
        "A" -> "설명 깊이: 입문자 대상. 배경·용어를 풀어서 상세히 설명하고, 왜 필요한지부터 알려주세요."
        "C" -> "설명 깊이: 숙련자 대상. 요점과 kurly 고유 컨벤션 위주로 간결하게. 일반적인 Android 개념 설명은 생략하세요."
        else -> "설명 깊이: 중급자 대상. 핵심 위주로 설명하고 익숙한 개념은 생략하세요."
    }
```

- [ ] **Step 3: 죽은 위키 파싱 코드 제거**

`OnboardingTool.kt`에서 다음을 삭제(ContentGatherer로 이전됨):
- `data class WikiSection(...)`
- `@Volatile private var wikiSectionsCache`
- `private fun loadWikiSections()`
- `private fun parseHtmlToSections(...)`
- `private fun getWikiContentForStep(step)`

`handleQuestion`이 아직 `getWikiContentForStep`를 호출하므로, 이 Step에서는 임시로 `handleQuestion` 내 `val content = getWikiContentForStep(currentStep)` 줄을 `val content = ContentGatherer.formatBlocks(gatherer.gather(currentStep))`로 교체해 컴파일을 유지한다. (handleQuestion 정식 개선은 Task 3)

- [ ] **Step 4: 컴파일 + 기존 테스트 실행**

Run: `./gradlew :onboarding:test`
Expected: 컴파일 성공. 기존 `OnboardingToolTest`는 static 소스를 쓰므로 가이드 콘텐츠가 비지만(STATIC 무시), 단계명/Phase 헤더는 그대로 → 기존 assert(`테스트 단계`, `Phase` 포함) PASS. `ContentGathererTest` PASS.

- [ ] **Step 5: 커밋**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt
git commit -m "feat(onboarding): generateGuide 멀티소스+레벨 깊이 전환

위키 파싱을 ContentGatherer로 이전, provenance 블록·레벨별 깊이 프롬프트 반영.
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: handleQuestion 라이브 검색 전환 + 메모 기록

질문 처리를 고정 위키 섹션에서 `gatherForQuestion` 라이브 검색으로 전환하고, 질문 요지를 세션 메모로 남긴다.

**Files:**
- Modify: `onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt`
- Test: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt`

**Interfaces:**
- Consumes: `ContentGatherer.gatherForQuestion(question, step)`, `OnboardingSessionStore.addMemo(userId, memo)`.
- Produces: 변경된 `handleQuestion` 동작(라이브 검색 주입 + 메모 기록).

- [ ] **Step 1: 실패하는 테스트 추가**

`OnboardingToolTest.kt`에 추가 (mock executor가 검색 도구를 통해 동작함을 확인):

```kotlin
    @Test
    fun `질문 시 codeSearch 라이브 검색이 호출되고 메모가 기록된다`() {
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

        val result = tool.handle(userId, "ProductViewModel 어디있어?")

        assertTrue(result.contains("feature/product"), "LLM 답변이 반환되어야 합니다. 실제: $result")
        verify { codeSearchTool.codeSearch("ProductViewModel 어디있어?") }

        val session = OnboardingSessionStore.load(userId)!!
        assertTrue(session.memos.any { it.contains("ProductViewModel") }, "질문이 메모로 기록되어야 합니다. 실제: ${session.memos}")
    }
```

테스트 상단 import에 `import io.mockk.every`, `import io.mockk.verify` 가 없으면 추가.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :onboarding:test --tests "*OnboardingToolTest*질문 시 codeSearch*"`
Expected: FAIL — 메모 미기록 / verify 실패 (현재 handleQuestion이 라이브 검색·메모를 안 함).

- [ ] **Step 3: handleQuestion 재작성**

`OnboardingTool.kt`의 `handleQuestion` 전체를 교체:

```kotlin
    private fun handleQuestion(userId: String, message: String, conversationContext: String): String {
        val session = OnboardingSessionStore.load(userId)
        val cur = curriculum
        val currentStep = if (session?.currentStepId != null && cur != null) {
            cur.phases.firstOrNull { it.id == session.currentStepId }
        } else null

        val gathered = gatherer.gatherForQuestion(message, currentStep)
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
            appendLine("모르는 내용은 모른다고 하고, 관련 문서나 담당자를 안내하세요.")
            if (contextBlock.isNotBlank()) {
                appendLine()
                appendLine(contextBlock)
            }
            appendLine()
            appendLine("사용자 질문: $message")
        }

        val answer = callLLM(questionPrompt)
        OnboardingSessionStore.addMemo(userId, "질문: ${message.take(80)}")
        return answer
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :onboarding:test`
Expected: 신규 질문 테스트 PASS, 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add onboarding/src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt \
        onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt
git commit -m "feat(onboarding): 질문 기반 라이브 검색 + 메모 기록

handleQuestion이 질문으로 코드·위키를 실시간 검색해 답변, 질문을 세션 메모로 기록.
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 기존 테스트 픽스처 멀티소스화 + 레벨 깊이 검증

기존 `OnboardingToolTest` 커리큘럼이 실효 없는 `type: static`을 쓰므로, 멀티소스(`code`)로 교체해 실제 수집 경로를 검증하고, 레벨별 깊이 지시가 프롬프트에 반영되는지 확인한다.

**Files:**
- Modify: `onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt`

**Interfaces:**
- Consumes: 기존 `createTool`/`createMockExecutor`, `CodeSearchTool` mock.
- Produces: 멀티소스 픽스처 + 깊이 검증 테스트.

- [ ] **Step 1: 커리큘럼 픽스처를 code 소스로 교체**

`curriculumYaml`의 각 step `sources`를 static → code로 변경:

```kotlin
    private val curriculumYaml = """
lastUpdated: "2026-06-02"
phases:
  - id: step-1
    name: "테스트 단계 1"
    phase: 1
    day: "Day 1"
    skippable: true
    levelFilter:
      skipWhen:
        android: "C"
    sources:
      - type: code
        query: "step1 환경 세팅"
  - id: step-2
    name: "테스트 단계 2"
    phase: 1
    day: "Day 1"
    skippable: false
    sources:
      - type: code
        query: "step2 Compose"
  - id: step-3
    name: "테스트 단계 3"
    phase: 2
    day: "Day 2"
    skippable: false
    sources:
      - type: code
        query: "step3 도메인"
    """.trimIndent()
```

`setup()`에서 `$testDir/steps/*.md` 파일 생성 코드는 더 이상 불필요하므로 제거(디렉터리 생성도 제거 가능):

```kotlin
    @BeforeEach
    fun setup() {
        File(testDir).mkdirs()
        File(curriculumPath).writeText(curriculumYaml)
    }
```

- [ ] **Step 2: createTool이 code 검색 모킹을 제공하도록 수정**

`createTool`을 codeSearchTool 주입 가능하게 변경(기본은 relaxed가 빈 문자열 반환하므로 명시 모킹):

```kotlin
    private fun createTool(
        executor: MultiLLMPromptExecutor = createMockExecutor("LLM 가이드 응답입니다."),
        codeSearchTool: CodeSearchTool = mockk<CodeSearchTool>(relaxed = true).also {
            every { it.codeSearch(any()) } returns "테스트 코드 자료"
        },
    ): OnboardingTool {
        return OnboardingTool(
            curriculumPath = curriculumPath,
            executor = executor,
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = mockk<ConfluenceTool>(relaxed = true),
            codeSearchTool = codeSearchTool,
        )
    }
```

상단 import에 `import io.mockk.every` 추가(없으면).

- [ ] **Step 3: 레벨 깊이 검증 테스트 추가**

LLM executor에 전달되는 프롬프트를 캡처해 깊이 지시를 검증:

```kotlin
    @Test
    fun `숙련자(C) 레벨이면 간결 깊이 지시가 프롬프트에 포함된다`() {
        val promptSlot = slot<ai.koog.prompt.dsl.Prompt>()
        val executor = mockk<MultiLLMPromptExecutor>()
        coEvery { executor.execute(capture(promptSlot), any()) } returns listOf(
            Message.Assistant(content = "응답", metaInfo = ResponseMetaInfo.Empty)
        )
        val tool = createTool(executor = executor)
        val userId = uniqueUserId()

        // C 레벨 → step-1 스킵, step-2부터. 가이드 생성 시 깊이 지시 포함
        tool.handle(userId, "C, A, A")

        val sentText = promptSlot.captured.messages.joinToString(" ") { it.content }
        assertTrue(sentText.contains("숙련자"), "C 레벨이면 숙련자 깊이 지시가 포함되어야 합니다. 실제: $sentText")
    }
```

상단 import에 `import io.mockk.slot`, `import io.mockk.coEvery` 추가(없으면).

> 참고: `Prompt`의 메시지 접근 경로(`.messages`/`.content`)가 koog 버전과 다르면, 대신 `createMockExecutor`로 받은 응답 대신 프롬프트 캡처를 `every`로 바꾸거나, 가이드 결과 문자열에 깊이 영향을 주는 간접 검증으로 대체한다. 우선 위 형태로 시도하고 컴파일/런타임 에러 시 `promptSlot.captured.toString().contains("숙련자")`로 폴백.

- [ ] **Step 4: 전체 테스트 실행**

Run: `./gradlew :onboarding:test`
Expected: 전체 PASS (기존 + 신규). 기존 `질문 시 현재 단계 컨텍스트로 LLM 답변이 반환된다` 등도 code 소스 모킹으로 정상 동작.

- [ ] **Step 5: 커밋**

```bash
git add onboarding/src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt
git commit -m "test(onboarding): 픽스처 멀티소스화 + 레벨 깊이 검증

static 픽스처를 code 소스로 교체해 실제 수집 경로 검증, C 레벨 깊이 지시 테스트 추가.
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage**
- 멀티소스 수집 배선(위키/코드/confluence/github-file) → Task 1 ✅
- STATIC 무시(위키 SSOT) → Task 1 (`SourceType.STATIC -> log.warn`) ✅
- 결정적·바운드(길이/개수 상한, graceful) → Task 1 (MAX_CHARS/MAX_SOURCES/runCatching) ✅
- 레벨 기반 깊이 → Task 2 (`depthInstruction`) ✅
- 생성 프롬프트 품질(provenance + 구조 출력) → Task 2 ✅
- 질문 기반 라이브 검색 + 메모 → Task 3 ✅
- 테스트(신규 ContentGatherer, 픽스처 교체, 깊이 검증) → Task 1/3/4 ✅
- 변경 영향: `OnboardingCurriculum.kt`/`OnboardingSession.kt`/`Main.kt` 무변경 → 플랜이 이를 건드리지 않음 ✅

**2. Placeholder scan** — "TBD/TODO/적절히 처리" 없음. 모든 코드 스텝에 실제 코드 포함. (Task 4 Step 3에 koog 버전 차이 대비 폴백 명시 — 플레이스홀더 아님, 구체적 대안 제시.) ✅

**3. Type consistency**
- `ContentGatherer.gather`/`gatherForQuestion`/`formatBlocks`/`GatheredContent`/`Provenance(emoji,display)` — Task 1 정의, Task 2/3에서 동일 시그니처 사용 ✅
- `depthInstruction(level: UserLevel?)` — Task 2 정의·사용 ✅
- `OnboardingSessionStore.addMemo(userId, memo)` — 기존 API(OnboardingSession.kt:96) 재사용 ✅
- `fetchFileContent(repo, path, branch)` / `codeSearch(query)` / `confluenceSearch(query)` / `fetchPageRawHtml(pageId)` — 실제 시그니처와 일치 ✅
