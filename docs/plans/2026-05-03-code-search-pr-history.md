# Code Search & PR History Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** wiki-agent에 Kurly Android 소스코드 검색(CodeSearchTool)과 PR 기반 작업 이력 검색(PrHistoryTool)을 추가한다.

**Architecture:** PR은 IngestAgent 패턴을 따라 LLM이 컴파일한 구조화 문서로 ChromaDB(code_prs)에 저장. 코드 파일은 regex 기반 class 단위로 추출해 ChromaDB(code_index)에 저장. 3가지 트리거(webhook / polling / DM PR URL)로 인덱싱을 자동화한다.

**Tech Stack:** Ktor Client(이미 있음), Ktor Server CIO(추가), ChromaDB(기존 rag 패키지 재사용), Kotlin coroutines

---

## 사전 지식

### 프로젝트 구조
```
src/main/kotlin/io/github/veronikapj/wiki/
├── Main.kt                          # 진입점 — tool 등록, polling 코루틴, webhook 서버
├── config/WikiConfig.kt             # 설정 데이터 클래스
├── github/GitHubWikiClient.kt       # 기존 wiki 검색 클라이언트 (참고용)
├── knowledge/IngestAgent.kt         # URL → LLM 컴파일 → KnowledgeStore 패턴 (참고용)
├── rag/
│   ├── ChromaClient.kt              # ChromaDB HTTP REST 클라이언트
│   ├── VectorIndexAgent.kt          # Confluence → ChromaDB 인덱싱 패턴 (참고용)
│   └── VectorSearchAgent.kt         # ChromaDB 검색 패턴 (참고용)
├── agent/OrchestratorAgent.kt       # 라우팅 결정 + executeParallel (수정 필요)
└── slack/
    ├── SlackBotGateway.kt           # DM URL 감지 패턴 (수정 필요)
    └── SlackConfigHandler.kt        # /wiki 슬래시 커맨드 (수정 필요)
```

### ChromaDB 컬렉션 (신규)
- `code_prs` — PR LLM 컴파일 문서. metadata: `repo`, `pr_number`, `state`, `ticket`, `author`, `merged_at`
- `code_index` — 코드 class 단위 문서. metadata: `repo`, `file_path`, `class_name`, `branch`

### config.yml 추가 (최종 형태)
```yaml
github:
  enabled: true
  repos:
    - Veronikapj/wiki-agent
  codeRepos:
    - thefarmersfront/kurly-android
  codeSearch:
    branch: develop
    pollIntervalMinutes: 60   # 0이면 비활성
    webhookPort: 8080         # 0이면 비활성
```

---

## Task 1: Config 확장

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt`

**Step 1: `GithubConfig`에 `codeRepos`, `codeSearch` 추가**

```kotlin
data class GithubConfig(
    val enabled: Boolean = false,
    val token: String = "",
    val repos: List<String> = emptyList(),
    val codeRepos: List<String> = emptyList(),
    val codeSearch: CodeSearchConfig = CodeSearchConfig(),
)

data class CodeSearchConfig(
    val branch: String = "develop",
    val pollIntervalMinutes: Int = 60,
    val webhookPort: Int = 0,
)
```

**Step 2: 컴파일 검증**
```bash
./gradlew :compileKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
```bash
git add src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt
git commit -m "feat: GithubConfig에 codeRepos, CodeSearchConfig 추가"
```

---

## Task 2: GitHubCodeClient

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/github/GitHubCodeClient.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/github/GitHubCodeClientTest.kt`

### 배경
GitHub REST API 엔드포인트:
- PR 조회: `GET /repos/{owner}/{repo}/pulls/{pull_number}`
- PR diff: `GET /repos/{owner}/{repo}/pulls/{pull_number}` + `Accept: application/vnd.github.v3.diff`
- 최근 PR 목록: `GET /repos/{owner}/{repo}/pulls?state=all&sort=updated&direction=desc&per_page=50`
- Code Search: `GET /search/code?q={query}+repo:{owner}/{repo}`

**Step 1: 테스트 파일 작성**

```kotlin
package io.github.veronikapj.wiki.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class GitHubCodeClientTest {

    private val client = GitHubCodeClient(token = "")

    @Test
    fun `parsePrUrl — github PR URL에서 repo와 PR 번호 추출`() {
        val result = client.parsePrUrl("https://github.com/thefarmersfront/kurly-android/pull/7400")
        assertNotNull(result)
        assertEquals("thefarmersfront/kurly-android", result.first)
        assertEquals(7400, result.second)
    }

    @Test
    fun `parsePrUrl — PR URL이 아니면 null 반환`() {
        assertNull(client.parsePrUrl("https://github.com/owner/repo"))
        assertNull(client.parsePrUrl("https://github.com/owner/repo/issues/123"))
        assertNull(client.parsePrUrl("https://example.com/pull/1"))
    }

    @Test
    fun `filterDiffLines — lock 파일과 generated 파일 diff 제거`() {
        val diff = """
            diff --git a/Podfile.lock b/Podfile.lock
            +some lock content
            diff --git a/src/Main.kt b/src/Main.kt
            +real code change
            diff --git a/build/generated/Main.kt b/build/generated/Main.kt
            +generated content
        """.trimIndent()
        val result = client.filterDiffLines(diff)
        assert(!result.contains("Podfile.lock"))
        assert(!result.contains("generated"))
        assert(result.contains("src/Main.kt"))
    }

    @Test
    fun `extractTicket — 브랜치명 또는 PR 제목에서 KMA-XXXX 추출`() {
        assertEquals("KMA-7275", client.extractTicket("KMA-7275 배너 DSP Phase2", "feature/KMA-7275"))
        assertEquals("KMA-7275", client.extractTicket("배너 수정", "feature/KMA-7275-banner"))
        assertEquals("IUHG-123", client.extractTicket("IUHG-123 growth feature", ""))
        assertNull(client.extractTicket("일반 커밋", "main"))
    }
}
```

**Step 2: 테스트 실행 → 실패 확인**
```bash
./gradlew test --tests "io.github.veronikapj.wiki.github.GitHubCodeClientTest" 2>&1 | tail -10
```
Expected: FAILED (class not found)

**Step 3: GitHubCodeClient 구현**

```kotlin
package io.github.veronikapj.wiki.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory

data class GithubPrInfo(
    val repo: String,
    val number: Int,
    val title: String,
    val body: String,
    val state: String,        // "open" | "closed"
    val merged: Boolean,
    val mergedAt: String?,
    val author: String,
    val branch: String,
    val changedFiles: List<String>,
)

data class GithubCodeResult(
    val repo: String,
    val filePath: String,
    val htmlUrl: String,
    val snippet: String,
)

class GitHubCodeClient(private val token: String = "") {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    // PR 메타데이터 조회
    suspend fun fetchPr(repo: String, prNumber: Int): GithubPrInfo? {
        val url = "https://api.github.com/repos/$repo/pulls/$prNumber"
        val json = apiGet(url) ?: return null
        return runCatching { parsePrJson(repo, prNumber, json) }.getOrNull()
    }

    // PR diff 조회 (필터링 + truncate)
    suspend fun fetchPrDiff(repo: String, prNumber: Int, maxChars: Int = 2000): String {
        val url = "https://api.github.com/repos/$repo/pulls/$prNumber"
        val diff = runCatching {
            httpClient.get(url) {
                header("Accept", "application/vnd.github.v3.diff")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.bodyAsText()
        }.getOrDefault("")
        return filterDiffLines(diff).take(maxChars)
    }

    // since 이후 업데이트된 PR 목록 (최대 50개)
    suspend fun fetchRecentPrs(repo: String, since: String? = null): List<GithubPrInfo> {
        val url = buildString {
            append("https://api.github.com/repos/$repo/pulls?state=all&sort=updated&direction=desc&per_page=50")
            if (since != null) append("&since=$since")
        }
        val json = apiGet(url) ?: return emptyList()
        return runCatching { parsePrListJson(repo, json) }.getOrDefault(emptyList())
    }

    // GitHub Code Search API
    suspend fun searchCode(repo: String, query: String, branch: String = "develop"): List<GithubCodeResult> {
        val q = "$query+repo:$repo"
        val url = "https://api.github.com/search/code?q=${q.replace(" ", "+")}&per_page=10"
        val json = apiGet(url) ?: return emptyList()
        return runCatching { parseCodeSearchJson(repo, json) }.getOrDefault(emptyList())
    }

    // PR URL 파싱: "https://github.com/owner/repo/pull/123" → Pair("owner/repo", 123)
    fun parsePrUrl(url: String): Pair<String, Int>? {
        val regex = Regex("github\\.com/([^/]+/[^/]+)/pull/(\\d+)")
        val match = regex.find(url) ?: return null
        return match.groupValues[1] to match.groupValues[2].toInt()
    }

    // diff에서 lock/generated 파일 제거
    fun filterDiffLines(diff: String): String {
        val excludePatterns = listOf(
            "Podfile.lock", "package-lock.json", "yarn.lock",
            "build/generated", ".generated.", "/*.pb.swift",
        )
        val lines = diff.lines()
        val result = mutableListOf<String>()
        var skip = false
        for (line in lines) {
            if (line.startsWith("diff --git")) {
                skip = excludePatterns.any { line.contains(it) }
            }
            if (!skip) result += line
        }
        return result.joinToString("\n")
    }

    // PR title/branch에서 티켓 번호 추출
    fun extractTicket(title: String, branch: String): String? {
        val pattern = Regex("[A-Z]+-\\d+")
        return pattern.find(title)?.value ?: pattern.find(branch)?.value
    }

    private suspend fun apiGet(url: String): String? {
        return runCatching {
            val response = httpClient.get(url) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            response.bodyAsText()
        }.onFailure { log.warn("GitHub API failed: {} — {}", url, it.message) }.getOrNull()
    }

    private fun parsePrJson(repo: String, number: Int, json: String): GithubPrInfo {
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        fun boolField(name: String) = Regex("\"$name\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) == "true"

        val body = field("body").ifBlank { field("body") }
        val author = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        val branch = Regex("\"head\".*?\"ref\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: ""
        val filesJson = runCatching {
            Regex("\"filename\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        }.getOrDefault(emptyList())

        return GithubPrInfo(
            repo = repo,
            number = number,
            title = field("title"),
            body = body.take(1000),
            state = field("state"),
            merged = boolField("merged"),
            mergedAt = field("merged_at").ifBlank { null },
            author = author,
            branch = branch,
            changedFiles = filesJson,
        )
    }

    private fun parsePrListJson(repo: String, json: String): List<GithubPrInfo> {
        val prBlocks = Regex("\\{[^{}]*\"number\"[^{}]*\\}").findAll(json)
        return prBlocks.mapNotNull { match ->
            runCatching {
                val block = match.value
                fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: ""
                val number = Regex("\"number\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                GithubPrInfo(
                    repo = repo, number = number,
                    title = field("title"), body = "",
                    state = field("state"),
                    merged = field("merged_at").isNotBlank(),
                    mergedAt = field("merged_at").ifBlank { null },
                    author = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: "",
                    branch = "", changedFiles = emptyList(),
                )
            }.getOrNull()
        }.toList()
    }

    private fun parseCodeSearchJson(repo: String, json: String): List<GithubCodeResult> {
        return Regex("\"path\"\\s*:\\s*\"([^\"]+)\".*?\"html_url\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(json)
            .take(10)
            .map { m ->
                GithubCodeResult(
                    repo = repo,
                    filePath = m.groupValues[1],
                    htmlUrl = m.groupValues[2],
                    snippet = "",
                )
            }.toList()
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(GitHubCodeClient::class.java)
    }
}
```

**Step 4: 테스트 실행 → 통과 확인**
```bash
./gradlew test --tests "io.github.veronikapj.wiki.github.GitHubCodeClientTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 4 tests passed

**Step 5: Commit**
```bash
git add src/main/kotlin/io/github/veronikapj/wiki/github/GitHubCodeClient.kt \
        src/test/kotlin/io/github/veronikapj/wiki/github/GitHubCodeClientTest.kt
git commit -m "feat: GitHubCodeClient — PR fetch, diff, code search, PR URL 파싱"
```

---

## Task 3: PrIndexAgent

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/PrIndexAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/PrIndexAgentTest.kt`
- Modify: `build.gradle.kts` — ktor-server-cio 추가 (webhook용, 이 Task에선 아직 불필요, Task 8에서 사용)

### 배경
- `KnowledgeStore.save(path, content)` — `.wiki/knowledge/{path}` 파일 저장
- `ChromaClient.getOrCreateCollection(name)` + `addDocuments(...)` — 벡터 저장
- `LlmExpandClient.enrichDocument(text)` — LLM으로 문서 확장 (임베딩 품질 향상)
- Poll 상태 파일: `.wiki/code-poll-state.json`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.github.GithubPrInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PrIndexAgentTest {

    private val mockCodeClient = mockk<GitHubCodeClient>()
    private val mockStore = mockk<io.github.veronikapj.wiki.knowledge.KnowledgeStore>(relaxed = true)
    private val llmCalls = mutableListOf<String>()
    private val mockLlm: suspend (String) -> String = { prompt ->
        llmCalls += prompt
        "변경 목적: 테스트\n영향 영역: ViewModel\n핵심 변경: 기능 추가\n관련 티켓: KMA-1234"
    }

    private val agent = PrIndexAgent(
        codeClient = mockCodeClient,
        knowledgeStore = mockStore,
        llmFn = mockLlm,
        chromaClient = null,
        embeddingFn = null,
    )

    @Test
    fun `compilePrDocument — PR 정보를 LLM 프롬프트로 변환한다`() = runTest {
        val pr = GithubPrInfo(
            repo = "owner/repo", number = 123,
            title = "KMA-1234 배너 클릭 이벤트 추가",
            body = "배너 클릭 시 Amplitude 이벤트 전송",
            state = "closed", merged = true, mergedAt = "2026-05-01T10:00:00Z",
            author = "pilju.bae", branch = "feature/KMA-1234",
            changedFiles = listOf("features/banner/BannerViewModel.kt"),
        )
        coEvery { mockCodeClient.fetchPrDiff(any(), any(), any()) } returns "+fun onBannerClick() { }"

        val doc = agent.compilePrDocument(pr, "features/banner/BannerViewModel.kt\n+fun onBannerClick() {}")
        assertTrue(doc.contains("KMA-1234"))
        assertTrue(doc.contains("pilju.bae"))
        assertTrue(doc.contains("BannerViewModel"))
    }

    @Test
    fun `indexPr — KnowledgeStore에 저장한다`() = runTest {
        val pr = GithubPrInfo(
            repo = "owner/repo", number = 123,
            title = "KMA-1234 배너 수정", body = "수정 내용",
            state = "closed", merged = true, mergedAt = null,
            author = "dev", branch = "feature/KMA-1234",
            changedFiles = emptyList(),
        )
        coEvery { mockCodeClient.fetchPr(any(), any()) } returns pr
        coEvery { mockCodeClient.fetchPrDiff(any(), any(), any()) } returns ""

        agent.indexPr("owner/repo", 123)

        coVerify { mockStore.save(match { it.contains("pr-123") }, any()) }
    }
}
```

**Step 2: 테스트 실행 → 실패 확인**
```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.PrIndexAgentTest" 2>&1 | tail -10
```
Expected: FAILED

**Step 3: PrIndexAgent 구현**

```kotlin
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

    // PR 1개 인덱싱
    suspend fun indexPr(repo: String, prNumber: Int): String {
        val pr = codeClient.fetchPr(repo, prNumber)
            ?: return "PR #$prNumber를 가져올 수 없습니다."
        val diff = codeClient.fetchPrDiff(repo, prNumber)
        val compiledBody = llmFn(buildCompilePrompt(pr, diff))
        val document = compilePrDocument(pr, compiledBody)

        val path = "${repo.replace("/", "-")}-pr-${prNumber}.md"
        knowledgeStore.save("prs/$path", document)

        if (chromaClient != null) {
            val collectionId = chromaClient.getOrCreateCollection(collectionName)
            val embedText = if (embeddingFn != null) null else document
            val embeddings = embeddingFn?.let { listOf(it(document)) }
            chromaClient.addDocuments(
                collectionId = collectionId,
                ids = listOf("${repo}-pr-${prNumber}"),
                documents = listOf(embedText ?: document),
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

    // since 이후 PR 배치 인덱싱
    suspend fun indexRecentPrs(repos: List<String>): Int {
        var total = 0
        val stateMap = loadPollState()

        for (repo in repos) {
            val state = stateMap[repo] ?: PollState()
            val since = state.lastPolledAt.ifBlank { null }
            val prs = codeClient.fetchRecentPrs(repo, since)
            log.info("Found {} PRs to index for {} (since={})", prs.size, repo, since)

            for (pr in prs) {
                if (pr.number <= state.lastPrNumber && since != null) continue
                runCatching { indexPr(repo, pr.number) }
                    .onSuccess { total++ }
                    .onFailure { log.warn("Failed to index PR #{}: {}", pr.number, it.message) }
            }

            val maxPrNumber = prs.maxOfOrNull { it.number } ?: state.lastPrNumber
            stateMap[repo] = PollState(
                lastPolledAt = Instant.now().toString(),
                lastPrNumber = maxPrNumber,
            )
        }

        savePollState(stateMap)
        return total
    }

    // LLM 컴파일 프롬프트
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

    // 저장할 최종 document 조합
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

    private fun loadPollState(): MutableMap<String, PollState> {
        val file = File(pollStateFile)
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString<Map<String, PollState>>(file.readText()).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun savePollState(state: Map<String, PollState>) {
        val file = File(pollStateFile)
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(state))
    }

    companion object {
        private val log = LoggerFactory.getLogger(PrIndexAgent::class.java)
    }
}
```

**Step 4: KnowledgeStore에 `save` 메서드 확인**

`KnowledgeStore.kt`를 열어 `save(path, content)` 또는 `saveFile(path, content)` 메서드가 있는지 확인. 없으면 추가:
```kotlin
fun save(relativePath: String, content: String) {
    val file = File("$basePath/$relativePath")
    file.parentFile?.mkdirs()
    file.writeText(content)
}
```

**Step 5: 테스트 통과 확인**
```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.PrIndexAgentTest" 2>&1 | tail -10
```

**Step 6: Commit**
```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/PrIndexAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/PrIndexAgentTest.kt
git commit -m "feat: PrIndexAgent — PR LLM 컴파일, KnowledgeStore/ChromaDB 저장, polling 상태 관리"
```

---

## Task 4: CodeIndexAgent

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgentTest.kt`

### 배경
Kotlin 파일에서 class/object/interface 블록을 regex로 추출한다.
GitHub Contents API로 파일 내용 조회: `GET /repos/{owner}/{repo}/contents/{path}?ref={branch}`
응답은 base64 encoded content.

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.knowledge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeIndexAgentTest {

    private val agent = CodeIndexAgent(
        codeClient = mockk(relaxed = true),
        llmFn = { "클래스 요약" },
        chromaClient = mockk(relaxed = true),
        repos = emptyList(),
        branch = "develop",
    )

    @Test
    fun `extractClasses — Kotlin 파일에서 class 선언 추출`() {
        val content = """
            package com.kurly.features.banner

            class BannerViewModel : ViewModel() {
                fun onBannerClick() {}
                private fun loadData() {}
            }

            data class BannerUiState(val items: List<Banner> = emptyList())

            sealed interface BannerEvent {
                data object Click : BannerEvent
            }
        """.trimIndent()

        val classes = agent.extractClasses(content)
        assertEquals(3, classes.size)
        assertTrue(classes.any { it.name == "BannerViewModel" })
        assertTrue(classes.any { it.name == "BannerUiState" })
        assertTrue(classes.any { it.name == "BannerEvent" })
    }

    @Test
    fun `extractClasses — public 함수 시그니처 포함`() {
        val content = """
            class MyClass {
                fun publicFun(a: String): Int = 0
                private fun privateFun() {}
                suspend fun suspendFun(): String = ""
            }
        """.trimIndent()

        val classes = agent.extractClasses(content)
        val myClass = classes.first { it.name == "MyClass" }
        assertTrue(myClass.publicFunctions.contains("publicFun"))
        assertTrue(myClass.publicFunctions.contains("suspendFun"))
        assertTrue(!myClass.publicFunctions.contains("privateFun"))
    }

    @Test
    fun `buildIndexDocument — package + class + functions 포함된 문서 생성`() {
        val classInfo = CodeIndexAgent.KotlinClassInfo(
            name = "BannerViewModel",
            kind = "class",
            packageName = "com.kurly.features.banner",
            publicFunctions = listOf("onBannerClick(bannerId: String)", "loadBanners()"),
            firstLines = "class BannerViewModel : ViewModel() {",
        )
        val doc = agent.buildIndexDocument("features/BannerViewModel.kt", classInfo)
        assertTrue(doc.contains("BannerViewModel"))
        assertTrue(doc.contains("com.kurly.features.banner"))
        assertTrue(doc.contains("onBannerClick"))
    }
}
```

**Step 2: 테스트 실행 → 실패 확인**
```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.CodeIndexAgentTest" 2>&1 | tail -10
```

**Step 3: CodeIndexAgent 구현**

```kotlin
package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.rag.ChromaClient
import org.slf4j.LoggerFactory
import java.util.Base64

class CodeIndexAgent(
    private val codeClient: GitHubCodeClient,
    private val llmFn: suspend (String) -> String,
    private val chromaClient: ChromaClient,
    private val repos: List<String>,
    private val branch: String = "develop",
    private val collectionName: String = "code_index",
) {

    data class KotlinClassInfo(
        val name: String,
        val kind: String,  // class, data class, object, interface, sealed class
        val packageName: String,
        val publicFunctions: List<String>,
        val firstLines: String,
    )

    suspend fun indexAll(): Int {
        val collectionId = chromaClient.getOrCreateCollection(collectionName)
        var total = 0

        for (repo in repos) {
            val filePaths = fetchKotlinFilePaths(repo)
            log.info("Indexing {} Kotlin files from {}", filePaths.size, repo)

            filePaths.chunked(5).forEach { batch ->
                val ids = mutableListOf<String>()
                val docs = mutableListOf<String>()
                val metas = mutableListOf<Map<String, String>>()

                batch.forEach { path ->
                    runCatching {
                        val content = fetchFileContent(repo, path) ?: return@forEach
                        val classes = extractClasses(content)
                        if (classes.isEmpty()) return@forEach

                        classes.forEach { cls ->
                            val doc = buildIndexDocument(path, cls)
                            val enriched = runCatching { llmFn(buildEnrichPrompt(path, cls)) }.getOrDefault(doc)
                            ids += "$repo:$path:${cls.name}"
                            docs += enriched
                            metas += mapOf(
                                "repo" to repo,
                                "file_path" to path,
                                "class_name" to cls.name,
                                "branch" to branch,
                            )
                            total++
                        }
                    }.onFailure { log.warn("Failed to index {}/{}: {}", repo, path, it.message) }
                }

                if (ids.isNotEmpty()) {
                    chromaClient.addDocuments(collectionId, ids, docs, metadatas = metas)
                }
            }
        }
        log.info("Code index complete: {} class entries", total)
        return total
    }

    // GitHub Contents API로 파일 목록 조회
    private suspend fun fetchKotlinFilePaths(repo: String): List<String> {
        val treeUrl = "https://api.github.com/repos/$repo/git/trees/$branch?recursive=1"
        val json = runCatching {
            io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO {}).use { client ->
                client.get(treeUrl) {
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                }.bodyAsText()
            }
        }.getOrDefault("")
        return Regex("\"path\"\\s*:\\s*\"([^\"]+\\.kt)\"")
            .findAll(json)
            .map { it.groupValues[1] }
            .filter { !it.contains("Test") && !it.contains("build/") && !it.contains("generated") }
            .toList()
    }

    private suspend fun fetchFileContent(repo: String, path: String): String? {
        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$branch"
        val json = runCatching {
            io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO {}).use { client ->
                client.get(url) {
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2022-11-28")
                }.bodyAsText()
            }
        }.getOrNull() ?: return null
        val encoded = Regex("\"content\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        return runCatching {
            String(Base64.getMimeDecoder().decode(encoded.replace("\\n", "\n")))
        }.getOrNull()
    }

    // Kotlin 파일에서 class/object/interface 추출
    internal fun extractClasses(content: String): List<KotlinClassInfo> {
        val packageName = Regex("^package\\s+([\\w.]+)").find(content)?.groupValues?.get(1) ?: ""
        val classPattern = Regex(
            "((?:data |sealed |abstract |open )*(?:class|object|interface))\\s+(\\w+)"
        )
        val funPattern = Regex("(?<!private )(?<!protected )\\s+(?:suspend )?fun\\s+(\\w+\\([^)]*\\))")

        return classPattern.findAll(content).map { match ->
            val kind = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val startIdx = match.range.first
            val classBlock = content.substring(startIdx).take(2000)

            val publicFunctions = funPattern.findAll(classBlock)
                .map { it.groupValues[1] }
                .filter { !it.startsWith("invoke") }
                .take(10)
                .toList()

            KotlinClassInfo(
                name = name,
                kind = kind,
                packageName = packageName,
                publicFunctions = publicFunctions,
                firstLines = classBlock.lines().take(3).joinToString("\n"),
            )
        }.toList()
    }

    internal fun buildIndexDocument(filePath: String, cls: KotlinClassInfo): String = buildString {
        appendLine("package ${cls.packageName}")
        appendLine("file: $filePath")
        appendLine()
        appendLine("${cls.kind} ${cls.name}")
        if (cls.publicFunctions.isNotEmpty()) {
            appendLine("public functions:")
            cls.publicFunctions.forEach { appendLine("  fun $it") }
        }
    }

    private fun buildEnrichPrompt(filePath: String, cls: KotlinClassInfo): String = buildString {
        appendLine("Kotlin 클래스를 검색 최적화된 한국어 설명으로 변환하세요. 50자 이내.")
        appendLine("클래스: ${cls.name} (${cls.kind})")
        appendLine("파일: $filePath")
        appendLine("패키지: ${cls.packageName}")
        if (cls.publicFunctions.isNotEmpty()) {
            appendLine("주요 함수: ${cls.publicFunctions.take(5).joinToString(", ")}")
        }
        appendLine("출력: 한 문단, 한국어, 이 클래스가 무엇을 하는지 + 어디서 쓰이는지")
    }

    companion object {
        private val log = LoggerFactory.getLogger(CodeIndexAgent::class.java)
    }
}

// ktor-client import (코드 내 inline HttpClient 사용 시 필요)
private fun io.ktor.client.HttpClient.get(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit) =
    this.get(io.ktor.client.request.HttpRequestBuilder().apply { url(url); block() })
```

> 주의: `fetchKotlinFilePaths`와 `fetchFileContent` 내부의 inline HttpClient는 Task 2에서 만든 `GitHubCodeClient`를 사용하도록 리팩토링하는 게 더 깔끔하다. 위 코드는 동작하는 최소 구현이다.

**Step 4: 테스트 통과 확인**
```bash
./gradlew test --tests "io.github.veronikapj.wiki.knowledge.CodeIndexAgentTest" 2>&1 | tail -10
```

**Step 5: Commit**
```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgentTest.kt
git commit -m "feat: CodeIndexAgent — Kotlin class 단위 regex 추출, ChromaDB code_index 저장"
```

---

## Task 5: PrHistoryTool + CodeSearchTool

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/PrHistoryTool.kt`
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/CodeSearchTool.kt`

**Step 1: PrHistoryTool 구현**

```kotlin
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
```

**Step 2: CodeSearchTool 구현**

```kotlin
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
```

**Step 3: 컴파일 검증**
```bash
./gradlew :compileKotlin 2>&1 | tail -5
```

**Step 4: Commit**
```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/tool/PrHistoryTool.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/tool/CodeSearchTool.kt
git commit -m "feat: PrHistoryTool, CodeSearchTool — ChromaDB 기반 코드/PR 이력 검색 tool"
```

---

## Task 6: OrchestratorAgent 라우팅 업데이트

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

### 변경 내용
1. 생성자에 `prHistoryTool`, `codeSearchTool` 파라미터 추가
2. `answerWithManualLoop`의 `availableTools` 목록에 추가
3. 라우팅 결정 프롬프트에 `codeSearch`, `prHistory` tool 설명 추가
4. 티켓 번호 패턴 + 코드 질문 시 prHistory + codeSearch 병렬 실행 (executeParallel 확장)
5. `answerWithKoogAgent`의 `ToolRegistry`에 새 tool 등록

**Step 1: 생성자 파라미터 추가**

`OrchestratorAgent.kt:30` 근처 생성자에 추가:
```kotlin
class OrchestratorAgent(
    private val knowledgeTool: KnowledgeTool? = null,
    private val confluenceTool: ConfluenceTool? = null,
    private val githubWikiTool: GitHubWikiTool? = null,
    private val vectorSearchTool: VectorSearchTool? = null,
    private val prHistoryTool: PrHistoryTool? = null,     // 추가
    private val codeSearchTool: CodeSearchTool? = null,   // 추가
    // ... 나머지 동일
```

**Step 2: `availableTools` 목록 업데이트** (`answerWithManualLoop` 내부)

```kotlin
val availableTools = listOfNotNull(
    knowledgeTool?.let { "knowledgeSearch" },
    confluenceTool?.let { "confluenceSearch" },
    githubWikiTool?.let { "githubWikiSearch" },
    vectorSearchTool?.let { "vectorSearch" },
    prHistoryTool?.let { "prHistory" },       // 추가
    codeSearchTool?.let { "codeSearch" },     // 추가
)
```

**Step 3: 라우팅 프롬프트 업데이트**

기존 githubWikiSearch 관련 조건 블록 아래에 추가:
```kotlin
if (prHistoryTool != null || codeSearchTool != null) {
    appendLine("- codeSearch: 클래스/함수 위치, 구현 방법, '어디있어?' 질문.")
    appendLine("- prHistory: PR 변경 이력, KMA-XXXX 티켓 작업 내용, 누가 언제 변경했는지.")
    appendLine("  티켓 번호 + 코드 질문이 동시에 있으면 TOOL: prHistory+codeSearch (병렬 실행).")
}
```

**Step 4: 병렬 실행 분기 추가** (toolName 파싱 후)

```kotlin
var searchResult = when {
    toolName == "githubWikiSearch" && wikiTool != null ->
        runCatching { wikiTool.githubWikiSearch(query) }.getOrNull()
            ?.takeIf { !it.contains("찾을 수 없습니다") }

    toolName == "prHistory+codeSearch" ->
        runCatching { executeCodeParallel(query, synonyms) }.getOrNull()

    toolName == "prHistory" && prHistoryTool != null ->
        runCatching { prHistoryTool.prHistory(query) }.getOrNull()

    toolName == "codeSearch" && codeSearchTool != null ->
        runCatching { codeSearchTool.codeSearch(query) }.getOrNull()

    else ->
        runCatching { executeParallel(query, synonyms, dateAfter, dateBefore) }.getOrNull()
}
```

**Step 5: `executeCodeParallel` 추가** (executeParallel 아래)

```kotlin
internal suspend fun executeCodeParallel(query: String, synonyms: List<String> = emptyList()): String? {
    val (prResult, codeResult) = coroutineScope {
        val prDeferred = async {
            if (prHistoryTool != null)
                runCatching { prHistoryTool.prHistory(query) }.getOrNull()
            else null
        }
        val codeDeferred = async {
            if (codeSearchTool != null)
                runCatching { codeSearchTool.codeSearch(query) }.getOrNull()
            else null
        }
        prDeferred.await() to codeDeferred.await()
    }

    val prValid = prResult?.takeIf { !it.contains("찾을 수 없습니다") }
    val codeValid = codeResult?.takeIf { !it.contains("찾을 수 없습니다") }

    return when {
        prValid != null && codeValid != null -> "[PR 이력]\n$prValid\n\n---\n\n[코드]\n$codeValid"
        prValid != null -> prValid
        codeValid != null -> codeValid
        else -> null
    }
}
```

**Step 6: `answerWithKoogAgent` ToolRegistry 업데이트**

```kotlin
toolRegistry = ToolRegistry {
    if (knowledgeTool != null) tool(knowledgeTool::knowledgeSearch)
    if (confluenceTool != null) tool(confluenceTool::confluenceSearch)
    if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
    if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
    if (prHistoryTool != null) tool(prHistoryTool::prHistory)       // 추가
    if (codeSearchTool != null) tool(codeSearchTool::codeSearch)     // 추가
},
```

**Step 7: 컴파일 검증**
```bash
./gradlew :compileKotlin 2>&1 | tail -5
```

**Step 8: Commit**
```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat: OrchestratorAgent에 prHistory, codeSearch 라우팅 추가, 티켓+코드 병렬 실행"
```

---

## Task 7: SlackBotGateway PR URL 감지 + SlackConfigHandler 커맨드

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt`

### SlackBotGateway 변경

**Step 1: 생성자에 prIndexAgent 추가**

```kotlin
class SlackBotGateway(
    // ... 기존 파라미터 ...
    private val prIndexAgent: PrIndexAgent? = null,   // 추가
)
```

**Step 2: `classifyDmInput`에 PR URL 타입 추가**

```kotlin
private enum class DmInputType { PR_URL, URL, LONG_TEXT, NORMAL }

private val prUrlPattern = Regex("github\\.com/[^/]+/[^/]+/pull/\\d+")

private fun classifyDmInput(text: String): DmInputType = when {
    prUrlPattern.containsMatchIn(text) -> DmInputType.PR_URL
    text.startsWith("http://") || text.startsWith("https://") -> DmInputType.URL
    text.length > 500 -> DmInputType.LONG_TEXT
    else -> DmInputType.NORMAL
}
```

**Step 3: DM 처리 분기에 PR_URL 케이스 추가**

기존 `DmInputType.URL` 케이스 위에 추가:
```kotlin
DmInputType.PR_URL -> if (prIndexAgent != null) {
    postToThread(channel, null, ":hourglass_flowing_sand: PR 인덱싱 중...")
    val parsed = GitHubCodeClient("").parsePrUrl(query)
    if (parsed != null) {
        val result = runBlocking { prIndexAgent.indexPr(parsed.first, parsed.second) }
        postToThread(channel, null, ":white_check_mark: $result")
    } else {
        postToThread(channel, null, ":x: PR URL 형식을 인식하지 못했습니다.")
    }
} else {
    postToThread(channel, null, "PR 인덱싱이 비활성화 상태입니다. codeRepos를 설정하세요.")
}
```

### SlackConfigHandler 변경

**Step 4: 생성자에 `onReindexCode` 추가**

```kotlin
class SlackConfigHandler(
    // ... 기존 파라미터 ...
    private val onReindexCode: (suspend () -> Int)? = null,   // 추가
)
```

**Step 5: `handle()` 분기에 `reindex-code` 추가**

```kotlin
parts.size >= 2 && parts[1] == "reindex-code" ->
    triggerReindexCode()
```

**Step 6: `triggerReindexCode()` 구현**

```kotlin
private fun triggerReindexCode(): String {
    val indexer = onReindexCode
        ?: return "코드 인덱싱이 비활성화 상태입니다. config.yml에서 codeRepos를 설정하세요."
    Thread {
        runCatching {
            val count = runBlocking { indexer() }
            log.info("Code reindex completed: {} entries", count)
        }.onFailure { log.error("Code reindex failed", it) }
    }.start()
    return ":hourglass_flowing_sand: 코드 인덱싱을 시작했습니다."
}
```

**Step 7: helpMessage()에 새 커맨드 추가**

`helpMessage()` 반환 문자열에:
```
/wiki reindex-code     — Kurly Android 소스코드 재인덱싱
```

**Step 8: 컴파일 검증**
```bash
./gradlew :compileKotlin 2>&1 | tail -5
```

**Step 9: Commit**
```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt \
        src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt
git commit -m "feat: SlackBotGateway PR URL 감지 자동 인덱싱, /wiki reindex-code 커맨드"
```

---

## Task 8: Webhook 서버 + Polling 코루틴 + Main.kt 통합

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

**Step 1: build.gradle.kts에 ktor-server 추가**

```kotlin
dependencies {
    // ... 기존 ...
    implementation("io.ktor:ktor-server-cio:3.1.2")
    implementation("io.ktor:ktor-server-core:3.1.2")
}
```

**Step 2: 빌드 확인**
```bash
./gradlew :compileKotlin 2>&1 | tail -5
```

**Step 3: Main.kt — codeRepos 섹션 추가**

기존 `// GitHub Wiki` 블록 아래에 추가:

```kotlin
// Code Search + PR History (codeRepos 설정 시)
var prIndexAgent: PrIndexAgent? = null
var codeIndexAgent: CodeIndexAgent? = null
var prHistoryTool: PrHistoryTool? = null
var codeSearchTool: CodeSearchTool? = null

if (config.github.codeRepos.isNotEmpty() && config.rag.enabled) {
    val codeChromaClient = ChromaClient(config.rag.chromaUrl)
    val llmFn: suspend (String) -> String = { prompt ->
        executor.execute(prompt("code") { user(prompt) }, model).joinToString("") { it.content }
    }
    val llmExpandClient = LlmExpandClient(llmFn)
    val githubCodeClient = GitHubCodeClient(githubToken)

    prIndexAgent = PrIndexAgent(
        codeClient = githubCodeClient,
        knowledgeStore = knowledgeStore,
        llmFn = llmFn,
        chromaClient = codeChromaClient,
    )
    codeIndexAgent = CodeIndexAgent(
        codeClient = githubCodeClient,
        llmFn = llmFn,
        chromaClient = codeChromaClient,
        repos = config.github.codeRepos,
        branch = config.github.codeSearch.branch,
    )
    prHistoryTool = PrHistoryTool(codeChromaClient, llmExpandClient, sourceTracker)
    codeSearchTool = CodeSearchTool(
        chromaClient = codeChromaClient,
        llmExpandClient = llmExpandClient,
        codeClient = githubCodeClient,
        codeRepos = config.github.codeRepos,
        branch = config.github.codeSearch.branch,
        tracker = sourceTracker,
    )
    log.info("Code search enabled: repos={}, branch={}", config.github.codeRepos, config.github.codeSearch.branch)
} else if (config.github.codeRepos.isNotEmpty()) {
    log.warn("codeRepos is set but rag.enabled=false — code search disabled. Enable RAG (ChromaDB) to use code search.")
}
```

**Step 4: OrchestratorAgent 생성자에 새 tool 전달**

```kotlin
val orchestrator = OrchestratorAgent(
    knowledgeTool = knowledgeTool,
    confluenceTool = confluenceTool,
    githubWikiTool = githubWikiTool,
    vectorSearchTool = vectorSearchTool,
    prHistoryTool = prHistoryTool,     // 추가
    codeSearchTool = codeSearchTool,   // 추가
    // ... 나머지 동일
)
```

**Step 5: Polling 코루틴 추가** (Slack 시작 전)

```kotlin
// Polling 코루틴 시작
val finalPrIndexAgent = prIndexAgent
if (finalPrIndexAgent != null && config.github.codeSearch.pollIntervalMinutes > 0) {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    scope.launch {
        val intervalMs = config.github.codeSearch.pollIntervalMinutes * 60_000L
        log.info("PR polling started: interval={}min, repos={}", config.github.codeSearch.pollIntervalMinutes, config.github.codeRepos)
        while (true) {
            runCatching {
                val count = finalPrIndexAgent.indexRecentPrs(config.github.codeRepos)
                if (count > 0) log.info("Polling: indexed {} new PRs", count)
            }.onFailure { log.warn("Polling failed: {}", it.message) }
            kotlinx.coroutines.delay(intervalMs)
        }
    }
}
```

**Step 6: Webhook 서버 추가** (Polling 코루틴 아래)

```kotlin
// GitHub Webhook 서버
val finalPrIndexAgentForWebhook = prIndexAgent
if (finalPrIndexAgentForWebhook != null && config.github.codeSearch.webhookPort > 0) {
    val webhookScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    webhookScope.launch {
        io.ktor.server.cio.CIO.create(io.ktor.server.engine.applicationEngineEnvironment {
            connector { port = config.github.codeSearch.webhookPort }
            module {
                routing {
                    post("/webhook/github") {
                        val body = call.receiveText()
                        val event = call.request.headers["X-GitHub-Event"]
                        if (event == "pull_request") {
                            val action = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                            val merged = Regex("\"merged\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1) == "true"
                            val repo = Regex("\"full_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
                            val prNumber = Regex("\"number\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()

                            if ((action == "closed" || action == "opened") && prNumber != null && repo.isNotBlank()) {
                                webhookScope.launch {
                                    runCatching {
                                        finalPrIndexAgentForWebhook.indexPr(repo, prNumber)
                                        log.info("Webhook: indexed PR #{} from {}", prNumber, repo)
                                    }.onFailure { log.warn("Webhook indexing failed: {}", it.message) }
                                }
                            }
                        }
                        call.respond(io.ktor.http.HttpStatusCode.OK, "ok")
                    }
                }
            }
        }).start(wait = false)
        log.info("GitHub webhook server started on port {}", config.github.codeSearch.webhookPort)
    }
}
```

> Webhook imports 필요: `io.ktor.server.routing.*`, `io.ktor.server.request.*`, `io.ktor.server.response.*`, `io.ktor.server.application.*`

**Step 7: SlackConfigHandler에 `onReindexCode` 전달**

```kotlin
val configHandler = SlackConfigHandler(
    // ... 기존 ...
    onReindexCode = codeIndexAgent?.let { agent -> { agent.indexAll() } },  // 추가
)
```

**Step 8: SlackBotGateway에 `prIndexAgent` 전달**

```kotlin
val gateway = SlackBotGateway(
    // ... 기존 ...
    prIndexAgent = prIndexAgent,  // 추가
)
```

**Step 9: 전체 컴파일 검증**
```bash
./gradlew :compileKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

**Step 10: 로컬 실행 확인**
```bash
./gradlew run
```
Expected: `Code search enabled: repos=[thefarmersfront/kurly-android]` 로그 (rag.enabled=true + codeRepos 설정 시)

**Step 11: Commit**
```bash
git add build.gradle.kts src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat: Main.kt — codeSearch/prHistory tool 등록, polling 코루틴, webhook 서버 통합"
```

---

## Task 9: config.yml 예시 + README 업데이트

**Files:**
- Modify: `.wikiq/config.yml` (있으면) 또는 `README.md`

**Step 1: README.md에 코드 검색 섹션 추가**

`## GitHub Wiki (선택)` 섹션 아래에 추가:

```markdown
## 코드 검색 + PR 이력 (선택)

Kurly Android 소스코드와 PR 기반 작업 이력을 검색할 수 있습니다.

### 필수 조건
- `rag.enabled: true` (ChromaDB 필요)
- `GITHUB_TOKEN` — `repo` scope 필요 (private repo 접근)

### 활성화

`config.yml`:
\```yaml
github:
  codeRepos:
    - thefarmersfront/kurly-android
  codeSearch:
    branch: develop
    pollIntervalMinutes: 60    # 0이면 비활성
    webhookPort: 8080          # 0이면 비활성

rag:
  enabled: true
  chromaUrl: http://localhost:8000
\```

### 초기 인덱싱
\```
/wiki reindex-code    # 전체 코드베이스 인덱싱 (최초 1회)
\```

### 사용법
\```
@wiki BannerViewModel 어디있어?
@wiki panelCode 어디서 쓰여?
@wiki KMA-7275 어떤 작업이었어?
@wiki 배너 클릭 이벤트 누가 만들었어?
\```

DM에 PR URL을 붙여넣으면 자동 인덱싱됩니다:
\```
https://github.com/thefarmersfront/kurly-android/pull/7400
\```

### Webhook 설정 (선택)
PR merge 시 자동 인덱싱하려면 GitHub Repo Settings → Webhooks에서:
- Payload URL: `http://your-server:8080/webhook/github`
- Content type: `application/json`
- Events: `Pull requests`
```

**Step 2: Commit**
```bash
git add README.md
git commit -m "docs: 코드 검색, PR 이력, webhook 설정 가이드 추가"
```

---

## 검증 체크리스트

구현 완료 후 다음을 확인:

- [ ] `./gradlew test` — 전체 테스트 통과
- [ ] `./gradlew :compileKotlin` — 컴파일 오류 없음
- [ ] ChromaDB 없이 실행 시 경고 로그 출력 + 봇 정상 동작
- [ ] DM에 `https://github.com/owner/repo/pull/123` 입력 → 인덱싱 시작 메시지
- [ ] `/wiki reindex-code` → 시작 메시지
- [ ] `@wiki KMA-7275 어떤 작업이었어?` → TOOL: prHistory 라우팅
- [ ] `@wiki BannerViewModel 어디있어?` → TOOL: codeSearch 라우팅
