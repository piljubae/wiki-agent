# PR→Jira→Confluence 보강 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `prHistory` 검색 결과에 연결된 Jira 티켓(필드+본문+최근 댓글3)과 티켓의 대표 Confluence 기획서 1개 발췌를 query-time으로 덧붙인다.

**Architecture:** 신규 `:jira` 모듈의 `JiraClient`(ConfluenceClient 패턴 — HTTP는 내부 CIO, 파싱은 순수 함수로 분리). `PrHistoryTool`이 반환 PR들에서 Jira 키를 추출해 상위 3개 티켓을 병렬 fetch하고, 각 티켓의 대표 Confluence 페이지를 기존 `ConfluenceClient.fetchPageRawHtml`로 가져와 발췌. PR 인덱싱은 미변경.

**Tech Stack:** Kotlin, ktor-client-cio, kotlinx-serialization-json, JUnit5 + MockK, Gradle 멀티모듈.

## Global Constraints

- Atlassian 인증: Confluence와 같은 인스턴스/토큰 재사용. `:jira`는 다른 프로젝트 모듈에 의존하지 않는다.
- 파싱은 순수 internal 함수로 분리해 JSON 문자열로 단위테스트(ConfluenceClient 패턴). MockEngine 사용 안 함.
- 길이 상한: `MAX_TICKETS=3`, `MAX_COMMENTS=3`, `DESC_EXCERPT=600`, `COMMENT_EXCERPT=200`, `PAGE_EXCERPT=800`.
- Graceful: Jira 미설정/키없음/fetch실패는 PR 결과만 반환 — 보강 실패가 PR 결과를 막지 않는다.
- 커밋 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- 작업 브랜치: `feat/pr-jira-confluence-enrichment` (이어서 커밋).

---

### Task 1: `:jira` 모듈 + JiraClient

**Files:**
- Modify: `settings.gradle.kts` (`include(":confluence")` 다음 줄)
- Create: `jira/build.gradle.kts`
- Create: `jira/src/main/kotlin/io/github/veronikapj/wiki/jira/JiraClient.kt`
- Create (test): `jira/src/test/kotlin/io/github/veronikapj/wiki/jira/JiraClientTest.kt`

**Interfaces:**
- Produces:
  - `data class JiraConfluenceRef(val pageId: String, val title: String, val url: String)`
  - `data class JiraIssue(val key, summary, status, type, assignee, description: String, recentComments: List<String>, confluenceRefs: List<JiraConfluenceRef>)`
  - `class JiraClient(baseUrl: String, token: String)` with `suspend fun getIssue(key: String): JiraIssue?` and internal `parseIssue(key, issueJson, commentsJson, remoteLinksJson)`, `parseComments(commentsJson)`, `extractConfluenceRefs(description, remoteLinksJson)`.

- [ ] **Step 1: settings + build files**

`settings.gradle.kts` — `include(":confluence")` 아래에 추가:

```kotlin
include(":jira")
```

Create `jira/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the failing tests**

Create `jira/src/test/kotlin/io/github/veronikapj/wiki/jira/JiraClientTest.kt`:

```kotlin
package io.github.veronikapj.wiki.jira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraClientTest {
    private val client = JiraClient("https://kurly0521.atlassian.net", "dGVzdA==")

    private val issueJson = """
        {"key":"KMA-7275","fields":{
          "summary":"배너 DSP Phase2",
          "status":{"name":"In Progress"},
          "issuetype":{"name":"Story"},
          "assignee":{"displayName":"pilju.bae"},
          "description":"기획 상세입니다. https://kurly0521.atlassian.net/wiki/spaces/PA/pages/12345/Spec"
        }}
    """.trimIndent()

    @Test
    fun `parseIssue maps fields`() {
        val issue = client.parseIssue("KMA-7275", issueJson, "", "")
        assertEquals("KMA-7275", issue.key)
        assertEquals("배너 DSP Phase2", issue.summary)
        assertEquals("In Progress", issue.status)
        assertEquals("Story", issue.type)
        assertEquals("pilju.bae", issue.assignee)
        assertTrue(issue.description.contains("기획 상세"))
    }

    @Test
    fun `parseIssue with null assignee yields empty string`() {
        val json = """{"key":"K-1","fields":{"summary":"s","status":{"name":"To Do"},"issuetype":{"name":"Bug"},"assignee":null,"description":null}}"""
        val issue = client.parseIssue("K-1", json, "", "")
        assertEquals("", issue.assignee)
        assertEquals("", issue.description)
    }

    @Test
    fun `parseComments takes recent comments as author colon body`() {
        val commentsJson = """
            {"comments":[
              {"author":{"displayName":"kim"},"body":"첫 코멘트"},
              {"author":{"displayName":"lee"},"body":"둘째"}
            ]}
        """.trimIndent()
        val comments = client.parseComments(commentsJson)
        assertEquals(2, comments.size)
        assertEquals("kim: 첫 코멘트", comments[0])
    }

    @Test
    fun `extractConfluenceRefs finds page id in description`() {
        val refs = client.extractConfluenceRefs(
            "참고 https://kurly0521.atlassian.net/wiki/spaces/PA/pages/12345/Spec 끝", "")
        assertEquals(1, refs.size)
        assertEquals("12345", refs[0].pageId)
    }

    @Test
    fun `extractConfluenceRefs reads remote links and dedups by pageId`() {
        val remoteJson = """
            [
              {"object":{"url":"https://kurly0521.atlassian.net/wiki/spaces/PA/pages/999/Plan","title":"기획서"}},
              {"object":{"url":"https://github.com/o/r/pull/1","title":"PR"}}
            ]
        """.trimIndent()
        // description에도 999가 있으면 중복 제거되어 1개
        val refs = client.extractConfluenceRefs(
            "https://kurly0521.atlassian.net/wiki/x/pages/999/a", remoteJson)
        assertEquals(1, refs.size)
        assertEquals("999", refs[0].pageId)
        // remote link 제목이 반영됨
        assertEquals("기획서", refs.first { it.pageId == "999" }.title)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :jira:test`
Expected: FAIL — `JiraClient`/모델 미존재(compile error).

- [ ] **Step 4: Write the implementation**

Create `jira/src/main/kotlin/io/github/veronikapj/wiki/jira/JiraClient.kt`:

```kotlin
package io.github.veronikapj.wiki.jira

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

data class JiraConfluenceRef(val pageId: String, val title: String, val url: String)

data class JiraIssue(
    val key: String,
    val summary: String,
    val status: String,
    val type: String,
    val assignee: String,
    val description: String,
    val recentComments: List<String>,
    val confluenceRefs: List<JiraConfluenceRef>,
)

class JiraClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 이슈 조회 실패(404 등) → null. 댓글/원격링크 실패는 무시(빈값). */
    suspend fun getIssue(key: String): JiraIssue? {
        val issueJson = runCatching {
            httpGet("$baseUrl/rest/api/2/issue/$key?fields=summary,status,issuetype,assignee,description")
        }.getOrElse { log.warn("Jira getIssue {} failed: {}", key, it.message); return null }
        val commentsJson = runCatching {
            httpGet("$baseUrl/rest/api/2/issue/$key/comment?orderBy=-created&maxResults=3")
        }.getOrDefault("")
        val remoteJson = runCatching {
            httpGet("$baseUrl/rest/api/2/issue/$key/remotelinks")
        }.getOrDefault("")
        return runCatching { parseIssue(key, issueJson, commentsJson, remoteJson) }
            .getOrElse { log.warn("Jira parse {} failed: {}", key, it.message); null }
    }

    private suspend fun httpGet(url: String): String =
        httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()

    internal fun parseIssue(key: String, issueJson: String, commentsJson: String, remoteLinksJson: String): JiraIssue {
        val root = jsonParser.parseToJsonElement(sanitize(issueJson)).jsonObject
        val fields = root["fields"]?.jsonObject ?: JsonObject(emptyMap())
        val summary = fields["summary"]?.jsonPrimitive?.contentOrNull ?: ""
        val status = fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val type = fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val assignee = fields["assignee"]?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull ?: ""
        val descEl = fields["description"]
        val description = if (descEl == null || descEl is JsonNull) "" else descEl.jsonPrimitive.contentOrNull ?: ""
        return JiraIssue(
            key = key,
            summary = summary,
            status = status,
            type = type,
            assignee = assignee,
            description = description,
            recentComments = parseComments(commentsJson),
            confluenceRefs = extractConfluenceRefs(description, remoteLinksJson),
        )
    }

    internal fun parseComments(commentsJson: String): List<String> {
        if (commentsJson.isBlank()) return emptyList()
        return runCatching {
            val arr = jsonParser.parseToJsonElement(sanitize(commentsJson)).jsonObject["comments"]?.jsonArray
                ?: return emptyList()
            arr.take(3).map { c ->
                val o = c.jsonObject
                val author = o["author"]?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull ?: ""
                val body = o["body"]?.jsonPrimitive?.contentOrNull ?: ""
                if (author.isNotBlank()) "$author: $body" else body
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    internal fun extractConfluenceRefs(description: String, remoteLinksJson: String): List<JiraConfluenceRef> {
        val refs = mutableListOf<JiraConfluenceRef>()
        PAGE_REGEX.findAll(description).forEach { m ->
            val full = m.value
            refs += JiraConfluenceRef(
                pageId = m.groupValues[1],
                title = "",
                url = if (full.startsWith("http")) full else baseUrl + full,
            )
        }
        runCatching {
            val arr = jsonParser.parseToJsonElement(sanitize(remoteLinksJson)).jsonArray
            arr.forEach { el ->
                val obj = el.jsonObject["object"]?.jsonObject ?: return@forEach
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val m = PAGE_REGEX.find(url) ?: return@forEach
                refs += JiraConfluenceRef(pageId = m.groupValues[1], title = title, url = url)
            }
        }
        return refs.distinctBy { it.pageId }
    }

    private fun sanitize(s: String): String = s.replace(Regex("[\\p{Cntrl}&&[^\\r\\n]]"), " ")

    companion object {
        private val log = LoggerFactory.getLogger(JiraClient::class.java)
        private val PAGE_REGEX = Regex("""(?:https?://[^\s"')\]]+)?/wiki/[^\s"')\]]*?/pages/(\d+)""")
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :jira:test`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts jira/build.gradle.kts jira/src/main/kotlin/io/github/veronikapj/wiki/jira/JiraClient.kt jira/src/test/kotlin/io/github/veronikapj/wiki/jira/JiraClientTest.kt
git commit -m "$(cat <<'EOF'
feat(jira): JiraClient 추가 — 이슈/댓글/Confluence ref 파싱

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: PrHistoryTool 보강

**Files:**
- Modify: `search/build.gradle.kts` (dependencies에 `api(project(":jira"))`)
- Modify: `search/src/main/kotlin/io/github/veronikapj/wiki/search/tool/PrHistoryTool.kt`
- Create (test): `search/src/test/kotlin/io/github/veronikapj/wiki/search/tool/PrHistoryToolTest.kt`

**Interfaces:**
- Consumes: Task 1 `JiraClient.getIssue(key): JiraIssue?`, `JiraIssue`, `JiraConfluenceRef`. 기존 `io.github.veronikapj.wiki.confluence.ConfluenceClient.fetchPageRawHtml(pageId): String`, `io.github.veronikapj.wiki.rag.ChromaQueryResult(id, document, metadata, distance)`.
- Produces: `PrHistoryTool` 생성자에 `jiraClient: JiraClient? = null, confluenceClient: ConfluenceClient? = null` 추가.

- [ ] **Step 1: build 의존성 추가**

`search/build.gradle.kts`의 `dependencies { ... }`에서 `api(project(":github"))` 아래에 추가:

```kotlin
    api(project(":jira"))
```

- [ ] **Step 2: Write the failing tests**

Create `search/src/test/kotlin/io/github/veronikapj/wiki/search/tool/PrHistoryToolTest.kt`:

```kotlin
package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.jira.JiraClient
import io.github.veronikapj.wiki.jira.JiraConfluenceRef
import io.github.veronikapj.wiki.jira.JiraIssue
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.ChromaQueryResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrHistoryToolTest {

    private fun chromaWithOnePr(): ChromaClient {
        val chroma = mockk<ChromaClient>()
        coEvery { chroma.getOrCreateCollection(any()) } returns "cid"
        coEvery { chroma.query(any(), any(), any()) } returns listOf(
            ChromaQueryResult(
                id = "1",
                document = "# PR #10: KMA-7275 배너\n- 티켓: KMA-7275\n변경 요약",
                metadata = mapOf(
                    "repo" to "o/r", "pr_number" to "10", "ticket" to "KMA-7275",
                    "author" to "a", "merged_at" to "2026-06-01",
                ),
                distance = 0.1f,
            )
        )
        return chroma
    }

    private val issue = JiraIssue(
        key = "KMA-7275", summary = "배너 DSP", status = "In Progress", type = "Story",
        assignee = "pilju.bae", description = "배너 기획 설명",
        recentComments = listOf("pilju: 진행중"),
        confluenceRefs = listOf(JiraConfluenceRef("123", "배너 기획서", "https://x/wiki/spaces/P/pages/123")),
    )

    @Test
    fun `prHistory는 Jira 티켓과 Confluence 기획서를 보강한다`() = runBlocking {
        val jira = mockk<JiraClient>()
        coEvery { jira.getIssue("KMA-7275") } returns issue
        val conf = mockk<ConfluenceClient>()
        coEvery { conf.fetchPageRawHtml("123") } returns "<p>기획 본문 내용</p>"

        val tool = PrHistoryTool(
            chromaWithOnePr(), null, embeddingFn = { listOf(0.1f) },
            jiraClient = jira, confluenceClient = conf,
        )

        val result = tool.prHistory("KMA-7275")

        assertTrue(result.contains("🎫 KMA-7275"), result)
        assertTrue(result.contains("배너 DSP"), result)
        assertTrue(result.contains("📄 기획서"), result)
        assertTrue(result.contains("기획 본문 내용"), result)
    }

    @Test
    fun `jiraClient가 null이면 보강 없이 PR 결과만 반환한다`() = runBlocking {
        val tool = PrHistoryTool(chromaWithOnePr(), null, embeddingFn = { listOf(0.1f) })

        val result = tool.prHistory("KMA-7275")

        assertFalse(result.contains("🎫"), result)
        assertTrue(result.contains("PR #10"), result)
    }

    @Test
    fun `getIssue가 null이면 해당 티켓은 생략하고 PR 결과는 반환한다`() = runBlocking {
        val jira = mockk<JiraClient>()
        coEvery { jira.getIssue(any()) } returns null
        val tool = PrHistoryTool(
            chromaWithOnePr(), null, embeddingFn = { listOf(0.1f) }, jiraClient = jira,
        )

        val result = tool.prHistory("KMA-7275")

        assertFalse(result.contains("🎫"), result)
        assertTrue(result.contains("PR #10"), result)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :search:test --tests "io.github.veronikapj.wiki.search.tool.PrHistoryToolTest"`
Expected: FAIL — 생성자에 `jiraClient`/`confluenceClient` 없음(compile error).

- [ ] **Step 4: Write the implementation**

`PrHistoryTool.kt` — import 추가(파일 상단 import 블록):

```kotlin
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.jira.JiraClient
import io.github.veronikapj.wiki.rag.ChromaQueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
```

생성자에 두 파라미터 추가(`embeddingFn` 아래):

```kotlin
class PrHistoryTool(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val tracker: SourceTracker? = null,
    private val collectionName: String = "code_prs",
    private val embeddingFn: (suspend (String) -> List<Float>)? = null,
    private val jiraClient: JiraClient? = null,
    private val confluenceClient: ConfluenceClient? = null,
) {
```

`prHistory`의 마지막 `buildString { ... }.trim()` 블록을 아래로 교체 (PR 목록을 변수에 담고 보강을 덧붙임):

```kotlin
            val prBlock = buildString {
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

            val enrichment = if (jiraClient != null) {
                runCatching { buildJiraEnrichment(results) }
                    .getOrElse { log.warn("Jira enrichment failed: {}", it.message); "" }
            } else ""

            if (enrichment.isBlank()) prBlock else "$prBlock\n\n$enrichment"
```

`extractIdentifiers` 아래(또는 companion 위)에 보강 헬퍼 추가:

```kotlin
    /** 반환 PR들에서 Jira 키(상위 3) 추출 → 병렬 fetch → 티켓+대표 Confluence 페이지 발췌 블록 생성. */
    private suspend fun buildJiraEnrichment(results: List<ChromaQueryResult>): String {
        val jira = jiraClient ?: return ""
        val keys = results.flatMap { r ->
            val fromMeta = r.metadata["ticket"]?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            val fromDoc = TICKET_REGEX.findAll(r.document).map { it.value }.toList()
            fromMeta + fromDoc
        }.distinct().take(MAX_TICKETS)
        if (keys.isEmpty()) return ""

        val issues = coroutineScope {
            keys.map { k -> async(Dispatchers.IO) { runCatching { jira.getIssue(k) }.getOrNull() } }.awaitAll()
        }.filterNotNull()
        if (issues.isEmpty()) return ""

        return buildString {
            appendLine("=== 🎫 연결된 Jira 티켓 ===")
            issues.forEach { issue ->
                appendLine()
                append("🎫 ${issue.key} (${issue.type}, ${issue.status}")
                if (issue.assignee.isNotBlank()) append(", 담당: ${issue.assignee}")
                appendLine(")")
                if (issue.summary.isNotBlank()) appendLine("요약: ${issue.summary}")
                if (issue.description.isNotBlank()) appendLine("내용: ${issue.description.take(DESC_EXCERPT)}")
                if (issue.recentComments.isNotEmpty()) {
                    appendLine("최근 코멘트:")
                    issue.recentComments.take(MAX_COMMENTS).forEach { appendLine("- ${it.take(COMMENT_EXCERPT)}") }
                }
                val ref = issue.confluenceRefs.firstOrNull()
                if (ref != null && confluenceClient != null) {
                    val excerpt = runCatching { htmlToExcerpt(confluenceClient.fetchPageRawHtml(ref.pageId)) }
                        .getOrDefault("")
                    if (excerpt.isNotBlank()) {
                        appendLine("📄 기획서: ${ref.title.ifBlank { ref.url }} (${ref.url})")
                        appendLine("   $excerpt")
                    }
                }
            }
        }.trim()
    }

    private fun htmlToExcerpt(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(PAGE_EXCERPT)
```

`companion object`에 상수/정규식 추가:

```kotlin
    companion object {
        private val log = LoggerFactory.getLogger(PrHistoryTool::class.java)
        private const val MAX_TICKETS = 3
        private const val MAX_COMMENTS = 3
        private const val DESC_EXCERPT = 600
        private const val COMMENT_EXCERPT = 200
        private const val PAGE_EXCERPT = 800
        private val TICKET_REGEX = Regex("""[A-Z]+-\d+""")
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :search:test --tests "io.github.veronikapj.wiki.search.tool.PrHistoryToolTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add search/build.gradle.kts search/src/main/kotlin/io/github/veronikapj/wiki/search/tool/PrHistoryTool.kt search/src/test/kotlin/io/github/veronikapj/wiki/search/tool/PrHistoryToolTest.kt
git commit -m "$(cat <<'EOF'
feat(search): prHistory에 Jira 티켓+Confluence 기획서 보강

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Main.kt 배선 + 전체 검증

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt` (confluence 토큰 로드부 + PrHistoryTool 생성부)

**Interfaces:**
- Consumes: Task 1 `JiraClient`, Task 2 `PrHistoryTool(..., jiraClient, confluenceClient)`. 기존 `config.confluence.baseUrl`, `confluenceToken`(line 69), `confluenceClient`(line 113~), `SecretLoader.resolveNullable`.

- [ ] **Step 1: import 추가**

`Main.kt` import 블록에 추가:

```kotlin
import io.github.veronikapj.wiki.jira.JiraClient
```

- [ ] **Step 2: JiraClient 생성**

`Main.kt`에서 `confluenceClient` 생성 블록(약 113–120행, `if (config.confluence.baseUrl.isNotBlank() && confluenceToken.isNotBlank()) { ... }`) 바로 다음에 추가:

```kotlin
    val jiraToken = SecretLoader.resolveNullable("JIRA_TOKEN", null)?.takeIf { it.isNotBlank() } ?: confluenceToken
    var jiraClient: JiraClient? = null
    if (config.confluence.baseUrl.isNotBlank() && jiraToken.isNotBlank()) {
        jiraClient = JiraClient(baseUrl = config.confluence.baseUrl, token = jiraToken)
        log.info("Jira enabled: baseUrl={}", config.confluence.baseUrl)
    }
```

- [ ] **Step 3: PrHistoryTool에 주입**

`Main.kt`의 `prHistoryTool = PrHistoryTool(codeChromaClient, codeLlmExpandClient, sourceTracker, embeddingFn = searchEmbeddingFn)` (약 237행)을 아래로 교체:

```kotlin
        prHistoryTool = PrHistoryTool(
            codeChromaClient, codeLlmExpandClient, sourceTracker,
            embeddingFn = searchEmbeddingFn,
            jiraClient = jiraClient,
            confluenceClient = confluenceClient,
        )
```

- [ ] **Step 4: 전체 컴파일 + 테스트 검증**

Run: `./gradlew compileKotlin :jira:test :search:test :test`
Expected: BUILD SUCCESSFUL (Main.kt 포함 전체 컴파일, :jira/:search/root 테스트 통과).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "$(cat <<'EOF'
feat(app): JiraClient 생성 + PrHistoryTool 주입 배선

Confluence 인스턴스/토큰 재사용(JIRA_TOKEN env 옵션).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- 신규 `:jira` 모듈 + JiraClient(필드/댓글/Confluence ref) → Task 1. ✓
- 티켓 키 추출(ticket 메타 + document 정규식, 상위 3) → Task 2 `buildJiraEnrichment`. ✓
- 병렬 fetch → Task 2 `coroutineScope { async }`. ✓
- Confluence 대표 페이지 1개 발췌(`fetchPageRawHtml` + `htmlToExcerpt`) → Task 2. ✓
- 출력 블록 형식/상한(DESC600/COMMENT200×3/PAGE800/MAX_TICKETS3) → Task 2. ✓
- Graceful(jira null / getIssue null / confluence 실패) → Task 2 테스트 3건 + runCatching. ✓
- 인증 재사용 + JIRA_TOKEN 옵션 → Task 3. ✓
- 배선(`:search`→`:jira`, Main 주입) → Task 2 Step1 + Task 3. ✓
- 비목표(인덱싱·generateGuide 미변경) → 어떤 Task도 미수정. ✓

**Placeholder scan:** 모든 step에 실제 코드/명령/기대출력. TBD 없음. ✓

**Type consistency:**
- `JiraIssue`/`JiraConfluenceRef` 필드 — Task 1 정의, Task 2 사용 동일. ✓
- `getIssue(key): JiraIssue?` — Task 1 정의, Task 2 mockk·호출 동일. ✓
- `ChromaQueryResult(id, document, metadata, distance)` — 실제 정의와 일치(Task 2 테스트). ✓
- `fetchPageRawHtml(pageId: String): String` — 실제 시그니처와 일치. ✓
- 상수명(MAX_TICKETS/DESC_EXCERPT 등) — Task 2 내 정의·사용 일치. ✓

**리스크:** Jira v2 description은 wiki markup 문자열(발췌라 허용). `:search`→`:jira` 의존 추가가 순환 없음(`:jira`는 무의존). PrHistoryTool 테스트는 신규 파일이라 기존 테스트 영향 없음.
