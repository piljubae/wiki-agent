package io.github.veronikapj.wiki.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class QueryRewriterTest {

    @Test
    fun `rewrite returns parsed RewrittenQuery`() = runTest {
        val llm = mockk<LLMCaller>()
        coEvery { llm.call(any()) } returns """
            BM25: signUp register join SignUpActivity SignUpViewModel
            VECTOR: 사용자가 앱에서 처음 계정을 만드는 화면의 구조
            TOOLS: code_search
        """.trimIndent()

        val rewriter = QueryRewriter(llm)
        val result = rewriter.rewrite(
            query = "회원가입 화면 어떻게 구현돼 있어?",
            usedTools = listOf("confluence"),
        )

        assertTrue(result.bm25.contains("SignUpActivity"))
        assertTrue(result.vector.contains("화면"))
        assertTrue(result.additionalTools.contains("code_search"))
    }

    @Test
    fun `rewrite with SAME tools returns empty additionalTools`() = runTest {
        val llm = mockk<LLMCaller>()
        coEvery { llm.call(any()) } returns """
            BM25: push notification FCM token
            VECTOR: 푸시 알림 발송 구현
            TOOLS: SAME
        """.trimIndent()

        val rewriter = QueryRewriter(llm)
        val result = rewriter.rewrite("푸시 알림 어떻게 보내?", listOf("code_search"))

        assertTrue(result.additionalTools.isEmpty())
    }
}
