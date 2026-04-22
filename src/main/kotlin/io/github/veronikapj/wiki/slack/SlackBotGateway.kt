package io.github.veronikapj.wiki.slack

import com.slack.api.Slack
import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.methods.MethodsClient
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.SearchProgressListener
import io.github.veronikapj.wiki.agent.tool.SourceTracker
import io.github.veronikapj.wiki.config.SlackConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
    private val sourceTracker: SourceTracker,
) {
    private val app = App()
    private val slackClient: MethodsClient = Slack.getInstance().methods(slackConfig.botToken)

    private val toolDisplayNames = mapOf(
        "confluenceSearch" to "Confluence",
        "githubWikiSearch" to "GitHub Wiki",
        "vectorSearch" to "RAG",
    )

    fun start() {
        registerMentionHandler()
        registerDmHandler()
        registerSlashCommand()
        log.info("Starting Slack bot (Socket Mode)...")
        SocketModeApp(slackConfig.appToken, app).start()
    }

    private fun registerMentionHandler() {
        app.event(com.slack.api.model.event.AppMentionEvent::class.java) { payload, ctx ->
            val query = extractQuery(payload.event.text)
            val channel = payload.event.channel
            val threadTs = payload.event.ts
            log.info("Mention received: '{}'", query)

            val client = slackClient

            thread {
                var progressMessageTs: String? = null

                val listener = object : SearchProgressListener {
                    override suspend fun onSearchStarted(toolName: String) {
                        val displayName = toolDisplayNames[toolName] ?: toolName
                        val msg = ":mag: $displayName 검색 중..."
                        try {
                            if (progressMessageTs == null) {
                                val response = client.chatPostMessage { it
                                    .channel(channel)
                                    .threadTs(threadTs)
                                    .text(msg)
                                }
                                progressMessageTs = response.ts
                            } else {
                                client.chatUpdate { it
                                    .channel(channel)
                                    .ts(progressMessageTs)
                                    .text(msg)
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("Failed to send progress message: {}", e.message)
                        }
                    }

                    override suspend fun onSearchCompleted(toolName: String) {}
                }

                try {
                    sourceTracker.reset()
                    val result = runBlocking { orchestrator.answer(query, listener, sessionId = threadTs) }

                    progressMessageTs?.let { ts ->
                        runCatching { client.chatDelete { it.channel(channel).ts(ts) } }
                    }

                    val footer = sourceTracker.formatFooter()
                    val finalText = if (footer.isNotEmpty()) "$result\n\n$footer" else result

                    val sendResult = client.chatPostMessage { it
                        .channel(channel)
                        .threadTs(threadTs)
                        .text(finalText)
                    }
                    if (sendResult.isOk) {
                        log.info("Mention reply sent to {}", channel)
                    } else {
                        log.error("Slack send failed: error={}, needed={}", sendResult.error, sendResult.needed)
                    }
                } catch (e: Exception) {
                    log.error("Failed to process mention: {}", e.message, e)
                    client.chatPostMessage { it
                        .channel(channel)
                        .threadTs(threadTs)
                        .text("오류가 발생했습니다: ${e.message}")
                    }
                }
            }

            ctx.ack()
        }
    }

    private fun registerDmHandler() {
        app.event(com.slack.api.model.event.MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (event.channelType != "im" || event.botId != null || event.subtype != null) {
                return@event ctx.ack()
            }

            val query = event.text?.trim() ?: return@event ctx.ack()
            if (query.isBlank()) return@event ctx.ack()
            val channel = event.channel
            log.info("DM received: '{}'", query)

            val client = slackClient

            thread {
                var progressMessageTs: String? = null

                val listener = object : SearchProgressListener {
                    override suspend fun onSearchStarted(toolName: String) {
                        val displayName = toolDisplayNames[toolName] ?: toolName
                        val msg = ":mag: $displayName 검색 중..."
                        try {
                            if (progressMessageTs == null) {
                                val response = client.chatPostMessage { it
                                    .channel(channel)
                                    .text(msg)
                                }
                                progressMessageTs = response.ts
                            } else {
                                client.chatUpdate { it
                                    .channel(channel)
                                    .ts(progressMessageTs)
                                    .text(msg)
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("Failed to send progress message: {}", e.message)
                        }
                    }

                    override suspend fun onSearchCompleted(toolName: String) {}
                }

                try {
                    sourceTracker.reset()
                    val result = runBlocking { orchestrator.answer(query, listener, sessionId = "dm-$channel") }

                    progressMessageTs?.let { ts ->
                        runCatching { client.chatDelete { it.channel(channel).ts(ts) } }
                    }

                    val footer = sourceTracker.formatFooter()
                    val finalText = if (footer.isNotEmpty()) "$result\n\n$footer" else result

                    client.chatPostMessage { it
                        .channel(channel)
                        .text(finalText)
                    }
                    log.info("DM reply sent to {}", channel)
                } catch (e: Exception) {
                    log.error("Failed to process DM: {}", e.message, e)
                    client.chatPostMessage { it
                        .channel(channel)
                        .text("오류가 발생했습니다: ${e.message}")
                    }
                }
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
