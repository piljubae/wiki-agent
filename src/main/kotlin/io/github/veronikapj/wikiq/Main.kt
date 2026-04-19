@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wikiq

import io.github.veronikapj.wikiq.agent.ConfluenceSearchAgent
import io.github.veronikapj.wikiq.config.ConfigLoader
import io.github.veronikapj.wikiq.confluence.ConfluenceClient
import io.github.veronikapj.wikiq.llm.LLMExecutorBuilder
import io.github.veronikapj.wikiq.slack.SlackBotGateway
import io.github.veronikapj.wikiq.slack.SlackConfigHandler
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wikiq.Main")

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

    val configHandler = SlackConfigHandler(
        config = config,
        persistOnChange = true,
    )

    val gateway = SlackBotGateway(
        slackConfig = config.slack,
        searchAgent = searchAgent,
        configHandler = configHandler,
    )

    gateway.start()
}
