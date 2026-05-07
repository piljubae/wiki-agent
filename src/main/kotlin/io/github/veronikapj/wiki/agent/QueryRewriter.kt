package io.github.veronikapj.wiki.agent

import org.slf4j.LoggerFactory

data class RewrittenQuery(
    val bm25: String,
    val vector: String,
    val additionalTools: List<String>,
)

fun interface LLMCaller {
    suspend fun call(prompt: String): String
}

class QueryRewriter(private val llm: LLMCaller) {

    private val log = LoggerFactory.getLogger(QueryRewriter::class.java)

    suspend fun rewrite(query: String, usedTools: List<String>): RewrittenQuery {
        val prompt = """
            원래 질문: $query
            첫 검색에 사용한 도구: ${usedTools.joinToString(", ")}

            아래 3가지를 각각 한 줄로 출력하세요:
            BM25: [한국어 동의어 + 영문 클래스명/메서드명 패턴, 공백 구분]
            VECTOR: [같은 의미의 다른 표현으로 재작성한 자연어 문장]
            TOOLS: [추가로 시도할 도구 목록 (confluence/code_search/bm25 중), 없으면 SAME]
        """.trimIndent()

        val raw = llm.call(prompt)
        log.debug("QueryRewriter raw output: {}", raw)
        return parse(raw)
    }

    private fun parse(raw: String): RewrittenQuery {
        val lines = raw.lines().associate { line ->
            val colon = line.indexOf(':')
            if (colon < 0) return@associate "" to ""
            line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        val tools = lines["TOOLS"]
            ?.takeIf { it.isNotBlank() && it != "SAME" }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        return RewrittenQuery(
            bm25 = lines["BM25"].orEmpty(),
            vector = lines["VECTOR"].orEmpty(),
            additionalTools = tools,
        )
    }
}
