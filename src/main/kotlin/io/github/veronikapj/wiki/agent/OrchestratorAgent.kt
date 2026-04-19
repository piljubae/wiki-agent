@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import org.slf4j.LoggerFactory

class OrchestratorAgent(
    private val confluenceTool: ConfluenceTool,
    private val vectorSearchTool: VectorSearchTool?,
    private val executor: MultiLLMPromptExecutor,
) {
    suspend fun answer(question: String): String {
        log.info("OrchestratorAgent answering: '{}'", question)
        val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)
        for ((index, model) in fallbackModels.withIndex()) {
            val result = runCatching { buildAgent(model).run(question) }
            val ex = result.exceptionOrNull()
            if (ex == null) return result.getOrThrow()
            if (index < fallbackModels.lastIndex) {
                log.warn("Retrying with {}", fallbackModels[index + 1].id)
                continue
            }
            log.error("All models failed: {}", ex.message)
            return "검색 중 오류가 발생했습니다: ${ex.message}"
        }
        error("unreachable")
    }

    private fun buildAgent(model: LLModel): AIAgent<String, String> {
        val systemPrompt = buildString {
            appendLine("당신은 Confluence 위키 검색 전문가입니다.")
            appendLine("사용자의 질문에 답하기 위해 반드시 제공된 Tool을 사용해 검색하세요.")
            appendLine("검색 없이 직접 답변하지 마세요.")
            if (vectorSearchTool != null) {
                appendLine("confluenceSearch로 먼저 검색하고, 결과가 부족하면 vectorSearch도 사용하세요.")
            }
            appendLine("검색 결과를 바탕으로 요약과 링크를 함께 제공하세요.")
        }

        return AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
                    system(systemPrompt)
                },
                model = model,
                maxAgentIterations = 10,
            ),
            toolRegistry = ToolRegistry {
                tool(confluenceTool::confluenceSearch)
                if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
            },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrchestratorAgent::class.java)
    }
}
