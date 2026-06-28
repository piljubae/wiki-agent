package io.github.veronikapj.wiki.search.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.search.QueryUnderstanding
import kotlinx.coroutines.runBlocking

class ConfluenceTool(
    private val searchAgent: ConfluenceSearchAgent,
    private val tracker: SourceTracker? = null,
    private val queryUnderstanding: QueryUnderstanding? = null,
) {

    @Tool("confluenceSearch")
    @LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
    fun confluenceSearch(
        @LLMDescription("검색할 질문 또는 키워드 (한국어 가능)")
        query: String,
    ): String = runBlocking {
        tracker?.record("Confluence")
        val u = queryUnderstanding
        if (u == null) {
            searchAgent.search(query)
        } else {
            val understood = u.understand(query)
            searchAgent.search(
                understood.cleanedQuery,
                understood.synonyms,
                5,
                understood.dateAfter,
                understood.dateBefore,
                query,
            )
        }
    }

    suspend fun confluenceSearchSuspend(
        query: String, synonyms: List<String> = emptyList(),
        dateAfter: String? = null, dateBefore: String? = null,
        originalQuestion: String = "",
    ): String {
        tracker?.record("Confluence")
        return searchAgent.search(query, synonyms, dateAfter = dateAfter, dateBefore = dateBefore, originalQuestion = originalQuestion)
    }
}
