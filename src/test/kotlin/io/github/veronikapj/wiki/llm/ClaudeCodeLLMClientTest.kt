package io.github.veronikapj.wiki.llm

import io.github.veronikapj.wiki.config.ModelConfig
import io.github.veronikapj.wiki.config.ModelProvider
import kotlin.test.Test
import kotlin.test.assertNotNull

class ClaudeCodeLLMClientTest {

    @Test
    fun `buildExecutor returns executor for CLAUDE_CODE`() {
        val config = ModelConfig(provider = ModelProvider.CLAUDE_CODE)
        val executor = LLMExecutorBuilder.build(config)
        assertNotNull(executor)
    }

    @Test
    fun `buildExecutor returns executor for ANTHROPIC`() {
        val config = ModelConfig(provider = ModelProvider.ANTHROPIC, apiKey = "sk-ant-test")
        val executor = LLMExecutorBuilder.build(config)
        assertNotNull(executor)
    }

    @Test
    fun `buildExecutor returns executor for GOOGLE`() {
        val config = ModelConfig(provider = ModelProvider.GOOGLE, apiKey = "AIza-test")
        val executor = LLMExecutorBuilder.build(config)
        assertNotNull(executor)
    }
}
