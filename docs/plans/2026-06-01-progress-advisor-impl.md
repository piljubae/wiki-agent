# ProgressAdvisorTool Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** progress.json 데이터를 LLM에 넘겨 팀장/부문장 두 관점의 성과 코칭 피드백을 생성하는 ProgressAdvisorTool을 추가한다.

**Architecture:** PersonalDataTool(데이터 조회)과 분리된 별도 Tool. progress.json 전체를 읽어 현재 날짜와 함께 LLM 프롬프트에 전달하고, 팀장(실행 속도)/부문장(포트폴리오 전략) 두 페르소나의 피드백을 한 응답에 생성. OrchestratorAgent 라우터에 `progressAdvisor` 옵션으로 등록.

**Tech Stack:** Kotlin, Koog Agent SDK (`@Tool`/`@LLMDescription`), MultiLLMPromptExecutor, kotlinx.serialization (JSON 파싱), JUnit 5

---

### Task 1: ProgressAdvisorTool 구현

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorTool.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorToolTest.kt`

**Step 1: Write the failing test**

`ProgressAdvisorToolTest.kt`:

```kotlin
package io.github.veronikapj.wiki.agent.tool

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import io.mockk.coEvery
import io.mockk.mockk
import ai.koog.prompt.message.Message
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ProgressAdvisorToolTest {

    private val testFile = "build/test-advisor-progress-${System.nanoTime()}.json"
    private val sampleJson = """
    {
      "version": "1.0",
      "year": 2026,
      "owner": { "name": "테스트유저", "team": "테스트팀" },
      "goals": [
        {
          "id": "goal-1",
          "name": "기술력 향상",
          "weight": 30,
          "keywords": ["Tech"],
          "indicators": [
            { "id": "ind-1", "name": "발표", "start": 0, "target": 2, "current": 0, "unit": "회" }
          ]
        },
        {
          "id": "goal-2",
          "name": "자동화 구축",
          "weight": 40,
          "keywords": ["AI"],
          "indicators": [
            { "id": "ind-2", "name": "파이프라인", "start": 0, "target": 3, "current": 1, "unit": "건" }
          ]
        }
      ],
      "g7Expectations": [
        { "id": "g7-1", "label": "대규모 과제", "linkedGoals": ["goal-1"], "status": "covered" }
      ]
    }
    """.trimIndent()

    private fun createMockExecutor(response: String): MultiLLMPromptExecutor {
        val executor = mockk<MultiLLMPromptExecutor>()
        coEvery { executor.execute(any(), any()) } returns listOf(
            Message.Response(content = response)
        )
        return executor
    }

    private fun createTool(executor: MultiLLMPromptExecutor): ProgressAdvisorTool {
        File(testFile).writeText(sampleJson)
        return ProgressAdvisorTool(
            progressFile = testFile,
            allowedUsers = setOf("U_ALLOWED"),
            executor = executor,
            model = mockk<LLModel>(),
        )
    }

    @Test fun `advise returns LLM-generated coaching feedback`() {
        val mockResponse = "## 팀장 피드백\n좋은 진행입니다.\n\n## 부문장 피드백\n전략적으로 잘 하고 있어요."
        val tool = createTool(createMockExecutor(mockResponse))
        val result = tool.advise("U_ALLOWED")
        assertTrue(result.contains("팀장"))
        assertTrue(result.contains("부문장"))
        cleanup()
    }

    @Test fun `advise blocks unauthorized user`() {
        val tool = createTool(createMockExecutor("should not reach"))
        val result = tool.advise("U_OTHER")
        assertTrue(result.contains("권한") || result.contains("허용"))
        cleanup()
    }

    @Test fun `advise handles missing file gracefully`() {
        val executor = createMockExecutor("unused")
        val tool = ProgressAdvisorTool(
            progressFile = "/nonexistent/progress.json",
            allowedUsers = setOf("U_ALLOWED"),
            executor = executor,
            model = mockk<LLModel>(),
        )
        val result = tool.advise("U_ALLOWED")
        assertTrue(result.contains("설정되지 않") || result.contains("파일"))
    }

    private fun cleanup() { File(testFile).delete() }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :test --tests "*.ProgressAdvisorToolTest" -q`
Expected: FAIL — ProgressAdvisorTool 클래스 없음

**Step 3: Write minimal implementation**

`ProgressAdvisorTool.kt`:

```kotlin
package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

class ProgressAdvisorTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
    private val tracker: SourceTracker? = null,
) {
    private val log = LoggerFactory.getLogger(ProgressAdvisorTool::class.java)

    private fun loadFullJson(): String? {
        val file = File(progressFile)
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { log.warn("Failed to read progress.json: {}", it.message) }
            .getOrNull()
    }

    private fun isAllowed(userId: String?): Boolean =
        userId != null && userId in allowedUsers

    @Tool("progressAdvisor")
    @LLMDescription("성과 목표에 대한 조언/피드백/1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘' 질문에 사용하세요.")
    fun advise(
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("ProgressAdvisor")

        val json = loadFullJson() ?: return "성과 목표 파일이 설정되지 않았거나 읽을 수 없습니다."
        val today = LocalDate.now()

        val advisorPrompt = buildString {
            appendLine("당신은 성과 목표 코칭 전문가입니다. 아래 성과 데이터를 분석하고, 두 관점에서 1:1 피드백을 제공하세요.")
            appendLine()
            appendLine("오늘 날짜: $today")
            appendLine("평가 기간: 2026-01-01 ~ 2026-12-31")
            appendLine()
            appendLine("=== 성과 데이터 (JSON) ===")
            appendLine(json)
            appendLine("=== 끝 ===")
            appendLine()
            appendLine("두 관점으로 피드백을 작성하세요:")
            appendLine()
            appendLine("## 팀장 피드백")
            appendLine("앱개발팀장 관점. 스프린트 리뷰처럼 실행 속도에 집중.")
            appendLine("- 각 지표의 현재 값 vs 목표 값을 보고 실행 속도를 판단하세요.")
            appendLine("- 0이거나 진행이 느린 지표가 있으면 '이거 왜 안 움직여?' 라고 직접적으로 물으세요.")
            appendLine("- 이번 달 또는 이번 주에 할 수 있는 구체적인 액션을 제안하세요.")
            appendLine("- 잘 된 것도 짚어주세요 (completed=true인 지표, 활발한 workstream 등).")
            appendLine("- 톤: 직설적이고 실무적. '이번 주에 이거 해', '이건 잘했네' 느낌.")
            appendLine()
            appendLine("## 부문장 피드백")
            appendLine("클라이언트 부문장 관점. 반기 성과 면담처럼 포트폴리오 전략에 집중.")
            appendLine("- 가중치 배분이 적절한지, 가중치 0인 목표는 괜찮은지 검토하세요.")
            appendLine("- indicator가 비어있는 목표는 '연말에 이걸 어떻게 숫자로 증명할 거야?' 라고 물으세요.")
            appendLine("- g7Expectations가 있으면 각 항목이 실제 활동(events, workstreams)으로 뒷받침되는지 체크하세요.")
            appendLine("- 연말 평가 narrative에서 빠지면 안 되는 포인트를 짚어주세요.")
            appendLine("- 톤: 전략적이고 큰 그림 중심. '하반기 평가에서 이게 빠지면 곤란해' 느낌.")
            appendLine()
            appendLine("형식: Slack mrkdwn으로 출력. *굵게* `코드` 허용. # ## ** [링크](url) 금지.")
        }

        return runBlocking {
            runCatching {
                executor.execute(
                    prompt("advisor") { user(advisorPrompt) }, model
                ).joinToString("") { it.content }.trim()
            }.getOrElse { e ->
                log.error("Advisor LLM call failed: {}", e.message)
                "코칭 피드백 생성 중 오류가 발생했습니다: ${e.message}"
            }
        }
    }
}
```

**Step 4: Check if MockK is available, add if needed**

Run: `grep -r "mockk" build.gradle* --include="*.kts" --include="*.gradle"`
If MockK is not in dependencies, add to `build.gradle.kts`:
```kotlin
testImplementation("io.mockk:mockk:1.13.13")
```

**Step 5: Run test to verify it passes**

Run: `./gradlew :test --tests "*.ProgressAdvisorToolTest" -q`
Expected: PASS

**Step 6: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorTool.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorToolTest.kt
git commit -m "feat: add ProgressAdvisorTool with LLM-based coaching"
```

---

### Task 2: OrchestratorAgent에 ProgressAdvisorTool 연결

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Step 1: 생성자에 파라미터 추가**

import 추가:
```kotlin
import io.github.veronikapj.wiki.agent.tool.ProgressAdvisorTool
```

생성자 (line 42, `personalDataTool` 아래):
```kotlin
private val progressAdvisorTool: ProgressAdvisorTool? = null,
```

**Step 2: availableTools에 추가 (line 113 부근)**

`personalDataTool?.let { "personalGoalQuery" },` 아래에:
```kotlin
progressAdvisorTool?.let { "progressAdvisor" },
```

**Step 3: knownTools에 추가 (line 255 부근)**

`"personalProgress", "personalGoalQuery",` 아래에:
```kotlin
"progressAdvisor",
```

**Step 4: toolOptions에 추가 (line 148 부근)**

`if (personalDataTool != null) "personalGoalQuery" else null,` 아래에:
```kotlin
if (progressAdvisorTool != null) "progressAdvisor" else null,
```

**Step 5: 라우터 프롬프트 규칙에 추가 (line 191 부근)**

`personalGoalQuery` 규칙 아래, `}` 닫기 전에:
```kotlin
if (progressAdvisorTool != null) {
    appendLine("- progressAdvisor: 성과 목표 조언·피드백·1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘', '어떻게 하면 좋을까' 질문.")
}
```

**Step 6: when 디스패치에 분기 추가 (line 353 부근)**

`personalGoalQuery` 분기 아래에:
```kotlin
toolName == "progressAdvisor" && progressAdvisorTool != null ->
    runCatching { progressAdvisorTool!!.advise(userId ?: "") }.getOrNull()
```

**Step 7: 빌드 확인**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: wire ProgressAdvisorTool into OrchestratorAgent router"
```

---

### Task 3: Main.kt에서 ProgressAdvisorTool 생성 및 주입

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

**Step 1: import 추가**

```kotlin
import io.github.veronikapj.wiki.agent.tool.ProgressAdvisorTool
```

**Step 2: PersonalDataTool 생성 블록 아래에 추가 (line 249 부근)**

```kotlin
// Progress Advisor Tool
var progressAdvisorTool: ProgressAdvisorTool? = null
if (personalDataTool != null) {
    progressAdvisorTool = ProgressAdvisorTool(
        progressFile = config.personalData.progressFile,
        allowedUsers = config.personalData.allowedUsers.toSet(),
        executor = executor,
        model = routerModel,
        tracker = sourceTracker,
    )
    log.info("ProgressAdvisor enabled")
}
```

**Step 3: OrchestratorAgent 생성자에 추가 (line 261 부근)**

`personalDataTool = personalDataTool,` 아래에:
```kotlin
progressAdvisorTool = progressAdvisorTool,
```

**Step 4: 빌드 확인**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat: create and inject ProgressAdvisorTool in Main"
```

---

### Task 4: SlackBotGateway에 표시명 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: toolDisplayNames에 추가 (line 87 부근)**

`"personalGoalQuery" to "성과 목표",` 아래에:
```kotlin
"progressAdvisor" to "성과 코칭",
```

**Step 2: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat: add progressAdvisor display name in Slack UI"
```

---

### Task 5: 전체 빌드 및 테스트 검증

**Step 1: 전체 테스트**

Run: `./gradlew :test -q`
Expected: PersonalDataToolTest + ProgressAdvisorToolTest + ConfigLoaderTest 모두 PASS

**Step 2: 컴파일 확인**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL
