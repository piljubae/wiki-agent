package io.github.veronikapj.wiki.agent

import org.slf4j.LoggerFactory

/**
 * 복합 질문을 독립적으로 검색 가능한 sub-question으로 분해한다.
 * 단순 질문이면 원본 1개를 그대로 반환 → 기존 단일 경로와 동일하게 동작.
 */
class QueryDecomposer(private val llm: LLMCaller) {

    suspend fun decompose(question: String, context: String = ""): List<String> {
        return runCatching {
            val raw = llm.call(buildPrompt(question, context))
            val parsed = raw.lines()
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_SUB_QUESTIONS)
            parsed.ifEmpty { listOf(question) }
        }.getOrElse {
            log.warn("QueryDecomposer failed, fallback to original: {}", it.message)
            listOf(question)
        }
    }

    private fun buildPrompt(question: String, context: String): String = buildString {
        appendLine("아래 질문을 독립적으로 검색 가능한 하위 질문으로 나누세요.")
        appendLine("- 복합 질문(서로 다른 주제·대상이 둘 이상)이면 하위 질문을 한 줄에 하나씩 출력.")
        appendLine("- 단순 질문이면 원래 질문을 한 줄로 그대로 출력.")
        appendLine("- 최대 3개. 설명·번호·불릿 없이 질문 문장만 출력.")
        if (context.isNotBlank()) {
            appendLine()
            appendLine("이전 대화(지시어 해소용):")
            appendLine(context)
        }
        appendLine()
        appendLine("질문: $question")
    }

    companion object {
        private val log = LoggerFactory.getLogger(QueryDecomposer::class.java)
        private const val MAX_SUB_QUESTIONS = 3
    }
}
