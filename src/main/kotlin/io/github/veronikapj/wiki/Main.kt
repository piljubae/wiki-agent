@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki

import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import io.github.veronikapj.wiki.slack.SlackBotGateway
import io.github.veronikapj.wiki.slack.SlackConfigHandler
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wiki.Main")

fun main() {
    val config = ConfigLoader.load()
    log.info("Provider: {}, Spaces: {}", config.model.provider, config.confluence.spaces)

    val confluenceClient = ConfluenceClient(
        baseUrl = config.confluence.baseUrl,
        token = config.confluence.token,
    )

    val searchAgent = ConfluenceSearchAgent(
        confluenceClient = confluenceClient,
        spaces = config.confluence.spaces,
    )

    val confluenceTool = ConfluenceTool(searchAgent)

    val executor = LLMExecutorBuilder.build(config.model)

    val orchestrator = OrchestratorAgent(
        confluenceTool = confluenceTool,
        vectorSearchTool = null,
        executor = executor,
    )

    val configHandler = SlackConfigHandler(
        config = config,
        persistOnChange = true,
    )

    val gateway = SlackBotGateway(
        slackConfig = config.slack,
        orchestrator = orchestrator,
        configHandler = configHandler,
    )

    gateway.start()
}
