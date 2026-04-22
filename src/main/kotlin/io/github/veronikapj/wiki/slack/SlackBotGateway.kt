package io.github.veronikapj.wiki.slack

import com.slack.api.Slack
import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.methods.MethodsClient
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.SearchProgressListener
import io.github.veronikapj.wiki.config.SlackConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
) {
    private val app = App()
    private val slackClient: MethodsClient = Slack.getInstance().methods(slackConfig.botToken)
    private val messageExecutor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(20),
    )

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
            if (!handleQueryAsync(channel = channel, threadTs = threadTs, sessionId = threadTs, query = query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text("요청이 많아 잠시 후 다시 시도해주세요.") }
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
            if (!handleQueryAsync(channel = channel, threadTs = null, sessionId = "dm-$channel", query = query)) {
                slackClient.chatPostMessage { it.channel(channel).text("요청이 많아 잠시 후 다시 시도해주세요.") }
            }
            ctx.ack()
        }
    }

    private fun handleQueryAsync(channel: String, threadTs: String?, sessionId: String, query: String): Boolean {
        return try {
        messageExecutor.submit {
            var progressMessageTs: String? = null
            val searchedTools = mutableListOf<String>()

            val listener = object : SearchProgressListener {
                override suspend fun onSearchStarted(toolName: String) {
                    searchedTools.add(toolName)
                    val displayName = toolDisplayNames[toolName] ?: toolName
                    val msg = ":mag: $displayName 검색 중..."
                    try {
                        if (progressMessageTs == null) {
                            val response = slackClient.chatPostMessage { req ->
                                req.channel(channel).text(msg).let { b ->
                                    if (threadTs != null) b.threadTs(threadTs) else b
                                }
                            }
                            progressMessageTs = response.ts
                        } else {
                            slackClient.chatUpdate { req ->
                                req.channel(channel).ts(progressMessageTs).text(msg)
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to send progress message: {}", e.message)
                    }
                }

                override suspend fun onSearchCompleted(toolName: String) {}
            }

            try {
                val result = runBlocking { orchestrator.answer(query, listener, sessionId = sessionId) }

                progressMessageTs?.let { ts ->
                    runCatching { slackClient.chatDelete { it.channel(channel).ts(ts) } }
                }

                val footer = if (searchedTools.isNotEmpty())
                    "📋 " + searchedTools.distinct().joinToString(" · ") { toolDisplayNames[it] ?: it }
                else ""
                val finalText = if (footer.isNotEmpty()) "$result\n\n$footer" else result

                val sendResult = slackClient.chatPostMessage { req ->
                    req.channel(channel).text(finalText).let { b ->
                        if (threadTs != null) b.threadTs(threadTs) else b
                    }
                }
                if (sendResult.isOk) {
                    log.info("Reply sent to channel={} thread={}", channel, threadTs)
                } else {
                    log.error("Slack send failed: error={}, needed={}", sendResult.error, sendResult.needed)
                }
            } catch (e: Exception) {
                log.error("Failed to process query: {}", e.message, e)
                slackClient.chatPostMessage { req ->
                    req.channel(channel).text("오류가 발생했습니다: ${e.message}").let { b ->
                        if (threadTs != null) b.threadTs(threadTs) else b
                    }
                }
            }
        }
        true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            log.warn("Request queue full — rejected: channel={} thread={}", channel, threadTs)
            false
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
