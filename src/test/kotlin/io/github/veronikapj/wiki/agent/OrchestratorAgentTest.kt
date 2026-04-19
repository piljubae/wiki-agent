package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.config.ModelConfig
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNotNull

class OrchestratorAgentTest {

    @Test
    fun `build creates agent with confluenceTool only when rag disabled`() {
        val mockSearchAgent = mockk<ConfluenceSearchAgent>()
        val confluenceTool = ConfluenceTool(mockSearchAgent)
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            vectorSearchTool = null,
            executor = LLMExecutorBuilder.build(ModelConfig()),
        )
        assertNotNull(agent)
    }
}
