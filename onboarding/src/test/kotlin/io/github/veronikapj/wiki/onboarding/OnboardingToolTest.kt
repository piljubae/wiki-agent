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
import io.mockk.slot
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
      - type: code
        query: "step1 환경 세팅"
  - id: step-2
    name: "테스트 단계 2"
    phase: 1
    day: "Day 1"
    skippable: false
    sources:
      - type: code
        query: "step2 Compose"
  - id: step-3
    name: "테스트 단계 3"
    phase: 2
    day: "Day 2"
    skippable: false
    sources:
      - type: code
        query: "step3 도메인"
    """.trimIndent()

    @BeforeEach
    fun setup() {
        File(testDir).mkdirs()
        File(curriculumPath).writeText(curriculumYaml)
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
        codeSearchTool: CodeSearchTool = mockk<CodeSearchTool>(relaxed = true).also {
            every { it.codeSearch(any()) } returns "테스트 코드 자료"
        },
    ): OnboardingTool {
        return OnboardingTool(
            curriculumPath = curriculumPath,
            executor = executor,
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = mockk<ConfluenceTool>(relaxed = true),
            codeSearchTool = codeSearchTool,
        )
    }

    @Test
    fun `IOS_REFERENCE_RULE은 마커와 SSOT 안내를 포함한다`() {
        val rule = OnboardingTool.IOS_REFERENCE_RULE
        assertTrue(rule.contains("🍎 iOS 참조"))
        assertTrue(rule.contains("🔀 Android·iOS 공통"))
        assertTrue(rule.contains("SSOT"))
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
    fun `숙련자(C) 레벨이면 간결 깊이 지시가 프롬프트에 포함된다`() {
        val promptSlot = slot<ai.koog.prompt.dsl.Prompt>()
        val executor = mockk<MultiLLMPromptExecutor>()
        coEvery { executor.execute(capture(promptSlot), any()) } returns listOf(
            Message.Assistant(content = "응답", metaInfo = ResponseMetaInfo.Empty)
        )
        val tool = createTool(executor = executor)
        val userId = uniqueUserId()

        // C 레벨 → step-1 스킵, step-2부터. 가이드 생성 시 깊이 지시 포함
        tool.handle(userId, "C, A, A")

        assertTrue(promptSlot.isCaptured, "executor.execute()가 한 번도 호출되지 않았습니다 — 가이드 생성이 이 handle() 호출에서 발생하지 않습니다")
        val sentText = promptSlot.captured.messages.joinToString(" ") { it.content }
        assertTrue(sentText.contains("숙련자"), "C 레벨이면 숙련자 깊이 지시가 포함되어야 합니다. 실제: $sentText")
    }

    @Test
    fun `심화 키워드 질문 시 codeSearch가 호출되고 메모가 기록된다`() {
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

        val result = tool.handle(userId, "ProductViewModel 코드 어디있어?")

        assertTrue(result.contains("feature/product"), "LLM 답변이 반환되어야 합니다. 실제: $result")
        verify { codeSearchTool.codeSearch("ProductViewModel 코드 어디있어?") }

        val session = OnboardingSessionStore.load(userId)!!
        assertTrue(session.memos.any { it.contains("ProductViewModel") }, "질문이 메모로 기록되어야 합니다. 실제: ${session.memos}")
    }

    @Test
    fun `심화 키워드 없는 질문은 codeSearch를 호출하지 않는다`() {
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        val confluenceTool = mockk<ConfluenceTool>(relaxed = true)

        // Curriculum without code sources for this test
        val noCurriculumPath = "$testDir/no-code-curriculum.yaml"
        val noCurriculumYaml = """
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
    sources: []
        """.trimIndent()
        File(noCurriculumPath).writeText(noCurriculumYaml)

        val tool = OnboardingTool(
            curriculumPath = noCurriculumPath,
            executor = createMockExecutor("위키 기반 답변입니다."),
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = confluenceTool,
            codeSearchTool = codeSearchTool,
        )
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A")

        tool.handle(userId, "이 단계가 뭐야?")

        verify(exactly = 0) { codeSearchTool.codeSearch(any()) }
        verify(exactly = 0) { confluenceTool.confluenceSearch(any()) }
    }

    @Test
    fun `코드 보여줘는 JUMP가 아니라 심화 질문으로 codeSearch를 호출한다`() {
        // Tier1 안내문이 "'코드 보여줘'처럼 물어보세요"라고 유도하므로, 이 문구는
        // 단계 점프(JUMP, "보여줘" 매칭)가 아니라 Tier2 질문으로 라우팅돼야 한다.
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        every { codeSearchTool.codeSearch(any()) } returns "예시 코드"
        val tool = OnboardingTool(
            curriculumPath = curriculumPath,
            executor = createMockExecutor("코드 설명입니다."),
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = mockk<ConfluenceTool>(relaxed = true),
            codeSearchTool = codeSearchTool,
        )
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A") // 세션 + step-1

        tool.handle(userId, "코드 보여줘")

        verify { codeSearchTool.codeSearch("코드 보여줘") }
    }

    @Test
    fun `단계 이름 없는 보여줘는 JUMP가 아니라 질문으로 답한다`() {
        // "관련 claude 스킬 보여줘"처럼 단계 이름도 심화 키워드도 없는 "보여줘" 질문은
        // JUMP("해당 단계를 찾을 수 없습니다")로 새면 안 되고 QUESTION으로 답해야 한다.
        val tool = createTool() // executor → "LLM 가이드 응답입니다."
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A") // 세션 + step-1

        val result = tool.handle(userId, "관련 claude 스킬 보여줘")

        assertFalse(
            result.contains("해당 단계를 찾을 수 없습니다"),
            "단계 이름 없는 '보여줘' 질문이 JUMP로 새면 안 됩니다. 실제: $result",
        )
    }

    @Test
    fun `보여줘 질문은 심화(Tier2)로 codeSearch를 호출한다`() {
        // "보여줘"는 구체 산출물(코드/스킬/예시) 요청 → Tier2 심화로 라우팅돼야 한다.
        val codeSearchTool = mockk<CodeSearchTool>(relaxed = true)
        every { codeSearchTool.codeSearch(any()) } returns "스킬 코드 자료"
        val tool = OnboardingTool(
            curriculumPath = curriculumPath,
            executor = createMockExecutor("스킬 설명입니다."),
            model = mockk<LLModel>(relaxed = true),
            confluenceTool = mockk<ConfluenceTool>(relaxed = true),
            codeSearchTool = codeSearchTool,
        )
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A") // 세션 + step-1

        tool.handle(userId, "관련 claude 스킬 보여줘")

        verify { codeSearchTool.codeSearch("관련 claude 스킬 보여줘") }
    }

    @Test
    fun `온보딩 초기화 시 세션이 삭제되고 레벨 체크부터 다시 시작한다`() {
        val tool = createTool()
        val userId = uniqueUserId()

        // 진행 중 세션 생성 (B,A,A → step-1)
        tool.handle(userId, "B, A, A")
        assertTrue(OnboardingSessionStore.exists(userId))

        val result = tool.handle(userId, "온보딩 초기화")

        // 세션 삭제됨
        assertFalse(OnboardingSessionStore.exists(userId))
        // 레벨 체크 메시지로 재시작
        assertTrue(result.contains("경험 수준"), "초기화 후 레벨 체크가 표시되어야 합니다. 실제: $result")
    }

    @Test
    fun `초기화 substring 질문은 RESET이 아니어서 세션을 지우지 않는다`() {
        val tool = createTool()
        val userId = uniqueUserId()
        tool.handle(userId, "B, A, A") // 세션 생성
        assertTrue(OnboardingSessionStore.exists(userId))

        // "초기화"를 포함하지만 명백한 질문 — RESET이면 안 됨
        tool.handle(userId, "캐시 초기화는 어떻게 해?")

        assertTrue(OnboardingSessionStore.exists(userId), "초기화 substring 질문이 세션을 삭제하면 안 됩니다")
    }
}
