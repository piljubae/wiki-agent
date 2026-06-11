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
        val prId = "${repo.replace("/", "-")}-pr-${prNumber}"
        if (chromaClient != null) {
            val collectionId = chromaClient.getOrCreateCollection(collectionName)
            val existing = chromaClient.getExistingIds(collectionId, ids = listOf(prId))
            if (existing.isNotEmpty()) {
                log.info("PR #{} already indexed, skipping LLM & DB update", prNumber)
                return "PR #${prNumber} 이미 인덱싱됨."
            }
        }

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
            
            // 임베딩 실패 시 silent skip 금지: 디스크 문서는 이미 저장됐으므로 호출자가 실패로 인지하게 throw.
            // poll state가 전진하지 않아 다음 폴링/reconcile에서 재시도 대상으로 남는다.
            // (과거엔 여기서 skip + 성공 반환 → poll state 전진 → ChromaDB 누락분이 영구 미인덱싱됐음)
            if (embeddingFn != null && embeddings == null) {
                error("PR #$prNumber 임베딩 생성 실패 — ChromaDB 인덱싱 보류 (reconcile/재시도 대상)")
            }
            chromaClient.upsertDocuments(
                collectionId = collectionId,
                ids = listOf(prId),
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

    /**
     * 디스크에 PR 문서는 있으나 ChromaDB(벡터DB)에 없는 PR을 백필한다.
     * poll state의 high-water mark(lastPrNumber)에 가려 [indexRecentPrs]가 영구히 재시도하지 못하는
     * 누락분(과거 임베딩 실패로 발생)을 복구한다. GitHub 재조회·LLM 재컴파일 없이 디스크 문서를
     * 그대로 임베딩·업서트하므로 임베딩 호출 외 비용이 없다.
     *
     * @param maxPerRun 한 번 실행에서 백필할 최대 PR 수 (폭주 방지용 상한). 초과분은 다음 호출에서 처리.
     * @return 이번 실행에서 백필 성공한 PR 수
     */
    suspend fun reconcileMissing(repos: List<String>, maxPerRun: Int = 1000): Int {
        val chroma = chromaClient
        val embed = embeddingFn
        if (chroma == null || embed == null) {
            log.warn("reconcileMissing 생략: chromaClient 또는 embeddingFn 미설정")
            return 0
        }
        val collectionId = chroma.getOrCreateCollection(collectionName)
        val allDocs = knowledgeStore.loadPrDocs()
        var total = 0
        var budget = maxPerRun
        for (repo in repos) {
            if (budget <= 0) break
            val prefix = "${repo.replace("/", "-")}-pr-"
            val repoDocs = allDocs.filter { it.first.startsWith(prefix) }
            if (repoDocs.isEmpty()) continue
            val existing = chroma.getExistingIds(collectionId, repoDocs.map { it.first })
            val missing = repoDocs.filter { it.first !in existing }
            log.info("reconcileMissing {}: disk={}, ChromaDB 누락={}", repo, repoDocs.size, missing.size)
            for ((prId, document) in missing) {
                if (budget <= 0) {
                    log.warn("reconcileMissing: maxPerRun({}) 도달 — 남은 누락분은 다음 실행에서 처리", maxPerRun)
                    break
                }
                val meta = parsePrMetadata(prId, document)
                if (meta == null) {
                    log.warn("reconcileMissing: 메타데이터 파싱 실패, 건너뜀 — {}", prId)
                    continue
                }
                val embedding = runCatching { embed(document) }
                    .onFailure { log.warn("reconcileMissing 임베딩 실패 {}: {}", prId, it.message) }
                    .getOrNull() ?: continue
                budget--
                runCatching {
                    chroma.upsertDocuments(
                        collectionId = collectionId,
                        ids = listOf(prId),
                        documents = listOf(document),
                        embeddings = listOf(embedding),
                        metadatas = listOf(meta),
                    )
                }.onSuccess { total++; log.info("reconciled {}", prId) }
                    .onFailure { log.warn("reconcileMissing upsert 실패 {}: {}", prId, it.message) }
            }
        }
        log.info("reconcileMissing 완료: {} PR 백필", total)
        return total
    }

    /** [compilePrDocument]가 생성한 PR 마크다운에서 ChromaDB 메타데이터를 역파싱한다. */
    internal fun parsePrMetadata(prId: String, document: String): Map<String, String>? {
        val prNumber = prId.substringAfterLast("-pr-").toIntOrNull()?.toString() ?: return null
        fun field(label: String): String? =
            Regex("^- \\*$label\\*:\\s*(.+)$", RegexOption.MULTILINE)
                .find(document)?.groupValues?.get(1)?.trim()
        val repo = field("레포") ?: return null
        val stateRaw = field("상태") ?: ""
        return mapOf(
            "repo" to repo,
            "pr_number" to prNumber,
            "state" to stateRaw.substringBefore(" (merged)").trim(),
            "ticket" to (field("티켓") ?: ""),
            "author" to (field("작성자") ?: ""),
            "merged_at" to (field("머지") ?: ""),
        )
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
