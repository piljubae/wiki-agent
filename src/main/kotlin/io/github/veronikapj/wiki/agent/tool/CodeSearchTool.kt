package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import kotlinx.coroutines.runBlocking

class CodeSearchTool(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val codeClient: GitHubCodeClient,
    private val codeRepos: List<String>,
    private val branch: String = "develop",
    private val tracker: SourceTracker? = null,
    private val collectionName: String = "code_index",
) {

    @Tool("codeSearch")
    @LLMDescription(
        "Kurly Android 소스코드에서 클래스, 함수, 구현 위치를 검색합니다. " +
        "'ProductViewModel 어디있어?', 'panelCode 어디서 쓰여?', '배너 클릭 이벤트 구현 방법' 같은 질문에 사용하세요. " +
        "PR 변경 이력이나 작업 내용은 prHistory를 사용하세요."
    )
    fun codeSearch(
        @LLMDescription("검색할 클래스명, 함수명, 또는 기능 설명. 예: BannerViewModel, panelCode, 배너 클릭 이벤트")
        query: String,
    ): String = runBlocking {
        tracker?.record("CodeSearch")
        runCatching {
            val collectionId = chromaClient.getOrCreateCollection(collectionName)
            val expandedQuery = llmExpandClient?.expandQuery(query) ?: query
            val results = chromaClient.query(collectionId, queryTexts = listOf(expandedQuery), nResults = 5)

            val chromaResult = if (results.isNotEmpty()) {
                buildString {
                    appendLine("*\"$query\"* 관련 코드 (${results.size}건):\n")
                    results.forEachIndexed { i, r ->
                        val repo = r.metadata["repo"] ?: ""
                        val filePath = r.metadata["file_path"] ?: ""
                        val className = r.metadata["class_name"] ?: ""
                        appendLine("${i + 1}. `$className` — $filePath")
                        if (repo.isNotBlank() && filePath.isNotBlank()) {
                            appendLine("   <https://github.com/$repo/blob/$branch/$filePath|소스 보기>")
                        }
                        appendLine("   > ${r.document.lines().take(2).joinToString(" ").take(200)}")
                        appendLine()
                    }
                }.trim()
            } else null

            // ChromaDB 결과 없으면 GitHub Code Search API fallback
            if (chromaResult != null) chromaResult
            else {
                val apiResults = codeRepos.flatMap { repo ->
                    runCatching { codeClient.searchCode(repo, query, branch) }.getOrDefault(emptyList())
                }.take(5)

                if (apiResults.isEmpty()) return@runBlocking "관련 코드를 찾을 수 없습니다."

                buildString {
                    appendLine("*\"$query\"* 관련 코드 (GitHub Search):\n")
                    apiResults.forEachIndexed { i, r ->
                        appendLine("${i + 1}. `${r.filePath}`")
                        appendLine("   <${r.htmlUrl}|소스 보기>")
                        appendLine()
                    }
                }.trim()
            }
        }.getOrElse { "코드 검색 중 오류: ${it.message}" }
    }
}
