package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class VectorSearchTool(
    private val searchAgent: VectorSearchAgent,
    private val tracker: SourceTracker? = null,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    @Tool("vectorSearch")
    @LLMDescription("RAG(의미 기반 벡터 검색)으로 Confluence 문서를 검색합니다. CQL 검색 결과가 부족할 때 사용하세요.")
    fun vectorSearch(
        @LLMDescription("검색할 질문 또는 키워드")
        query: String,
    ): String = runBlocking {
        tracker?.record("RAG(ChromaDB)")
        withTimeoutOrNull(timeoutMillis) {
            searchAgent.search(query)
        } ?: "RAG 검색 타임아웃"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5000L
    }
}
