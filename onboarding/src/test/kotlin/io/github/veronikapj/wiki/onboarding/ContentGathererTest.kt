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
