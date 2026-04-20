package io.github.veronikapj.wiki.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.config.SlackConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
) {
    private val app = App()

    fun start() {
        registerMentionHandler()
        registerSlashCommand()
        log.info("Starting Slack bot (Socket Mode)...")
        SocketModeApp(slackConfig.appToken, app).start()
    }

    private fun registerMentionHandler() {
        app.event(com.slack.api.model.event.AppMentionEvent::class.java) { payload, ctx ->
            val query = extractQuery(payload.event.text)
            log.info("Mention received: '{}'", query)
            ctx.asyncClient().chatPostMessage { it
                .channel(payload.event.channel)
                .threadTs(payload.event.ts)
                .text(":mag: 검색 중...")
            }
            val result = runBlocking { orchestrator.answer(query) }
            ctx.asyncClient().chatPostMessage { it
                .channel(payload.event.channel)
                .threadTs(payload.event.ts)
                .text(result)
            }
            ctx.ack()
        }
    }

    private fun registerSlashCommand() {
        app.command("/wiki") { req, ctx ->
            val fullCommand = "/wiki ${req.payload.text}"
            val result = configHandler.handle(fullCommand)
            ctx.ack(result)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackBotGateway::class.java)

        fun extractQuery(text: String): String =
            text.replace(Regex("<@[A-Z0-9]+>"), "").trim()
    }
}
