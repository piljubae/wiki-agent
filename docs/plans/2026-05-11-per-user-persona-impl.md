# Per-User Persona Selection Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 각 Slack 사용자가 Home Tab 드롭다운으로 자신만의 페르소나를 선택하고, 재시작 후에도 유지되도록 한다.

**Architecture:** `UserPersonaStore`(파일 영속 Map)를 신규 생성하고, `OrchestratorAgent`에 주입해 메시지 처리 시 userId 기반으로 룩업한다. `SlackBotGateway` Home Tab에 `static_select` 드롭다운을 추가하고, `home_persona_select` block action으로 저장한다.

**Tech Stack:** Kotlin, Slack Bolt SDK (com.slack.api), kotlinx.serialization.json

---

### Task 1: UserPersonaStore 생성

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/slack/UserPersonaStore.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/slack/UserPersonaStoreTest.kt`

**Step 1: 테스트 작성**

```kotlin
// src/test/kotlin/io/github/veronikapj/wiki/slack/UserPersonaStoreTest.kt
package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.PersonaType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserPersonaStoreTest {

    private fun createTempStore(): UserPersonaStore {
        val tmp = File(System.getProperty("java.io.tmpdir"), "user-personas-${System.nanoTime()}.json")
        return UserPersonaStore(tmp.absolutePath)
    }

    @Test
    fun `get returns null for unknown user`() {
        val store = createTempStore()
        assertNull(store.get("U_UNKNOWN"))
    }

    @Test
    fun `set and get round-trip`() {
        val store = createTempStore()
        store.set("U123", PersonaType.MZ_INTERN)
        assertEquals(PersonaType.MZ_INTERN, store.get("U123"))
    }

    @Test
    fun `different users are isolated`() {
        val store = createTempStore()
        store.set("U1", PersonaType.BURNOUT)
        store.set("U2", PersonaType.SIGMA)
        assertEquals(PersonaType.BURNOUT, store.get("U1"))
        assertEquals(PersonaType.SIGMA, store.get("U2"))
    }

    @Test
    fun `persists to file and reloads`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "user-personas-persist-${System.nanoTime()}.json")
        val store1 = UserPersonaStore(tmp.absolutePath)
        store1.set("U999", PersonaType.STARTUP)

        val store2 = UserPersonaStore(tmp.absolutePath)
        assertEquals(PersonaType.STARTUP, store2.get("U999"))
    }
}
```

**Step 2: 테스트 실행 → FAIL 확인**

```bash
cd /Users/pilju.bae/projects/wiki-agent
./gradlew test --tests "*.UserPersonaStoreTest" 2>&1 | tail -20
```

Expected: 컴파일 에러 (UserPersonaStore 없음)

**Step 3: UserPersonaStore 구현**

```kotlin
// src/main/kotlin/io/github/veronikapj/wiki/slack/UserPersonaStore.kt
package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.PersonaType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

class UserPersonaStore(private val filePath: String = ".wiki/user-personas.json") {

    private val log = LoggerFactory.getLogger(javaClass)
    private val personas = mutableMapOf<String, PersonaType>()

    init { load() }

    fun get(userId: String): PersonaType? = personas[userId]

    fun set(userId: String, persona: PersonaType) {
        personas[userId] = persona
        save()
    }

    private fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        runCatching {
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            obj.forEach { (userId, value) ->
                runCatching { personas[userId] = PersonaType.valueOf(value.jsonPrimitive.content) }
                    .onFailure { log.warn("Unknown persona for user={}: {}", userId, value) }
            }
        }.onFailure { log.warn("Failed to load user personas: {}", it.message) }
    }

    private fun save() {
        runCatching {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            val obj = buildJsonObject {
                personas.forEach { (userId, persona) -> put(userId, persona.name) }
            }
            file.writeText(obj.toString())
        }.onFailure { log.warn("Failed to save user personas: {}", it.message) }
    }
}
```

**Step 4: 테스트 실행 → PASS 확인**

```bash
./gradlew test --tests "*.UserPersonaStoreTest" 2>&1 | tail -20
```

Expected: 4개 테스트 PASS

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/UserPersonaStore.kt \
        src/test/kotlin/io/github/veronikapj/wiki/slack/UserPersonaStoreTest.kt
git commit -m "feat: add UserPersonaStore with file persistence"
```

---

### Task 2: PersonaType에 displayName 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt:6-48`

**Step 1: 변경**

`WikiConfig.kt`의 `enum class PersonaType` 시그니처를 아래와 같이 변경한다.

Before:
```kotlin
enum class PersonaType(val description: String) {
    DEFAULT(""),
    MZ_INTERN("답변할 때는 MZ 인턴처럼..."),
    GODLIFE("답변할 때는 갓생러처럼..."),
    BURNOUT("답변할 때는 번아웃 5년차 개발자처럼..."),
    POLITE_GPT("답변할 때는 과도하게 정중한 AI처럼..."),
    YOUTUBER("답변할 때는 유튜버 편집장처럼..."),
    SIGMA("답변할 때는 시그마 개발자처럼..."),
    STARTUP("답변할 때는 스타트업 대표처럼..."),
    PHILOSOPHER("답변할 때는 중2병 현자처럼..."),
    NPC("답변할 때는 편의점 알바 NPC처럼..."),
    SENIOR("답변할 때는 K-직장선배처럼..."),
}
```

After — 각 enum 항목에 두 번째 파라미터 `displayName` 추가:
```kotlin
enum class PersonaType(val description: String, val displayName: String) {
    DEFAULT("", "기본"),
    MZ_INTERN(
        "답변할 때는 MZ 인턴처럼 말합니다...",   // description 그대로 유지
        "MZ 인턴"
    ),
    GODLIFE(
        "답변할 때는 갓생러처럼 말합니다...",
        "갓생러"
    ),
    BURNOUT(
        "답변할 때는 번아웃 5년차 개발자처럼 말합니다...",
        "번아웃 개발자"
    ),
    POLITE_GPT(
        "답변할 때는 과도하게 정중한 AI처럼 말합니다...",
        "정중한 GPT"
    ),
    YOUTUBER(
        "답변할 때는 유튜버 편집장처럼 말합니다...",
        "유튜버 편집장"
    ),
    SIGMA(
        "답변할 때는 시그마 개발자처럼 말합니다...",
        "시그마 개발자"
    ),
    STARTUP(
        "답변할 때는 스타트업 대표처럼 말합니다...",
        "스타트업 대표"
    ),
    PHILOSOPHER(
        "답변할 때는 중2병 현자처럼 말합니다...",
        "중2병 현자"
    ),
    NPC(
        "답변할 때는 편의점 알바 NPC처럼 말합니다...",
        "편의점 NPC"
    ),
    SENIOR(
        "답변할 때는 K-직장선배처럼 말합니다...",
        "K-직장선배"
    ),
}
```

> **주의:** description 문자열은 현재 WikiConfig.kt의 내용을 그대로 유지한다. 위 코드에서 `...`으로 축약된 부분은 실제 파일의 전체 문자열을 그대로 사용할 것.

**Step 2: 빌드 확인**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (description 파라미터 변경 없음이므로 기존 코드 영향 없음)

**Step 3: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt
git commit -m "feat: add displayName to PersonaType enum"
```

---

### Task 3: OrchestratorAgent — userId 기반 페르소나 룩업

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Step 1: 생성자 파라미터 교체**

`OrchestratorAgent.kt` line 47 (`private val persona: ...`)를 아래로 교체:

```kotlin
// Before (line 47):
private val persona: io.github.veronikapj.wiki.config.PersonaType = io.github.veronikapj.wiki.config.PersonaType.DEFAULT,

// After:
private val userPersonaStore: io.github.veronikapj.wiki.slack.UserPersonaStore? = null,
private val defaultPersona: io.github.veronikapj.wiki.config.PersonaType = io.github.veronikapj.wiki.config.PersonaType.DEFAULT,
```

**Step 2: `answer()` 시그니처에 userId 추가**

`answer()` (line 55-68)에 `userId: String? = null` 파라미터 추가:

```kotlin
suspend fun answer(
    question: String,
    listener: SearchProgressListener? = null,
    sessionId: String? = null,
    forceAllTools: Boolean = false,
    forceTool: String? = null,
    userId: String? = null,    // ← 추가
): String {
    log.info("OrchestratorAgent answering: '{}'", question)
    return if (useManualLoop) answerWithManualLoop(question, listener, sessionId, forceAllTools, forceTool, userId)
    else {
        if (forceAllTools) log.warn("forceAllTools=true is not supported in Koog agent path, ignored")
        answerWithKoogAgent(question, listener, sessionId, userId)
    }
}
```

**Step 3: `answerWithManualLoop()` 시그니처에 userId 추가, effectivePersona 교체**

`answerWithManualLoop` (line 73-79) 시그니처에 `userId: String? = null` 추가.

line 80의 `val effectivePersona = persona.description`을 교체:

```kotlin
// Before:
val effectivePersona = persona.description

// After:
val effectivePersona = userId?.let { userPersonaStore?.get(it) }?.description
    ?: defaultPersona.description
```

**Step 4: `answerWithKoogAgent()` 시그니처에 userId 추가, effectivePersona 교체**

`answerWithKoogAgent` (line 524) 시그니처에 `userId: String? = null` 추가.

line 524의 `val effectivePersona = persona.description`을 교체:

```kotlin
// Before:
val effectivePersona = persona.description

// After:
val effectivePersona = userId?.let { userPersonaStore?.get(it) }?.description
    ?: defaultPersona.description
```

**Step 5: 빌드 확인**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (Main.kt에서 `persona = config.persona`는 다음 task에서 수정)

실패 시: 컴파일 에러 메시지를 보고 `persona =` 를 참조하는 모든 위치 수정.

**Step 6: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: refactor OrchestratorAgent to support per-user persona lookup"
```

---

### Task 4: SlackBotGateway — userId를 answer() 호출에 전달

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

이 Task에서는 Home Tab UI는 건드리지 않는다. answer() 호출 4곳에 userId를 추가하는 것만 한다.

**Step 1: 생성자에 userPersonaStore 파라미터 추가**

`SlackBotGateway` 생성자 (line 33-41)에 파라미터 추가:

```kotlin
class SlackBotGateway(
    private val slackConfig: SlackConfig,
    private val orchestrator: OrchestratorAgent,
    private val configHandler: SlackConfigHandler,
    private val projectMemory: ProjectMemory? = null,
    private val confluenceClient: ConfluenceClient? = null,
    private val feedbackStore: FeedbackStore = FeedbackStore(),
    private val queryRewriter: QueryRewriter? = null,
    private val userPersonaStore: UserPersonaStore = UserPersonaStore(),  // ← 추가
)
```

**Step 2: `handleQueryAsync()` — userId 파라미터 추가 및 전달**

`handleQueryAsync` (line 250) 시그니처에 `userId: String? = null` 추가:

```kotlin
private fun handleQueryAsync(
    channel: String, threadTs: String?, sessionId: String, query: String,
    userId: String? = null,    // ← 추가
): Boolean {
```

line 283의 `orchestrator.answer(...)` 호출에 `userId = userId` 추가:
```kotlin
val result = runBlocking { orchestrator.answer(query, listener, sessionId = sessionId, userId = userId) }
```

**Step 3: `registerMentionHandler()` — userId 추출 및 전달**

`registerMentionHandler` (line 231-248)에서 userId 추출 후 `handleQueryAsync` 호출 시 전달:

```kotlin
app.event(com.slack.api.model.event.AppMentionEvent::class.java) { payload, ctx ->
    val query = extractQuery(payload.event.text)
    val channel = payload.event.channel
    val threadTs = payload.event.ts
    val userId = payload.event.user    // ← 추가
    ...
    } else if (!handleQueryAsync(channel = channel, threadTs = threadTs, sessionId = threadTs, query = query, userId = userId)) {
```

**Step 4: `handleAssistantQueryAsync()` — userId 파라미터 추가 및 전달**

`handleAssistantQueryAsync` (line 617) 시그니처에 `userId: String? = null` 추가.

line 637의 `orchestrator.answer(...)` 호출에 `userId = userId` 추가:
```kotlin
val result = runBlocking {
    orchestrator.answer(query, listener, sessionId = "assistant-$threadTs", forceTool = forcedTool, userId = userId)
}
```

**Step 5: `registerAssistantHandler()` — MessageEvent에서 userId 추출 및 전달**

MessageEvent 핸들러 (line 578) 에서 userId 추출:
```kotlin
val userId = event.user    // ← 추가 (event.channel 추출 근처)
```

`handleAssistantQueryAsync` 호출 (line 610)에 userId 전달:
```kotlin
if (!handleAssistantQueryAsync(channel, threadTs, query, forcedTool, userId = userId)) {
```

**Step 6: `triggerRequery()` — userId 파라미터 추가 및 전달 (optional, fallback null)**

`triggerRequery` (line 355) 에 `userId: String? = null` 추가.

line 394, 403의 `orchestrator.answer(...)` 호출에 `userId = userId` 추가.

`registerReactionHandler` (line 334) 에서 `event.user`를 triggerRequery에 전달:
```kotlin
triggerRequery(messageTs, channel, threadTs = messageTs, userId = event.user)
```

**Step 7: 빌드 확인**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

**Step 8: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: pass userId through Slack handlers to OrchestratorAgent"
```

---

### Task 5: Home Tab — 페르소나 선택 UI 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: Home Tab 뷰 빌드 로직을 메서드로 추출**

현재 `registerHomeHandler()` (line 442-499) 안에 인라인으로 작성된 `val view = view { ... }` 블록을 별도 private 메서드 `buildHomeView(userId: String): View`로 추출한다.

메서드 시그니처:
```kotlin
private fun buildHomeView(userId: String): com.slack.api.model.view.View {
    val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
    val codeStatus = lastCodeIndexedAt?.let { fmt.format(it) } ?: "미실행"
    val prStatus = lastPrIndexedAt?.let { fmt.format(it) } ?: "미실행"
    val confluenceStatus = lastConfluenceIndexedAt?.let { fmt.format(it) } ?: "미실행"
    val spaces = projectMemory?.load()
        ?.lines()
        ?.firstOrNull { it.contains("검색 스페이스") }
        ?.substringAfter("검색 스페이스:")?.trim()
        ?: "미설정"

    val currentPersona = userPersonaStore.get(userId) ?: PersonaType.DEFAULT

    return view { v ->
        v.type("home").blocks(
            listOf(
                // ... 기존 블록들 그대로 ...
                divider(),
                section { s -> s.text(markdownText("*🎭 페르소나 설정*\n나에게 적용되는 응답 스타일을 선택하세요.")) },
                actions { a ->
                    a.elements(listOf(
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
                    ))
                },
            )
        )
    }
}
```

> **기존 블록:** header, section(설명), divider, section(상태), divider, section(빠른액션), actions(버튼4개), divider, section(사용법) — 이 순서 그대로 유지하고 사용법 section **이후**에 divider + 페르소나 섹션 + actions 추가.

`registerHomeHandler()`에서는:
```kotlin
val view = buildHomeView(userId)
```

**Step 2: PersonaType import 추가**

`SlackBotGateway.kt` 상단 imports에 추가:
```kotlin
import io.github.veronikapj.wiki.config.PersonaType
```

**Step 3: `home_persona_select` block action 핸들러 추가**

`registerHomeHandler()` 안 기존 버튼 핸들러(line 539-544) 이후에 추가:

```kotlin
app.blockAction("home_persona_select") { req, ctx ->
    val userId = req.payload.user.id
    val selectedValue = req.payload.actions.firstOrNull()?.let {
        (it as? com.slack.api.model.block.element.StaticSelectElement)?.selectedOption?.value
            ?: runCatching {
                val field = it.javaClass.getDeclaredField("selectedOption")
                field.isAccessible = true
                (field.get(it) as? com.slack.api.model.block.composition.OptionObject)?.value
            }.getOrNull()
    }
    val persona = selectedValue?.let {
        runCatching { PersonaType.valueOf(it) }.getOrNull()
    } ?: PersonaType.DEFAULT

    userPersonaStore.set(userId, persona)

    runCatching {
        slackClient.viewsPublish { it.userId(userId).view(buildHomeView(userId)) }
    }.onFailure { log.warn("Failed to refresh home view: {}", it.message) }

    slackClient.chatPostMessage { it.channel(userId).text("✅ 페르소나가 *${persona.displayName}*으로 변경되었습니다.") }
    ctx.ack()
}
```

> **Note:** Slack Bolt SDK의 block action에서 `StaticSelectElement` 접근 방식은 SDK 버전에 따라 다를 수 있다. 컴파일 에러 시, `req.payload.actions.firstOrNull()` 타입을 확인하고 실제 SDK API에 맞게 조정한다. 단순하게 `req.payload.actions.firstOrNull()?.toString()`으로 JSON 파싱하는 방법도 대안이다.

**Step 4: 빌드 확인**

```bash
./gradlew compileKotlin 2>&1 | tail -20
```

컴파일 에러 시 SDK API를 확인하고 block action value 추출 방식 조정.

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: add per-user persona selector to Home Tab"
```

---

### Task 6: Main.kt — UserPersonaStore 연결

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt:272-413`

**Step 1: UserPersonaStore 초기화 추가**

OrchestratorAgent 초기화(line 272) 바로 위에 추가:

```kotlin
val userPersonaStore = io.github.veronikapj.wiki.slack.UserPersonaStore()
```

**Step 2: OrchestratorAgent에서 `persona =` → `userPersonaStore + defaultPersona =` 교체**

line 287의 `persona = config.persona`를 아래로 교체:

```kotlin
userPersonaStore = userPersonaStore,
defaultPersona = config.persona,
```

**Step 3: SlackBotGateway에 userPersonaStore 추가**

line 405-413의 `SlackBotGateway(...)` 호출에 추가:

```kotlin
val gateway = SlackBotGateway(
    slackConfig = config.slack.copy(botToken = slackBotToken, appToken = slackAppToken),
    orchestrator = orchestrator,
    configHandler = configHandler,
    projectMemory = projectMemory,
    confluenceClient = confluenceClient,
    queryRewriter = queryRewriter,
    userPersonaStore = userPersonaStore,    // ← 추가
)
```

**Step 4: 전체 빌드 + 테스트**

```bash
./gradlew build 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

**Step 5: 커밋**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat: wire UserPersonaStore into OrchestratorAgent and SlackBotGateway"
```

---

## 수동 검증

1. 봇 실행 후 Slack App Home 열기
2. "🎭 페르소나 설정" 섹션에 드롭다운이 표시되는지 확인
3. 드롭다운에서 "MZ 인턴" 선택
4. DM "✅ 페르소나가 MZ 인턴으로 변경되었습니다." 수신 확인
5. AI 패널에서 질문 → MZ 인턴 말투로 답변 오는지 확인
6. 다른 Slack 사용자로 접속 → 페르소나 설정이 독립적인지 확인
7. 봇 재시작 후 설정이 유지되는지 확인 (`.wiki/user-personas.json` 생성 확인)
