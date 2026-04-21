package io.github.veronikapj.wiki.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.SearchProgressListener
import io.github.veronikapj.wiki.agent.tool.SourceTracker
import io.github.veronikapj.wiki.config.SlackConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
    private val sourceTracker: SourceTracker,
) {
    private val app = App()

    private val toolDisplayNames = mapOf(
        "confluenceSearch" to "Confluence",
        "githubWikiSearch" to "GitHub Wiki",
        "vectorSearch" to "RAG",
    )

    fun start() {
        registerMentionHandler()
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

            var progressMessageTs: String? = null

            val listener = object : SearchProgressListener {
                override suspend fun onSearchStarted(toolName: String) {
                    val displayName = toolDisplayNames[toolName] ?: toolName
                    val msg = ":mag: $displayName 검색 중..."
                    try {
                        if (progressMessageTs == null) {
                            val response = ctx.client().chatPostMessage { it
                                .channel(channel)
                                .threadTs(threadTs)
                                .text(msg)
                            }
                            progressMessageTs = response.ts
                        } else {
                            ctx.client().chatUpdate { it
                                .channel(channel)
                                .ts(progressMessageTs)
                                .text(msg)
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to send progress message: {}", e.message)
                    }
                }

                override suspend fun onSearchCompleted(toolName: String) {
                    // SourceTracker handles recording — no action needed here
                }
            }

            sourceTracker.reset()
            val result = runBlocking { orchestrator.answer(query, listener, sessionId = threadTs) }

            // Delete intermediate message
            progressMessageTs?.let { ts ->
                runCatching {
                    ctx.client().chatDelete { it.channel(channel).ts(ts) }
                }.onFailure { log.warn("Failed to delete progress message: {}", it.message) }
            }

            // Final answer with footer
            val footer = sourceTracker.formatFooter()
            val finalText = if (footer.isNotEmpty()) "$result\n\n$footer" else result

            ctx.client().chatPostMessage { it
                .channel(channel)
                .threadTs(threadTs)
                .text(finalText)
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
