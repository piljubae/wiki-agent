# Slack App Assistant + Home Tab Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** App Assistant를 Q&A 메인 진입점으로 전환하고, App Home 탭에 상태/관리 UI를 추가한다.

**Architecture:** `SlackBotGateway`에 `registerAssistantHandler()`, `registerHomeHandler()` 메서드를 인라인으로 추가한다. 기존 DM 핸들러(`registerDmHandler`)는 삭제한다. App Assistant 메시지는 기존 `handleQueryAsync` 로직을 재사용하되, 진행 상태를 `assistantThreadsSetStatus` API로 표시한다.

**Tech Stack:** Slack Bolt 1.46.0 (Socket Mode), Kotlin coroutines, Block Kit

---

## Slack 앱 대시보드 사전 설정 (코드 작업 전 필수)

아래 설정을 `api.slack.com/apps/A0AVARFDU2U` 에서 완료해야 이벤트가 도착한다.

1. **App Assistant 활성화** — `/app-assistant` 탭 → 활성화 토글
2. **Home Tab 활성화** — `/app-home` 탭 → Home Tab ON
3. **OAuth Scopes 추가** — `/oauth` 탭 → Bot Token Scopes:
   - `assistant:write`
   - `reactions:read`
4. **Event Subscriptions 추가** — `/event-subscriptions` 탭:
   - `assistant_thread_started`
   - `assistant_thread_context_changed`
   - `app_home_opened`
5. **앱 재설치** — `/install-on-team` 탭

---

### Task 1: App Assistant 이벤트 핸들러 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: import 추가**

`SlackBotGateway.kt` 상단 import 블록에 추가:

```kotlin
import com.slack.api.model.event.AssistantThreadStartedEvent
import com.slack.api.model.event.AssistantThreadContextChangedEvent
import com.slack.api.methods.request.assistant.threads.AssistantThreadsSetSuggestedPromptsRequest.Prompt
```

**Step 2: `registerAssistantHandler()` 메서드 추가**

`registerSlashCommand()` 아래에 추가:

```kotlin
private fun registerAssistantHandler() {
    // 1) 패널 열릴 때 — 추천 프롬프트 세팅
    app.event(AssistantThreadStartedEvent::class.java) { payload, ctx ->
        val thread = payload.event.assistantThread
        val channelId = thread.channelId
        val threadTs = thread.threadTs
        runCatching {
            slackClient.assistantThreadsSetSuggestedPrompts { req ->
                req.channelId(channelId)
                    .threadTs(threadTs)
                    .prompts(SUGGESTED_PROMPTS)
            }
        }.onFailure { log.warn("Failed to set suggested prompts: {}", it.message) }
        ctx.ack()
    }

    // 2) 채널 컨텍스트 변경 — 무시
    app.event(AssistantThreadContextChangedEvent::class.java) { _, ctx ->
        ctx.ack()
    }
}
```

**Step 3: `SUGGESTED_PROMPTS` 상수를 `companion object`에 추가**

```kotlin
private val SUGGESTED_PROMPTS = listOf(
    Prompt.builder().title("Confluence에서 검색").message("무엇을 Confluence에서 찾을까요?").build(),
    Prompt.builder().title("코드에서 찾기").message("어떤 코드를 찾고 있나요?").build(),
    Prompt.builder().title("PR 히스토리 보기").message("어떤 PR 히스토리가 궁금하세요?").build(),
    Prompt.builder().title("문서 인제스트").message("인제스트할 URL을 입력해주세요.").build(),
)
```

**Step 4: `start()` 에서 `registerAssistantHandler()` 호출 추가**

```kotlin
fun start() {
    registerMentionHandler()
    registerAssistantHandler()   // 추가
    registerReactionHandler()
    registerSlashCommand()
    registerHomeHandler()        // Task 3에서 추가
    log.info("Starting Slack bot (Socket Mode)...")
    SocketModeApp(slackConfig.appToken, app).start()
}
```

**Step 5: 빌드 확인**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

**Step 6: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: add App Assistant event handlers with suggested prompts"
```

---

### Task 2: DM 핸들러 → Assistant 메시지 핸들러로 교체

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

기존 `registerDmHandler()`는 `MessageEvent`를 통해 DM을 처리했다.  
App Assistant 활성화 후에는 IM 채널 메시지가 assistant thread 메시지로 도착한다.  
DM 핸들러를 삭제하고, MessageEvent 핸들러를 assistant 용도로 교체한다.

**Step 1: `registerDmHandler()` 전체 삭제**

`SlackBotGateway.kt` 에서 `registerDmHandler()` 메서드 전체(222~277줄)와  
`start()`의 `registerDmHandler()` 호출 라인을 삭제한다.

아울러 더 이상 쓰이지 않는 멤버도 삭제:
- `prUrlPattern`, `classifyDmInput()`, `DmInputType` enum  
- `CONFIG_COMMANDS` (companion object에서 — `/wiki` 슬래시가 처리하므로 불필요)

**Step 2: `registerAssistantHandler()` 에 메시지 핸들러 추가**

Task 1의 `registerAssistantHandler()` 안, `AssistantThreadContextChangedEvent` 블록 아래에 추가:

```kotlin
// 3) 사용자 메시지 — orchestrator로 라우팅
app.event(com.slack.api.model.event.MessageEvent::class.java) { payload, ctx ->
    val event = payload.event
    // assistant thread의 사용자 메시지만 처리 (봇 메시지, subtype 제외)
    if (event.channelType != "im" || event.botId != null || event.subtype != null) {
        return@event ctx.ack()
    }
    val query = extractQuery(event.text?.trim() ?: return@event ctx.ack())
    if (query.isBlank()) return@event ctx.ack()

    val channel = event.channel
    val threadTs = event.threadTs ?: event.ts  // assistant 메시지는 항상 threadTs 있음

    log.info("Assistant message received: '{}'", query.take(80))

    if (isHelpQuery(query)) {
        slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text(configHandler.helpMessage()) }
        return@event ctx.ack()
    }

    if (!handleAssistantQueryAsync(channel, threadTs, query)) {
        slackClient.chatPostMessage { it.channel(channel).threadTs(threadTs).text("요청이 많아 잠시 후 다시 시도해주세요.") }
    }
    ctx.ack()
}
```

**Step 3: `handleAssistantQueryAsync()` 추가**

기존 `handleQueryAsync()`를 assistant API를 사용하도록 변형한 버전.  
`handleQueryAsync()` 아래에 추가:

```kotlin
private fun handleAssistantQueryAsync(channel: String, threadTs: String, query: String): Boolean {
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
                    orchestrator.answer(query, listener, sessionId = "assistant-$threadTs")
                }

                // 상태 초기화
                runCatching {
                    slackClient.assistantThreadsSetStatus { req ->
                        req.channelId(channel).threadTs(threadTs).status("")
                    }
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
            }
        }
        true
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        log.warn("Request queue full — rejected: channel={} thread={}", channel, threadTs)
        false
    }
}
```

**Step 4: 빌드 확인**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL (unused import 경고 있으면 함께 정리)

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: replace DM handler with App Assistant message handler"
```

---

### Task 3: App Home 탭 핸들러 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: import 추가**

```kotlin
import com.slack.api.model.event.AppHomeOpenedEvent
import com.slack.api.model.block.Blocks.*
import com.slack.api.model.block.composition.BlockCompositions.*
import com.slack.api.model.block.element.BlockElements.*
import com.slack.api.model.view.Views.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
```

**Step 2: 마지막 인덱싱 시각 추적 필드 추가**

`SlackBotGateway` 클래스 본문 상단 필드들 아래에:

```kotlin
@Volatile var lastCodeIndexedAt: Instant? = null
@Volatile var lastConfluenceIndexedAt: Instant? = null
```

> 이 필드는 `Main.kt` 또는 인덱싱 에이전트에서 갱신 가능. 일단 null이면 "미실행"으로 표시.

**Step 3: `registerHomeHandler()` 메서드 추가**

```kotlin
private fun registerHomeHandler() {
    app.event(AppHomeOpenedEvent::class.java) { payload, ctx ->
        val userId = payload.event.user
        val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

        val codeStatus = lastCodeIndexedAt?.let { fmt.format(it) } ?: "미실행"
        val confluenceStatus = lastConfluenceIndexedAt?.let { fmt.format(it) } ?: "미실행"
        val spaces = projectMemory?.load()
            ?.lines()
            ?.firstOrNull { it.contains("검색 스페이스") }
            ?.removePrefix("검색 스페이스:")?.trim()
            ?: "미설정"

        val view = view { v ->
            v.type("home").blocks(
                listOf(
                    header { h -> h.text(plainText("📚 Wiki 검색 봇", true)) },
                    section { s ->
                        s.text(markdownText("Confluence · GitHub Wiki · 코드베이스를 AI 패널에서 검색하세요."))
                    },
                    divider(),
                    section { s ->
                        s.text(markdownText(
                            "*🟢 상태*\n" +
                            "코드 인덱싱: `$codeStatus`\n" +
                            "Confluence 인덱싱: `$confluenceStatus`\n" +
                            "검색 스페이스: `$spaces`"
                        ))
                    },
                    divider(),
                    section { s -> s.text(markdownText("*⚡ 빠른 액션*")) },
                    actions { a ->
                        a.elements(
                            listOf(
                                button { b ->
                                    b.text(plainText("🔄 코드 재인덱싱", true))
                                        .actionId("home_reindex_code")
                                        .value("reindex-code")
                                },
                                button { b ->
                                    b.text(plainText("🔄 Confluence 재인덱싱", true))
                                        .actionId("home_reindex")
                                        .value("reindex")
                                },
                                button { b ->
                                    b.text(plainText("📋 메모리 보기", true))
                                        .actionId("home_memory_show")
                                        .value("memory show")
                                },
                            )
                        )
                    },
                    divider(),
                    section { s ->
                        s.text(markdownText(
                            "*💡 사용법*\n" +
                            "• Slack 좌측 AI 패널에서 질문하세요\n" +
                            "• URL 인제스트: `/wiki ingest <URL>`\n" +
                            "• 관리 명령: `/askpj reindex-code` | `/askpj reindex`\n" +
                            "• 피드백: 👍 도움됨 | 👎 아쉬움 | 🔁 재검색"
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
            lastCodeIndexedAt = Instant.now()
            slackClient.chatPostMessage { it.channel(userId).text(result) }
        }
        ctx.ack()
    }

    app.blockAction("home_reindex") { req, ctx ->
        val userId = req.payload.user.id
        messageExecutor.submit {
            val result = configHandler.handle("/wiki reindex")
            lastConfluenceIndexedAt = Instant.now()
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
```

**Step 4: 빌드 확인**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: add App Home tab with status panel and quick actions"
```

---

### Task 4: 불필요 코드 정리 및 최종 확인

**Step 1: 미사용 import, 필드 제거**

삭제된 DM 핸들러로 인해 미사용이 된 것들 확인:
- `import io.github.veronikapj.wiki.github.GitHubCodeClient` — `triggerRequery`에서 여전히 사용 중이면 유지
- `prIndexAgent`, `ingestAgent` 파라미터 — `registerDmHandler` 제거로 미사용이면 삭제, `configHandler`가 이미 처리하면 유지

**Step 2: 전체 빌드**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

**Step 3: 로컬 실행 (Slack 설정 완료 후)**

```bash
./gradlew run
```

확인 사항:
1. Slack AI 패널 열기 → 추천 프롬프트 4개 표시 여부
2. 프롬프트 버튼 클릭 → 검색 상태 표시 → 응답 수신 여부
3. App Home 탭 → 상태/버튼 표시 여부
4. 홈 탭 버튼 클릭 → DM으로 결과 수신 여부
5. `/wiki reindex-code` 슬래시 커맨드 → 정상 응답 여부

**Step 4: 최종 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "chore: clean up unused code after DM handler removal"
```
