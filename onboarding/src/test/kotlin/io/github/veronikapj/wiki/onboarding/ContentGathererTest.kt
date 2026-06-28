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
    fun `gatherForQuestion에서 한 도구가 예외를 던져도 나머지는 수집된다`() {
        val codeSearchTool = mockk<CodeSearchTool>()
        val confluenceTool = mockk<ConfluenceTool>()
        every { codeSearchTool.codeSearch(any()) } throws RuntimeException("fail")
        every { confluenceTool.confluenceSearch(any()) } returns "위키 결과"
        val g = gatherer(codeSearchTool = codeSearchTool, confluenceTool = confluenceTool)

        val result = g.gatherForQuestion("q", step = null)

        assertEquals(1, result.size)
        assertEquals(ContentGatherer.Provenance.CONFLUENCE, result[0].provenance)
    }

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
