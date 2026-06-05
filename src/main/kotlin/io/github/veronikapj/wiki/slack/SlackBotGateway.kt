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
import io.github.veronikapj.wiki.config.PersonaType
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.agent.SearchProgressListener
import io.github.veronikapj.wiki.agent.QueryRewriter
import io.github.veronikapj.wiki.config.SlackConfig
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.context.ProjectMemory
import io.github.veronikapj.wiki.onboarding.OnboardingSessionStore
import io.github.veronikapj.wiki.onboarding.OnboardingTool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
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
    private val userPersonaStore: UserPersonaStore,
) {
    private val app = App()
    private val slackClient: MethodsClient = Slack.getInstance().methods(slackConfig.botToken)
    private val messageExecutor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(20),
    )

    @Volatile var lastCodeIndexedAt: Instant? = loadStatusFile("lastCodeIndexedAt")
        set(value) { field = value; saveStatusFile("lastCodeIndexedAt", value) }
    @Volatile var lastPrIndexedAt: Instant? = loadStatusFile("lastPrIndexedAt")
        set(value) { field = value; saveStatusFile("lastPrIndexedAt", value) }


    private fun loadStatusFile(key: String): Instant? = runCatching {
        val file = File(STATUS_FILE)
        if (!file.exists()) return null
        file.readLines().firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.let { Instant.parse(it) }
    }.getOrNull()

    private fun saveStatusFile(key: String, value: Instant?) {
        runCatching {
            val file = File(STATUS_FILE)
            file.parentFile?.mkdirs()
            val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
            val idx = lines.indexOfFirst { it.startsWith("$key=") }
            val entry = if (value != null) "$key=$value" else return
            if (idx >= 0) lines[idx] = entry else lines += entry
            file.writeText(lines.joinToString("\n"))
        }
    }

    // 버튼 클릭 후 해당 스레드에서 라우터를 스킵하고 지정 툴로 직행
    private val threadForcedTool = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val toolDisplayNames = mapOf(
        "knowledgeSearch" to "지식베이스",
        "confluenceSearch" to "Confluence",
        "githubWikiSearch" to "GitHub Wiki",
        "prHistory" to "PR 이력",
        "codeSearch" to "코드 검색",
        "personalProgress" to "성과 목표",
        "personalGoalQuery" to "성과 목표",
        "progressAdvisor" to "성과 코칭",
        "onboarding" to "온보딩 가이드",
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

    private fun isOnboardingQuery(query: String): Boolean {
        val q = query.lowercase()
        return (q.contains("온보딩") || q.contains("onboarding")) &&
            !isOnboarding("") // 온보딩 채널이 아닌 일반 채널에서만
    }

    private val ONBOARDING_DM_GUIDE = """:books: *온보딩 가이드 안내*

온보딩은 DM(다이렉트 메시지)에서 진행할 수 있어요!

*진행 방식:*
• 저에게 DM을 보내고 `온보딩 시작`을 입력하면 시작됩니다
• 경험 수준에 맞춰 커리큘럼이 자동 구성됩니다
• 단계별로 `다음`을 입력하면 진행, `건너뛰기`로 스킵 가능
• 중간에 궁금한 점은 자유롭게 질문할 수 있어요

*커리큘럼 (Day 1~5):*
• Phase 1: 환경 셋업 & 프로젝트 구조
• Phase 2: 도메인 용어 & Compose 컨벤션
• Phase 3: 브랜치 / QA / 배포 / 모니터링
• Phase 4: 첫 PR과 코드 리뷰
• Phase 5: Claude 스킬 & CI/CD

:point_right: 저에게 DM을 보내서 시작해보세요!"""

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
            val userId = payload.event.user
            log.info("Mention received: '{}'", query)
            if (isHelpQuery(query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text(configHandler.helpMessage()) }
            } else if (isOnboardingQuery(query)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text(ONBOARDING_DM_GUIDE) }
            } else if (isOnboarding(channel)) {
                log.info("Onboarding step for channel={}", channel)
                runCatching { handleOnboarding(channel, threadTs, query) }
                    .onFailure { log.error("Onboarding error: {}", it.message, it) }
            } else if (!handleQueryAsync(channel = channel, threadTs = threadTs, sessionId = threadTs, query = query, userId = userId)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text("요청이 많아 잠시 후 다시 시도해주세요.") }
            }
            ctx.ack()
        }
    }

    private fun handleQueryAsync(channel: String, threadTs: String?, sessionId: String, query: String, userId: String? = null): Boolean {
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
                val result = runBlocking { orchestrator.answer(query, listener, sessionId = sessionId, userId = userId) }

                progressMessageTs?.let { ts ->
                    runCatching { slackClient.chatDelete { it.channel(channel).ts(ts) } }
                }

                val footer = buildString {
                    if (searchedTools.isNotEmpty()) {
                        append("\uD83D\uDCCB ")
                        append(searchedTools.distinct().joinToString(" · ") { toolDisplayNames[it] ?: it })
                    }
                    if (result.isRag) append("\n$FEEDBACK_GUIDE")
                }
                val finalText = if (footer.isBlank()) result.answer else "${result.answer}\n\n$footer"

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
                            answer = result.answer,
                            usedTools = searchedTools.distinct(),
                            ts = ts,
                            responseType = result.responseType,
                            isRag = result.isRag,
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
                        triggerRequery(messageTs, channel, threadTs = messageTs, userId = event.user)
                    }
                }
            }
            ctx.ack()
        }
    }

    private fun triggerRequery(messageTs: String, channel: String, threadTs: String, userId: String? = null) {
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
                orchestrator.answer(entry.query, sessionId = "requery-$messageTs", forceAllTools = forceAllTools, userId = userId)
            }
            val reply = ":repeat: 다른 방식으로 찾아봤어요\n\n${fallbackResult.answer}"
            slackClient.chatPostMessage { req -> req.channel(channel).threadTs(threadTs).text(reply) }
            feedbackStore.saveRequery(ts = messageTs, requeryBm25 = "", requeryVec = "", requeryAnswer = fallbackResult.answer, stage = stage)
            return
        }

        val result = runBlocking {
            orchestrator.answer(combinedQuery, sessionId = "requery-$messageTs", forceAllTools = forceAllTools, userId = userId)
        }

        val reply = ":repeat: 다른 방식으로 찾아봤어요\n\n${result.answer}"
        slackClient.chatPostMessage { req ->
            req.channel(channel).threadTs(threadTs).text(reply)
        }

        feedbackStore.saveRequery(
            ts = messageTs,
            requeryBm25 = bm25Query,
            requeryVec = vectorQuery,
            requeryAnswer = result.answer,
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

    private fun buildHomeView(userId: String): com.slack.api.model.view.View {
        val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

        val codeStatus = lastCodeIndexedAt?.let { fmt.format(it) } ?: "미실행"
        val prStatus = lastPrIndexedAt?.let { fmt.format(it) } ?: "미실행"
        val spaces = projectMemory?.load()
            ?.lines()
            ?.firstOrNull { it.contains("검색 스페이스") }
            ?.substringAfter("검색 스페이스:")?.trim()
            ?: "미설정"

        return view { v ->
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
                            "PR 인덱싱: `$prStatus`\n" +
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
                                    b.text(plainText("PR 재인덱싱", true))
                                        .actionId("home_reindex_pr")
                                        .value("reindex-pr")
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
                            "• 관리 명령: `/askpj reindex-code` | `/askpj reindex-pr`\n" +
                            "• 피드백: :thumbsup: 도움됨 | :thumbsdown: 아쉬움 | :repeat: 재검색"
                        ))
                    },
                    divider(),
                    section { s -> s.text(markdownText("*🎭 페르소나 설정*\n나에게 적용되는 응답 스타일을 선택하세요.")) },
                    actions { a ->
                        val currentPersona = userPersonaStore.get(userId) ?: PersonaType.DEFAULT
                        a.elements(
                            listOf(
                                staticSelect { s ->
                                    s.actionId("home_persona_select")
                                        .placeholder(plainText("페르소나 선택"))
                                        .initialOption(
                                            option { o ->
                                                o.text(plainText(currentPersona.displayName))
                                                    .value(currentPersona.name)
                                            }
                                        )
                                        .options(
                                            PersonaType.entries.map { p ->
                                                option { o -> o.text(plainText(p.displayName)).value(p.name) }
                                            }
                                        )
                                }
                            )
                        )
                    },
                )
            )
        }
    }

    private fun registerHomeHandler() {
        app.event(AppHomeOpenedEvent::class.java) { payload, ctx ->
            val userId = payload.event.user
            val view = buildHomeView(userId)

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
                if (!result.contains("비활성화") && !result.contains("오류") && !result.contains("실패")) lastCodeIndexedAt = Instant.now()
                slackClient.chatPostMessage { it.channel(userId).text(result) }
            }
            ctx.ack()
        }

        app.blockAction("home_reindex_pr") { req, ctx ->
            val userId = req.payload.user.id
            messageExecutor.submit {
                val result = configHandler.handle("/wiki reindex-pr")
                if (!result.contains("비활성화") && !result.contains("오류") && !result.contains("실패")) lastPrIndexedAt = Instant.now()
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

        app.blockAction("home_persona_select") { req, ctx ->
            val response = ctx.ack()
            val userId = req.payload.user.id
            val selectedValue = req.payload.actions
                .firstOrNull()
                ?.selectedOption
                ?.value
            val persona = selectedValue?.let { runCatching { PersonaType.valueOf(it) }.getOrNull() }
                ?: PersonaType.DEFAULT

            userPersonaStore.set(userId, persona)

            runCatching {
                slackClient.viewsPublish { it.userId(userId).view(buildHomeView(userId)) }
            }.onFailure { log.warn("Failed to refresh home view after persona change: {}", it.message) }

            runCatching {
                slackClient.chatPostMessage { it.channel(userId).text("✅ 페르소나가 *${persona.displayName}*으로 변경되었습니다.") }
            }.onFailure { log.warn("Failed to send persona change DM to userId={}: {}", userId, it.message) }

            response
        }

        // 온보딩 Block Kit 버튼 핸들러
        val onboardingActionMessages = mapOf(
            "onboarding_next" to "다음",
            "onboarding_skip" to "건너뛰기",
            "onboarding_progress" to "진행률",
        )
        for ((actionId, message) in onboardingActionMessages) {
            app.blockAction(actionId) { req, ctx ->
                val userId = req.payload.user.id
                val channel = req.payload.channel?.id ?: req.payload.user.id
                val threadTs = req.payload.message?.threadTs ?: req.payload.message?.ts ?: ""
                if (channel.isNotBlank() && threadTs.isNotBlank()) {
                    handleAssistantQueryAsync(channel, threadTs, message, forcedTool = "onboarding", userId = userId)
                }
                ctx.ack()
            }
        }

        // 온보딩 레벨 체크 드롭다운 핸들러
        app.blockAction("onboarding_level_android") { _, ctx -> ctx.ack() }
        app.blockAction("onboarding_level_compose") { _, ctx -> ctx.ack() }
        app.blockAction("onboarding_level_domain") { _, ctx -> ctx.ack() }
        app.blockAction("onboarding_level_submit") { req, ctx ->
            val userId = req.payload.user.id
            val channel = req.payload.channel?.id ?: req.payload.user.id
            val threadTs = req.payload.message?.threadTs ?: req.payload.message?.ts ?: ""
            // state.values에서 드롭다운 선택값 추출
            val state = req.payload.state?.values ?: emptyMap()
            val android = state["level_android"]?.get("onboarding_level_android")?.selectedOption?.value ?: "A"
            val compose = state["level_compose"]?.get("onboarding_level_compose")?.selectedOption?.value ?: "A"
            val domain = state["level_domain"]?.get("onboarding_level_domain")?.selectedOption?.value ?: "A"
            val levelMessage = "$android, $compose, $domain"
            if (channel.isNotBlank() && threadTs.isNotBlank()) {
                handleAssistantQueryAsync(channel, threadTs, levelMessage, forcedTool = "onboarding", userId = userId)
            }
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

        // 파일 공유 이벤트 — 무시 (핸들러 없으면 404 경고 발생)
        app.event(com.slack.api.model.event.MessageFileShareEvent::class.java) { _, ctx ->
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
            val userId = event.user

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
                ?: if (userId != null && OnboardingSessionStore.isActive(userId)) "onboarding" else null
            if (!handleAssistantQueryAsync(channel, threadTs, query, forcedTool, userId = userId)) {
                slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text("요청이 많아 잠시 후 다시 시도해주세요.") }
            }
            ctx.ack()
        }
    }

    private fun handleAssistantQueryAsync(channel: String, threadTs: String, query: String, forcedTool: String? = null, userId: String? = null): Boolean {
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

                // 라우터 LLM 실행 전에도 상태 표시 (라우터 응답까지 수 초 지연 존재)
                runCatching {
                    slackClient.assistantThreadsSetStatus { req ->
                        req.channelId(channel).threadTs(threadTs).status("생각 중...")
                    }
                }.onFailure { log.warn("Failed to set initial status: {}", it.message) }

                try {
                    val result = runBlocking {
                        orchestrator.answer(query, listener, sessionId = "assistant-$threadTs", forceTool = forcedTool, userId = userId)
                    }

                    val footer = buildString {
                        if (searchedTools.isNotEmpty()) {
                            append("\uD83D\uDCCB ")
                            append(searchedTools.distinct().joinToString(" · ") { toolDisplayNames[it] ?: it })
                        }
                        if (result.isRag) append("\n$FEEDBACK_GUIDE")
                    }
                    val finalText = if (footer.isBlank()) result.answer else "${result.answer}\n\n$footer"

                    val sendResult = if (forcedTool == "onboarding" && !result.answer.contains("비활성화")) {
                        slackClient.chatPostMessage { req ->
                            req.channel(channel).threadTs(threadTs).text(finalText)
                                .blocks(buildOnboardingBlocks(finalText))
                        }
                    } else {
                        slackClient.chatPostMessage { req ->
                            req.channel(channel).threadTs(threadTs).text(finalText)
                        }
                    }

                    if (sendResult.isOk) {
                        sendResult.ts?.let { ts ->
                            feedbackStore.save(ts, FeedbackEntry(
                                query = query, 
                                answer = result.answer, 
                                usedTools = searchedTools.distinct(), 
                                ts = ts,
                                responseType = result.responseType,
                                isRag = result.isRag,
                            ))
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

    private fun buildOnboardingBlocks(text: String): List<com.slack.api.model.block.LayoutBlock> {
        // 레벨 체크 메시지면 드롭다운 UI로 대체
        if (text.contains(OnboardingTool.LEVEL_CHECK_MESSAGE.take(20))) {
            return buildLevelCheckBlocks()
        }
        val sanitized = sanitizeSlackMrkdwn(text)
        val blocks = mutableListOf<com.slack.api.model.block.LayoutBlock>()
        // Slack section text 제한 3000자 — 초과 시 분할
        splitForSlackSections(sanitized).forEach { chunk ->
            blocks.add(section { it.text(markdownText(chunk)) })
        }
        blocks.add(actions { it.elements(listOf(
            button { b -> b.text(plainText("다음 ➡️")).actionId("onboarding_next") },
            button { b -> b.text(plainText("건너뛰기 ⏭")).actionId("onboarding_skip") },
            button { b -> b.text(plainText("진행률 📊")).actionId("onboarding_progress") },
        )) })
        return blocks
    }

    /** LLM이 출력한 GitHub-flavored markdown을 Slack mrkdwn 호환으로 변환 */
    private fun sanitizeSlackMrkdwn(text: String): String {
        return text
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "") // # ## ### 헤더 → 제거
            .replace(Regex("^---+\\s*$", RegexOption.MULTILINE), "")  // --- 수평선 → 제거
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "*$1*")              // **bold** → *bold*
            .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<$2|$1>")   // [text](url) → <url|text>
            .replace(Regex("\n{3,}"), "\n\n")                         // 과다 개행 정리
    }

    /** 텍스트를 3000자 이하 청크로 분할 (빈 줄 경계 우선) */
    private fun splitForSlackSections(text: String, limit: Int = 2900): List<String> {
        if (text.length <= limit) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > limit) {
            // 빈 줄(\n\n) 경계에서 분할 시도
            val splitIdx = remaining.lastIndexOf("\n\n", limit)
            val cutAt = if (splitIdx > limit / 3) splitIdx else remaining.lastIndexOf('\n', limit).let { if (it > limit / 3) it else limit }
            chunks.add(remaining.substring(0, cutAt).trimEnd())
            remaining = remaining.substring(cutAt).trimStart()
        }
        if (remaining.isNotBlank()) chunks.add(remaining)
        return chunks
    }

    private fun buildLevelCheckBlocks(): List<com.slack.api.model.block.LayoutBlock> {
        return listOf(
            section { it.text(markdownText(":wave: 안녕하세요! 온보딩 가이드를 시작합니다.\n먼저 경험 수준을 선택해주세요.")) },
            input { it
                .blockId("level_android")
                .label(plainText("1. Android 개발 경험"))
                .element(staticSelect { s -> s
                    .actionId("onboarding_level_android")
                    .placeholder(plainText("선택하세요"))
                    .options(listOf(
                        option(plainText("A — 1년 미만 (입문)"), "A"),
                        option(plainText("B — 1~3년 (중급)"), "B"),
                        option(plainText("C — 3년 이상 (숙련)"), "C"),
                    ))
                })
                .optional(false)
            },
            input { it
                .blockId("level_compose")
                .label(plainText("2. Compose 프로덕션 배포 경험"))
                .element(staticSelect { s -> s
                    .actionId("onboarding_level_compose")
                    .placeholder(plainText("선택하세요"))
                    .options(listOf(
                        option(plainText("A — 없음"), "A"),
                        option(plainText("B — 있음"), "B"),
                    ))
                })
                .optional(false)
            },
            input { it
                .blockId("level_domain")
                .label(plainText("3. 커머스 도메인 경험"))
                .element(staticSelect { s -> s
                    .actionId("onboarding_level_domain")
                    .placeholder(plainText("선택하세요"))
                    .options(listOf(
                        option(plainText("A — 없음"), "A"),
                        option(plainText("B — 있음"), "B"),
                    ))
                })
                .optional(false)
            },
            actions { it.elements(listOf(
                button { b -> b.text(plainText("시작하기 🚀")).actionId("onboarding_level_submit").style("primary") },
            )) },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackBotGateway::class.java)
        private const val STATUS_FILE = ".wiki/index-status.properties"

        // SuggestedPrompt.message = 버튼 클릭 시 유저 메시지로 표시되는 텍스트
        // CANNED_RESPONSES 키와 exact match하여 LLM 없이 즉시 응답
        // HINT_FORCED_TOOL: 힌트 트리거 → 해당 스레드에서 라우터 스킵하고 지정 툴로 직행
        private val SUGGESTED_PROMPTS = listOf(
            SuggestedPrompt.builder().title("Confluence에서 검색").message("Confluence 검색 예시 보여줘").build(),
            SuggestedPrompt.builder().title("코드에서 찾기").message("코드 검색 예시 보여줘").build(),
            SuggestedPrompt.builder().title("PR 히스토리 보기").message("PR 검색 예시 보여줘").build(),
            SuggestedPrompt.builder().title("온보딩 가이드 시작").message("온보딩 가이드 시작").build(),
        )

        val HINT_FORCED_TOOL = mapOf(
            "Confluence 검색 예시 보여줘" to "confluenceSearch",
            "코드 검색 예시 보여줘" to "codeSearch",
            "PR 검색 예시 보여줘" to "prHistory",
            "종합 검색 예시 보여줘" to "confluenceSearch+codeSearch",
            "온보딩 가이드 시작" to "onboarding",
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
                :computer: *Android 코드 검색 예시*

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

                GitHub PR 변경 내역을 검색합니다.
                이 버튼을 누른 후 입력하는 질문은 PR 히스토리에서만 검색합니다.

                *실제 검색 결과 예시:*
                • `배너 관련 최근 PR 보여줘`
                  → PR #7557 (KMA-7275) — 빌드/Domain/Data Layer — panel_code DSP 광고 배너 지원
                     작성자: piljubae | 2026-05-07
                  → PR #7543 (KMA-7798) — 카테고리 상품 목록 배너 영역 이슈 수정
                     작성자: hyunkyoung-jung | 2026-05-06

                • `KMA-7275 어떤 작업이야?`
                  → PR #7540 (KMA-7275) — 배너 광고 DSP 프로퍼티 추가 및 Amplitude 이벤트 전파
                     작성자: piljubae

                *이런 질문에 적합해요:*
                • `ProductDetailActivity 최근 변경 내역`
                • `결제 관련 PR 목록`
                • `지난주 머지된 PR 뭐 있어?`
                • `piljubae 최근 작업 내역`
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
                • `/askpj reindex-code` — Android 소스코드 재인덱싱
                • `/askpj reindex-pr` — PR 히스토리 재인덱싱
                • `/askpj lint` — 지식베이스 품질 검사
            """.trimIndent(),

            "도움말" to """
                :wave: *배필주2 사용법*

                :confluence: *Confluence 문서 검색*
                자연어로 질문하면 됩니다.
                • `온보딩 가이드 알려줘`
                • `배포 프로세스 어떻게 돼?`
                • `KMA-1234 관련 기획 문서 있어?`

                :computer: *Android 코드 검색*
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
                • `/askpj reindex-code` — 코드 재인덱싱
                • `/askpj reindex-pr` — PR 히스토리 재인덱싱

                :repeat: 답변에 이모지로 피드백: :thumbsup: 도움됨 | :thumbsdown: 아쉬움 | :repeat: 재검색
            """.trimIndent(),

            "온보딩 가이드 시작" to OnboardingTool.CANNED_RESPONSE,

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
