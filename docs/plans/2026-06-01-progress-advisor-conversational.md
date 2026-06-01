# ProgressAdvisorTool 대화형 코칭 확장 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** ProgressAdvisorTool을 일방적 분석에서 대화형 1:1 코칭으로 확장한다. 사용자 메시지를 받아 대화를 이어가고, 성과 데이터가 없으면 URL/파일 경로를 요청하여 읽어서 사용한다.

**Architecture:** 기존 `advise(userId)` → `advise(userId, message)`로 시그니처 변경. 성과 데이터 로딩을 3단계(config 파일 → 메시지 내 URL/경로 감지 → 안내 메시지)로 확장. 첫 호출은 팀장/부문장 두 관점 분석, 후속은 단일 코치 페르소나. 대화 이력은 기존 ConversationStore 세션 그대로 활용 (Tool은 stateless).

**Tech Stack:** Kotlin, java.net.URI (URL fetch), java.io.File (로컬 파일), MultiLLMPromptExecutor, JUnit 5 + MockK

---

### Task 1: 테스트 업데이트 — advise(userId, message) 시그니처

**Files:**
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorToolTest.kt`

**Step 1: 기존 테스트를 새 시그니처에 맞게 수정 + 새 테스트 추가**

기존 3개 테스트의 `advise("U_ALLOWED")` → `advise("U_ALLOWED", "피드백 줘")` 로 변경하고, 새 테스트 4개를 추가한다.

`ProgressAdvisorToolTest.kt` 전체를 아래로 교체:

```kotlin
package io.github.veronikapj.wiki.agent.tool

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

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
            Message.Assistant(content = response, metaInfo = ResponseMetaInfo.Empty)
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

    private fun createToolNoFile(executor: MultiLLMPromptExecutor): ProgressAdvisorTool {
        return ProgressAdvisorTool(
            progressFile = "/nonexistent/progress.json",
            allowedUsers = setOf("U_ALLOWED"),
            executor = executor,
            model = mockk<LLModel>(),
        )
    }

    @Test fun `advise with data returns coaching feedback`() {
        val mockResponse = "## 팀장 피드백\n좋은 진행입니다.\n\n## 부문장 피드백\n전략적으로 잘 하고 있어요."
        val tool = createTool(createMockExecutor(mockResponse))
        val result = tool.advise("U_ALLOWED", "피드백 줘")
        assertTrue(result.contains("팀장"))
        assertTrue(result.contains("부문장"))
        cleanup()
    }

    @Test fun `advise blocks unauthorized user`() {
        val tool = createTool(createMockExecutor("should not reach"))
        val result = tool.advise("U_OTHER", "피드백 줘")
        assertTrue(result.contains("권한") || result.contains("허용"))
        cleanup()
    }

    @Test fun `advise without data asks for link or path`() {
        val tool = createToolNoFile(createMockExecutor("unused"))
        val result = tool.advise("U_ALLOWED", "피드백 줘")
        assertTrue(result.contains("파일 경로") || result.contains("URL"))
    }

    @Test fun `advise reads local file path from message`() {
        val tempFile = File.createTempFile("progress-test", ".json")
        tempFile.writeText(sampleJson)
        try {
            val mockResponse = "코칭 결과입니다."
            val tool = createToolNoFile(createMockExecutor(mockResponse))
            val result = tool.advise("U_ALLOWED", "여기 봐줘 ${tempFile.absolutePath}")
            assertTrue(result.contains("코칭"))
        } finally {
            tempFile.delete()
        }
    }

    @Test fun `advise without data allows freeform coaching`() {
        val mockResponse = "커리어 조언입니다."
        val tool = createToolNoFile(createMockExecutor(mockResponse))
        val result = tool.advise("U_ALLOWED", "요즘 역할이 애매한데 어떻게 해야 할까")
        assertTrue(result.contains("커리어"))
    }

    private fun cleanup() { File(testFile).delete() }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :test --tests "*.ProgressAdvisorToolTest" -q`
Expected: FAIL — `advise` 시그니처 불일치

**Step 3: Commit**

```bash
git add src/test/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorToolTest.kt
git commit -m "test: update ProgressAdvisorToolTest for conversational advise(userId, message)"
```

---

### Task 2: ProgressAdvisorTool 대화형 확장 구현

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorTool.kt`

**Step 1: 전체 구현 교체**

`ProgressAdvisorTool.kt` 전체를 아래로 교체:

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
import java.net.URI
import java.time.LocalDate

class ProgressAdvisorTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
    private val tracker: SourceTracker? = null,
) {
    private val log = LoggerFactory.getLogger(ProgressAdvisorTool::class.java)

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")
        val FILE_PATH_PATTERN = Regex("""(?:^|\s)([~/]\S+\.\w+)""")

        const val DATA_REQUEST_MESSAGE =
            "성과 데이터를 찾을 수 없어요. 아래 중 하나를 보내주세요:\n" +
            "• 파일 경로 (예: `/Users/.../progress.json`)\n" +
            "• URL (예: Google Docs, Notion 공유 링크)"
    }

    private fun loadFromConfigFile(): String? {
        val file = File(progressFile)
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { log.warn("Failed to read progress file: {}", it.message) }
            .getOrNull()
    }

    private fun loadFromUrl(url: String): String? {
        return runCatching {
            URI(url).toURL().readText()
        }.onFailure { log.warn("Failed to fetch URL {}: {}", url, it.message) }
            .getOrNull()
    }

    private fun loadFromFilePath(path: String): String? {
        val resolved = if (path.startsWith("~")) path.replaceFirst("~", System.getProperty("user.home")) else path
        val file = File(resolved)
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { log.warn("Failed to read file {}: {}", path, it.message) }
            .getOrNull()
    }

    private fun extractDataFromMessage(message: String): String? {
        URL_PATTERN.find(message)?.value?.let { url ->
            loadFromUrl(url)?.let { return it }
        }
        FILE_PATH_PATTERN.find(message)?.groupValues?.get(1)?.let { path ->
            loadFromFilePath(path)?.let { return it }
        }
        return null
    }

    private fun isAllowed(userId: String?): Boolean =
        userId != null && userId in allowedUsers

    private fun isDataRequest(message: String): Boolean {
        val keywords = listOf("피드백", "조언", "1:1", "코칭", "성과", "목표", "진척", "어때")
        return keywords.any { message.contains(it) }
    }

    @Tool("progressAdvisor")
    @LLMDescription("성과 목표에 대한 조언/피드백/1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘' 질문에 사용하세요.")
    fun advise(
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
        @LLMDescription("사용자의 메시지")
        message: String = "",
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("ProgressAdvisor")

        val today = LocalDate.now()

        // 1. config 파일에서 로드
        var data = loadFromConfigFile()

        // 2. 없으면 메시지에서 URL/경로 감지
        if (data == null) {
            data = extractDataFromMessage(message)
        }

        // 3. 데이터 없고 성과 관련 질문이면 링크 요청
        if (data == null && isDataRequest(message)) {
            return DATA_REQUEST_MESSAGE
        }

        // 4. LLM 프롬프트 구성
        val advisorPrompt = buildString {
            if (data != null) {
                appendLine("당신은 성과 목표 코칭 전문가입니다. 아래 성과 데이터를 분석하고, 두 관점에서 1:1 피드백을 제공하세요.")
                appendLine()
                appendLine("오늘 날짜: $today")
                appendLine("평가 기간: 2026-01-01 ~ 2026-12-31")
                appendLine()
                appendLine("=== 성과 데이터 ===")
                appendLine(data)
                appendLine("=== 끝 ===")
                appendLine()
                appendLine("두 관점으로 피드백을 작성하세요:")
                appendLine()
                appendLine("*팀장 피드백*")
                appendLine("앱개발팀장 관점. 스프린트 리뷰처럼 실행 속도에 집중.")
                appendLine("- 각 지표의 현재 값 vs 목표 값을 보고 실행 속도를 판단하세요.")
                appendLine("- 0이거나 진행이 느린 지표가 있으면 '이거 왜 안 움직여?' 라고 직접적으로 물으세요.")
                appendLine("- 이번 달 또는 이번 주에 할 수 있는 구체적인 액션을 제안하세요.")
                appendLine("- 잘 된 것도 짚어주세요 (completed=true인 지표, 활발한 workstream 등).")
                appendLine("- 톤: 직설적이고 실무적. '이번 주에 이거 해', '이건 잘했네' 느낌.")
                appendLine()
                appendLine("*부문장 피드백*")
                appendLine("클라이언트 부문장 관점. 반기 성과 면담처럼 포트폴리오 전략에 집중.")
                appendLine("- 가중치 배분이 적절한지, 가중치 0인 목표는 괜찮은지 검토하세요.")
                appendLine("- indicator가 비어있는 목표는 '연말에 이걸 어떻게 숫자로 증명할 거야?' 라고 물으세요.")
                appendLine("- g7Expectations가 있으면 각 항목이 실제 활동(events, workstreams)으로 뒷받침되는지 체크하세요.")
                appendLine("- 연말 평가 narrative에서 빠지면 안 되는 포인트를 짚어주세요.")
                appendLine("- 톤: 전략적이고 큰 그림 중심. '하반기 평가에서 이게 빠지면 곤란해' 느낌.")
            } else {
                appendLine("당신은 성과·커리어 코칭 전문가입니다. 1:1 코칭 대화를 이어가세요.")
                appendLine("성과 데이터 없이 대화하고 있습니다. 사용자의 고민에 공감하고 구체적인 조언을 하세요.")
                appendLine("톤: 따뜻하지만 직설적. 실행 가능한 제안 위주.")
            }
            appendLine()
            appendLine("사용자 메시지: $message")
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

**Step 2: Run test to verify it passes**

Run: `./gradlew :test --tests "*.ProgressAdvisorToolTest" -q`
Expected: PASS (5개 테스트 전부)

**Step 3: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ProgressAdvisorTool.kt
git commit -m "feat: extend ProgressAdvisorTool to conversational coaching with URL/path loading"
```

---

### Task 3: OrchestratorAgent dispatch에 question 전달

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Step 1: dispatch 분기 수정 (line 362)**

변경 전:
```kotlin
toolName == "progressAdvisor" && progressAdvisorTool != null ->
    runCatching { progressAdvisorTool!!.advise(userId ?: "") }.getOrNull()
```

변경 후:
```kotlin
toolName == "progressAdvisor" && progressAdvisorTool != null ->
    runCatching { progressAdvisorTool!!.advise(userId ?: "", question) }.getOrNull()
```

**Step 2: 라우터 프롬프트 규칙 확장 (line 196)**

변경 전:
```kotlin
appendLine("- progressAdvisor: 성과 목표 조언·피드백·1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘', '어떻게 하면 좋을까' 질문.")
```

변경 후:
```kotlin
appendLine("- progressAdvisor: 성과 목표 조언·피드백·1:1 코칭·커리어 고민. '조언해줘', '피드백 줘', '1:1 해줘', '어떻게 하면 좋을까', 커리어 상담, 이전 코칭 대화의 후속 질문.")
```

**Step 3: 빌드 확인**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: pass user message to ProgressAdvisorTool for conversational coaching"
```

---

### Task 4: 전체 빌드 및 테스트 검증

**Step 1: 전체 테스트**

Run: `./gradlew :test -q`
Expected: ProgressAdvisorToolTest (5개) + 기존 테스트 모두 PASS (기존 실패 2건은 pre-existing)

**Step 2: 컴파일 확인**

Run: `./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL
