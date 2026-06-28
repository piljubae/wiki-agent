package io.github.veronikapj.wiki.search

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryUnderstandingTest {

    @Test
    fun `parses query synonyms and dates from llm output`() = runTest {
        val llm = QueryLlm {
            """
            QUERY: iOS 배포 가이드
            SYNONYMS: iOS 릴리즈, iOS Release, 배포 프로세스
            DATE_AFTER: 2026-01-01
            DATE_BEFORE:
            """.trimIndent()
        }
        val result = QueryUnderstanding(llm).understand("iOS 배포 가이드 알려줘")

        assertEquals("iOS 배포 가이드", result.cleanedQuery)
        assertEquals(listOf("iOS 릴리즈", "iOS Release", "배포 프로세스"), result.synonyms)
        assertEquals("2026-01-01", result.dateAfter)
        assertEquals(null, result.dateBefore)
    }

    @Test
    fun `falls back to raw query when llm throws`() = runTest {
        val llm = QueryLlm { throw RuntimeException("boom") }
        val result = QueryUnderstanding(llm).understand("결제 흐름")

        assertEquals("결제 흐름", result.cleanedQuery)
        assertTrue(result.synonyms.isEmpty())
        assertEquals(null, result.dateAfter)
        assertEquals(null, result.dateBefore)
    }

    @Test
    fun `falls back when output has no recognizable fields`() = runTest {
        val llm = QueryLlm { "죄송하지만 도와드릴 수 없습니다." }
        val result = QueryUnderstanding(llm).understand("배포")

        assertEquals("배포", result.cleanedQuery)
        assertTrue(result.synonyms.isEmpty())
    }
}
