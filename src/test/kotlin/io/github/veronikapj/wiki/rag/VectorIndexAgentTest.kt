package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePage
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VectorIndexAgentTest {

    private val mockChroma = mockk<ChromaClient>(relaxed = true)
    private val mockConfluence = mockk<ConfluenceClient>()
    private val llmExpand = LlmExpandClient(llmFn = { text -> "keyword: $text" })

    @Test
    fun `index fetches pages and adds to chroma`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"
        coEvery { mockConfluence.listAllPages(any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "https://co.atlassian.net/wiki/1", lastModified = "2026-05-01T00:00:00.000Z")
        )
        coEvery { mockConfluence.fetchPageContent("1") } returns ConfluencePage(
            "1", "배포 가이드", "배포 절차 내용", "https://co.atlassian.net/wiki/1"
        )

        val agent = VectorIndexAgent(mockConfluence, mockChroma, llmExpand, null, config, listOf("DEV"))
        val count = agent.indexAll()

        assertEquals(1, count)
        coVerify { mockChroma.upsertDocuments(any(), any(), any(), null, any()) }
    }

    @Test
    fun `indexAll skips unchanged pages`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"

        val pages = listOf(
            ConfluencePageRef("page-1", "페이지1", "https://ex.com/1", lastModified = "2026-05-01T00:00:00.000Z"),
            ConfluencePageRef("page-2", "페이지2", "https://ex.com/2", lastModified = "2026-05-10T00:00:00.000Z"),
        )
        // page-1은 이미 같은 lastModified로 인덱스됨 → 스킵
        // page-2는 신규 → 처리
        coEvery { mockConfluence.listAllPages(any(), any()) } returns pages
        coEvery { mockChroma.getAllIdsWithLastModified(any()) } returns mapOf(
            "page-1" to "2026-05-01T00:00:00.000Z",
        )
        coEvery { mockConfluence.fetchPageContent("page-2") } returns
            ConfluencePage("page-2", "페이지2", "내용", "https://ex.com/2")
        coEvery { mockChroma.upsertDocuments(any(), any(), any(), any(), any()) } returns Unit
        coEvery { mockChroma.deleteByIds(any(), any()) } returns Unit

        val agent = VectorIndexAgent(mockConfluence, mockChroma, llmExpand, null, config, listOf("DEV"))
        val count = agent.indexAll()

        assertEquals(1, count)
        coVerify(exactly = 1) {
            mockChroma.upsertDocuments(any(), eq(listOf("page-2")), any(), any(), any())
        }
        coVerify(exactly = 0) { mockChroma.deleteByIds(any(), any()) }
    }

    @Test
    fun `indexAll skips pages with empty lastModified`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"

        // lastModified = "" (Confluence이 필드 미제공) → 스킵
        coEvery { mockConfluence.listAllPages(any(), any()) } returns listOf(
            ConfluencePageRef("page-no-ts", "이름없음", "https://ex.com/0", lastModified = "")
        )
        coEvery { mockChroma.getAllIdsWithLastModified(any()) } returns emptyMap()

        val agent = VectorIndexAgent(mockConfluence, mockChroma, llmExpand, null, config, listOf("DEV"))
        val count = agent.indexAll()

        assertEquals(0, count)
        coVerify(exactly = 0) { mockChroma.upsertDocuments(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `indexAll deletes pages removed from Confluence`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"

        coEvery { mockConfluence.listAllPages(any(), any()) } returns emptyList()
        coEvery { mockChroma.getAllIdsWithLastModified(any()) } returns mapOf(
            "page-old" to "2026-04-01T00:00:00.000Z",
        )
        coEvery { mockChroma.deleteByIds(any(), eq(listOf("page-old"))) } returns Unit

        val agent = VectorIndexAgent(mockConfluence, mockChroma, llmExpand, null, config, listOf("DEV"))
        val count = agent.indexAll()

        assertEquals(0, count)
        coVerify(exactly = 1) { mockChroma.deleteByIds(any(), eq(listOf("page-old"))) }
    }
}
