package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.github.GithubPrInfo
import io.github.veronikapj.wiki.rag.ChromaClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        every { mockCodeClient.extractTicket(any(), any()) } returns "KMA-1234"
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
        every { mockCodeClient.extractTicket(any(), any()) } returns "KMA-1234"

        agent.indexPr("owner/repo", 123)

        coVerify { mockStore.savePage(match { it.contains("pr-123") }, any()) }
    }

    @Test
    fun `indexPr — PR를 가져올 수 없으면 에러 메시지를 반환한다`() = runTest {
        coEvery { mockCodeClient.fetchPr("owner/repo", 999) } returns null

        val result = agent.indexPr("owner/repo", 999)

        assertTrue(result.contains("999"))
        coVerify(exactly = 0) { mockStore.savePage(any(), any()) }
    }

    @Test
    fun `indexRecentPrs — lastPrNumber 이하의 PR은 건너뛴다`() = runTest {
        val pr100 = GithubPrInfo(
            repo = "owner/repo", number = 100,
            title = "이전 PR", body = "",
            state = "closed", merged = true, mergedAt = null,
            author = "dev", branch = "feature/old",
            changedFiles = emptyList(),
        )
        val pr200 = GithubPrInfo(
            repo = "owner/repo", number = 200,
            title = "신규 PR", body = "",
            state = "open", merged = false, mergedAt = null,
            author = "dev", branch = "feature/new",
            changedFiles = emptyList(),
        )
        coEvery { mockCodeClient.fetchRecentPrs("owner/repo", any()) } returns listOf(pr100, pr200)
        coEvery { mockCodeClient.fetchPr("owner/repo", 200) } returns pr200
        coEvery { mockCodeClient.fetchPrDiff("owner/repo", 200, any()) } returns ""
        every { mockCodeClient.extractTicket(any(), any()) } returns null
        // pollStateFile in temp dir with lastPrNumber=100
        val tmpState = File.createTempFile("poll-state", ".json")
        tmpState.writeText("""{"owner/repo":{"lastPolledAt":"","lastPrNumber":100}}""")

        val agentWithState = PrIndexAgent(
            codeClient = mockCodeClient,
            knowledgeStore = mockStore,
            llmFn = mockLlm,
            pollStateFile = tmpState.absolutePath,
        )
        val count = agentWithState.indexRecentPrs(listOf("owner/repo"))

        assertEquals(1, count)
        coVerify(exactly = 0) { mockCodeClient.fetchPr("owner/repo", 100) }
        coVerify(exactly = 1) { mockCodeClient.fetchPr("owner/repo", 200) }
    }

    @Test
    fun `parsePrMetadata — compilePrDocument 출력에서 메타데이터를 역파싱한다`() = runTest {
        every { mockCodeClient.extractTicket(any(), any()) } returns "KMA-8032"
        val pr = GithubPrInfo(
            repo = "thefarmersfront/kurly-android", number = 7737,
            title = "KMA-8032 Copilot instruction 분리", body = "",
            state = "open", merged = false, mergedAt = null,
            author = "piljubae", branch = "feature/KMA-8032",
            changedFiles = emptyList(),
        )
        val doc = agent.compilePrDocument(pr, "변경 목적: Copilot instruction 분리")

        val meta = agent.parsePrMetadata("thefarmersfront-kurly-android-pr-7737", doc)

        assertNotNull(meta)
        assertEquals("thefarmersfront/kurly-android", meta["repo"])
        assertEquals("7737", meta["pr_number"])
        assertEquals("open", meta["state"])
        assertEquals("KMA-8032", meta["ticket"])
        assertEquals("piljubae", meta["author"])
    }

    @Test
    fun `parsePrMetadata — merged 상태에서 state는 (merged) 접미사를 제외한다`() = runTest {
        every { mockCodeClient.extractTicket(any(), any()) } returns null
        val pr = GithubPrInfo(
            repo = "owner/repo", number = 5,
            title = "t", body = "",
            state = "closed", merged = true, mergedAt = "2026-05-01T10:00:00Z",
            author = "dev", branch = "b",
            changedFiles = emptyList(),
        )
        val doc = agent.compilePrDocument(pr, "변경 목적: x")

        val meta = agent.parsePrMetadata("owner-repo-pr-5", doc)

        assertNotNull(meta)
        assertEquals("closed", meta["state"])
        assertEquals("2026-05-01T10:00:00Z", meta["merged_at"])
    }

    @Test
    fun `parsePrMetadata — PR 번호를 추출할 수 없으면 null`() {
        assertNull(agent.parsePrMetadata("invalid-id", "# PR\n- *레포*: owner/repo"))
    }

    @Test
    fun `reconcileMissing — ChromaDB에 없는 디스크 PR만 백필한다`() = runTest {
        val mockChroma = mockk<ChromaClient>(relaxed = true)
        val embedCalls = mutableListOf<String>()
        val reconcileAgent = PrIndexAgent(
            codeClient = mockCodeClient,
            knowledgeStore = mockStore,
            llmFn = mockLlm,
            chromaClient = mockChroma,
            embeddingFn = { text -> embedCalls += text; listOf(0.1f, 0.2f) },
        )
        val doc7737 = """
            # PR #7737: KMA-8032 Copilot instruction 분리

            - *레포*: thefarmersfront/kurly-android
            - *작성자*: piljubae
            - *상태*: open
            - *티켓*: KMA-8032

            변경 목적: Copilot instruction 분리
        """.trimIndent()
        every { mockStore.loadPrDocs() } returns listOf(
            "thefarmersfront-kurly-android-pr-7700" to doc7737,  // 이미 인덱싱됨 → 스킵
            "thefarmersfront-kurly-android-pr-7737" to doc7737,  // 누락 → 백필
            "other-repo-pr-1" to doc7737,                         // repo 불일치 → 무시
        )
        coEvery { mockChroma.getOrCreateCollection(any()) } returns "cid"
        coEvery { mockChroma.getExistingIds("cid", any()) } returns setOf("thefarmersfront-kurly-android-pr-7700")
        val idsSlot = slot<List<String>>()
        coEvery { mockChroma.upsertDocuments("cid", capture(idsSlot), any(), any(), any()) } returns Unit

        val count = reconcileAgent.reconcileMissing(listOf("thefarmersfront/kurly-android"))

        assertEquals(1, count)
        assertEquals(listOf("thefarmersfront-kurly-android-pr-7737"), idsSlot.captured)
        coVerify(exactly = 1) { mockChroma.upsertDocuments("cid", any(), any(), any(), any()) }
    }

    @Test
    fun `reconcileMissing — embeddingFn 미설정이면 0 반환`() = runTest {
        val count = agent.reconcileMissing(listOf("owner/repo"))
        assertEquals(0, count)
    }
}
