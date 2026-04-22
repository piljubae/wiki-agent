@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.core.feature.handler.tool.ToolCallStartingContext
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.GitHubWikiTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import io.github.veronikapj.wiki.context.ConversationStore
import io.github.veronikapj.wiki.context.ProjectMemory
import io.github.veronikapj.wiki.context.Turn
import org.slf4j.LoggerFactory

class OrchestratorAgent(
    private val confluenceTool: ConfluenceTool? = null,
    private val githubWikiTool: GitHubWikiTool? = null,
    private val vectorSearchTool: VectorSearchTool? = null,
    private val executor: MultiLLMPromptExecutor,
    private val useManualLoop: Boolean = false,
    private val conversationStore: ConversationStore? = null,
    private val projectMemory: ProjectMemory? = null,
) {
    init {
        require(confluenceTool != null || githubWikiTool != null || vectorSearchTool != null) {
            "At least one tool must be enabled"
        }
    }

    suspend fun answer(
        question: String,
        listener: SearchProgressListener? = null,
        sessionId: String? = null,
    ): String {
        log.info("OrchestratorAgent answering: '{}'", question)
        return if (useManualLoop) answerWithManualLoop(question, listener, sessionId)
        else answerWithKoogAgent(question, listener, sessionId)
    }

    // In-memory history fallback when no conversationStore/sessionId
    private val history = ArrayDeque<Pair<String, String>>()

    private suspend fun answerWithManualLoop(
        question: String,
        listener: SearchProgressListener? = null,
        sessionId: String? = null,
    ): String {
        val contextHistory: List<Turn> = if (sessionId != null && conversationStore != null) {
            conversationStore.load(sessionId)
        } else {
            history.map { (q, a) -> Turn(q, a) }
        }

        val availableTools = listOfNotNull(
            githubWikiTool?.let { "githubWikiSearch" },
            confluenceTool?.let { "confluenceSearch" },
            vectorSearchTool?.let { "vectorSearch" },
        )
        val model = AnthropicModels.Haiku_4_5

        // 1단계: 검색어 결정 (항상 검색 — 예외 없음)
        val decisionPrompt = buildString {
            if (contextHistory.isNotEmpty()) {
                appendLine("=== 이전 대화 ===")
                contextHistory.forEach { t -> appendLine("Q: ${t.question}\nA: ${t.answer.take(150)}...") }
                appendLine()
            }
            appendLine("당신은 검색 라우터입니다. 사용자의 질문을 반드시 아래 도구 중 하나로 검색해야 합니다.")
            appendLine("사용 가능한 도구: ${availableTools.joinToString(", ")}")
            appendLine()
            appendLine("출력 형식 (이 두 줄만 출력, 다른 텍스트 금지):")
            appendLine("TOOL: <도구이름>")
            appendLine("QUERY: <한국어 검색어>")
            appendLine()
            appendLine("규칙:")
            appendLine("- 어떤 질문이든 반드시 검색해야 합니다. 검색하지 않는 경우는 없습니다.")
            appendLine("- TOOL과 QUERY 두 줄만 출력하세요.")
            appendLine()
            appendLine("질문: $question")
        }

        val decision = executor.execute(
            prompt("decision") { user(decisionPrompt) }, model
        ).joinToString("") { it.content }.trim()

        log.info("Search decision: {}", decision.take(150))

        // 2단계: tool 실행 (파싱 실패 시 기본 도구로 원본 질문 검색)
        val toolName = Regex("TOOL:\\s*(\\S+)").find(decision)?.groupValues?.get(1)?.trim()
        if (toolName != null) listener?.onSearchStarted(toolName)
        val searchResult = runCatching { executeFromDecision(decision) }.getOrNull()
            ?: runCatching { executeDefault(question, availableTools) }.getOrNull()
        if (toolName != null) listener?.onSearchCompleted(toolName)
        log.info("Search result: {}", searchResult?.take(100) ?: "none")

        // 3단계: 검색 결과 + 히스토리로 최종 답변
        val summaryPrompt = buildString {
            if (contextHistory.isNotEmpty()) {
                appendLine("=== 이전 대화 ===")
                contextHistory.forEach { t -> appendLine("Q: ${t.question}\nA: ${t.answer.take(200)}...") }
                appendLine()
            }
            appendLine("질문: $question")
            appendLine()
            if (searchResult != null) {
                appendLine("=== 검색 결과 ===")
                appendLine(searchResult)
                appendLine()
                appendLine("위 검색 결과를 바탕으로 요약과 관련 링크를 포함해 답변하세요.")
            } else {
                appendLine("검색 결과가 없습니다. 알고 있는 내용으로 간략히 답변하세요.")
            }
        }

        val answer = executor.execute(
            prompt("summary") { user(summaryPrompt) }, model
        ).joinToString("") { it.content }

        if (sessionId != null && conversationStore != null) {
            conversationStore.append(sessionId, question, answer)
            val summarizer: suspend (String) -> String = { p ->
                executor.execute(prompt("compress") { user(p) }, model).joinToString("") { it.content }
            }
            conversationStore.compress(sessionId, summarizer)
        } else {
            history.addLast(question to answer)
            if (history.size > 5) history.removeFirst()
        }

        return answer
    }

    private fun executeFromDecision(decision: String): String? {
        val toolMatch = Regex("TOOL:\\s*(\\S+)").find(decision) ?: return null
        val queryMatch = Regex("QUERY:\\s*(.+)").find(decision) ?: return null
        val toolName = toolMatch.groupValues[1].trim()
        val query = queryMatch.groupValues[1].trim()

        if (query.isBlank()) return null
        log.info("Executing tool: {} query: {}", toolName, query)
        return when (toolName) {
            "githubWikiSearch" -> githubWikiTool?.githubWikiSearch(query)
            "confluenceSearch" -> confluenceTool?.confluenceSearch(query)
            "vectorSearch" -> vectorSearchTool?.vectorSearch(query)
            else -> null
        }
    }

    private fun executeDefault(question: String, availableTools: List<String>): String? {
        log.info("Fallback: searching with original question using {}", availableTools.firstOrNull())
        return when (availableTools.firstOrNull()) {
            "githubWikiSearch" -> githubWikiTool?.githubWikiSearch(question)
            "confluenceSearch" -> confluenceTool?.confluenceSearch(question)
            "vectorSearch" -> vectorSearchTool?.vectorSearch(question)
            else -> null
        }
    }

    // API 키 사용 시: Koog AIAgent의 네이티브 tool calling 루프
    private suspend fun answerWithKoogAgent(
        question: String,
        listener: SearchProgressListener? = null,
        sessionId: String? = null,
    ): String {
        val contextHistory: List<Turn> = if (sessionId != null && conversationStore != null) {
            conversationStore.load(sessionId)
        } else emptyList()

        val summary = if (sessionId != null && conversationStore != null) {
            conversationStore.loadSummary(sessionId)
        } else null

        val memory = projectMemory?.load()

        val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)
        for ((index, model) in fallbackModels.withIndex()) {
            val result = runCatching { buildAgent(model, contextHistory, listener, summary, memory).run(question) }
            val ex = result.exceptionOrNull()
            if (ex == null) {
                val answer = result.getOrThrow()
                if (sessionId != null && conversationStore != null) {
                    conversationStore.append(sessionId, question, answer)
                    val summarizer: suspend (String) -> String = { p ->
                        executor.execute(prompt("compress") { user(p) }, model).joinToString("") { it.content }
                    }
                    conversationStore.compress(sessionId, summarizer)
                }
                return answer
            }
            if (index < fallbackModels.lastIndex) {
                log.warn("Retrying with {}", fallbackModels[index + 1].id)
                continue
            }
            log.error("All models failed: {}", ex.message)
            return "검색 중 오류가 발생했습니다: ${ex.message}"
        }
        error("unreachable")
    }

    private fun buildAgent(
        model: LLModel,
        history: List<Turn> = emptyList(),
        listener: SearchProgressListener? = null,
        summary: String? = null,
        memory: String? = null,
    ): AIAgent<String, String> {
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
            memory?.let {
                appendLine()
                appendLine("# 프로젝트 정보")
                appendLine(it)
            }
            summary?.let {
                appendLine()
                appendLine("# 이전 대화 요약")
                appendLine(it)
            }
        }

        return AIAgent(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
                    system(systemPrompt)
                    for (turn in history) {
                        user(turn.question)
                        assistant(turn.answer)
                    }
                },
                model = model,
                maxAgentIterations = 10,
            ),
            toolRegistry = ToolRegistry {
                if (confluenceTool != null) tool(confluenceTool::confluenceSearch)
                if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
                if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
            },
            installFeatures = {
                if (listener != null) install(SearchProgressFeature(listener))
            },
        )
    }

    private class SearchProgressFeature(
        private val listener: SearchProgressListener,
    ) : AIAgentGraphFeature<SearchProgressFeature.Config, Unit> {

        class Config : FeatureConfig()

        override val key: AIAgentStorageKey<Unit> = AIAgentStorageKey("wiki-search-progress-${System.nanoTime()}")

        override fun createInitialConfig(agentConfig: AIAgentConfig) = Config()

        override fun install(config: Config, pipeline: AIAgentGraphPipeline): Unit {
            pipeline.interceptToolCallStarting(this) { ctx: ToolCallStartingContext ->
                listener.onSearchStarted(ctx.toolName)
            }
            pipeline.interceptToolCallCompleted(this) { ctx: ToolCallCompletedContext ->
                listener.onSearchCompleted(ctx.toolName)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(OrchestratorAgent::class.java)
    }
}
