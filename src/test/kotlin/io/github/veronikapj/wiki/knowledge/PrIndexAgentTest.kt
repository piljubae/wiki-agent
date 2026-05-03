package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.github.GithubPrInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PrIndexAgentTest {

    private val mockCodeClient = mockk<GitHubCodeClient>()
    private val mockStore = mockk<KnowledgeStore>(relaxed = true)
    private val llmCalls = mutableListOf<String>()
    private val mockLlm: suspend (String) -> String = { prompt ->
        llmCalls += prompt
        "변경 목적: 테스트\n영향 영역: ViewModel\n핵심 변경: 기능 추가\n관련 티켓: KMA-1234"
    }

    private val agent = PrIndexAgent(
        codeClient = mockCodeClient,
        knowledgeStore = mockStore,
        llmFn = mockLlm,
        chromaClient = null,
        embeddingFn = null,
    )

    @Test
    fun `compilePrDocument — PR 정보를 포함한 문서를 생성한다`() = runTest {
        val pr = GithubPrInfo(
            repo = "owner/repo", number = 123,
            title = "KMA-1234 배너 클릭 이벤트 추가",
            body = "배너 클릭 시 Amplitude 이벤트 전송",
            state = "closed", merged = true, mergedAt = "2026-05-01T10:00:00Z",
            author = "pilju.bae", branch = "feature/KMA-1234",
            changedFiles = listOf("features/banner/BannerViewModel.kt"),
        )

        val doc = agent.compilePrDocument(pr, "LLM output here")
        assertTrue(doc.contains("123"))
        assertTrue(doc.contains("pilju.bae"))
        assertTrue(doc.contains("BannerViewModel.kt"))
        assertTrue(doc.contains("LLM output here"))
    }

    @Test
    fun `indexPr — KnowledgeStore에 PR 문서를 저장한다`() = runTest {
        val pr = GithubPrInfo(
            repo = "owner/repo", number = 123,
            title = "KMA-1234 배너 수정", body = "수정 내용",
            state = "closed", merged = true, mergedAt = null,
            author = "dev", branch = "feature/KMA-1234",
            changedFiles = emptyList(),
        )
        coEvery { mockCodeClient.fetchPr("owner/repo", 123) } returns pr
        coEvery { mockCodeClient.fetchPrDiff("owner/repo", 123, any()) } returns ""

        agent.indexPr("owner/repo", 123)

        coVerify { mockStore.savePage(match { it.contains("pr-123") }, any()) }
    }
}
