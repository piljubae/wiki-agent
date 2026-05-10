package io.github.veronikapj.wiki.slack

import com.slack.api.Slack
import com.slack.api.bolt.App
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.methods.MethodsClient
import com.slack.api.model.event.AppHomeOpenedEvent
import com.slack.api.model.event.AssistantThreadStartedEvent
import com.slack.api.model.event.AssistantThreadContextChangedEvent
import com.slack.api.model.event.MessageChangedEvent
import com.slack.api.model.assistant.SuggestedPrompt
import com.slack.api.model.block.Blocks.*
import com.slack.api.model.block.composition.BlockCompositions.*
import com.slack.api.model.block.element.BlockElements.*
import com.slack.api.model.view.Views.*
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.SearchProgressListener
import io.github.veronikapj.wiki.agent.QueryRewriter
import io.github.veronikapj.wiki.config.SlackConfig
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.context.ProjectMemory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
    private val projectMemory: ProjectMemory? = null,
    private val confluenceClient: ConfluenceClient? = null,
    private val feedbackStore: FeedbackStore = FeedbackStore(),
    private val queryRewriter: QueryRewriter? = null,
) {
    private val app = App()
    private val slackClient: MethodsClient = Slack.getInstance().methods(slackConfig.botToken)
    private val messageExecutor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(20),
    )

    @Volatile var lastCodeIndexedAt: Instant? = null
    @Volatile var lastConfluenceIndexedAt: Instant? = null

    // 버튼 클릭 후 해당 스레드에서 라우터를 스킵하고 지정 툴로 직행
    private val threadForcedTool = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val toolDisplayNames = mapOf(
        "knowledgeSearch" to "지식베이스",
        "confluenceSearch" to "Confluence",
        "githubWikiSearch" to "GitHub Wiki",
        "vectorSearch" to "RAG",
        "prHistory" to "PR 이력",
        "codeSearch" to "코드 검색",
    )

    // 온보딩 상태: 채널별 진행 단계
    private val onboardingState = ConcurrentHashMap<String, Int>()
    private val needsOnboarding: Boolean get() = projectMemory?.load() == null

    // 스페이스 목록 캐시 (온보딩 시 1회 조회)
    private var cachedSpaces: List<ConfluenceClient.SpaceInfo>? = null
    private fun getSpaces(): List<ConfluenceClient.SpaceInfo> {
        cachedSpaces?.let { return it }
        val spaces = confluenceClient?.let { runBlocking { it.listSpaces() } } ?: emptyList()
        cachedSpaces = spaces
        return spaces
    }

    private fun isOnboarding(channel: String): Boolean =
        needsOnboarding || onboardingState.containsKey(channel)

    private fun postToThread(channel: String, threadTs: String?, msg: String) {
        slackClient.chatPostMessage { req ->
            req.channel(channel).text(msg).let { b -> if (threadTs != null) b.threadTs(threadTs) else b }
        }
    }

    private fun handleOnboarding(channel: String, threadTs: String?, text: String) {
        val step = onboardingState.getOrPut(channel) { 0 }
        when (step) {
            0 -> {
                // Step 0: 팀/조직 질문
                val msg = buildString {
                    appendLine("안녕하세요! :wave: 위키 검색 봇입니다.")
                    appendLine("더 정확한 검색을 위해 몇 가지 알려주세요.")
                    appendLine()
                    appendLine("*1/4* 이 팀/조직의 이름과 역할은 무엇인가요?")
                    appendLine("예: _모바일 앱(iOS/Android) 개발팀, 프로덕트앱개발 트라이브_")
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 1
            }
            1 -> {
                // Step 1: 팀 저장 + 도메인 용어 질문
                projectMemory?.add("팀/조직: $text")
                val msg = buildString {
                    appendLine(":white_check_mark:")
                    appendLine()
                    appendLine("*2/4* 자주 사용하는 도메인 용어 중 일반적 의미와 다른 것이 있나요?")
                    appendLine("예: _클라이언트 = 모바일 앱(고객이 아님), PR = Pull Request_")
                    appendLine("없으면 '없음'이라고 답해주세요.")
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 2
            }
            2 -> {
                // Step 2: 도메인 용어 저장 + 검색 대상 질문
                if (text != "없음") projectMemory?.add("도메인 용어: $text")
                val msg = buildString {
                    appendLine(":white_check_mark:")
                    appendLine()
                    appendLine("*3/4* 주로 어떤 정보를 검색하실 건가요?")
                    appendLine("예: _온보딩 가이드, 배포 프로세스, 기술 문서, 회의록_")
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 3
            }
            3 -> {
                // Step 3: 검색 대상 저장 + 스페이스 선택 (팀 정보 기반 추천)
                projectMemory?.add("주요 검색 대상: $text")
                val allSpaces = getSpaces()
                val memoryText = projectMemory?.load()?.lowercase() ?: ""
                val keywords = memoryText.split(" ", ",", "/", "(", ")", "\n", "-", "=")
                    .map { it.trim() }.filter { it.length >= 2 }.toSet()

                val msg = buildString {
                    appendLine(":white_check_mark:")
                    appendLine()
                    appendLine("*4/4* 검색할 Confluence 스페이스를 선택해주세요.")
                    if (allSpaces.isNotEmpty()) {
                        // collaboration 타입 중 팀 정보와 매칭되는 것 = 추천
                        val collabSpaces = allSpaces.filter { it.type == "collaboration" }
                        val recommended = collabSpaces.filter { space ->
                            val lower = (space.key + " " + space.name).lowercase()
                            keywords.any { lower.contains(it) }
                        }
                        val otherCollab = collabSpaces - recommended.toSet()

                        if (recommended.isNotEmpty()) {
                            appendLine()
                            appendLine(":star: 추천 (팀 정보 기반):")
                            recommended.forEach { appendLine("• `${it.key}` — ${it.name}") }
                        }
                        if (otherCollab.isNotEmpty()) {
                            appendLine()
                            appendLine("기타 팀 스페이스:")
                            otherCollab.forEach { appendLine("• `${it.key}` — ${it.name}") }
                        }
                        appendLine()
                        appendLine("추가할 스페이스 키를 쉼표로 구분해 입력하세요.")
                        appendLine("_위 목록 외 글로벌 스페이스도 키를 직접 입력하면 추가됩니다._")
                    } else {
                        appendLine("스페이스 목록을 불러올 수 없습니다. 직접 입력해주세요.")
                        appendLine("예: _ProductApp, ClientDivision_")
                    }
                }
                postToThread(channel, threadTs, msg)
                onboardingState[channel] = 4
            }
            4 -> {
                // Step 4: 스페이스 저장 + 완료
                val selectedSpaces = text.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val allKeys = getSpaces().map { it.key }.toSet()
                val valid = selectedSpaces.filter { it in allKeys }
                val invalid = selectedSpaces - valid.toSet()
                if (invalid.isNotEmpty()) {
                    val msg = ":warning: 다음 스페이스 키가 유효하지 않습니다: ${invalid.joinToString(", ")}\n다시 입력해주세요."
                    postToThread(channel, threadTs, msg)
                    return // step 4 유지, 재입력 대기
                }
                if (valid.isNotEmpty()) {
                    projectMemory?.add("검색 스페이스: ${valid.joinToString(", ")}")
                }
                val msg = buildString {
                    appendLine("설정 완료! :tada: 스페이스: ${selectedSpaces.joinToString(", ")}")
                    appendLine()
                    appendLine("이제 질문하시면 Confluence에서 검색해 답변드립니다.")
                    appendLine("이 스레드에서 바로 질문하거나, 채널에서 멘션해주세요.")
                    appendLine()
                    appendLine("설정은 `/askpj memory show`로 확인, `/askpj memory add <내용>`으로 추가할 수 있습니다.")
                }
                postToThread(channel, threadTs, msg)
                onboardingState.remove(channel)
                log.info("Onboarding completed for channel={}", channel)
            }
        }
    }

    fun start() {
        registerMentionHandler()
        registerAssistantHandler()
        registerReactionHandler()
        registerSlashCommand()
        registerHomeHandler()
        log.info("Starting Slack bot (Socket Mode)...")
        SocketModeApp(slackConfig.appToken, app).start()
    }

    private fun registerMentionHandler() {
        app.event(com.slack.api.model.event.AppMentionEvent::class.java) { payload, ctx ->
            val query = extractQuery(payload.event.text)
            val channel = payload.event.channel
            val threadTs = payload.event.ts
            log.info("Mention received: '{}'", query)
            if (isHelpQuery(query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text(configHandler.helpMessage()) }
            } else if (isOnboarding(channel)) {
                log.info("Onboarding step for channel={}", channel)
                runCatching { handleOnboarding(channel, threadTs, query) }
                    .onFailure { log.error("Onboarding error: {}", it.message, it) }
            } else if (!handleQueryAsync(channel = channel, threadTs = threadTs, sessionId = threadTs, query = query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text("요청이 많아 잠시 후 다시 시도해주세요.") }
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

                val footer = buildString {
                    if (searchedTools.isNotEmpty()) {
                        append("\uD83D\uDCCB ")
                        append(searchedTools.distinct().joinToString(" · ") { toolDisplayNames[it] ?: it })
                    }
                    append("\n$FEEDBACK_GUIDE")
                }
                val finalText = "$result\n\n$footer"

                val sendResult = slackClient.chatPostMessage { req ->
                    req.channel(channel).text(finalText).let { b ->
                        if (threadTs != null) b.threadTs(threadTs) else b
                    }
                }
                if (sendResult.isOk) {
                    log.info("Reply sent to channel={} thread={}", channel, threadTs)
                    sendResult.ts?.let { ts ->
                        val entry = FeedbackEntry(
                            query = query,
                            answer = result,
                            usedTools = searchedTools.distinct(),
                            ts = ts,
                        )
                        feedbackStore.save(ts, entry)
                    }
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

    private fun registerReactionHandler() {
        app.event(com.slack.api.model.event.ReactionAddedEvent::class.java) { payload, ctx ->
            val event = payload.event
            val reaction = event.reaction
            val messageTs = event.item.ts

            when {
                reaction in FEEDBACK_REACTIONS && feedbackStore.get(messageTs) != null -> {
                    feedbackStore.saveReaction(messageTs, reaction)
                    log.info("Feedback saved: reaction={}, ts={}", reaction, messageTs)
                }
                reaction in RETRY_REACTIONS && feedbackStore.get(messageTs) != null -> {
                    val channel = event.item.channel
                    messageExecutor.submit {
                        triggerRequery(messageTs, channel, threadTs = messageTs)
                    }
                }
            }
            ctx.ack()
        }
    }

    private fun triggerRequery(messageTs: String, channel: String, threadTs: String) {
        val entry = feedbackStore.get(messageTs) ?: run {
            log.warn("triggerRequery: entry not found for ts={}", messageTs)
            return
        }

        // Stage 상한: 최대 2회 재검색 — 원자적 증가로 TOCTOU race 방지
        val stage = feedbackStore.incrementStageIfBelow(messageTs, maxStage = 2) ?: run {
            log.info("Max requery stage reached for ts={}, skipping", messageTs)
            slackClient.chatPostMessage { req ->
                req.channel(channel).threadTs(threadTs)
                    .text(":pray: 이미 여러 방식으로 찾아봤어요. 질문을 다르게 표현해서 다시 시도해보세요.")
            }
            return
        }

        log.info("Requery stage={} for query='{}'", stage, entry.query)

        slackClient.chatPostMessage { req ->
            req.channel(channel).threadTs(threadTs).text(":arrows_counterclockwise: 다시 찾아볼게요...")
        }

        val forceAllTools = stage >= 2

        val (bm25Query, vectorQuery) = if (!forceAllTools && queryRewriter != null) {
            runBlocking {
                val rewritten = queryRewriter.rewrite(entry.query, entry.usedTools)
                rewritten.bm25 to rewritten.vector
            }
        } else {
            entry.query to entry.query
        }

        val combinedQuery = if (vectorQuery.isNotBlank() && vectorQuery != bm25Query)
            "$bm25Query\n$vectorQuery" else bm25Query

        if (combinedQuery.isBlank()) {
            log.warn("QueryRewriter returned empty query for ts={}, falling back to original query", messageTs)
            val fallbackResult = runBlocking {
                orchestrator.answer(entry.query, sessionId = "requery-$messageTs", forceAllTools = forceAllTools)
            }
            val reply = ":repeat: 다른 방식으로 찾아봤어요\n\n$fallbackResult"
            slackClient.chatPostMessage { req -> req.channel(channel).threadTs(threadTs).text(reply) }
            feedbackStore.saveRequery(ts = messageTs, requeryBm25 = "", requeryVec = "", requeryAnswer = fallbackResult, stage = stage)
            return
        }

        val result = runBlocking {
            orchestrator.answer(combinedQuery, sessionId = "requery-$messageTs", forceAllTools = forceAllTools)
        }

        val reply = ":repeat: 다른 방식으로 찾아봤어요\n\n$result"
        slackClient.chatPostMessage { req ->
            req.channel(channel).threadTs(threadTs).text(reply)
        }

        feedbackStore.saveRequery(
            ts = messageTs,
            requeryBm25 = bm25Query,
            requeryVec = vectorQuery,
            requeryAnswer = result,
            stage = stage,
        )
    }

    private fun registerSlashCommand() {
        app.command("/askpj") { req, ctx ->
            val fullCommand = "/wiki ${req.payload.text}"
            val result = configHandler.handle(fullCommand)
            ctx.ack(result)
        }
    }

    private fun registerHomeHandler() {
        app.event(AppHomeOpenedEvent::class.java) { payload, ctx ->
            val userId = payload.event.user
            val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

            val codeStatus = lastCodeIndexedAt?.let { fmt.format(it) } ?: "미실행"
            val confluenceStatus = lastConfluenceIndexedAt?.let { fmt.format(it) } ?: "미실행"
            val spaces = projectMemory?.load()
                ?.lines()
                ?.firstOrNull { it.contains("검색 스페이스") }
                ?.substringAfter("검색 스페이스:")?.trim()
                ?: "미설정"

            val view = view { v ->
                v.type("home").blocks(
                    listOf(
                        header { h -> h.text(plainText("Wiki 검색 봇", true)) },
                        section { s ->
                            s.text(markdownText("Confluence · GitHub Wiki · 코드베이스를 AI 패널에서 검색하세요."))
                        },
                        divider(),
                        section { s ->
                            s.text(markdownText(
                                "*상태*\n" +
                                "코드 인덱싱: `$codeStatus`\n" +
                                "Confluence 인덱싱: `$confluenceStatus`\n" +
                                "검색 스페이스: `$spaces`"
                            ))
                        },
                        divider(),
                        section { s -> s.text(markdownText("*빠른 액션*")) },
                        actions { a ->
                            a.elements(
                                listOf(
                                    button { b ->
                                        b.text(plainText("코드 재인덱싱", true))
                                            .actionId("home_reindex_code")
                                            .value("reindex-code")
                                    },
                                    button { b ->
                                        b.text(plainText("Confluence 재인덱싱", true))
                                            .actionId("home_reindex")
                                            .value("reindex")
                                    },
                                    button { b ->
                                        b.text(plainText("메모리 보기", true))
                                            .actionId("home_memory_show")
                                            .value("memory show")
                                    },
                                )
                            )
                        },
                        divider(),
                        section { s ->
                            s.text(markdownText(
                                "*사용법*\n" +
                                "• Slack 좌측 AI 패널에서 질문하세요\n" +
                                "• URL 인제스트: `/askpj ingest <URL>`\n" +
                                "• 관리 명령: `/askpj reindex-code` | `/askpj reindex`\n" +
                                "• 피드백: :thumbsup: 도움됨 | :thumbsdown: 아쉬움 | :repeat: 재검색"
                            ))
                        },
                    )
                )
            }

            runCatching {
                slackClient.viewsPublish { req -> req.userId(userId).view(view) }
            }.onFailure { log.error("Failed to publish home view: {}", it.message) }

            ctx.ack()
        }

        // 홈 탭 버튼 액션 핸들러
        app.blockAction("home_reindex_code") { req, ctx ->
            val userId = req.payload.user.id
            messageExecutor.submit {
                val result = configHandler.handle("/wiki reindex-code")
                if (!result.contains("비활성화")) lastCodeIndexedAt = Instant.now()
                slackClient.chatPostMessage { it.channel(userId).text(result) }
            }
            ctx.ack()
        }

        app.blockAction("home_reindex") { req, ctx ->
            val userId = req.payload.user.id
            messageExecutor.submit {
                val result = configHandler.handle("/wiki reindex")
                if (!result.contains("비활성화")) lastConfluenceIndexedAt = Instant.now()
                slackClient.chatPostMessage { it.channel(userId).text(result) }
            }
            ctx.ack()
        }

        app.blockAction("home_memory_show") { req, ctx ->
            val userId = req.payload.user.id
            val result = configHandler.handle("/wiki memory show")
            slackClient.chatPostMessage { it.channel(userId).text(result) }
            ctx.ack()
        }
    }

    private fun registerAssistantHandler() {
        // 1) 패널 열릴 때 — 추천 프롬프트 세팅
        app.event(AssistantThreadStartedEvent::class.java) { payload, ctx ->
            val thread = payload.event.assistantThread
            val channelId = thread.channelId
            val threadTs = thread.threadTs
            log.info("AssistantThreadStarted: channel={} thread={}", channelId, threadTs)
            runCatching {
                val resp = slackClient.assistantThreadsSetSuggestedPrompts { req ->
                    req.channelId(channelId)
                        .threadTs(threadTs)
                        .title("무엇을 찾아드릴까요? (전체 도움말: \"도움말\" 입력)")
                        .prompts(SUGGESTED_PROMPTS)
                }
                if (!resp.isOk) log.warn("setSuggestedPrompts failed: {}", resp.error)
                else log.info("setSuggestedPrompts ok ({}개)", SUGGESTED_PROMPTS.size)
            }.onFailure { log.warn("Failed to set suggested prompts: {}", it.message) }
            ctx.ack()
        }

        // 2) 채널 컨텍스트 변경 — 무시
        app.event(AssistantThreadContextChangedEvent::class.java) { _, ctx ->
            ctx.ack()
        }

        // 메시지 편집 이벤트 — 무시 (핸들러 없으면 404 경고 발생)
        app.event(MessageChangedEvent::class.java) { _, ctx ->
            ctx.ack()
        }

        // 3) 사용자 메시지 — orchestrator로 라우팅
        app.event(com.slack.api.model.event.MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            // 새 사용자 메시지만 처리. 메시지 편집(message_changed), 삭제, 봇 메시지는 무시.
            if (event.channelType != "im" || event.botId != null || event.subtype != null) {
                return@event ctx.ack()
            }
            val query = extractQuery(event.text?.trim() ?: return@event ctx.ack())
            if (query.isBlank()) return@event ctx.ack()

            val channel = event.channel
            val threadTs = event.threadTs ?: event.ts

            log.info("Assistant message received: '{}'", query.take(80))

            // canned 먼저 — "도움말" exact match도 CANNED_RESPONSES에서 처리
            val canned = CANNED_RESPONSES[query.lowercase()]
                ?: CANNED_RESPONSES[query]
            if (canned != null) {
                HINT_FORCED_TOOL[query]?.let { threadForcedTool[threadTs] = it }
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text(canned) }
                return@event ctx.ack()
            }

            // 변형 표현 (사용법 알려줘, 어떻게 써 등) → helpMessage() fallback
            if (isHelpQuery(query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text(
                    CANNED_RESPONSES["도움말"] ?: configHandler.helpMessage()
                )}
                return@event ctx.ack()
            }

            val forcedTool = threadForcedTool[threadTs]
            if (!handleAssistantQueryAsync(channel, threadTs, query, forcedTool)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text("요청이 많아 잠시 후 다시 시도해주세요.") }
            }
            ctx.ack()
        }
    }

    private fun handleAssistantQueryAsync(channel: String, threadTs: String, query: String, forcedTool: String? = null): Boolean {
        return try {
            messageExecutor.submit {
                val searchedTools = mutableListOf<String>()

                val listener = object : SearchProgressListener {
                    override suspend fun onSearchStarted(toolName: String) {
                        searchedTools.add(toolName)
                        val displayName = toolDisplayNames[toolName] ?: toolName
                        runCatching {
                            slackClient.assistantThreadsSetStatus { req ->
                                req.channelId(channel).threadTs(threadTs).status("🔍 $displayName 검색 중...")
                            }
                        }.onFailure { log.warn("Failed to set assistant status: {}", it.message) }
                    }
                    override suspend fun onSearchCompleted(toolName: String) {}
                }

                try {
                    val result = runBlocking {
                        orchestrator.answer(query, listener, sessionId = "assistant-$threadTs", forceTool = forcedTool)
                    }

                    val footer = buildString {
                        if (searchedTools.isNotEmpty()) {
                            append("\uD83D\uDCCB ")
                            append(searchedTools.distinct().joinToString(" · ") { toolDisplayNames[it] ?: it })
                        }
                        append("\n$FEEDBACK_GUIDE")
                    }

                    val sendResult = slackClient.chatPostMessage { req ->
                        req.channel(channel).threadTs(threadTs).text("$result\n\n$footer")
                    }

                    if (sendResult.isOk) {
                        sendResult.ts?.let { ts ->
                            feedbackStore.save(ts, FeedbackEntry(query, result, searchedTools.distinct(), ts))
                        }
                    } else {
                        log.error("Slack send failed: {}", sendResult.error)
                    }
                } catch (e: Exception) {
                    log.error("Failed to process assistant query: {}", e.message, e)
                    slackClient.chatPostMessage { req ->
                        req.channel(channel).threadTs(threadTs).text("오류가 발생했습니다: ${e.message}")
                    }
                } finally {
                    runCatching {
                        slackClient.assistantThreadsSetStatus { req ->
                            req.channelId(channel).threadTs(threadTs).status("")
                        }
                    }.onFailure { log.warn("Failed to clear assistant status: {}", it.message) }
                }
            }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            log.warn("Request queue full — rejected: channel={} thread={}", channel, threadTs)
            runCatching {
                slackClient.assistantThreadsSetStatus { req ->
                    req.channelId(channel).threadTs(threadTs).status("")
                }
            }
            false
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackBotGateway::class.java)

        // SuggestedPrompt.message = 버튼 클릭 시 유저 메시지로 표시되는 텍스트
        // CANNED_RESPONSES 키와 exact match하여 LLM 없이 즉시 응답
        // HINT_FORCED_TOOL: 힌트 트리거 → 해당 스레드에서 라우터 스킵하고 지정 툴로 직행
        private val SUGGESTED_PROMPTS = listOf(
            SuggestedPrompt.builder().title("Confluence에서 검색").message("Confluence 검색 예시 보여줘").build(),
            SuggestedPrompt.builder().title("코드에서 찾기").message("코드 검색 예시 보여줘").build(),
            SuggestedPrompt.builder().title("PR 히스토리 보기").message("PR 검색 예시 보여줘").build(),
            SuggestedPrompt.builder().title("종합 검색 (문서+코드)").message("종합 검색 예시 보여줘").build(),
        )

        val HINT_FORCED_TOOL = mapOf(
            "Confluence 검색 예시 보여줘" to "confluenceSearch",
            "코드 검색 예시 보여줘" to "codeSearch",
            "PR 검색 예시 보여줘" to "prHistory",
            "종합 검색 예시 보여줘" to "confluenceSearch+codeSearch",
        )

        val CANNED_RESPONSES = mapOf(
            "Confluence 검색 예시 보여줘" to """
                :confluence: *Confluence 문서 검색 예시*

                자연어로 질문하면 됩니다:

                *실제 검색 결과 예시:*
                • `온보딩 가이드 알려줘`
                  → _신규 입사자 온보딩_ (ProductApp 스페이스) — 권한 요청, 개발 환경 세팅, Wifi 설정 등 포함

                • `배포 프로세스 알려줘`
                  → _웹 정기 배포 프로세스_ — Code Freeze 목요일 17:00, Pre-Prod 배포 목요일 17:30, 운영 배포 월요일 11:00

                • `Android 코드 컨벤션 문서 찾아줘`
                • `KMA-1234 관련 기획 문서 있어?`
                • `최근 회의록 공유해줘`

                검색 스페이스: ProductApp · project · ClientDivision · PSD
            """.trimIndent(),

            "코드 검색 예시 보여줘" to """
                :kotlin: *Android 코드 검색 예시*

                클래스명, 함수명, 또는 기능으로 검색합니다:

                *실제 검색 결과 예시:*
                • `ProductViewModel 어디 있어?`
                  → `ProductListViewModel` — `features/.../product/list/viewmodel/`
                  → `ProductDetailViewModel` — `features/.../product/detail/`

                • `상품상세 스킴 URL?`
                  → `AppLinkGenerator.buildProductDetailUri()` — `link/src/main/kotlin/...`
                  → `ProductDeepLinkConverter.isProductDetailUri()` — `app/src/...`

                • `panelCode 어디서 쓰여?`
                • `배너 클릭 이벤트 구현 어떻게 돼?`
                • `HomeFragment 찾아줘`

                벡터 + BM25 하이브리드 검색 · 로컬 grep 병행
            """.trimIndent(),

            "PR 검색 예시 보여줘" to """
                :github: *PR 히스토리 검색 예시*

                GitHub PR 변경 내역을 검색합니다:

                • `배너 관련 최근 PR 보여줘`
                • `KMA-7282 PR 내용 알려줘`
                • `ProductDetailActivity 최근 변경 내역`
                • `결제 관련 PR 목록`
                • `지난주 머지된 PR 뭐 있어?`

                _PR 제목, 본문, 변경 파일 기준으로 검색합니다._
            """.trimIndent(),

            "인제스트 방법 알려줘" to """
                :books: *문서 인제스트 사용법*

                URL 내용을 지식베이스(RAG)에 저장하면 이후 검색에서 활용됩니다.

                *슬래시 커맨드로 사용:*
                • `/askpj ingest <URL>` — 단일 URL 인제스트
                • `/askpj ingest-wiki` — `docs/wiki/` 전체 로드

                *예시:*
                ```
                /askpj ingest https://confluence.kurly.com/spaces/ProductApp/pages/...
                ```

                *관리 명령:*
                • `/askpj reindex` — Confluence RAG 재인덱싱
                • `/askpj reindex-code` — Android 소스코드 재인덱싱
                • `/askpj lint` — 지식베이스 품질 검사
            """.trimIndent(),

            "도움말" to """
                :wave: *배필주2 사용법*

                :confluence: *Confluence 문서 검색*
                자연어로 질문하면 됩니다.
                • `온보딩 가이드 알려줘`
                • `배포 프로세스 어떻게 돼?`
                • `KMA-1234 관련 기획 문서 있어?`

                :kotlin: *Android 코드 검색*
                클래스명, 함수명, 기능으로 검색합니다.
                • `ProductViewModel 어디 있어?`
                • `배너 클릭 이벤트 구현 방법`
                • `딥링크 스킴 값이 뭐야?`

                :github: *PR 히스토리*
                PR 변경 내역을 검색합니다.
                • `KMA-7282 어떤 작업이야?`
                • `결제 관련 최근 PR 보여줘`

                :mag: *종합 검색*
                문서 + 코드 동시 검색이 필요할 때.
                • `컬리페이 결제 흐름 알려줘`
                • `BaseFragment 어떻게 써?`

                :books: *문서 인제스트*
                • `/askpj ingest <URL>` — URL 지식베이스 저장
                • `/askpj reindex` — Confluence 재인덱싱
                • `/askpj reindex-code` — 코드 재인덱싱

                :repeat: 답변에 이모지로 피드백: :thumbsup: 도움됨 | :thumbsdown: 아쉬움 | :repeat: 재검색
            """.trimIndent(),

            "종합 검색 예시 보여줘" to """
                :mag: *종합 검색 — Confluence + 코드 동시 검색*

                문서와 코드 양쪽에 답이 있을 때 사용합니다.
                이 버튼을 누른 후 입력하는 질문은 Confluence와 코드를 동시에 검색합니다.

                *이런 질문에 적합해요:*
                • `컬리페이 결제 흐름 알려줘` — 기획 문서 + 코드 구현 동시 확인
                • `BaseFragment 어떻게 써?` — 팀 가이드 문서 + 클래스 위치
                • `딥링크 스킴 목록` — Confluence 정리 문서 + 코드에 정의된 값
                • `코드 리뷰 기준` — 팀 컨벤션 문서 + 실제 lint 규칙
                • `HomeFragment 설명해줘` — 설계 문서 + 구현 코드
            """.trimIndent(),
        )

        const val FEEDBACK_GUIDE =
            ":thumbsup: 도움됐다면 | :thumbsdown: 아쉬웠다면 | :repeat: 다시 검색해드릴게요"
        val FEEDBACK_REACTIONS = listOf("+1", "-1", "thumbsup", "thumbsdown")
        val RETRY_REACTIONS = listOf("repeat", "arrows_counterclockwise")
        private val HELP_EXACT = setOf("도움말", "사용법", "help", "도움", "사용방법")
        private val HELP_CONTAINS = listOf("사용법 알려줘", "어떻게 써", "어떻게 사용", "how to use", "how do i")

        fun isHelpQuery(text: String): Boolean {
            val lower = text.trim().lowercase()
            return lower in HELP_EXACT || HELP_CONTAINS.any { lower.contains(it) }
        }

        fun extractQuery(text: String): String =
            text.replace(Regex("<@[A-Z0-9]+[^>]*>"), "")
                .replace(Regex("\\*Sent using\\*.*$"), "")
                .trim()
    }
}
