package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import kotlinx.coroutines.runBlocking

class PrHistoryTool(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val tracker: SourceTracker? = null,
    private val collectionName: String = "code_prs",
) {

    @Tool("prHistory")
    @LLMDescription(
        "GitHub PR 기반 코드 변경 이력을 검색합니다. " +
        "언제 어떤 기능이 추가/수정됐는지, 특정 티켓(KMA-XXXX)의 작업 내용, " +
        "누가 어떤 파일을 변경했는지 알고 싶을 때 사용하세요. " +
        "소스코드 위치나 함수 찾기에는 codeSearch를 사용하세요."
    )
    fun prHistory(
        @LLMDescription("검색할 내용 (티켓 번호, 기능명, 작성자, 변경 내용 등). 예: KMA-7275, 배너 클릭 이벤트, pilju.bae")
        query: String,
    ): String = runBlocking {
        tracker?.record("PRHistory")
        runCatching {
            val collectionId = chromaClient.getOrCreateCollection(collectionName)
            val expandedQuery = llmExpandClient?.expandQuery(query) ?: query
            val results = chromaClient.query(collectionId, queryTexts = listOf(expandedQuery), nResults = 5)

            if (results.isEmpty()) return@runBlocking "관련 PR 이력을 찾을 수 없습니다."

            buildString {
                appendLine("*\"$query\"* 관련 PR 이력 (${results.size}건):\n")
                results.forEachIndexed { i, r ->
                    val repo = r.metadata["repo"] ?: ""
                    val prNumber = r.metadata["pr_number"] ?: ""
                    val ticket = r.metadata["ticket"]?.takeIf { it.isNotBlank() }
                    val author = r.metadata["author"] ?: ""
                    val mergedAt = r.metadata["merged_at"]?.take(10) ?: ""

                    appendLine("${i + 1}. PR #$prNumber${if (ticket != null) " ($ticket)" else ""}")
                    if (author.isNotBlank()) appendLine("   작성자: $author${if (mergedAt.isNotBlank()) " | $mergedAt" else ""}")
                    if (repo.isNotBlank()) appendLine("   <https://github.com/$repo/pull/$prNumber|GitHub PR>")
                    appendLine("   > ${r.document.lines().take(3).joinToString(" ").take(200)}")
                    appendLine()
                }
            }.trim()
        }.getOrElse { "PR 이력 검색 중 오류: ${it.message}" }
    }
}
