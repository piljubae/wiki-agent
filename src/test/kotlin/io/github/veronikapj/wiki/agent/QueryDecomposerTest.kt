package io.github.veronikapj.wiki.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryDecomposerTest {

    @Test
    fun `splits compound question into sub-questions`() = runTest {
        val llm = LLMCaller {
            """
            결제 흐름 코드
            결제 관련 위키 문서
            최근 결제 PR
            """.trimIndent()
        }
        val result = QueryDecomposer(llm).decompose("결제 흐름 코드랑 위키, 최근 PR 알려줘")

        assertEquals(
            listOf("결제 흐름 코드", "결제 관련 위키 문서", "최근 결제 PR"),
            result,
        )
    }

    @Test
    fun `returns single sub-question for simple question`() = runTest {
        val llm = LLMCaller { "iOS 배포 가이드" }
        val result = QueryDecomposer(llm).decompose("iOS 배포 가이드 알려줘")
        assertEquals(listOf("iOS 배포 가이드"), result)
    }

    @Test
    fun `strips bullet prefixes and blank lines`() = runTest {
        val llm = LLMCaller { "- A 질문\n\n* B 질문\n" }
        val result = QueryDecomposer(llm).decompose("A랑 B")
        assertEquals(listOf("A 질문", "B 질문"), result)
    }

    @Test
    fun `falls back to original question when llm throws`() = runTest {
        val llm = LLMCaller { throw RuntimeException("boom") }
        val result = QueryDecomposer(llm).decompose("원본 질문")
        assertEquals(listOf("원본 질문"), result)
    }

    @Test
    fun `falls back to original when llm returns blank`() = runTest {
        val llm = LLMCaller { "   \n  " }
        val result = QueryDecomposer(llm).decompose("원본 질문")
        assertEquals(listOf("원본 질문"), result)
    }

    @Test
    fun `caps at three sub-questions`() = runTest {
        val llm = LLMCaller { "a\nb\nc\nd\ne" }
        val result = QueryDecomposer(llm).decompose("많은 질문")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `strips numbered dot prefixes`() = runTest {
        val llm = LLMCaller { "1. A 질문\n2. B 질문" }
        val result = QueryDecomposer(llm).decompose("A랑 B")
        assertEquals(listOf("A 질문", "B 질문"), result)
    }

    @Test
    fun `strips numbered paren prefixes`() = runTest {
        val llm = LLMCaller { "1) A 질문\n2) B 질문" }
        val result = QueryDecomposer(llm).decompose("A랑 B")
        assertEquals(listOf("A 질문", "B 질문"), result)
    }
}
