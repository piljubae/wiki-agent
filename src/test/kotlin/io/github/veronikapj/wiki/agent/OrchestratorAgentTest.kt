package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.GitHubWikiSearchAgent

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import io.github.veronikapj.wiki.search.tool.ConfluenceTool
import io.github.veronikapj.wiki.search.tool.GitHubWikiTool
import io.github.veronikapj.wiki.knowledge.KnowledgeTool
import io.github.veronikapj.wiki.knowledge.KnowledgeStore
import io.github.veronikapj.wiki.onboarding.OnboardingTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OrchestratorAgentTest {

    private val mockExecutor = mockk<MultiLLMPromptExecutor>(relaxed = true)

    @Test
    fun `build creates agent with confluenceTool only when rag disabled`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = mockExecutor,
        )
        assertNotNull(agent)
    }

    @Test
    fun `build creates agent with githubWikiTool only when confluence disabled`() {
        val githubWikiTool = GitHubWikiTool(mockk<GitHubWikiSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = null,
            githubWikiTool = githubWikiTool,
            executor = mockExecutor,
        )
        assertNotNull(agent)
    }

    @Test
    fun `answer method accepts progressListener parameter`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = mockExecutor,
        )
        assertNotNull(agent)
    }

    @Test
    fun `accepts conversationStore and sessionId`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val store = io.github.veronikapj.wiki.context.ConversationStore(
            java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-${System.nanoTime()}").absolutePath
        )
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = mockExecutor,
            conversationStore = store,
        )
        assertNotNull(agent)
    }

    @Test
    fun `throws when no tools are provided`() {
        assertFailsWith<IllegalArgumentException> {
            OrchestratorAgent(
                confluenceTool = null,
                githubWikiTool = null,
                executor = mockExecutor,
            )
        }
    }

    @Test
    fun `executeParallel combines knowledge and confluence when both return results`() = runTest {
        val knowledgeStore = mockk<KnowledgeStore>()
        every { knowledgeStore.loadAll() } returns listOf("배포.md" to "배포 가이드 내용입니다.")
        val knowledgeTool = KnowledgeTool(knowledgeStore)

        val confluenceTool = mockk<ConfluenceTool>()
        coEvery { confluenceTool.confluenceSearchSuspend(any(), any(), any(), any(), any()) } returns "Confluence 결과"

        val agent = OrchestratorAgent(
            knowledgeTool = knowledgeTool,
            confluenceTool = confluenceTool,
            executor = mockExecutor,
        )
        val result = agent.executeParallel("배포")
        assertContains(result!!, "[지식베이스]")
        assertContains(result, "[Confluence]")
        assertContains(result, "Confluence 결과")
    }

    @Test
    fun `executeParallel returns knowledge only when confluence finds nothing`() = runTest {
        val knowledgeStore = mockk<KnowledgeStore>()
        every { knowledgeStore.loadAll() } returns listOf("배포.md" to "배포 가이드 내용입니다.")
        val knowledgeTool = KnowledgeTool(knowledgeStore)

        val confluenceTool = mockk<ConfluenceTool>()
        coEvery { confluenceTool.confluenceSearchSuspend(any(), any(), any(), any(), any()) } returns "관련 문서를 찾을 수 없습니다."

        val agent = OrchestratorAgent(
            knowledgeTool = knowledgeTool,
            confluenceTool = confluenceTool,
            executor = mockExecutor,
        )
        val result = agent.executeParallel("배포")
        assertContains(result!!, "배포 가이드")
        assert(!result.contains("[Confluence]")) { "Confluence 섹션이 포함되면 안 됩니다" }
    }

    @Test
    fun `executeParallel returns null when both find nothing`() = runTest {
        val knowledgeStore = mockk<KnowledgeStore>()
        every { knowledgeStore.loadAll() } returns emptyList()
        val knowledgeTool = KnowledgeTool(knowledgeStore)

        val confluenceTool = mockk<ConfluenceTool>()
        coEvery { confluenceTool.confluenceSearchSuspend(any(), any(), any(), any(), any()) } returns "관련 문서를 찾을 수 없습니다."

        val agent = OrchestratorAgent(
            knowledgeTool = knowledgeTool,
            confluenceTool = confluenceTool,
            executor = mockExecutor,
        )
        val result = agent.executeParallel("없는내용")
        assertNull(result)
    }

    @Test
    fun `routerExecutor defaults to executor when not specified`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = mockExecutor,
        )
        assertNotNull(agent)
    }

    @Test
    fun `routerExecutor can be set independently from executor`() {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = mockExecutor,
            routerExecutor = mockExecutor,
        )
        assertNotNull(agent)
    }

    @Test
    fun `extractKeywordsAsSynonyms generates bigrams before individual words`() {
        val result = OrchestratorAgent.extractKeywordsAsSynonyms("신규 입사 온보딩 문서")
        // "문서" is a stopword, should be excluded
        assertFalse(result.contains("문서"))
        // bigrams appear before individual words
        assertTrue(result.contains("신규 입사"))
        assertTrue(result.contains("입사 온보딩"))
        assertTrue(result.indexOf("신규 입사") < result.indexOf("신규"))
        assertTrue(result.indexOf("입사 온보딩") < result.indexOf("입사"))
        // individual keywords still present
        assertTrue(result.contains("신규"))
        assertTrue(result.contains("입사"))
        assertTrue(result.contains("온보딩"))
    }

    @Test
    fun `extractKeywordsAsSynonyms returns empty for all-stopword query`() {
        val result = OrchestratorAgent.extractKeywordsAsSynonyms("문서 찾아줘 알려줘")
        assertTrue(result.isEmpty())
    }

    /** routeAndRetrieve가 검색 단계에서 listener.onSearchStarted를 호출하므로,
     *  그 호출 횟수로 라우팅된 sub-question 수를 간접 관측한다. */
    private class CountingListener : SearchProgressListener {
        val startedCount = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun onSearchStarted(toolName: String) { startedCount.incrementAndGet() }
        override suspend fun onSearchCompleted(toolName: String) {}
    }

    @Test
    fun `compound question routes one search per sub-question`() = runTest {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>(relaxed = true))
        // 분해기가 2개의 sub-question을 반환 → 2개의 routeAndRetrieve 경로가 실행돼야 함
        val decomposer = QueryDecomposer(LLMCaller { "배포 절차가 무엇인가요\n온보딩 가이드는 어디있나요" })
        val listener = CountingListener()

        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = mockExecutor,
            useManualLoop = true,
            queryDecomposer = decomposer,
        )

        val result = agent.answer("A랑 B 알려줘", listener = listener)

        assertNotNull(result)
        assertEquals(2, listener.startedCount.get(), "복합 질문은 sub-question 수(2)만큼 검색을 라우팅해야 한다")
    }

    @Test
    fun `simple question routes a single search`() = runTest {
        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>(relaxed = true))
        // 분해기가 1개만 반환 → 단일 경로 (기존 동작과 동일)
        val decomposer = QueryDecomposer(LLMCaller { "배포 절차가 어떻게 되나요" })
        val listener = CountingListener()

        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = mockExecutor,
            useManualLoop = true,
            queryDecomposer = decomposer,
        )

        val result = agent.answer("배포 절차 알려줘", listener = listener)

        assertNotNull(result)
        assertEquals(1, listener.startedCount.get(), "단순 질문은 단일 검색만 라우팅해야 한다")
    }

    /**
     * Fix 1 regression: 단일 질문 요약 프롬프트에 "[1. <sub-question>]" 섹션 라벨이 없어야 하고,
     * 복합 질문(분해기→2개) 요약 프롬프트에는 섹션 라벨이 있어야 한다.
     */
    @Test
    fun `single question summary prompt does not contain section label`() = runTest {
        val capturedPrompts = mutableListOf<Prompt>()
        val capturingExecutor = mockk<MultiLLMPromptExecutor>(relaxed = true)
        coEvery { capturingExecutor.execute(capture(capturedPrompts), any(), any()) } returns emptyList()

        val confluenceTool = mockk<ConfluenceTool>()
        coEvery { confluenceTool.confluenceSearchSuspend(any(), any(), any(), any(), any()) } returns "단일 검색 결과"

        // 분해기가 1개 반환 → 단일 경로
        val decomposer = QueryDecomposer(LLMCaller { "배포 절차 질문" })

        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = capturingExecutor,
            useManualLoop = true,
            queryDecomposer = decomposer,
        )

        agent.answer("배포 절차 알려줘")

        // "summary" ID를 가진 프롬프트를 찾아 검증
        val summaryPrompt = capturedPrompts.firstOrNull { it.id == "summary" }
        assertNotNull(summaryPrompt, "summary 프롬프트가 executor에 전달되어야 한다")

        val promptText = summaryPrompt.messages.joinToString("") { it.content }
        assertFalse(
            promptText.contains("[1. "),
            "단일 질문 요약 프롬프트에 '[1. ' 섹션 라벨이 없어야 한다. 실제 프롬프트:\n${promptText.take(500)}"
        )
    }

    /**
     * Regression: Koog 에이전트 경로(useManualLoop=false)에서도 forceTool="onboarding"이면
     * OnboardingTool.handle()를 호출하고 그 결과를 그대로 반환해야 한다.
     * (이전: Koog 경로에서 forceTool이 무시되어 온보딩 SSOT 파이프라인이 통째로 우회됨)
     */
    @Test
    fun `koog path honors forceTool onboarding by delegating to OnboardingTool`() = runTest {
        val onboardingTool = mockk<OnboardingTool>()
        every { onboardingTool.handle(any(), any(), any()) } returns "온보딩 가이드 응답"

        val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>(relaxed = true))

        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            onboardingTool = onboardingTool,
            executor = mockExecutor,
            // useManualLoop = false (기본값) → Koog 경로
        )

        val result = agent.answer("온보딩 시작", forceTool = "onboarding", userId = "U123")

        assertEquals("온보딩 가이드 응답", result.answer)
        verify { onboardingTool.handle("U123", "온보딩 시작", any()) }
    }

    @Test
    fun `compound question summary prompt contains section labels`() = runTest {
        val capturedPrompts = mutableListOf<Prompt>()
        val capturingExecutor = mockk<MultiLLMPromptExecutor>(relaxed = true)
        coEvery { capturingExecutor.execute(capture(capturedPrompts), any(), any()) } returns emptyList()

        val confluenceTool = mockk<ConfluenceTool>()
        coEvery { confluenceTool.confluenceSearchSuspend(any(), any(), any(), any(), any()) } returns "복합 검색 결과"

        // 분해기가 2개 반환 → 복합 경로
        val decomposer = QueryDecomposer(LLMCaller { "배포 절차가 무엇인가요\n온보딩 가이드는 어디있나요" })

        val agent = OrchestratorAgent(
            confluenceTool = confluenceTool,
            executor = capturingExecutor,
            useManualLoop = true,
            queryDecomposer = decomposer,
        )

        agent.answer("배포랑 온보딩 알려줘")

        val summaryPrompt = capturedPrompts.firstOrNull { it.id == "summary" }
        assertNotNull(summaryPrompt, "summary 프롬프트가 executor에 전달되어야 한다")

        val promptText = summaryPrompt.messages.joinToString("") { it.content }
        assertTrue(
            promptText.contains("[1. "),
            "복합 질문 요약 프롬프트에 '[1. ' 섹션 라벨이 있어야 한다. 실제 프롬프트:\n${promptText.take(500)}"
        )
    }
}
