package io.github.veronikapj.wiki.knowledge

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class IngestAgentTest {

    private val baseDir = "build/test-ingest-${System.nanoTime()}"
    private val store = KnowledgeStore(baseDir)
    private val llmFn: suspend (String) -> String = mockk()
    private val chromaIndexFn: (suspend (String, String, String) -> Unit)? = null
    private val agent = IngestAgent(store, llmFn, chromaIndexFn)

    @AfterEach fun cleanup() {
        agent.close()
        File(baseDir).deleteRecursively()
    }

    @Test fun `ingest text compiles and saves concept page`() = runBlocking {
        coEvery { llmFn(any()) } returns """
            PAGES:
            concepts/테스트-개념.md:
            # 테스트 개념
            테스트 설명입니다.
            ---
        """.trimIndent()

        val result = agent.ingestText("테스트 개념에 대한 긴 설명 텍스트입니다.")

        assertTrue(result.contains("테스트-개념.md") || result.contains("저장"))
        assertTrue(store.pageExists("concepts/테스트-개념.md"))
    }

    @Test fun `ingest detects duplicate URL via sources dir`() = runBlocking {
        val url = "https://example.com"
        val key = agent.urlToSourceKey(url)
        store.savePage("sources/$key.md", "url: $url\n날짜: 2024-01")
        coEvery { llmFn(any()) } returns "PAGES:\n"

        val result = agent.ingestUrl(url)

        assertTrue(result.contains("이미 등록") || result.contains("duplicate"))
    }

    @Test fun `ingest uses confluence fetch fn content for confluence urls`() = runBlocking {
        var capturedPrompt = ""
        val confluenceFetchFn: suspend (String) -> String? = { "BigQuery 위키 본문 내용입니다." }
        val capturingLlmFn: suspend (String) -> String = { p ->
            capturedPrompt = p
            "PAGES:\nconcepts/빅쿼리.md:\n# BigQuery\n내용\n---"
        }
        val agentWithConfluence = IngestAgent(store, capturingLlmFn, null, confluenceFetchFn)

        agentWithConfluence.ingestUrl("https://kurly0521.atlassian.net/wiki/spaces/knS/pages/4607150022/BigQuery")

        assertTrue(
            capturedPrompt.contains("BigQuery 위키 본문 내용입니다."),
            "authenticated Confluence content should reach the compile prompt",
        )
        agentWithConfluence.close()
    }

    @Test fun `ingest empty LLM response saves to sources only`() = runBlocking {
        coEvery { llmFn(any()) } returns ""

        val result = agent.ingestText("짧은 텍스트")

        assertTrue(result.contains("일부 저장") || result.contains("sources"))
    }
}
