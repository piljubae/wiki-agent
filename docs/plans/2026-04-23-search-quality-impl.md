# 검색 품질 개선 구현 계획

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** CQL 검색의 recall/precision을 높이고 골든 데이터셋 기반 품질 측정 체계를 구축한다.

**Architecture:** SYNONYMS를 단일 CQL OR 절로 합쳐 API 호출을 줄이고, RAG fallback으로 의미 검색을 보충한다. SearchResult data class로 결과를 구조화하여 자동 평가를 가능하게 한다.

**Tech Stack:** Kotlin 2.3, JUnit 5, mockk, Confluence REST API (CQL), ChromaDB (RAG)

---

### Task 1: CQL 특수문자 이스케이프 + Stopword 필터

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClient.kt:36-53`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClientTest.kt`

**Step 1: Write the failing tests**

```kotlin
// ConfluenceClientTest.kt 에 추가

@Test
fun `buildCqlSearchUrl escapes CQL special characters`() {
    val url = client.buildCqlSearchUrl("""test") OR space = "SECRET""", listOf("DEV"))
    // 괄호가 이스케이프되어 CQL injection 불가
    assertTrue(!url.contains("OR+space"))
}

@Test
fun `buildCqlSearchUrl includes short keywords like UI and QA`() {
    val url = client.buildCqlSearchUrl("UI QA 가이드", listOf("DEV"))
    val decoded = java.net.URLDecoder.decode(url, "UTF-8")
    assertTrue(decoded.contains("UI"), "Should include 'UI'")
    assertTrue(decoded.contains("QA"), "Should include 'QA'")
}

@Test
fun `buildCqlSearchUrl filters Korean stopwords`() {
    val url = client.buildCqlSearchUrl("배포는 어떻게 하는가", listOf("DEV"))
    val decoded = java.net.URLDecoder.decode(url, "UTF-8")
    assertTrue(!decoded.contains("text ~ \"는\""), "Should filter stopword '는'")
    assertTrue(!decoded.contains("text ~ \"어떻게\""), "Should filter stopword '어떻게'")
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.confluence.ConfluenceClientTest" -i`
Expected: FAIL — CQL injection test passes incorrectly or stopword test fails

**Step 3: Write implementation**

```kotlin
// ConfluenceClient.kt — companion object 안에 추가
private val STOPWORDS = setOf(
    "의", "를", "은", "는", "이", "가", "에", "도", "로", "와", "과", "을",
    "그", "저", "이것", "저것", "어떻게", "무엇", "하는", "하는가", "합니다",
)

private fun escapeCql(input: String): String {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("(", "\\(")
        .replace(")", "\\)")
}

// buildCqlSearchUrl 수정
fun buildCqlSearchUrl(query: String, spaces: List<String>, limit: Int = 5): String {
    val spaceCql = if (spaces.isNotEmpty())
        " AND space IN (${spaces.joinToString(",") { "\"$it\"" }})"
    else ""
    val safeQuery = escapeCql(query)
    val words = query.trim().split(Regex("\\s+"))
        .filter { it.length >= 1 && it !in STOPWORDS }
    val textCql = if (words.size <= 1) {
        "(title ~ \"$safeQuery\" OR text ~ \"$safeQuery\")"
    } else {
        val wordClauses = words.joinToString(" OR ") { w ->
            "text ~ \"${escapeCql(w)}\""
        }
        "(title ~ \"$safeQuery\" OR ($wordClauses))"
    }
    val cql = URLEncoder.encode("$textCql$spaceCql", "UTF-8")
    return "$baseUrl/wiki/rest/api/content/search?cql=$cql&limit=$limit&expand=body.storage"
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.confluence.ConfluenceClientTest" -i`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClient.kt \
        src/test/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClientTest.kt
git commit -m "fix: CQL 특수문자 이스케이프 + stopword 기반 필터링"
```

---

### Task 2: SYNONYMS → 단일 CQL OR 절

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClient.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClientTest.kt`

**Step 1: Write the failing test**

```kotlin
@Test
fun `buildCqlSearchUrl with synonyms combines into single OR clause`() {
    val url = client.buildCqlSearchUrl(
        query = "신입 온보딩",
        spaces = listOf("DEV"),
        synonyms = listOf("신규 입사자", "입사 가이드"),
    )
    val decoded = java.net.URLDecoder.decode(url, "UTF-8")
    assertTrue(decoded.contains("신규 입사자"), "Should include synonym")
    assertTrue(decoded.contains("입사 가이드"), "Should include synonym")
    // 단일 URL이므로 API 1회 호출
}

@Test
fun `buildCqlSearchUrl limits synonyms to 5`() {
    val synonyms = (1..10).map { "동의어$it" }
    val url = client.buildCqlSearchUrl("테스트", listOf("DEV"), synonyms = synonyms)
    val decoded = java.net.URLDecoder.decode(url, "UTF-8")
    assertTrue(decoded.contains("동의어5"), "Should include 5th synonym")
    assertTrue(!decoded.contains("동의어6"), "Should NOT include 6th synonym")
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.confluence.ConfluenceClientTest" -i`
Expected: FAIL — buildCqlSearchUrl doesn't accept synonyms parameter

**Step 3: Write implementation**

```kotlin
// ConfluenceClient.kt — buildCqlSearchUrl 시그니처 변경
fun buildCqlSearchUrl(
    query: String,
    spaces: List<String>,
    synonyms: List<String> = emptyList(),
    limit: Int = 5,
): String {
    val spaceCql = if (spaces.isNotEmpty())
        " AND space IN (${spaces.joinToString(",") { "\"$it\"" }})"
    else ""
    val safeQuery = escapeCql(query)
    val words = query.trim().split(Regex("\\s+"))
        .filter { it.length >= 1 && it !in STOPWORDS }

    // 원본 쿼리 단어 + 동의어 (상한 5개)
    val synonymTerms = synonyms.take(5 - words.size.coerceAtMost(3))
        .map { escapeCql(it) }

    val titleClause = "title ~ \"$safeQuery\""
    val textClauses = mutableListOf<String>()

    if (words.size <= 1) {
        textClauses.add("text ~ \"$safeQuery\"")
    } else {
        words.forEach { w -> textClauses.add("text ~ \"${escapeCql(w)}\"") }
    }
    synonymTerms.forEach { s -> textClauses.add("text ~ \"$s\"") }

    val textCql = "($titleClause OR ${textClauses.joinToString(" OR ")})"
    val cql = URLEncoder.encode("$textCql$spaceCql", "UTF-8")
    return "$baseUrl/wiki/rest/api/content/search?cql=$cql&limit=$limit&expand=body.storage"
}

// searchPages도 synonyms 전달
suspend fun searchPages(
    query: String,
    spaces: List<String>,
    synonyms: List<String> = emptyList(),
    limit: Int = 5,
): List<ConfluencePageRef> {
    val url = buildCqlSearchUrl(query, spaces, synonyms, limit)
    log.info("Confluence CQL search: {}", url)
    val response = httpClient.get(url) {
        header("Authorization", "Basic $token")
        header("Accept", "application/json")
    }.bodyAsText()
    return parseSearchResults(response, baseUrl)
}
```

```kotlin
// ConfluenceSearchAgent.kt — search에 synonyms 추가
suspend fun search(query: String, synonyms: List<String> = emptyList(), topK: Int = 5): String {
    log.info("Searching Confluence: query='{}', synonyms={}, spaces={}", query, synonyms, spaces)
    val pages = confluenceClient.searchPages(query, spaces, synonyms, topK)
    // ... 나머지 동일
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.confluence.ConfluenceClientTest" -i`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClient.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClientTest.kt
git commit -m "feat: SYNONYMS를 단일 CQL OR 절로 합쳐 API 1회 호출"
```

---

### Task 3: SearchResult data class 도입

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/SearchResult.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt` (기존 파일 수정)

**Step 1: Write the failing test**

```kotlin
// ConfluenceSearchAgentTest.kt 에 추가
@Test
fun `searchStructured returns list of SearchResult`() = runTest {
    val mockClient = mockk<ConfluenceClient>()
    coEvery { mockClient.searchPages("배포", any(), any(), any()) } returns listOf(
        ConfluencePageRef("1", "배포 가이드", "https://example.com/wiki/1"),
    )
    coEvery { mockClient.fetchPageContent("1") } returns ConfluencePage(
        "1", "배포 가이드", "배포 절차를 설명합니다.", "https://example.com/wiki/1"
    )
    val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"))
    val results = agent.searchStructured("배포")
    assertEquals(1, results.size)
    assertEquals("1", results[0].pageId)
    assertEquals(Source.CQL, results[0].source)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.ConfluenceSearchAgentTest" -i`
Expected: FAIL — searchStructured doesn't exist

**Step 3: Write implementation**

```kotlin
// SearchResult.kt
package io.github.veronikapj.wiki.agent

enum class Source { CQL, RAG }

data class SearchResult(
    val pageId: String,
    val title: String,
    val url: String,
    val snippet: String,
    val source: Source,
)

fun List<SearchResult>.formatForSlack(): String {
    if (isEmpty()) return "관련 문서를 찾을 수 없습니다."
    val sb = StringBuilder()
    sb.appendLine("*검색 결과 (${size}건):*\n")
    forEachIndexed { i, r ->
        sb.appendLine("${i + 1}. *${r.title}*")
        sb.appendLine("   <${r.url}|링크>")
        sb.appendLine("   > ${r.snippet.replace("\n", "\n   > ")}")
        sb.appendLine()
    }
    return sb.toString().trim()
}
```

```kotlin
// ConfluenceSearchAgent.kt — searchStructured 추가, search는 searchStructured + formatForSlack 으로 위임
suspend fun searchStructured(
    query: String,
    synonyms: List<String> = emptyList(),
    topK: Int = 5,
): List<SearchResult> {
    log.info("Searching Confluence: query='{}', synonyms={}, spaces={}", query, synonyms, spaces)
    val pages = confluenceClient.searchPages(query, spaces, synonyms, topK)
    return pages.mapNotNull { ref ->
        runCatching {
            val page = confluenceClient.fetchPageContent(ref.id)
            val snippet = page.content.lines().take(5).joinToString("\n").take(300)
            SearchResult(
                pageId = ref.id,
                title = ref.title,
                url = ref.webUrl,
                snippet = snippet,
                source = Source.CQL,
            )
        }.getOrNull()
    }
}

suspend fun search(query: String, synonyms: List<String> = emptyList(), topK: Int = 5): String {
    val results = searchStructured(query, synonyms, topK)
    if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (query: $query)"
    return results.formatForSlack()
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.ConfluenceSearchAgentTest" -i`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/SearchResult.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt
git commit -m "feat: SearchResult data class 도입 + 구조화된 검색 반환"
```

---

### Task 4: OrchestratorAgent — 단일 CQL 호출 + 할루시네이션 방지

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt:157-197`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt` (기존 파일 수정)

**Step 1: Write the failing test**

```kotlin
// OrchestratorAgentTest.kt — 기존 테스트 중 executeFromDecision 관련 동작 검증
// ConfluenceTool이 synonyms를 받아 1회만 호출되는지 확인
@Test
fun `manual loop passes synonyms to confluenceSearch in single call`() = runTest {
    // ConfluenceTool.confluenceSearch가 synonyms 파라미터를 받는지 확인
    val tool = ConfluenceTool(mockSearchAgent)
    // 새 시그니처: confluenceSearch(query, synonyms)
    val result = tool.confluenceSearch("배포", listOf("릴리스", "deploy"))
    // 검증: searchAgent.search가 synonyms와 함께 1회 호출됨
    coVerify(exactly = 1) { mockSearchAgent.search("배포", listOf("릴리스", "deploy"), any()) }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.OrchestratorAgentTest" -i`
Expected: FAIL — confluenceSearch doesn't accept synonyms

**Step 3: Write implementation**

```kotlin
// ConfluenceTool.kt — synonyms 파라미터 추가
@Tool("confluenceSearch")
@LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
fun confluenceSearch(
    @LLMDescription("검색할 질문 또는 키워드 (한국어 가능)")
    query: String,
    synonyms: List<String> = emptyList(),
): String = runBlocking {
    tracker?.record("Confluence")
    searchAgent.search(query, synonyms)
}
```

```kotlin
// OrchestratorAgent.kt — executeFromDecision 수정
private fun executeFromDecision(decision: String): String? {
    val toolMatch = Regex("TOOL:\\s*(\\S+)").find(decision) ?: return null
    val queryMatch = Regex("QUERY:\\s*(.+)").find(decision) ?: return null
    val toolName = toolMatch.groupValues[1].trim()
    val query = queryMatch.groupValues[1].trim()
    val synonyms = Regex("SYNONYMS:\\s*(.+)").find(decision)?.groupValues?.get(1)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    if (query.isBlank()) return null
    log.info("Executing tool: {} query: {} synonyms: {}", toolName, query, synonyms)

    // 단일 호출 — synonyms를 CQL에 직접 반영
    val result = runCatching {
        when (toolName) {
            "githubWikiSearch" -> githubWikiTool?.githubWikiSearch(query)
            "confluenceSearch" -> confluenceTool?.confluenceSearch(query, synonyms)
            "vectorSearch" -> vectorSearchTool?.vectorSearch(query)
            else -> null
        }
    }.getOrNull()

    return result?.takeIf { !it.contains("찾을 수 없습니다") }
}
```

```kotlin
// OrchestratorAgent.kt — summaryPrompt 할루시네이션 방지 (line 133-134)
// 변경 전:
//   appendLine("검색 결과가 없습니다. 알고 있는 내용으로 간략히 답변하세요.")
// 변경 후:
    appendLine("검색 결과가 없습니다. '관련 문서를 찾지 못했습니다'라고 답변하세요.")
    appendLine("검색 대상: Confluence 스페이스. 질문을 다르게 표현하면 찾을 수도 있다고 안내하세요.")
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.*" -i`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat: 단일 CQL 호출로 변경 + 할루시네이션 방지 프롬프트"
```

---

### Task 5: RAG fallback + graceful degradation

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/VectorSearchTool.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt`

**Step 1: Write the failing test**

```kotlin
@Test
fun `manual loop falls back to RAG when CQL returns no results`() = runTest {
    // confluenceTool returns "찾을 수 없습니다"
    // vectorSearchTool returns valid results
    // 검증: vectorSearch가 호출됨
}

@Test
fun `manual loop returns CQL results only when RAG times out`() = runTest {
    // confluenceTool returns valid results
    // vectorSearchTool throws timeout exception
    // 검증: CQL 결과만 반환, 에러 없음
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.OrchestratorAgentTest" -i`
Expected: FAIL — RAG fallback not implemented in manual loop

**Step 3: Write implementation**

```kotlin
// VectorSearchTool.kt — 타임아웃 추가
fun vectorSearch(
    @LLMDescription("검색할 질문 또는 키워드") query: String,
): String = runBlocking {
    tracker?.record("RAG(ChromaDB)")
    withTimeoutOrNull(5000) {
        searchAgent.search(query)
    } ?: "RAG 검색 타임아웃"
}
```

```kotlin
// OrchestratorAgent.kt — executeFromDecision 후 RAG fallback
// answer 메서드 내 searchResult 결정 로직:
val searchResult = runCatching { executeFromDecision(decision) }.getOrNull()
    ?: runCatching { executeDefault(question, availableTools) }.getOrNull()

// 변경: CQL 결과가 없고 vectorSearchTool이 있으면 RAG fallback
var searchResult = runCatching { executeFromDecision(decision) }.getOrNull()
if (searchResult == null && vectorSearchTool != null && toolName != "vectorSearch") {
    log.info("CQL result empty, falling back to RAG")
    listener?.onSearchStarted("vectorSearch")
    searchResult = runCatching { vectorSearchTool.vectorSearch(question) }.getOrNull()
        ?.takeIf { !it.contains("찾을 수 없습니다") && !it.contains("타임아웃") }
    listener?.onSearchCompleted("vectorSearch")
}
if (searchResult == null) {
    searchResult = runCatching { executeDefault(question, availableTools) }.getOrNull()
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.agent.OrchestratorAgentTest" -i`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/tool/VectorSearchTool.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat: RAG fallback + 5초 타임아웃 + graceful degradation"
```

---

### Task 6: 골든 데이터셋 + 평가 테스트

**Files:**
- Create: `src/test/resources/golden-dataset.json`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenCase.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/eval/SearchQualityEvalTest.kt`
- Modify: `build.gradle.kts` (JUnit tag 설정)

**Step 1: Write the data model + dataset**

```kotlin
// GoldenCase.kt
package io.github.veronikapj.wiki.eval

data class GoldenCase(
    val id: String,
    val question: String,
    val category: Category,
    val expectedDocTitles: List<String>,
    val keyPoints: List<String> = emptyList(),
    val negativePoints: List<String> = emptyList(),
)

enum class Category {
    EXACT_MATCH,
    SYNONYM_GAP,
    ABBREVIATION,
    PARTIAL_MATCH,
    MULTI_DOC,
    ZERO_EXPECTED,
}
```

```json
// golden-dataset.json — 초기 시드 (실제 Confluence 문서에 맞게 조정 필요)
[
  {
    "id": "GC-001",
    "question": "배포 가이드",
    "category": "EXACT_MATCH",
    "expectedDocTitles": ["배포 프로세스 가이드"],
    "keyPoints": ["배포 절차"]
  },
  {
    "id": "GC-002",
    "question": "신입 온보딩",
    "category": "SYNONYM_GAP",
    "expectedDocTitles": ["신규 입사자 온보딩 가이드"],
    "keyPoints": ["입사 체크리스트"]
  },
  {
    "id": "GC-003",
    "question": "PR 리뷰 규칙",
    "category": "ABBREVIATION",
    "expectedDocTitles": ["Pull Request 리뷰 가이드"],
    "keyPoints": ["리뷰 프로세스"]
  }
]
```

**Step 2: Write the eval test**

```kotlin
// SearchQualityEvalTest.kt
package io.github.veronikapj.wiki.eval

import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertTrue

@Tag("eval")
class SearchQualityEvalTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    fun `recall at 5`(case: GoldenCase) {
        // 실제 Confluence 환경 필요 — CI에서는 skip
        // ConfluenceSearchAgent.searchStructured() 호출
        // returnedTitles에 expectedDocTitles가 포함되는지 확인
    }

    companion object {
        @JvmStatic
        fun goldenCases(): List<GoldenCase> {
            val json = SearchQualityEvalTest::class.java
                .getResourceAsStream("/golden-dataset.json")?.reader()?.readText()
                ?: error("golden-dataset.json not found")
            // 간단한 JSON 파싱 (kotlinx.serialization 활용)
            return parseGoldenCases(json)
        }
    }
}
```

**Step 3: build.gradle.kts — eval 태그 제외**

```kotlin
// tasks.test 블록 수정
tasks.test {
    useJUnitPlatform {
        excludeTags("eval")
    }
}

// eval 전용 태스크 추가
tasks.register<Test>("evalTest") {
    useJUnitPlatform {
        includeTags("eval")
    }
}
```

**Step 4: Run to verify structure**

Run: `./gradlew test -i` (eval 제외 확인)
Run: `./gradlew evalTest -i` (eval만 실행 확인)

**Step 5: Commit**

```bash
git add src/test/resources/golden-dataset.json \
        src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenCase.kt \
        src/test/kotlin/io/github/veronikapj/wiki/eval/SearchQualityEvalTest.kt \
        build.gradle.kts
git commit -m "feat: 골든 데이터셋 + 검색 품질 평가 테스트 (eval tag)"
```

---

### Task 7: Slack 리액션 피드백 수집

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/slack/SlackBotGatewayTest.kt`

**Step 1: Write the failing test**

```kotlin
@Test
fun `answer message includes feedback guide text`() {
    // handleQueryAsync 결과 메시지에 리액션 안내가 포함되는지 확인
    // footer에 ":thumbsup: 도움이 됐다면 | :thumbsdown: 아쉬웠다면" 포함
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.slack.SlackBotGatewayTest" -i`
Expected: FAIL

**Step 3: Write implementation**

```kotlin
// SlackBotGateway.kt — handleQueryAsync 내 finalText 구성 수정 (line 111-114)
val footer = buildString {
    if (searchedTools.isNotEmpty()) {
        append("\U0001F4CB ")
        append(searchedTools.distinct().joinToString(" · ") { toolDisplayNames[it] ?: it })
    }
    append("\n:thumbsup: 도움이 됐다면 | :thumbsdown: 아쉬웠다면 리액션을 남겨주세요")
}
val finalText = "$result\n\n$footer"
```

```kotlin
// SlackBotGateway.kt — reactionAdded 이벤트 핸들러 등록
private fun registerReactionHandler() {
    app.event(com.slack.api.model.event.ReactionAddedEvent::class.java) { payload, ctx ->
        val event = payload.event
        val reaction = event.reaction
        if (reaction in listOf("+1", "-1", "thumbsup", "thumbsdown")) {
            log.info(
                "Feedback received: reaction={}, user={}, channel={}, ts={}",
                reaction, event.user, event.item.channel, event.item.ts,
            )
            // TODO: 파일/DB에 로깅하여 골든 데이터셋 후보로 축적
        }
        ctx.ack()
    }
}

// start() 에서 registerReactionHandler() 호출 추가
fun start() {
    registerMentionHandler()
    registerDmHandler()
    registerReactionHandler()
    registerSlashCommand()
    log.info("Starting Slack bot (Socket Mode)...")
    SocketModeApp(slackConfig.appToken, app).start()
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.veronikapj.wiki.slack.*" -i`
Expected: ALL PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt \
        src/test/kotlin/io/github/veronikapj/wiki/slack/SlackBotGatewayTest.kt
git commit -m "feat: Slack 리액션 피드백 수집 + 답변에 안내 텍스트 추가"
```

---

## 실행 순서 요약

| Task | 내용 | 의존성 |
|------|------|--------|
| 1 | CQL 이스케이프 + stopword | 없음 |
| 2 | SYNONYMS → 단일 CQL OR | Task 1 |
| 3 | SearchResult data class | 없음 |
| 4 | 단일 CQL 호출 + 할루시네이션 방지 | Task 2, 3 |
| 5 | RAG fallback + 타임아웃 | Task 4 |
| 6 | 골든 데이터셋 + eval 테스트 | Task 3 |
| 7 | Slack 리액션 피드백 | 없음 |

병렬 가능: Task 1+3+7 → Task 2 → Task 4 → Task 5+6
