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
        coEvery { mockConfluence.listPages(any(), any()) } returns listOf(
            ConfluencePageRef("1", "배포 가이드", "https://co.atlassian.net/wiki/1")
        )
        coEvery { mockConfluence.fetchPageContent("1") } returns ConfluencePage(
            "1", "배포 가이드", "배포 절차 내용", "https://co.atlassian.net/wiki/1"
        )

        val agent = VectorIndexAgent(mockConfluence, mockChroma, llmExpand, null, config, listOf("DEV"))
        val count = agent.indexAll()

        assertEquals(1, count)
        coVerify { mockChroma.addDocuments(any(), any(), any(), null, any()) }
    }
}
