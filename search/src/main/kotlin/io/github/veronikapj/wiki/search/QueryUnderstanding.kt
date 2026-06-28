package io.github.veronikapj.wiki.search

import org.slf4j.LoggerFactory

fun interface QueryLlm {
    suspend fun call(prompt: String): String
}

data class UnderstoodQuery(
    val cleanedQuery: String,
    val synonyms: List<String>,
    val dateAfter: String?,
    val dateBefore: String?,
)

/**
 * bare query 하나로 검색 품질을 끌어올리는 enrich 프리미티브.
 * Koog 경로의 confluenceSearch(query) 가 synonyms·날짜 없이 호출될 때 보완한다.
 */
class QueryUnderstanding(private val llm: QueryLlm) {

    suspend fun understand(rawQuery: String): UnderstoodQuery {
        return runCatching {
            val raw = llm.call(buildPrompt(rawQuery))
            val query = QUERY_RE.find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: rawQuery
            val synonyms = SYN_RE.find(raw)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val dateAfter = DATE_AFTER_RE.find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val dateBefore = DATE_BEFORE_RE.find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            UnderstoodQuery(query, synonyms, dateAfter, dateBefore)
        }.getOrElse {
            log.warn("QueryUnderstanding failed, fallback to raw: {}", it.message)
            UnderstoodQuery(rawQuery, emptyList(), null, null)
        }
    }

    private fun buildPrompt(query: String): String = """
        아래 검색어를 Confluence 위키 검색에 맞게 분석하세요. 지정 형식만 출력하고 다른 텍스트는 금지합니다.

        QUERY: [Confluence 제목에 들어갈 법한 핵심 용어. 대화형 접미사 제거, 팀명·플랫폼 수식어는 유지]
        SYNONYMS: [같은 개념의 다른 표현 3~6개, 쉼표 구분. 한국어↔영어 양방향, 약어 확장 포함]
        DATE_AFTER: [최신/최근/지난주 의도면 yyyy-MM-dd, 아니면 빈칸]
        DATE_BEFORE: [기간 상한이 있으면 yyyy-MM-dd, 아니면 빈칸]

        검색어: $query
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(QueryUnderstanding::class.java)
        private val QUERY_RE = Regex("QUERY:\\s*(.+)")
        private val SYN_RE = Regex("SYNONYMS:\\s*(.+)")
        private val DATE_AFTER_RE = Regex("DATE_AFTER:\\s*(\\S*)")
        private val DATE_BEFORE_RE = Regex("DATE_BEFORE:\\s*(\\S*)")
    }
}
