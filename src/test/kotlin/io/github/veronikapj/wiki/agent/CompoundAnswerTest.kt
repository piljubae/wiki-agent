package io.github.veronikapj.wiki.agent

import kotlin.test.Test
import kotlin.test.assertTrue

class CompoundAnswerTest {

    @Test
    fun `builds labeled sections for each step`() {
        val steps = listOf(
            StepResult("결제 흐름 코드", "codeSearch", "PaymentFlow.kt ..."),
            StepResult("결제 위키", "confluenceSearch", "컬리페이 설계 문서 ..."),
        )
        val block = buildSectionedResultBlock(steps)

        assertTrue(block.contains("결제 흐름 코드"))
        assertTrue(block.contains("PaymentFlow.kt"))
        assertTrue(block.contains("결제 위키"))
        assertTrue(block.contains("컬리페이 설계 문서"))
    }

    @Test
    fun `marks step with null result as not found`() {
        val steps = listOf(StepResult("없는 주제", "confluenceSearch", null))
        val block = buildSectionedResultBlock(steps)
        assertTrue(block.contains("없는 주제"))
        assertTrue(block.contains("찾지 못했습니다"))
    }
}
