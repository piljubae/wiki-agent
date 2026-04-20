package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import kotlinx.coroutines.runBlocking

class ConfluenceTool(private val searchAgent: ConfluenceSearchAgent) {

    @Tool("confluenceSearch")
    @LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
    fun confluenceSearch(
        @LLMDescription("검색할 질문 또는 키워드 (한국어 가능)")
        query: String,
    ): String = runBlocking { searchAgent.search(query) }
}
