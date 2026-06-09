package io.github.veronikapj.wiki.search.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.search.GitHubWikiSearchAgent
import kotlinx.coroutines.runBlocking

class GitHubWikiTool(
    private val searchAgent: GitHubWikiSearchAgent,
    private val tracker: SourceTracker? = null,
) {

    @Tool("githubWikiSearch")
    @LLMDescription("GitHub 레포지토리 Wiki에서 문서를 검색합니다. Confluence에 없는 기술 문서나 가이드를 찾을 때 사용하세요.")
    fun githubWikiSearch(
        @LLMDescription("검색할 질문 또는 키워드")
        query: String,
    ): String = runBlocking {
        tracker?.record("GitHub Wiki")
        searchAgent.search(query)
    }
}
