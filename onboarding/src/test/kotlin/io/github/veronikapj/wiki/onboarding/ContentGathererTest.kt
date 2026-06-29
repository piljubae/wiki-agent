package io.github.veronikapj.wiki.onboarding

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.search.tool.CodeSearchTool
import io.github.veronikapj.wiki.search.tool.ConfluenceTool
import io.github.veronikapj.wiki.search.tool.PrHistoryTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentGathererTest {

    private fun gatherer(
        confluenceClient: ConfluenceClient? = mockk(relaxed = true),
        confluenceTool: ConfluenceTool = mockk(relaxed = true),
        codeSearchTool: CodeSearchTool = mockk(relaxed = true),
        codeClient: GitHubCodeClient? = mockk(relaxed = true),
        codeRepo: String? = "kurly/kurly-android",
        wikiPageId: String? = "5912232879",
        prHistoryTool: PrHistoryTool? = mockk(relaxed = true),
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
    fun `CONFLUENCE_PAGE 위키 섹션의 제목과 본문 HTML 엔티티가 디코딩된다`() {
        val confluenceClient = mockk<ConfluenceClient>()
        coEvery { confluenceClient.fetchPageRawHtml("5912232879") } returns
            "<h2>프로젝트 구조 &amp; 모듈 맵 &mdash; 개요</h2><p>data &amp; domain 모듈은 &lt;independent&gt;.</p>"
        val g = gatherer(confluenceClient = confluenceClient)

        val result = g.gather(step(
            ContentSource(type = SourceType.CONFLUENCE_PAGE, pageId = "5912232879", section = "프로젝트 구조"),
        ))

        assertEquals(1, result.size)
        assertEquals(ContentGatherer.Provenance.WIKI, result[0].provenance)
        // 제목: 엔티티 디코딩 + 잔여 엔티티 없음
        assertEquals("프로젝트 구조 & 모듈 맵 — 개요", result[0].label)
        // 본문: 엔티티 디코딩
        assertTrue(result[0].text.contains("data & domain"), "본문 &amp; 디코딩. 실제: ${result[0].text}")
        assertTrue(result[0].text.contains("<independent>"), "본문 &lt;&gt; 디코딩. 실제: ${result[0].text}")
        assertTrue(!result[0].label.contains("&") || result[0].label.contains("& 모듈"),
            "라벨에 미디코딩 엔티티(&amp; 등)가 없어야 함. 실제: ${result[0].label}")
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

        assertTrue(result[0].text.length <= 4000 + "\n…(이하 생략)".length)
        assertTrue(result[0].text.contains("생략"))
    }

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
        val prHistoryTool = mockk<PrHistoryTool>(relaxed = true)
        every { prHistoryTool.prHistory(any()) } returns ""
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool,
            prHistoryTool = prHistoryTool)

        val result = g.gatherForQuestion("q", step = null, includeDeep = true)

        assertEquals(1, result.size)
        assertEquals(ContentGatherer.Provenance.CONFLUENCE, result[0].provenance)
    }

    @Test
    fun `gatherForQuestion(tier1)은 codeSearch와 confluenceSearch를 호출하지 않는다`() {
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true)
        val prHistoryTool = mockk<PrHistoryTool>(relaxed = true)
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool, prHistoryTool = prHistoryTool)

        g.gatherForQuestion("UseCase 어디있어", step = null, includeDeep = false)

        verify(exactly = 0) { codeSearchTool.codeSearch(any()) }
        verify(exactly = 0) { confluenceTool.confluenceSearch(any()) }
        verify(exactly = 0) { prHistoryTool.prHistory(any()) }
    }

    @Test
    fun `gatherForQuestion(deep)은 prHistory를 호출해 PR provenance로 수집한다`() {
        val prHistoryTool = mockk<PrHistoryTool>()
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
