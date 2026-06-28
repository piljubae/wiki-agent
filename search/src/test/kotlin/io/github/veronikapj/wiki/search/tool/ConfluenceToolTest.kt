package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.QueryLlm
import io.github.veronikapj.wiki.search.QueryUnderstanding
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class ConfluenceToolTest {

    @Test
    fun `enriches query with synonyms and dates when QueryUnderstanding present`() = runBlocking {
        val searchAgent = mockk<ConfluenceSearchAgent>()
        coEvery { searchAgent.search(any(), any(), any(), any(), any(), any()) } returns "결과"

        val llm = QueryLlm {
            """
            QUERY: iOS 배포 가이드
            SYNONYMS: iOS 릴리즈, iOS Release
            DATE_AFTER: 2026-01-01
            DATE_BEFORE:
            """.trimIndent()
        }
        val tool = ConfluenceTool(searchAgent, null, QueryUnderstanding(llm))

        tool.confluenceSearch("iOS 배포 가이드 알려줘")

        coVerify {
            searchAgent.search(
                "iOS 배포 가이드",
                listOf("iOS 릴리즈", "iOS Release"),
                any(),
                "2026-01-01",
                null,
                "iOS 배포 가이드 알려줘",
            )
        }
    }

    @Test
    fun `uses bare query when QueryUnderstanding absent`() = runBlocking {
        val searchAgent = mockk<ConfluenceSearchAgent>()
        coEvery { searchAgent.search(any()) } returns "결과"

        val tool = ConfluenceTool(searchAgent, null, null)
        tool.confluenceSearch("결제 흐름")

        coVerify { searchAgent.search("결제 흐름") }
    }
}
