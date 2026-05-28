package io.github.veronikapj.wiki.agent

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.GitHubWikiTool
import io.github.veronikapj.wiki.knowledge.KnowledgeTool
import io.github.veronikapj.wiki.knowledge.KnowledgeStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
}
