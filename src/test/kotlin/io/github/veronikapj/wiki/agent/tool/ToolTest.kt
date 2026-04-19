package io.github.veronikapj.wiki.agent.tool

import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolTest {

    @Test
    fun `ConfluenceTool delegates to ConfluenceSearchAgent`() = runTest {
        val mockAgent = mockk<ConfluenceSearchAgent>()
        coEvery { mockAgent.search("배포") } returns "배포 가이드 결과"
        val tool = ConfluenceTool(mockAgent)
        val result = tool.confluenceSearch("배포")
        assertTrue(result.contains("배포"))
    }

    @Test
    fun `VectorSearchTool delegates to VectorSearchAgent`() = runTest {
        val mockAgent = mockk<VectorSearchAgent>()
        coEvery { mockAgent.search("배포") } returns "RAG 결과"
        val tool = VectorSearchTool(mockAgent)
        val result = tool.vectorSearch("배포")
        assertTrue(result.contains("RAG"))
    }
}
