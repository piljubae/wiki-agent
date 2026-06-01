# PersonalDataTool Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** progress.json 성과 트래커를 읽어 "목표 진척도" 질문에 답하는 PersonalDataTool을 wiki-agent에 추가한다.

**Architecture:** 기존 KnowledgeTool 패턴을 따라 `@Tool` 어노테이션 기반 Koog Tool 클래스를 생성하고, OrchestratorAgent의 라우터 프롬프트에 `personalData` 옵션을 추가한다. userId 화이트리스트로 접근 제어한다.

**Tech Stack:** Kotlin, Koog Agent SDK (`@Tool`/`@LLMDescription`), kotlinx.serialization (JSON 파싱), JUnit 5

---

### Task 1: PersonalDataConfig 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/ConfigLoader.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/config/ConfigLoaderTest.kt`

**Step 1: Write the failing test**

`ConfigLoaderTest.kt`에 추가:

```kotlin
@Test fun `parses personalData section`() {
    val yaml = """
        model:
          provider: CLAUDE_CODE
        personalData:
          enabled: true
          progressFile: /tmp/progress.json
          allowedUsers:
            - U01ABC
            - U02DEF
    """.trimIndent()
    val config = ConfigLoader.fromString(yaml)
    assertTrue(config.personalData.enabled)
    assertEquals("/tmp/progress.json", config.personalData.progressFile)
    assertEquals(listOf("U01ABC", "U02DEF"), config.personalData.allowedUsers)
}

@Test fun `personalData defaults to disabled`() {
    val yaml = """
        model:
          provider: CLAUDE_CODE
    """.trimIndent()
    val config = ConfigLoader.fromString(yaml)
    assertFalse(config.personalData.enabled)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.ConfigLoaderTest.parses personalData section" -q`
Expected: FAIL — `personalData` 필드 없음

**Step 3: Write minimal implementation**

`WikiConfig.kt`에 추가:

```kotlin
data class PersonalDataConfig(
    val enabled: Boolean = false,
    val progressFile: String = "",
    val allowedUsers: List<String> = emptyList(),
)
```

`WikiConfig` data class에 필드 추가:

```kotlin
val personalData: PersonalDataConfig = PersonalDataConfig(),
```

`ConfigLoader.kt`에 파싱 로직 추가:
- `inPersonalData` 플래그 + `inAllowedUsers` 플래그
- `personalData:` 섹션 파싱: `enabled`, `progressFile`, `allowedUsers` 리스트
- `WikiConfig` 생성 시 `personalData = PersonalDataConfig(...)` 전달

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*.ConfigLoaderTest" -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt \
        src/main/kotlin/io/github/veronikapj/wiki/config/ConfigLoader.kt \
        src/test/kotlin/io/github/veronikapj/wiki/config/ConfigLoaderTest.kt
git commit -m "feat: add PersonalDataConfig to WikiConfig"
```

---

### Task 2: PersonalDataTool 구현

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/PersonalDataTool.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/tool/PersonalDataToolTest.kt`

**Step 1: Write the failing test**

```kotlin
package io.github.veronikapj.wiki.agent.tool

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class PersonalDataToolTest {

    private val testFile = "build/test-progress-${System.nanoTime()}.json"
    private val sampleJson = """
    {
      "version": "1.0",
      "year": 2026,
      "owner": { "name": "배필주", "team": "프로덕트앱개발" },
      "goals": [
        {
          "id": "goal-1",
          "name": "Google 협업을 통한 Android 기술력 향상",
          "weight": 20,
          "keywords": ["Google", "I/O"],
          "indicators": [
            { "id": "io-workshop", "name": "I/O 워크숍 발표", "start": 0, "target": 1, "current": 0, "unit": "회" }
          ]
        },
        {
          "id": "goal-2",
          "name": "AI 팀 셋업 자동화",
          "weight": 30,
          "keywords": ["AI", "Skill"],
          "indicators": [
            { "id": "team-skills", "name": "팀 공용 Skill/MCP 자산", "start": 3, "target": 8, "current": 5, "unit": "개" },
            { "id": "code-review-bot", "name": "AI 코드리뷰 봇", "start": 0, "target": 1, "current": 1, "unit": "건", "completed": true }
          ]
        }
      ]
    }
    """.trimIndent()

    @AfterEach fun cleanup() { File(testFile).delete() }

    private fun createTool(userId: String = "U_ALLOWED") : PersonalDataTool {
        File(testFile).writeText(sampleJson)
        return PersonalDataTool(
            progressFile = testFile,
            allowedUsers = setOf("U_ALLOWED"),
        )
    }

    @Test fun `getProgressSummary returns all goals with progress`() {
        val tool = createTool()
        val result = tool.getProgressSummary("U_ALLOWED")
        assertTrue(result.contains("Google"))
        assertTrue(result.contains("AI"))
        assertTrue(result.contains("5"))  // current value
        assertTrue(result.contains("8"))  // target value
    }

    @Test fun `getProgressSummary blocks unauthorized user`() {
        val tool = createTool()
        val result = tool.getProgressSummary("U_OTHER")
        assertTrue(result.contains("권한") || result.contains("허용"))
    }

    @Test fun `queryGoal finds by keyword`() {
        val tool = createTool()
        val result = tool.queryGoal("AI", "U_ALLOWED")
        assertTrue(result.contains("AI"))
        assertTrue(result.contains("Skill"))
        assertFalse(result.contains("Google"))
    }

    @Test fun `queryGoal returns not-found for unmatched keyword`() {
        val tool = createTool()
        val result = tool.queryGoal("없는키워드", "U_ALLOWED")
        assertTrue(result.contains("찾을 수 없") || result.contains("없습니다"))
    }

    @Test fun `handles missing file gracefully`() {
        val tool = PersonalDataTool(
            progressFile = "/nonexistent/progress.json",
            allowedUsers = setOf("U_ALLOWED"),
        )
        val result = tool.getProgressSummary("U_ALLOWED")
        assertTrue(result.contains("설정되지 않") || result.contains("파일"))
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.PersonalDataToolTest" -q`
Expected: FAIL — PersonalDataTool 클래스 없음

**Step 3: Write minimal implementation**

```kotlin
package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

class PersonalDataTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
    private val tracker: SourceTracker? = null,
) {
    private val log = LoggerFactory.getLogger(PersonalDataTool::class.java)

    private fun loadGoals(): JsonArray? {
        val file = File(progressFile)
        if (!file.exists()) return null
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject["goals"]?.jsonArray
        }.onFailure { log.warn("Failed to parse progress.json: {}", it.message) }
            .getOrNull()
    }

    private fun isAllowed(userId: String?): Boolean =
        userId != null && userId in allowedUsers

    @Tool("personalProgress")
    @LLMDescription("개인 성과 목표 전체 진척도를 조회합니다. '올해 성과', '목표 진척도' 같은 질문에 사용하세요.")
    fun getProgressSummary(
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("PersonalData")

        val goals = loadGoals() ?: return "성과 목표 파일이 설정되지 않았거나 읽을 수 없습니다."

        return buildString {
            appendLine("📊 2026 성과 목표 현황\n")
            for (goal in goals) {
                val obj = goal.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val weight = obj["weight"]?.jsonPrimitive?.int ?: 0
                appendLine("### $name (가중치 ${weight}%)")

                val indicators = obj["indicators"]?.jsonArray ?: continue
                for (ind in indicators) {
                    val io = ind.jsonObject
                    val indName = io["name"]?.jsonPrimitive?.content ?: ""
                    val current = io["current"]?.jsonPrimitive?.int ?: 0
                    val target = io["target"]?.jsonPrimitive?.int ?: 0
                    val unit = io["unit"]?.jsonPrimitive?.content ?: ""
                    val completed = io["completed"]?.jsonPrimitive?.booleanOrNull ?: false
                    val pct = if (target > 0) (current * 100 / target) else 0
                    val status = if (completed) "✅" else if (pct >= 100) "✅" else "🔄"
                    appendLine("- $status $indName: $current/$target $unit ($pct%)")
                }
                appendLine()
            }
        }.trimEnd()
    }

    @Tool("personalGoalQuery")
    @LLMDescription("특정 성과 목표를 키워드로 검색합니다. 'AI 목표', 'Google 진척도' 같은 질문에 사용하세요.")
    fun queryGoal(
        @LLMDescription("검색할 키워드 (목표명, 지표명, 또는 keywords에 포함된 단어)")
        keyword: String,
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("PersonalData")

        val goals = loadGoals() ?: return "성과 목표 파일이 설정되지 않았거나 읽을 수 없습니다."
        val kw = keyword.lowercase()

        val matched = goals.filter { goal ->
            val obj = goal.jsonObject
            val name = (obj["name"]?.jsonPrimitive?.content ?: "").lowercase()
            val keywords = obj["keywords"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() } ?: emptyList()
            val indicators = obj["indicators"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.lowercase() } ?: emptyList()
            name.contains(kw) || keywords.any { it.contains(kw) } || indicators.any { it.contains(kw) }
        }

        if (matched.isEmpty()) return "'$keyword' 관련 성과 목표를 찾을 수 없습니다."

        return buildString {
            for (goal in matched) {
                val obj = goal.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val weight = obj["weight"]?.jsonPrimitive?.int ?: 0
                val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                appendLine("### $name (가중치 ${weight}%)")
                if (desc.isNotBlank()) appendLine("> $desc\n")

                val indicators = obj["indicators"]?.jsonArray ?: continue
                for (ind in indicators) {
                    val io = ind.jsonObject
                    val indName = io["name"]?.jsonPrimitive?.content ?: ""
                    val current = io["current"]?.jsonPrimitive?.int ?: 0
                    val target = io["target"]?.jsonPrimitive?.int ?: 0
                    val unit = io["unit"]?.jsonPrimitive?.content ?: ""
                    val details = io["details"]?.jsonPrimitive?.content ?: ""
                    val completed = io["completed"]?.jsonPrimitive?.booleanOrNull ?: false
                    val pct = if (target > 0) (current * 100 / target) else 0
                    val status = if (completed) "✅" else "🔄"
                    appendLine("- $status **$indName**: $current/$target $unit ($pct%)")
                    if (details.isNotBlank()) appendLine("  $details")
                }
                appendLine()
            }
        }.trimEnd()
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*.PersonalDataToolTest" -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/tool/PersonalDataTool.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/tool/PersonalDataToolTest.kt
git commit -m "feat: add PersonalDataTool with progress.json reader"
```

---

### Task 3: OrchestratorAgent에 PersonalDataTool 연결

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

**Step 1: OrchestratorAgent에 personalDataTool 파라미터 추가**

`OrchestratorAgent.kt` 생성자에 추가:

```kotlin
private val personalDataTool: PersonalDataTool? = null,
```

import 추가:

```kotlin
import io.github.veronikapj.wiki.agent.tool.PersonalDataTool
```

**Step 2: 라우터 프롬프트에 personalData 옵션 추가**

`answerWithManualLoop()`의 `availableTools` 리스트에 추가:

```kotlin
personalDataTool?.let { "personalProgress" },
personalDataTool?.let { "personalGoalQuery" },
```

`toolOptions`에 추가:

```kotlin
if (personalDataTool != null) "personalProgress" else null,
if (personalDataTool != null) "personalGoalQuery" else null,
```

라우터 프롬프트 규칙에 추가 (`appendLine` 블록):

```kotlin
if (personalDataTool != null) {
    appendLine("- personalProgress: 성과 목표 전체 현황. '올해 성과', '진척도', '목표 어때' 질문.")
    appendLine("- personalGoalQuery: 특정 목표/지표 검색. 'AI 목표', 'Google 진척도', 'Skill 몇 개' 질문.")
}
```

**Step 3: 도구 호출부에 personalDataTool 분기 추가**

`answerWithManualLoop()`의 tool 호출 `when` 분기에 추가:

```kotlin
"personalProgress" -> personalDataTool!!.getProgressSummary(userId ?: "")
"personalGoalQuery" -> personalDataTool!!.queryGoal(query, userId ?: "")
```

**Step 4: Main.kt에서 PersonalDataTool 생성 및 주입**

`Main.kt`에서 orchestrator 생성 전에 추가:

```kotlin
// Personal Data Tool
var personalDataTool: PersonalDataTool? = null
if (config.personalData.enabled && config.personalData.progressFile.isNotBlank()) {
    personalDataTool = PersonalDataTool(
        progressFile = config.personalData.progressFile,
        allowedUsers = config.personalData.allowedUsers.toSet(),
        tracker = sourceTracker,
    )
    log.info("PersonalData enabled: file={}, allowedUsers={}", config.personalData.progressFile, config.personalData.allowedUsers.size)
}
```

`OrchestratorAgent` 생성자에 추가:

```kotlin
personalDataTool = personalDataTool,
```

**Step 5: 빌드 확인**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat: wire PersonalDataTool into OrchestratorAgent router"
```

---

### Task 4: SlackBotGateway에 PersonalDataTool 표시 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: toolDisplayNames에 추가**

```kotlin
"personalProgress" to "성과 목표",
"personalGoalQuery" to "성과 목표",
```

**Step 2: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: add PersonalData display name in Slack UI"
```

---

### Task 5: config.yml에 personalData 설정 추가

**Files:**
- Modify: `.wikiq/config.yml`

**Step 1: config.yml에 섹션 추가**

```yaml
personalData:
  enabled: true
  progressFile: /Users/pilju.bae/Documents/Claude Cowork/성과목표-2026/progress.json
  allowedUsers:
    - U_PILJU_REAL_ID    # Slack에서 본인 userId 확인 후 교체
```

> 본인 Slack userId는 Slack 프로필 → ... → Copy member ID로 확인

**Step 2: Commit**

```bash
git add .wikiq/config.yml
git commit -m "chore: enable personalData in config"
```

---

### Task 6: 통합 테스트 — CLI 모드로 검증

**Step 1: 빌드 및 실행**

Run: `./gradlew run -q`

Slack 토큰이 없으면 CLI 모드로 진입.

**Step 2: 테스트 질문**

```
질문 > 올해 성과 목표 진척도 알려줘
질문 > AI Skill 몇 개 만들었어?
질문 > Google 관련 목표 어때?
```

Expected: progress.json에서 읽어온 데이터 기반 답변

**Step 3: 접근 제어 확인**

CLI 모드에서는 userId가 없으므로 "허용된 사용자만" 메시지가 나와야 정상.
Slack DM에서 본인 userId로 테스트하면 정상 답변.
