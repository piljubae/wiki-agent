package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.github.GithubPrInfo
import io.github.veronikapj.wiki.rag.ChromaClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

class PrIndexAgent(
    private val codeClient: GitHubCodeClient,
    private val knowledgeStore: KnowledgeStore,
    private val llmFn: suspend (String) -> String,
    private val chromaClient: ChromaClient? = null,
    private val embeddingFn: (suspend (String) -> List<Float>)? = null,
    private val pollStateFile: String = ".wiki/code-poll-state.json",
    private val collectionName: String = "code_prs",
) {

    @Serializable
    data class PollState(
        val lastPolledAt: String = "",
        val lastPrNumber: Int = 0,
    )

    suspend fun indexPr(repo: String, prNumber: Int): String {
        val pr = codeClient.fetchPr(repo, prNumber)
            ?: return "PR #${prNumber}를 가져올 수 없습니다."
        val diff = codeClient.fetchPrDiff(repo, prNumber)
        val llmOutput = runCatching { llmFn(buildCompilePrompt(pr, diff)) }
            .getOrDefault("변경 목적: 불명\n영향 영역: 없음\n핵심 변경: 없음\n관련 티켓: 없음")
        val document = compilePrDocument(pr, llmOutput)

        // KnowledgeStore prepends .wiki/knowledge/ → resolves to .wiki/knowledge/prs/owner-repo-pr-NNN.md
        val path = "prs/${repo.replace("/", "-")}-pr-${prNumber}.md"
        knowledgeStore.savePage(path, document)

        if (chromaClient != null) {
            val collectionId = chromaClient.getOrCreateCollection(collectionName)
            val embeddings = embeddingFn?.let { fn ->
                runCatching { listOf(fn(document)) }
                    .onFailure { log.error("Failed to generate embedding for PR #{}: {}", prNumber, it.message) }
                    .getOrNull()
            }
            
            if (embeddings == null && embeddingFn != null) {
                log.warn("Skipping ChromaDB indexing for PR #{} due to embedding failure", prNumber)
            } else {
                chromaClient.upsertDocuments(
                    collectionId = collectionId,
                    ids = listOf("${repo.replace("/", "-")}-pr-${prNumber}"),
                    documents = listOf(document),
                    embeddings = embeddings,
                    metadatas = listOf(mapOf(
                        "repo" to repo,
                        "pr_number" to prNumber.toString(),
                        "state" to pr.state,
                        "ticket" to (codeClient.extractTicket(pr.title, pr.branch) ?: ""),
                        "author" to pr.author,
                        "merged_at" to (pr.mergedAt ?: ""),
                    )),
                )
            }
        }

        log.info("Indexed PR #{} from {}", prNumber, repo)
        return "PR #${prNumber} 인덱싱 완료: ${pr.title}"
    }

    suspend fun indexPrsBulk(repos: List<String>, limit: Int = 500): Int {
        var total = 0
        for (repo in repos) {
            val prs = codeClient.fetchPrsPaged(repo, limit)
            log.info("Bulk PR index: fetched {} PRs for {}", prs.size, repo)
            for (pr in prs) {
                runCatching { indexPr(repo, pr.number) }
                    .onSuccess { total++ }
                    .onFailure { log.warn("Failed to index PR #{}: {}", pr.number, it.message) }
            }
            log.info("Bulk PR index done: {} PRs indexed for {}", total, repo)
        }
        return total
    }

    suspend fun indexRecentPrs(repos: List<String>): Int {
        var total = 0
        val stateMap = loadPollState()

        for (repo in repos) {
            val state = stateMap[repo] ?: PollState()
            val since = state.lastPolledAt.ifBlank { null }
            val prs = codeClient.fetchRecentPrs(repo, since)
            log.info("Found {} PRs to check for {} (since={})", prs.size, repo, since)

            var maxPrNumber = state.lastPrNumber
            for (pr in prs) {
                if (pr.number <= state.lastPrNumber && state.lastPrNumber > 0) continue
                runCatching { indexPr(repo, pr.number) }
                    .onSuccess {
                        total++
                        if (pr.number > maxPrNumber) maxPrNumber = pr.number
                    }
                    .onFailure { log.warn("Failed to index PR #{}: {}", pr.number, it.message) }
            }

            if (prs.isNotEmpty() && maxPrNumber == state.lastPrNumber) {
                log.warn("No PRs indexed for {} ({} fetched, all failed)", repo, prs.size)
            }
            stateMap[repo] = PollState(
                lastPolledAt = Instant.now().toString(),
                lastPrNumber = maxPrNumber,
            )
        }

        savePollState(stateMap)
        return total
    }

    internal fun compilePrDocument(pr: GithubPrInfo, llmOutput: String): String = buildString {
        appendLine("# PR #${pr.number}: ${pr.title}")
        appendLine()
        appendLine("- *레포*: ${pr.repo}")
        appendLine("- *작성자*: ${pr.author}")
        appendLine("- *상태*: ${pr.state}${if (pr.merged) " (merged)" else ""}")
        pr.mergedAt?.let { appendLine("- *머지*: $it") }
        codeClient.extractTicket(pr.title, pr.branch)?.let { appendLine("- *티켓*: $it") }
        appendLine()
        appendLine(llmOutput)
        if (pr.changedFiles.isNotEmpty()) {
            appendLine()
            appendLine("## 변경 파일")
            pr.changedFiles.forEach { appendLine("- $it") }
        }
    }

    private fun buildCompilePrompt(pr: GithubPrInfo, diff: String): String = buildString {
        appendLine("다음 GitHub Pull Request를 구조화된 문서로 정리하세요.")
        appendLine("출력 형식 (정확히 이 4개 항목만):")
        appendLine("변경 목적: <한 줄 요약>")
        appendLine("영향 영역: <파일/모듈/클래스명 목록>")
        appendLine("핵심 변경: <주요 변경 내용 2-3줄>")
        appendLine("관련 티켓: <티켓 번호 또는 없음>")
        appendLine()
        appendLine("PR #${pr.number}: ${pr.title}")
        appendLine("작성자: ${pr.author} | 상태: ${pr.state} | 브랜치: ${pr.branch}")
        if (pr.body.isNotBlank()) {
            appendLine()
            appendLine("PR 설명:")
            appendLine(pr.body.take(800))
        }
        if (pr.changedFiles.isNotEmpty()) {
            appendLine()
            appendLine("변경 파일 (${pr.changedFiles.size}개):")
            pr.changedFiles.take(20).forEach { appendLine("  - $it") }
        }
        if (diff.isNotBlank()) {
            appendLine()
            appendLine("주요 diff:")
            appendLine(diff.take(1500))
        }
    }

    private fun loadPollState(): MutableMap<String, PollState> {
        val file = File(pollStateFile)
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, PollState>>(file.readText()).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun savePollState(state: Map<String, PollState>) {
        val file = File(pollStateFile)
        file.parentFile?.mkdirs()
        val tmp = File("$pollStateFile.tmp")
        tmp.writeText(Json.encodeToString(state))
        tmp.renameTo(file)
    }

    companion object {
        private val log = LoggerFactory.getLogger(PrIndexAgent::class.java)
    }
}
