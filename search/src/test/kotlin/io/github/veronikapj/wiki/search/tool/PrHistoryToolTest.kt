package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.jira.JiraClient
import io.github.veronikapj.wiki.jira.JiraConfluenceRef
import io.github.veronikapj.wiki.jira.JiraIssue
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.ChromaQueryResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrHistoryToolTest {

    private fun chromaWithOnePr(): ChromaClient {
        val chroma = mockk<ChromaClient>()
        coEvery { chroma.getOrCreateCollection(any()) } returns "cid"
        coEvery { chroma.query(any(), null, any(), any()) } returns listOf(
            ChromaQueryResult(
                id = "1",
                document = "# PR #10: KMA-7275 배너\n- 티켓: KMA-7275\n변경 요약",
                metadata = mapOf(
                    "repo" to "o/r", "pr_number" to "10", "ticket" to "KMA-7275",
                    "author" to "a", "merged_at" to "2026-06-01",
                ),
                distance = 0.1f,
            )
        )
        return chroma
    }

    private val issue = JiraIssue(
        key = "KMA-7275", summary = "배너 DSP", status = "In Progress", type = "Story",
        assignee = "pilju.bae", description = "배너 기획 설명",
        recentComments = listOf("pilju: 진행중"),
        confluenceRefs = listOf(JiraConfluenceRef("123", "배너 기획서", "https://x/wiki/spaces/P/pages/123")),
    )

    @Test
    fun `prHistory는 Jira 티켓과 Confluence 기획서를 보강한다`() = runBlocking {
        val jira = mockk<JiraClient>()
        coEvery { jira.getIssue("KMA-7275") } returns issue
        val conf = mockk<ConfluenceClient>()
        coEvery { conf.fetchPageRawHtml("123") } returns "<p>기획 본문 내용</p>"

        val tool = PrHistoryTool(
            chromaWithOnePr(), null, embeddingFn = { listOf(0.1f) },
            jiraClient = jira, confluenceClient = conf,
        )

        val result = tool.prHistory("KMA-7275")

        assertTrue(result.contains("🎫 KMA-7275"), result)
        assertTrue(result.contains("배너 DSP"), result)
        assertTrue(result.contains("📄 기획서"), result)
        assertTrue(result.contains("기획 본문 내용"), result)
    }

    @Test
    fun `jiraClient가 null이면 보강 없이 PR 결과만 반환한다`() = runBlocking {
        val tool = PrHistoryTool(chromaWithOnePr(), null, embeddingFn = { listOf(0.1f) })

        val result = tool.prHistory("KMA-7275")

        assertFalse(result.contains("🎫"), result)
        assertTrue(result.contains("PR #10"), result)
    }

    @Test
    fun `getIssue가 null이면 해당 티켓은 생략하고 PR 결과는 반환한다`() = runBlocking {
        val jira = mockk<JiraClient>()
        coEvery { jira.getIssue(any()) } returns null
        val tool = PrHistoryTool(
            chromaWithOnePr(), null, embeddingFn = { listOf(0.1f) }, jiraClient = jira,
        )

        val result = tool.prHistory("KMA-7275")

        assertFalse(result.contains("🎫"), result)
        assertTrue(result.contains("PR #10"), result)
    }
}
