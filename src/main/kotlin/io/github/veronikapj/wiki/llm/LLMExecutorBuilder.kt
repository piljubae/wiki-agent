@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki.llm

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.veronikapj.wiki.config.ModelConfig
import io.github.veronikapj.wiki.config.ModelProvider

object LLMExecutorBuilder {

    fun build(config: ModelConfig): MultiLLMPromptExecutor =
        when (config.provider) {
            ModelProvider.CLAUDE_CODE -> MultiLLMPromptExecutor(ClaudeCodeLLMClient())
            ModelProvider.GEMINI_CODE -> MultiLLMPromptExecutor(GeminiCodeLLMClient())
            ModelProvider.ANTHROPIC -> MultiLLMPromptExecutor(
                AnthropicLLMClient(
                    apiKey = requireNotNull(config.apiKey) { "ANTHROPIC apiKey required" }
                )
            )
            ModelProvider.GOOGLE -> MultiLLMPromptExecutor(
                GoogleLLMClient(
                    apiKey = requireNotNull(config.apiKey) { "GOOGLE apiKey required" }
                )
            )
        }

    fun defaultModel(config: ModelConfig): LLModel =
        when (config.provider) {
            ModelProvider.CLAUDE_CODE -> AnthropicModels.Sonnet_4
            ModelProvider.GEMINI_CODE -> GoogleModels.Gemini2_5Flash
            ModelProvider.ANTHROPIC -> AnthropicModels.Sonnet_4
            ModelProvider.GOOGLE -> GoogleModels.Gemini2_5Flash
        }

    fun lowCostModel(config: ModelConfig): LLModel =
        when (config.provider) {
            ModelProvider.CLAUDE_CODE -> AnthropicModels.Haiku_4_5
            ModelProvider.GEMINI_CODE -> GoogleModels.Gemini2_5Flash
            ModelProvider.ANTHROPIC -> AnthropicModels.Haiku_4_5
            ModelProvider.GOOGLE -> GoogleModels.Gemini2_5Flash
        }
}
