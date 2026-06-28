package io.github.veronikapj.wiki.onboarding

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import io.github.veronikapj.wiki.search.tool.CodeSearchTool
import io.github.veronikapj.wiki.search.tool.ConfluenceTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingToolTest {

    private val testDir = "build/test-onboarding"
    private val curriculumPath = "$testDir/curriculum.yaml"
    private val testUserPrefix = "U_TEST_"
    private fun uniqueUserId() = "$testUserPrefix${System.nanoTime()}"

    private val curriculumYaml = """
lastUpdated: "2026-06-02"
phases:
  - id: step-1
    name: "테스트 단계 1"
    phase: 1
    day: "Day 1"
    skippable: true
    levelFilter:
      skipWhen:
        android: "C"
    sources:
      - type: static
        path: "$testDir/steps/step-1.md"
  - id: step-2
    name: "테스트 단계 2"
    phase: 1
    day: "Day 1"
    skippable: false
    sources:
      - type: static
        path: "$testDir/steps/step-2.md"
  - id: step-3
    name: "테스트 단계 3"
    phase: 2
    day: "Day 2"
    skippable: false
    sources:
      - type: static
        path: "$testDir/steps/step-3.md"
    """.trimIndent()

    @BeforeEach
    fun setup() {
        File(testDir).mkdirs()
        File("$testDir/steps").mkdirs()
        File(curriculumPath).writeText(curriculumYaml)
        File("$testDir/steps/step-1.md").writeText("# Step 1\nAndroid 기초 환경 세팅 가이드")
        File("$testDir/steps/step-2.md").writeText("# Step 2\nCompose 기본 학습 자료")
        File("$testDir/steps/step-3.md").writeText("# Step 3\n도메인 이해 자료")
    }

    @AfterEach
    fun cleanup() {
        File(".wiki/onboarding/sessions/").listFiles()
            ?.filter { it.name.startsWith(testUserPrefix) }
            ?.forEach { it.delete() }
    }

    private fun createMockExecutor(response: String): MultiLLMPromptExecutor {
        val executor = mockk<MultiLLMPromptExecutor>()
        coEvery { executor.execute(any(), any()) } returns listOf(
            Message.Assistant(content = response, metaInfo = ResponseMetaInfo.Empty)
        )
        return executor
    }

    private fun createTool(
        executor: MultiLLMPromptExecutor = createMockExecutor("LLM 가이드 응답입니다."),
    ): OnboardingTool {
        return OnboardingTool(
            curriculumPath = curriculumPath,
            executor = executor,
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = mockk<ConfluenceTool>(relaxed = true),
            codeSearchTool = mockk<CodeSearchTool>(relaxed = true),
        )
    }

    @Test
    fun `온보딩 시작 시 레벨 체크 메시지가 반환된다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        val result = tool.handle(userId, "온보딩 시작")

        assertTrue(result.contains("경험 수준"), "레벨 체크 질문이 포함되어야 합니다. 실제: $result")
        assertTrue(result.contains("Android"), "Android 레벨 질문이 포함되어야 합니다.")
        assertTrue(result.contains("Compose"), "Compose 레벨 질문이 포함되어야 합니다.")
    }

    @Test
    fun `레벨 응답 후 첫 번째 가이드가 생성된다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // Start onboarding first
        tool.handle(userId, "온보딩 시작")

        // Respond with level (B=mid Android, A=no Compose, A=no domain)
        val result = tool.handle(userId, "B, A, A")

        assertTrue(result.contains("테스트 단계"), "가이드에 단계 이름이 포함되어야 합니다. 실제: $result")
        assertTrue(result.contains("Phase"), "가이드에 Phase 정보가 포함되어야 합니다. 실제: $result")
    }

    @Test
    fun `경력자 레벨이면 skippable 단계가 스킵된다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // C level Android → step-1 should be skipped (levelFilter.skipWhen.android = "C")
        val result = tool.handle(userId, "C, A, A")

        // step-1 is skipped, so the first guide should be step-2
        assertTrue(result.contains("테스트 단계 2"), "step-1이 스킵되고 step-2 가이드가 표시되어야 합니다. 실제: $result")
        assertFalse(result.contains("테스트 단계 1"), "step-1은 스킵되어 표시되지 않아야 합니다. 실제: $result")
    }

    @Test
    fun `다음 입력 시 다음 단계로 이동한다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // Start → Level → Now on step-1
        tool.handle(userId, "B, A, A")

        // Move to next
        val result = tool.handle(userId, "다음")

        // Should now be on step-2
        assertTrue(result.contains("테스트 단계 2"), "다음 단계(step-2) 가이드가 표시되어야 합니다. 실제: $result")

        // Verify session state
        val session = OnboardingSessionStore.load(userId)!!
        assertTrue(session.steps[0].status == StepStatusType.COMPLETED, "step-1이 COMPLETED여야 합니다.")
        assertTrue(session.currentStepId == "step-2", "현재 단계가 step-2여야 합니다.")
    }

    @Test
    fun `건너뛰기 시 상태가 SKIPPED로 변경된다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // Start → Level → Now on step-1
        tool.handle(userId, "B, A, A")

        // Skip step-1
        val result = tool.handle(userId, "건너뛰기")

        // Should now be on step-2
        assertTrue(result.contains("테스트 단계 2"), "건너뛴 후 step-2 가이드가 표시되어야 합니다. 실제: $result")

        // Verify session state
        val session = OnboardingSessionStore.load(userId)!!
        assertTrue(session.steps[0].status == StepStatusType.SKIPPED, "step-1이 SKIPPED여야 합니다.")
        assertTrue(session.currentStepId == "step-2", "현재 단계가 step-2여야 합니다.")
    }

    @Test
    fun `진행률 요청 시 Phase별 현황이 표시된다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // Start → Level → Now on step-1
        tool.handle(userId, "B, A, A")

        // Request progress
        val result = tool.handle(userId, "진행률")

        assertTrue(result.contains("Phase 1"), "Phase 1 현황이 포함되어야 합니다. 실제: $result")
        assertTrue(result.contains("Phase 2"), "Phase 2 현황이 포함되어야 합니다. 실제: $result")
        assertTrue(result.contains("%"), "진행률 퍼센트가 포함되어야 합니다. 실제: $result")
    }

    @Test
    fun `모든 단계 완료 시 완료 메시지가 반환된다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // C level → step-1 skipped, starts from step-2
        tool.handle(userId, "C, A, A")

        // Complete step-2 → step-3
        tool.handle(userId, "다음")

        // Complete step-3 → all done
        val result = tool.handle(userId, "다음")

        assertTrue(result.contains("완료"), "완료 메시지가 포함되어야 합니다. 실제: $result")
    }

    @Test
    fun `질문 시 현재 단계 컨텍스트로 LLM 답변이 반환된다`() {
        val mockResponse = "이것은 Android 개발 환경 세팅에 대한 설명입니다."
        val tool = createTool(executor = createMockExecutor(mockResponse))
        val userId = uniqueUserId()

        // Start → Level → Now on step-1
        tool.handle(userId, "B, A, A")

        // Ask a question
        val result = tool.handle(userId, "이게 뭐야?")

        assertTrue(result.contains("Android 개발 환경"), "LLM 응답이 반환되어야 합니다. 실제: $result")
    }

    @Test
    fun `완료된 세션에서 시작 시 완료 안내가 표시된다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // Complete all steps: C level → step-1 skipped, step-2 → step-3 → done
        tool.handle(userId, "C, A, A")
        tool.handle(userId, "다음")
        tool.handle(userId, "다음")

        // Try to start again
        val result = tool.handle(userId, "온보딩 시작")

        assertTrue(result.contains("완료"), "이미 완료된 세션 안내가 표시되어야 합니다. 실제: $result")
    }

    @Test
    fun `질문 시 codeSearch 라이브 검색이 호출되고 메모가 기록된다`() {
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        every { codeSearchTool.codeSearch(any()) } returns "ProductViewModel 위치: feature/product"
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true)
        every { confluenceTool.confluenceSearch(any()) } returns ""

        val tool = OnboardingTool(
            curriculumPath = curriculumPath,
            executor = createMockExecutor("코드는 feature/product 모듈에 있습니다."),
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = confluenceTool,
            codeSearchTool = codeSearchTool,
        )
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A")

        val result = tool.handle(userId, "ProductViewModel 어디있어?")

        assertTrue(result.contains("feature/product"), "LLM 답변이 반환되어야 합니다. 실제: $result")
        verify { codeSearchTool.codeSearch("ProductViewModel 어디있어?") }

        val session = OnboardingSessionStore.load(userId)!!
        assertTrue(session.memos.any { it.contains("ProductViewModel") }, "질문이 메모로 기록되어야 합니다. 실제: ${session.memos}")
    }
}
