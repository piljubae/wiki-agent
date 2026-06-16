package io.github.veronikapj.wiki.search.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class PrHistoryTool(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val tracker: SourceTracker? = null,
    private val collectionName: String = "code_prs",
    private val embeddingFn: (suspend (String) -> List<Float>)? = null,
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
            
            // 이 컬렉션은 서버 내장 임베딩 함수 없음, 반드시 queryEmbeddings 필요
            val queryEmbeddings = embeddingFn?.let { fn ->
                runCatching { listOf(fn(expandedQuery)) }.getOrElse { e ->
                    log.warn("Embedding failed, skipping vector search: {}", e.message)
                    null
                }
            }
            if (queryEmbeddings == null) {
                log.warn("embeddingFn unavailable — vector search disabled for collection '{}'", collectionName)
                return@runBlocking "PR 이력 검색을 위한 임베딩 기능이 비활성화 상태입니다."
            }

            // 넓게 뽑아 dedup·재정렬 여지를 둔다 (표현 차이에 따른 결과 흔들림 완화).
            val rawResults = chromaClient.query(
                collectionId = collectionId,
                queryEmbeddings = queryEmbeddings,
                nResults = 15
            )

            if (rawResults.isEmpty()) return@runBlocking "관련 PR 이력을 찾을 수 없습니다."

            // 1) pr_number 기준 중복 제거 (유사도 높은 첫 청크 유지)
            val deduped = rawResults.distinctBy { it.metadata["pr_number"] ?: it.document }

            // 2) 질문에 포함된 코드 식별자를 실제 언급한 PR을 우선 — 같은 대상이면 표현이 달라도 상위 고정
            val identifiers = extractIdentifiers(query)
            val results = if (identifiers.isNotEmpty()) {
                val (matched, rest) = deduped.partition { r ->
                    val hay = (r.document + " " + r.metadata.values.joinToString(" ")).lowercase()
                    identifiers.any { hay.contains(it.lowercase()) }
                }
                (matched + rest).take(5)
            } else {
                deduped.take(5)
            }

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

    /**
     * 질문에서 코드 식별자 후보를 추출 — camelCase/PascalCase, snake_case,
     * 또는 4자 이상 영숫자 토큰. 일반 영어 단어(관련/PR 등 불용어)는 안 잡히도록
     * "대문자 포함 또는 _ 포함" 형태만 식별자로 인정한다.
     */
    private fun extractIdentifiers(query: String): List<String> =
        Regex("[A-Za-z_][A-Za-z0-9_]{3,}").findAll(query)
            .map { it.value }
            .filter { tok -> tok.any { it.isUpperCase() } || tok.contains('_') }
            .distinct()
            .toList()

    companion object {
        private val log = LoggerFactory.getLogger(PrHistoryTool::class.java)
    }
}
