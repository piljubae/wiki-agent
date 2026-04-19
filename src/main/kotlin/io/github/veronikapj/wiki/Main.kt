@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki

import ai.koog.prompt.dsl.prompt
import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.GoogleEmbeddingClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import io.github.veronikapj.wiki.rag.VectorIndexAgent
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.github.veronikapj.wiki.slack.SlackBotGateway
import io.github.veronikapj.wiki.slack.SlackConfigHandler
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wiki.Main")

fun main() {
    val config = ConfigLoader.load()
    log.info("Provider: {}, Spaces: {}, RAG: {}", config.model.provider, config.confluence.spaces, config.rag.enabled)

    // 시크릿 로드 (env → .env → config 폴백)
    val confluenceToken = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
    val slackBotToken = SecretLoader.resolve("SLACK_BOT_TOKEN", config.slack.botToken)
    val slackAppToken = SecretLoader.resolve("SLACK_APP_TOKEN", config.slack.appToken)
    val resolvedModelApiKey = when (config.model.provider) {
        io.github.veronikapj.wiki.config.ModelProvider.ANTHROPIC ->
            SecretLoader.resolveNullable("ANTHROPIC_API_KEY", config.model.apiKey)
        io.github.veronikapj.wiki.config.ModelProvider.GOOGLE ->
            SecretLoader.resolveNullable("GOOGLE_API_KEY", config.model.apiKey)
        else -> config.model.apiKey
    }
    val resolvedModelConfig = config.model.copy(apiKey = resolvedModelApiKey)

    val confluenceClient = ConfluenceClient(
        baseUrl = config.confluence.baseUrl,
        token = confluenceToken,
    )

    val executor = LLMExecutorBuilder.build(resolvedModelConfig)
    val model = LLMExecutorBuilder.defaultModel(resolvedModelConfig)

    val confluenceSearchAgent = ConfluenceSearchAgent(
        confluenceClient = confluenceClient,
        spaces = config.confluence.spaces,
    )
    val confluenceTool = ConfluenceTool(confluenceSearchAgent)

    var vectorSearchTool: VectorSearchTool? = null
    var vectorIndexAgent: VectorIndexAgent? = null

    if (config.rag.enabled) {
        val chromaClient = ChromaClient(config.rag.chromaUrl)
        val googleApiKey = SecretLoader.resolveNullable("GOOGLE_API_KEY", config.rag.googleApiKey)
        val llmFn: suspend (String) -> String = { userPrompt ->
            executor.execute(
                prompt("llm") { user(userPrompt) }, model
            ).joinToString("") { it.content }
        }
        val llmExpandClient = LlmExpandClient(llmFn)
        val googleEmbeddingClient = if (config.rag.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING)
            GoogleEmbeddingClient(requireNotNull(googleApiKey) { "GOOGLE_API_KEY required for GOOGLE_EMBEDDING mode" })
        else null

        val vectorSearchAgent = VectorSearchAgent(chromaClient, llmExpandClient, googleEmbeddingClient, config.rag)
        vectorSearchTool = VectorSearchTool(vectorSearchAgent)
        vectorIndexAgent = VectorIndexAgent(
            confluenceClient, chromaClient, llmExpandClient, googleEmbeddingClient, config.rag, config.confluence.spaces
        )
        log.info("RAG enabled (mode={})", config.rag.embeddingMode)
    }

    val orchestrator = OrchestratorAgent(confluenceTool, vectorSearchTool, executor)

    val configHandler = SlackConfigHandler(
        config = config,
        persistOnChange = true,
        onReindex = vectorIndexAgent?.let { agent -> { agent.indexAll() } },
    )

    val gateway = SlackBotGateway(
        slackConfig = config.slack.copy(botToken = slackBotToken, appToken = slackAppToken),
        orchestrator = orchestrator,
        configHandler = configHandler,
    )

    gateway.start()
}
