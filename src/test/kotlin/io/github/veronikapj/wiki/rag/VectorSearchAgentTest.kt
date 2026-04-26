package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class VectorSearchAgentTest {

    private val mockChroma = mockk<ChromaClient>(relaxed = true)
    private val llmExpand = LlmExpandClient(llmFn = { query -> "$query deploy release" })

    @Test
    fun `search LLM_EXPAND returns formatted results`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"
        coEvery { mockChroma.query("col-id", any(), null, any()) } returns listOf(
            ChromaQueryResult("1", "배포 절차 내용", mapOf("title" to "배포 가이드", "url" to "https://co.atlassian.net/1"), 0.2f)
        )

        val agent = VectorSearchAgent(mockChroma, llmExpand, null, config)
        val result = agent.search("배포")

        assertTrue(result.contains("배포 가이드"))
        assertTrue(result.contains("https://"))
    }

    @Test
    fun `search returns no results message when empty`() = runTest {
        val config = RagConfig(enabled = true, embeddingMode = EmbeddingMode.LLM_EXPAND)
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "col-id"
        coEvery { mockChroma.query(any(), any(), any(), any()) } returns emptyList()

        val agent = VectorSearchAgent(mockChroma, llmExpand, null, config)
        val result = agent.search("존재하지않는것")

        assertTrue(result.contains("찾을 수 없"))
    }
}
