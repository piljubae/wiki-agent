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
import io.github.veronikapj.wiki.agent.tool.GitHubWikiTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import org.slf4j.LoggerFactory

class OrchestratorAgent(
    private val confluenceTool: ConfluenceTool? = null,
    private val githubWikiTool: GitHubWikiTool? = null,
    private val vectorSearchTool: VectorSearchTool? = null,
    private val executor: MultiLLMPromptExecutor,
    private val useManualLoop: Boolean = false,
) {
    init {
        require(confluenceTool != null || githubWikiTool != null || vectorSearchTool != null) {
            "At least one tool must be enabled"
        }
    }

    suspend fun answer(question: String): String {
        log.info("OrchestratorAgent answering: '{}'", question)
        return if (useManualLoop) answerWithManualLoop(question)
        else answerWithKoogAgent(question)
    }

    // Claude Code CLI용: 2단계 직접 루프 (tool call 프로토콜 불필요)
    private suspend fun answerWithManualLoop(question: String): String {
        val availableTools = listOfNotNull(
            githubWikiTool?.let { "githubWikiSearch" },
            confluenceTool?.let { "confluenceSearch" },
            vectorSearchTool?.let { "vectorSearch" },
        )

        // 1단계: 어떤 tool로 무엇을 검색할지 결정
        val decisionPrompt = buildString {
            appendLine("당신은 검색 라우터입니다. 아래 질문에 답하기 위해 어떤 검색이 필요한지 판단하세요.")
            appendLine()
            appendLine("사용 가능한 검색 도구: ${availableTools.joinToString(", ")}")
            appendLine()
            appendLine("규칙:")
            appendLine("- 검색이 필요하면 다음 형식으로만 응답하세요 (다른 내용 금지):")
            appendLine("  TOOL: <tool이름>")
            appendLine("  QUERY: <검색어>")
            appendLine("- 검색이 불필요하면 NO_SEARCH 라고만 응답하세요.")
            appendLine()
            appendLine("질문: $question")
        }

        val model = AnthropicModels.Haiku_4_5
        val decision = executor.execute(
            prompt("decision") { user(decisionPrompt) }, model
        ).joinToString("") { it.content }.trim()

        log.info("Search decision: {}", decision.take(150))

        // 2단계: 결정에 따라 tool 실행
        val searchResult = runCatching { executeFromDecision(decision) }.getOrNull()

        // 3단계: 결과를 바탕으로 최종 답변
        val summaryPrompt = if (searchResult != null) {
            buildString {
                appendLine("질문: $question")
                appendLine()
                appendLine("검색 결과:")
                appendLine(searchResult)
                appendLine()
                appendLine("위 검색 결과를 바탕으로 요약과 관련 링크를 함께 답변하세요.")
            }
        } else {
            question
        }

        return executor.execute(
            prompt("summary") { user(summaryPrompt) }, model
        ).joinToString("") { it.content }
    }

    private fun executeFromDecision(decision: String): String? {
        val toolMatch = Regex("TOOL:\\s*(\\S+)").find(decision) ?: return null
        val queryMatch = Regex("QUERY:\\s*(.+)").find(decision) ?: return null
        val toolName = toolMatch.groupValues[1].trim()
        val query = queryMatch.groupValues[2].trim()

        log.info("Executing tool: {} query: {}", toolName, query)
        return when (toolName) {
            "githubWikiSearch" -> githubWikiTool?.githubWikiSearch(query)
            "confluenceSearch" -> confluenceTool?.confluenceSearch(query)
            "vectorSearch" -> vectorSearchTool?.vectorSearch(query)
            else -> null
        }
    }

    // API 키 사용 시: Koog AIAgent의 네이티브 tool calling 루프
    private suspend fun answerWithKoogAgent(question: String): String {
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
            val sources = listOfNotNull(
                if (confluenceTool != null) "Confluence 위키" else null,
                if (githubWikiTool != null) "GitHub Wiki" else null,
                if (vectorSearchTool != null) "벡터 검색(RAG)" else null,
            )
            appendLine("당신은 ${sources.joinToString("와 ")} 검색 전문가입니다.")
            appendLine("사용자의 질문에 답하기 위해 반드시 제공된 Tool을 사용해 검색하세요.")
            appendLine("검색 없이 직접 답변하지 마세요.")
            if (confluenceTool != null && vectorSearchTool != null) {
                appendLine("confluenceSearch로 먼저 검색하고, 결과가 부족하면 vectorSearch도 사용하세요.")
            }
            if (githubWikiTool != null) {
                appendLine("기술 문서나 코드 관련 질문은 githubWikiSearch도 사용하세요.")
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
                if (confluenceTool != null) tool(confluenceTool::confluenceSearch)
                if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
                if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
            },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrchestratorAgent::class.java)
    }
}
