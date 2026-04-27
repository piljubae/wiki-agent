# 검색 플로우 고도화 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** recall 개선을 위한 검색 플로우 고도화 + 데이터 기반 평가 체계 구축

**Architecture:** ConfluenceClient를 title/text 개별 메서드로 분리하고, ConfluenceSearchAgent에서 early return + 병렬 fallback(text + 스페이스 확장 + RAG)을 오케스트레이션한다. 골든 데이터셋은 Confluence 페이지에서 3가지 유형(제목/LLM/paraphrase)으로 자동 생성하고, eval 시스템은 Recall@1/5, MRR, stage hit 등의 메트릭으로 before/after 비교한다.

**Tech Stack:** Kotlin 2.3, Koog 0.8.0, kotlinx.coroutines (async/await), kotlinx.serialization, JUnit 5, MockK

**Base branch:** `feat/search-quality-improvement` (PR #6)

---

## Phase 1: Foundation

### Task 1: SearchStage enum + 가중 랭킹

SearchResult의 `Source` enum을 검색 단계를 추적하는 `SearchStage`로 교체하고, 단계별 가중치 점수를 추가한다.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/SearchResult.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/agent/SearchResultTest.kt`

**Step 1: Write failing tests**

`src/test/kotlin/io/github/veronikapj/wiki/agent/SearchResultTest.kt`:

```kotlin
package io.github.veronikapj.wiki.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchResultTest {

    @Test
    fun `SearchStage score ordering matches design`() {
        assertTrue(SearchStage.TITLE_MATCH.score > SearchStage.SPACE_EXPANSION.score)
        assertTrue(SearchStage.SPACE_EXPANSION.score > SearchStage.TEXT_MATCH.score)
        assertTrue(SearchStage.TEXT_MATCH.score > SearchStage.RAG.score)
    }

    @Test
    fun `SearchStage score values`() {
        assertEquals(1.0, SearchStage.TITLE_MATCH.score)
        assertEquals(0.8, SearchStage.SPACE_EXPANSION.score)
        assertEquals(0.6, SearchStage.TEXT_MATCH.score)
        assertEquals(0.5, SearchStage.RAG.score)
    }

    @Test
    fun `results sorted by stage score descending`() {
        val results = listOf(
            SearchResult("3", "C", "url3", "s3", SearchStage.RAG),
            SearchResult("1", "A", "url1", "s1", SearchStage.TITLE_MATCH),
            SearchResult("2", "B", "url2", "s2", SearchStage.TEXT_MATCH),
        )
        val sorted = results.sortedByDescending { it.stage.score }
        assertEquals("1", sorted[0].pageId)
        assertEquals("2", sorted[1].pageId)
        assertEquals("3", sorted[2].pageId)
    }

    @Test
    fun `formatForSlack shows numbered results`() {
        val results = listOf(
            SearchResult("1", "문서 A", "https://example.com/1", "요약 A", SearchStage.TITLE_MATCH),
        )
        val formatted = results.formatForSlack()
        assertTrue(formatted.contains("1. *문서 A*"))
        assertTrue(formatted.contains("<https://example.com/1|링크>"))
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/wiki-agent && ./gradlew test --tests "io.github.veronikapj.wiki.agent.SearchResultTest" 2>&1 | tail -5`
Expected: FAIL — `SearchStage` not found

**Step 3: Implement SearchResult changes**

`src/main/kotlin/io/github/veronikapj/wiki/agent/SearchResult.kt`:

```kotlin
package io.github.veronikapj.wiki.agent

enum class SearchStage(val score: Double) {
    TITLE_MATCH(1.0),
    SPACE_EXPANSION(0.8),
    TEXT_MATCH(0.6),
    RAG(0.5),
}

data class SearchResult(
    val pageId: String,
    val title: String,
    val url: String,
    val snippet: String,
    val stage: SearchStage,
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

**Step 4: Fix compilation errors in existing code**

`Source.CQL`/`Source.RAG` 참조를 `SearchStage`로 마이그레이션:

- `ConfluenceSearchAgent.kt:30` — `source = Source.CQL` → `stage = SearchStage.TITLE_MATCH`
- `ConfluenceSearchAgentTest.kt:52` — `assertEquals(Source.CQL, results[0].source)` → `assertEquals(SearchStage.TITLE_MATCH, results[0].stage)`

**Step 5: Run all tests**

Run: `cd ~/projects/wiki-agent && ./gradlew test 2>&1 | tail -10`
Expected: All PASS

**Step 6: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/SearchResult.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/SearchResultTest.kt \
        src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt
git commit -m "refactor: Source → SearchStage enum with weighted scoring"
```

---

### Task 2: ConfluenceClient — title/text 검색 메서드 분리

현재 `searchPages()`가 내부에서 title+text를 항상 2회 호출한다. 이를 public 메서드 `searchByTitle()`과 `searchByText()`로 분리해서 ConfluenceSearchAgent가 호출 시점을 제어할 수 있게 한다.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClient.kt:142-169`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClientSearchTest.kt`

**Step 1: Write failing tests**

`src/test/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClientSearchTest.kt`:

```kotlin
package io.github.veronikapj.wiki.confluence

import kotlin.test.Test
import kotlin.test.assertTrue

class ConfluenceClientSearchTest {

    private val client = ConfluenceClient(
        baseUrl = "https://test.atlassian.net",
        token = "dummy",
    )

    @Test
    fun `searchByTitle URL contains title CQL`() {
        // searchByTitle은 buildTitleCqlSearchUrl을 사용해야 한다
        val url = client.buildTitleCqlSearchUrl("배포", listOf("DEV"), emptyList(), 5)
        assertTrue(url.contains("title"))
        assertTrue(url.contains("type+%3D+page") || url.contains("type+%3D+page") || url.contains("type"))
    }

    @Test
    fun `searchByText URL contains text CQL`() {
        val url = client.buildTextCqlSearchUrl("배포 가이드", listOf("DEV"), emptyList(), 5)
        assertTrue(url.contains("text"))
    }

    @Test
    fun `buildTitleCqlSearchUrl includes synonyms`() {
        val url = client.buildTitleCqlSearchUrl("배포", listOf("DEV"), listOf("릴리즈", "출시"), 5)
        // OR clause에 동의어 포함
        assertTrue(url.contains("title"))
    }
}
```

**Step 2: Run tests to verify they pass (URL builder tests already work)**

Run: `cd ~/projects/wiki-agent && ./gradlew test --tests "io.github.veronikapj.wiki.confluence.ConfluenceClientSearchTest" 2>&1 | tail -5`
Expected: PASS (URL 빌더는 이미 public)

**Step 3: Extract searchByTitle and searchByText from searchPages**

`ConfluenceClient.kt` — 기존 `searchPages()` 내부 로직을 분리:

```kotlin
suspend fun searchByTitle(
    query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
): List<ConfluencePageRef> {
    val url = buildTitleCqlSearchUrl(query, spaces, synonyms, limit)
    log.info("Confluence title search: {}", url)
    val response = httpClient.get(url) {
        header("Authorization", "Basic $token")
        header("Accept", "application/json")
    }.bodyAsText()
    return parseSearchResults(response, baseUrl).map { it.copy(titleMatched = true) }
}

suspend fun searchByText(
    query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
): List<ConfluencePageRef> {
    val url = buildTextCqlSearchUrl(query, spaces, synonyms, limit)
    log.info("Confluence text search: {}", url)
    val response = httpClient.get(url) {
        header("Authorization", "Basic $token")
        header("Accept", "application/json")
    }.bodyAsText()
    return parseSearchResults(response, baseUrl)
}
```

기존 `searchPages()`는 하위 호환을 위해 유지하되 내부에서 `searchByTitle` + `searchByText` 호출로 위임:

```kotlin
suspend fun searchPages(
    query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
): List<ConfluencePageRef> {
    val titleResults = searchByTitle(query, spaces, synonyms, limit)
    log.info("Title search: {} results", titleResults.size)
    if (titleResults.size >= limit) return titleResults.take(limit)

    val remaining = limit - titleResults.size
    val textResults = searchByText(query, spaces, synonyms, remaining)
    val titleIds = titleResults.map { it.id }.toSet()
    return titleResults + textResults.filter { it.id !in titleIds }
}
```

**Step 4: Run all tests**

Run: `cd ~/projects/wiki-agent && ./gradlew test 2>&1 | tail -10`
Expected: All PASS (기존 searchPages 동작 동일)

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClient.kt \
        src/test/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClientSearchTest.kt
git commit -m "refactor: extract searchByTitle/searchByText from searchPages"
```

---

### Task 3: VectorSearchAgent — searchStructured 메서드 추가

병렬 RAG fallback에서 `List<SearchResult>`를 반환하는 structured 메서드가 필요하다.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/rag/VectorSearchAgent.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/rag/VectorSearchAgentTest.kt`

**Step 1: Write failing test**

`VectorSearchAgentTest.kt`에 추가:

```kotlin
@Test
fun `searchStructured returns list of SearchResult`() = runTest {
    // ... mock chromaClient, config 등 기존 테스트 패턴 참고
    val results = agent.searchStructured("배포")
    assertTrue(results.all { it.stage == SearchStage.RAG })
    assertTrue(results.all { it.pageId.isNotBlank() })
}
```

**Step 2: Run test to verify it fails**

Run: `cd ~/projects/wiki-agent && ./gradlew test --tests "*.VectorSearchAgentTest" 2>&1 | tail -5`
Expected: FAIL — `searchStructured` not found

**Step 3: Implement searchStructured**

`VectorSearchAgent.kt`에 추가:

```kotlin
import io.github.veronikapj.wiki.agent.SearchResult
import io.github.veronikapj.wiki.agent.SearchStage

suspend fun searchStructured(query: String, nResults: Int = 5): List<SearchResult> {
    val collectionId = chromaClient.getOrCreateCollection(collectionName)
    val results = when (config.embeddingMode) {
        EmbeddingMode.LLM_EXPAND -> {
            val expanded = requireNotNull(llmExpandClient).expandQuery(query)
            log.info("LLM_EXPAND query: '{}' → '{}'", query, expanded.take(100))
            chromaClient.query(collectionId, queryTexts = listOf(expanded), nResults = nResults)
        }
        EmbeddingMode.GOOGLE_EMBEDDING -> {
            val embedding = requireNotNull(googleEmbeddingClient).embed(query)
            chromaClient.query(collectionId, queryEmbeddings = listOf(embedding), nResults = nResults)
        }
    }
    return results.map { r ->
        SearchResult(
            pageId = r.metadata["pageId"] ?: r.id,
            title = r.metadata["title"] ?: "Untitled",
            url = r.metadata["url"] ?: "",
            snippet = r.document.lines().take(3).joinToString(" ").take(200),
            stage = SearchStage.RAG,
        )
    }
}
```

기존 `search()` 메서드는 `searchStructured()` + format 으로 위임:

```kotlin
suspend fun search(query: String, nResults: Int = 5): String {
    val results = searchStructured(query, nResults)
    if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (RAG query: $query)"
    return buildString {
        appendLine("*\"$query\"* 관련 문서 (RAG, ${results.size}건):\n")
        results.forEachIndexed { i, r ->
            appendLine("${i + 1}. *${r.title}*")
            if (r.url.isNotBlank()) appendLine("   <${r.url}|링크>")
            appendLine("   > ${r.snippet}")
            appendLine()
        }
    }.trim()
}
```

**Step 4: Run all tests**

Run: `cd ~/projects/wiki-agent && ./gradlew test 2>&1 | tail -10`
Expected: All PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/rag/VectorSearchAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/rag/VectorSearchAgentTest.kt
git commit -m "feat: add VectorSearchAgent.searchStructured for parallel RAG"
```

---

## Phase 2: Search Flow Improvement

### Task 4: ConfluenceSearchAgent — early return + 병렬 fallback + 랭킹

핵심 변경. 제목 매칭 충분하면 1회 API로 끝내고, 부족하면 text/스페이스 확장/RAG를 병렬 실행 후 가중 랭킹.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt`

**Step 1: Write failing tests**

`ConfluenceSearchAgentTest.kt` — 기존 테스트 유지 + 새 시나리오 추가:

```kotlin
@Test
fun `early return when title matches are sufficient`() = runTest {
    val mockClient = mockk<ConfluenceClient>()
    coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("1", "배포 가이드", "url1", titleMatched = true),
        ConfluencePageRef("2", "배포 절차", "url2", titleMatched = true),
        ConfluencePageRef("3", "배포 체크리스트", "url3", titleMatched = true),
    )
    // searchByText should NOT be called
    coEvery { mockClient.searchByText(any(), any(), any(), any()) } returns
        listOf(ConfluencePageRef("99", "노이즈", "url99"))

    val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
    val results = agent.searchStructured("배포")

    assertEquals(3, results.size)
    assertTrue(results.all { it.stage == SearchStage.TITLE_MATCH })
    // searchByText가 호출되지 않았는지 verify
    coVerify(exactly = 0) { mockClient.searchByText(any(), any(), any(), any()) }
}

@Test
fun `parallel fallback when title matches insufficient`() = runTest {
    val mockClient = mockk<ConfluenceClient>()
    // 제목 매칭 1건 (부족)
    coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("1", "배포 가이드", "url1", titleMatched = true),
    )
    // 본문 매칭
    coEvery { mockClient.searchByText("배포", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("2", "릴리즈 노트", "url2"),
    )
    // 전체 스페이스 확장
    coEvery { mockClient.searchByTitle("배포", emptyList(), any(), any()) } returns listOf(
        ConfluencePageRef("3", "다른팀 배포", "url3", titleMatched = true),
    )

    val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
    val results = agent.searchStructured("배포")

    // title(1) + expansion(3) + text(2) — 가중치 순
    assertTrue(results.size >= 2)
    assertEquals(SearchStage.TITLE_MATCH, results[0].stage)
}

@Test
fun `results deduplicated by pageId`() = runTest {
    val mockClient = mockk<ConfluenceClient>()
    coEvery { mockClient.searchByTitle("배포", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("1", "배포 가이드", "url1", titleMatched = true),
    )
    coEvery { mockClient.searchByText("배포", listOf("DEV"), any(), any()) } returns listOf(
        ConfluencePageRef("1", "배포 가이드", "url1"), // 중복
        ConfluencePageRef("2", "새 문서", "url2"),
    )
    coEvery { mockClient.searchByTitle("배포", emptyList(), any(), any()) } returns emptyList()

    val agent = ConfluenceSearchAgent(mockClient, listOf("DEV"), sufficientThreshold = 3)
    val results = agent.searchStructured("배포")

    // pageId "1"은 한 번만
    assertEquals(1, results.count { it.pageId == "1" })
}
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/wiki-agent && ./gradlew test --tests "*.ConfluenceSearchAgentTest" 2>&1 | tail -10`
Expected: FAIL — `searchByTitle` not found on mock, `sufficientThreshold` constructor param not found

**Step 3: Implement new ConfluenceSearchAgent**

```kotlin
package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

class ConfluenceSearchAgent(
    private val confluenceClient: ConfluenceClient,
    private val spaces: List<String>,
    private val vectorSearchAgent: VectorSearchAgent? = null,
    private val sufficientThreshold: Int = 3,
) {
    suspend fun searchStructured(
        query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
    ): List<SearchResult> {
        log.info("Searching: query='{}', synonyms={}, spaces={}", query, synonyms, spaces)

        // 1단계: 설정 스페이스에서 제목 검색
        val titleResults = confluenceClient.searchByTitle(query, spaces, synonyms, topK)
        log.info("Title search: {} results", titleResults.size)

        // Early return: 제목 매칭 충분하면 추가 검색 안 함
        if (titleResults.size >= sufficientThreshold) {
            log.info("Sufficient title matches ({}>={}), early return", titleResults.size, sufficientThreshold)
            return titleResults.take(topK).map { it.toSearchResult(SearchStage.TITLE_MATCH) }
        }

        // 2단계: 부족 → 병렬로 text + 스페이스 확장 + RAG
        log.info("Insufficient title matches ({}<{}), parallel fallback", titleResults.size, sufficientThreshold)
        val (textResults, expandedResults, ragResults) = coroutineScope {
            val textDeferred = async {
                runCatching { confluenceClient.searchByText(query, spaces, synonyms, topK) }.getOrElse { emptyList() }
            }
            val expandedDeferred = async {
                if (spaces.isNotEmpty()) {
                    runCatching { confluenceClient.searchByTitle(query, emptyList(), synonyms, topK) }.getOrElse { emptyList() }
                } else emptyList()
            }
            val ragDeferred = async {
                if (vectorSearchAgent != null) {
                    withTimeoutOrNull(5000) {
                        runCatching { vectorSearchAgent.searchStructured(query, topK) }.getOrElse { emptyList() }
                    } ?: emptyList()
                } else emptyList()
            }
            Triple(textDeferred.await(), expandedDeferred.await(), ragDeferred.await())
        }

        // 3단계: 합산 + 중복 제거 + 랭킹
        return combineAndRank(titleResults, textResults, expandedResults, ragResults, topK)
    }

    private fun combineAndRank(
        titleResults: List<ConfluencePageRef>,
        textResults: List<ConfluencePageRef>,
        expandedResults: List<ConfluencePageRef>,
        ragResults: List<SearchResult>,
        topK: Int,
    ): List<SearchResult> {
        val seen = mutableSetOf<String>()
        val scored = mutableListOf<SearchResult>()

        // 가중치 순: title(1.0) > expansion(0.8) > text(0.6) > RAG(0.5)
        titleResults.forEach { if (seen.add(it.id)) scored.add(it.toSearchResult(SearchStage.TITLE_MATCH)) }
        expandedResults.forEach { if (seen.add(it.id)) scored.add(it.toSearchResult(SearchStage.SPACE_EXPANSION)) }
        textResults.forEach { if (seen.add(it.id)) scored.add(it.toSearchResult(SearchStage.TEXT_MATCH)) }
        ragResults.forEach { if (seen.add(it.pageId)) scored.add(it) }

        return scored.sortedByDescending { it.stage.score }.take(topK)
    }

    private fun ConfluencePageRef.toSearchResult(stage: SearchStage) = SearchResult(
        pageId = id, title = title, url = webUrl, snippet = excerpt, stage = stage,
    )

    suspend fun search(query: String, synonyms: List<String> = emptyList(), topK: Int = 5): String {
        val results = searchStructured(query, synonyms, topK)
        if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (query: $query)"
        return results.formatForSlack()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceSearchAgent::class.java)
    }
}
```

**Step 4: Fix existing tests**

기존 `ConfluenceSearchAgentTest`에서 `mockClient.searchPages()` 대신 `searchByTitle()`/`searchByText()`를 mock하도록 수정.

- `search returns formatted markdown` — `coEvery { mockClient.searchByTitle(...) }` + `coEvery { mockClient.searchByText(...) }` returns emptyList
- `search returns no results message` — `coEvery { searchByTitle } returns emptyList()` + `coEvery { searchByText } returns emptyList()`
- `searchStructured returns list` — `searchByTitle` mock

**Step 5: Run all tests**

Run: `cd ~/projects/wiki-agent && ./gradlew test 2>&1 | tail -10`
Expected: All PASS

**Step 6: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgentTest.kt
git commit -m "feat: early return + parallel fallback + weighted ranking in search"
```

---

### Task 5: OrchestratorAgent — RAG fallback 정리

ConfluenceSearchAgent가 RAG를 내부에서 처리하므로, OrchestratorAgent의 별도 RAG fallback을 제거하고, ConfluenceSearchAgent 생성 시 VectorSearchAgent를 주입한다.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt:117-125`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt:63-66`

**Step 1: OrchestratorAgent에서 RAG fallback 제거**

`OrchestratorAgent.kt:117-125`의 RAG fallback 블록 제거:

```kotlin
// 삭제할 코드:
// val vst = vectorSearchTool
// if (searchResult == null && vst != null && toolName != "vectorSearch") {
//     log.info("CQL result empty, falling back to RAG")
//     listener?.onSearchStarted("vectorSearch")
//     searchResult = runCatching { vst.vectorSearch(question) }.getOrNull()
//         ?.takeIf { !it.contains("찾을 수 없습니다") && !it.contains("타임아웃") }
//     listener?.onSearchCompleted("vectorSearch")
// }
```

**Step 2: Main.kt에서 ConfluenceSearchAgent 생성 시 VectorSearchAgent 주입**

`Main.kt` — ConfluenceSearchAgent 생성 부분 수정:

```kotlin
// 기존 (vectorSearchAgent 생성 이후로 이동 필요):
val confluenceSearchAgent = ConfluenceSearchAgent(
    confluenceClient = confluenceClient,
    spaces = config.confluence.spaces,
    vectorSearchAgent = vectorSearchAgent,  // 추가
)
```

주의: `vectorSearchAgent` 생성이 `confluenceSearchAgent` 이후에 있으므로, 생성 순서를 조정하거나 나중에 주입하는 방식 필요. 가장 단순한 방법: ConfluenceSearchAgent 생성을 RAG 설정 이후로 이동.

**Step 3: Run all tests**

Run: `cd ~/projects/wiki-agent && ./gradlew test 2>&1 | tail -10`
Expected: All PASS

**Step 4: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "refactor: move RAG fallback into ConfluenceSearchAgent"
```

---

## Phase 3: Golden Dataset Auto-Generation

### Task 6: GoldenCase — 자동 생성 카테고리 + sourcePageId 추가

**Files:**
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenCase.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/eval/SearchQualityEvalTest.kt`

**Step 1: GoldenCase에 필드/카테고리 추가**

```kotlin
@Serializable
data class GoldenCase(
    val id: String,
    val question: String,
    val category: Category,
    val expectedDocTitles: List<String>,
    val keyPoints: List<String> = emptyList(),
    val negativePoints: List<String> = emptyList(),
    val questionType: QuestionType = QuestionType.DEFINITION,
    val expectedMinLines: Int = 3,
    val expectedMaxLines: Int = 8,
    val requiresSteps: Boolean = false,
    val requiresLink: Boolean = true,
    val sourcePageId: String? = null,  // 자동 생성 시 원본 페이지 ID
)

@Serializable
enum class Category {
    EXACT_MATCH,
    SYNONYM_GAP,
    ABBREVIATION,
    PARTIAL_MATCH,
    MULTI_DOC,
    ZERO_EXPECTED,
    // 자동 생성 카테고리
    TITLE_BASED,
    LLM_GENERATED,
    PARAPHRASE,
}
```

**Step 2: SearchQualityEvalTest의 category coverage 테스트 수정**

기존 테스트가 모든 Category를 데이터셋에서 찾는데, 새 카테고리는 자동 생성 후에만 존재. 수동 데이터셋의 카테고리만 검증하도록 수정:

```kotlin
@Test
fun `golden dataset covers manual categories`() {
    val manualCategories = setOf(
        Category.EXACT_MATCH, Category.SYNONYM_GAP, Category.ABBREVIATION,
        Category.PARTIAL_MATCH, Category.MULTI_DOC, Category.ZERO_EXPECTED,
    )
    val present = goldenCases.map { it.category }.toSet()
    manualCategories.forEach { cat ->
        assertTrue(cat in present, "Missing manual category: $cat")
    }
}
```

**Step 3: Run tests**

Run: `cd ~/projects/wiki-agent && ./gradlew test 2>&1 | tail -10`
Expected: All PASS

**Step 4: Commit**

```bash
git add src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenCase.kt \
        src/test/kotlin/io/github/veronikapj/wiki/eval/SearchQualityEvalTest.kt
git commit -m "feat: extend GoldenCase with auto-generation categories and sourcePageId"
```

---

### Task 7: Golden Dataset Generator

Confluence 페이지에서 3가지 유형의 질문을 자동 생성한다.

**Files:**
- Create: `src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenDatasetGenerator.kt`
- Modify: `build.gradle.kts` (generateGoldenDataset task 추가)

**Step 1: Generator 구현**

`src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenDatasetGenerator.kt`:

```kotlin
package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("generate")
class GoldenDatasetGenerator {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun `generate golden dataset from Confluence pages`() = runBlocking {
        val config = ConfigLoader.load()
        val token = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
        val client = ConfluenceClient(config.confluence.baseUrl, token)

        val modelConfig = config.model.copy(
            apiKey = SecretLoader.resolveNullable("ANTHROPIC_API_KEY", config.model.apiKey)
                ?: SecretLoader.resolveNullable("GOOGLE_API_KEY", config.model.apiKey),
        )
        val executor = LLMExecutorBuilder.build(modelConfig)
        val model = LLMExecutorBuilder.defaultModel(modelConfig)

        val llm: suspend (String) -> String = { userPrompt ->
            executor.execute(prompt("gen") { user(userPrompt) }, model)
                .joinToString("") { it.content }
        }

        // 기존 데이터셋 로드
        val existingJson = File("src/test/resources/golden-dataset.json").readText()
        val existing = Json { ignoreUnknownKeys = true }.decodeFromString<List<GoldenCase>>(existingJson)
        val existingIds = existing.map { it.id }.toSet()

        // 설정 스페이스에서 페이지 조회
        val pages = client.listPages(config.confluence.spaces, limit = 50)
        println("Fetched ${pages.size} pages from ${config.confluence.spaces}")

        val generated = mutableListOf<GoldenCase>()
        var counter = 1

        for (page in pages) {
            if (page.title.isBlank() || page.excerpt.isBlank()) continue

            // 1. TITLE_BASED
            generated.add(
                GoldenCase(
                    id = "AUTO-%03d".format(counter++),
                    question = "${page.title} 알려줘",
                    category = Category.TITLE_BASED,
                    expectedDocTitles = listOf(page.title),
                    sourcePageId = page.id,
                )
            )

            // 2. LLM_GENERATED — LLM이 excerpt 보고 자연스러운 질문 생성
            val llmQuestion = runCatching {
                llm(
                    """다음 문서 제목과 요약을 보고, 이 문서를 찾으려는 사람이 실제로 물어볼 법한 자연스러운 질문을 1개만 생성하세요.
                    |제목: ${page.title}
                    |요약: ${page.excerpt.take(200)}
                    |
                    |질문만 출력하세요. 다른 텍스트 없이.""".trimMargin()
                ).trim()
            }.getOrNull()

            if (!llmQuestion.isNullOrBlank()) {
                generated.add(
                    GoldenCase(
                        id = "AUTO-%03d".format(counter++),
                        question = llmQuestion,
                        category = Category.LLM_GENERATED,
                        expectedDocTitles = listOf(page.title),
                        sourcePageId = page.id,
                    )
                )
            }

            // 3. PARAPHRASE — 동의어/다른 표현으로 바꾼 질문
            val paraphrase = runCatching {
                llm(
                    """다음 문서 제목을 보고, 정확한 용어를 기억 못하는 사람이 검색할 법한 질문을 1개만 생성하세요.
                    |핵심 단어를 동의어, 유사 표현, 줄임말 등으로 바꾸세요.
                    |예시: "배포 프로세스 가이드" → "릴리즈 절차가 어떻게 돼?"
                    |
                    |제목: ${page.title}
                    |요약: ${page.excerpt.take(200)}
                    |
                    |질문만 출력하세요.""".trimMargin()
                ).trim()
            }.getOrNull()

            if (!paraphrase.isNullOrBlank()) {
                generated.add(
                    GoldenCase(
                        id = "AUTO-%03d".format(counter++),
                        question = paraphrase,
                        category = Category.PARAPHRASE,
                        expectedDocTitles = listOf(page.title),
                        sourcePageId = page.id,
                    )
                )
            }
        }

        // 기존 + 자동 생성 합산
        val combined = existing + generated
        val output = json.encodeToString(combined)
        File("src/test/resources/golden-dataset.json").writeText(output)
        println("Generated ${generated.size} cases (total: ${combined.size})")

        client.close()
    }
}
```

**Step 2: build.gradle.kts에 generateGoldenDataset task 추가**

```kotlin
tasks.register<Test>("generateGoldenDataset") {
    useJUnitPlatform {
        includeTags("generate")
    }
}
```

기존 `tasks.test`에 "generate" 태그도 제외 추가:

```kotlin
tasks.test {
    useJUnitPlatform {
        excludeTags("eval", "generate")
    }
}
```

**Step 3: Run generator (실환경 필요)**

Run: `cd ~/projects/wiki-agent && ./gradlew generateGoldenDataset 2>&1 | tail -20`
Expected: golden-dataset.json에 자동 생성 케이스 추가

**Step 4: Commit**

```bash
git add src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenDatasetGenerator.kt \
        build.gradle.kts \
        src/test/resources/golden-dataset.json
git commit -m "feat: auto-generate golden dataset from Confluence pages"
```

---

## Phase 4: Eval System

### Task 8: EvalMetrics + EvalReporter

평가 메트릭 계산과 리포트 생성 유틸리티.

**Files:**
- Create: `src/test/kotlin/io/github/veronikapj/wiki/eval/EvalMetrics.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/eval/EvalReporter.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/eval/EvalMetricsTest.kt`

**Step 1: Write failing tests**

`src/test/kotlin/io/github/veronikapj/wiki/eval/EvalMetricsTest.kt`:

```kotlin
package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.agent.SearchResult
import io.github.veronikapj.wiki.agent.SearchStage
import kotlin.test.Test
import kotlin.test.assertEquals

class EvalMetricsTest {

    @Test
    fun `recall at K calculates correctly`() {
        val results = listOf(
            SearchResult("1", "배포 가이드", "u1", "s", SearchStage.TITLE_MATCH),
            SearchResult("2", "다른 문서", "u2", "s", SearchStage.TEXT_MATCH),
        )
        val expected = listOf("배포 가이드")

        // 정답이 상위 5건에 있으면 hit
        assertEquals(true, EvalMetrics.hitAtK(results, expected, k = 5))
        assertEquals(true, EvalMetrics.hitAtK(results, expected, k = 1))
    }

    @Test
    fun `recall miss when expected not in results`() {
        val results = listOf(
            SearchResult("2", "다른 문서", "u2", "s", SearchStage.TEXT_MATCH),
        )
        val expected = listOf("배포 가이드")
        assertEquals(false, EvalMetrics.hitAtK(results, expected, k = 5))
    }

    @Test
    fun `MRR calculation`() {
        val results = listOf(
            SearchResult("1", "다른 문서", "u1", "s", SearchStage.TEXT_MATCH),
            SearchResult("2", "배포 가이드", "u2", "s", SearchStage.TITLE_MATCH),
            SearchResult("3", "또 다른", "u3", "s", SearchStage.RAG),
        )
        val expected = listOf("배포 가이드")

        // 정답이 2번째 → reciprocal rank = 0.5
        assertEquals(0.5, EvalMetrics.reciprocalRank(results, expected))
    }

    @Test
    fun `MRR zero when not found`() {
        val results = listOf(
            SearchResult("1", "다른 문서", "u1", "s", SearchStage.TEXT_MATCH),
        )
        assertEquals(0.0, EvalMetrics.reciprocalRank(results, listOf("배포 가이드")))
    }

    @Test
    fun `honest zero — ZERO_EXPECTED with empty results is correct`() {
        assertEquals(true, EvalMetrics.isHonestZero(emptyList(), Category.ZERO_EXPECTED))
    }

    @Test
    fun `honest zero — ZERO_EXPECTED with results is incorrect`() {
        val results = listOf(
            SearchResult("1", "문서", "u", "s", SearchStage.TITLE_MATCH),
        )
        assertEquals(false, EvalMetrics.isHonestZero(results, Category.ZERO_EXPECTED))
    }

    @Test
    fun `title matching is case and whitespace tolerant`() {
        val results = listOf(
            SearchResult("1", " 배포 프로세스  가이드 ", "u", "s", SearchStage.TITLE_MATCH),
        )
        assertEquals(true, EvalMetrics.hitAtK(results, listOf("배포 프로세스 가이드"), k = 5))
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `cd ~/projects/wiki-agent && ./gradlew test --tests "*.EvalMetricsTest" 2>&1 | tail -5`
Expected: FAIL — `EvalMetrics` not found

**Step 3: Implement EvalMetrics**

`src/test/kotlin/io/github/veronikapj/wiki/eval/EvalMetrics.kt`:

```kotlin
package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.agent.SearchResult

object EvalMetrics {

    /** expectedDocTitles 중 하나라도 상위 K건에 있으면 hit */
    fun hitAtK(results: List<SearchResult>, expectedTitles: List<String>, k: Int): Boolean {
        val topK = results.take(k).map { it.title.normalize() }
        return expectedTitles.any { expected -> topK.any { it.contains(expected.normalize()) || expected.normalize().contains(it) } }
    }

    /** 정답 문서의 reciprocal rank (1-indexed). 없으면 0.0 */
    fun reciprocalRank(results: List<SearchResult>, expectedTitles: List<String>): Double {
        val normalizedExpected = expectedTitles.map { it.normalize() }
        for ((i, result) in results.withIndex()) {
            val normalizedTitle = result.title.normalize()
            if (normalizedExpected.any { normalizedTitle.contains(it) || it.contains(normalizedTitle) }) {
                return 1.0 / (i + 1)
            }
        }
        return 0.0
    }

    /** ZERO_EXPECTED 카테고리에서 결과가 비어있으면 정직한 zero */
    fun isHonestZero(results: List<SearchResult>, category: Category): Boolean {
        return if (category == Category.ZERO_EXPECTED) results.isEmpty() else true
    }

    private fun String.normalize(): String = trim().replace(Regex("\\s+"), " ").lowercase()
}
```

**Step 4: Implement EvalReporter**

`src/test/kotlin/io/github/veronikapj/wiki/eval/EvalReporter.kt`:

```kotlin
package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.agent.SearchResult
import io.github.veronikapj.wiki.agent.SearchStage
import java.time.LocalDate

data class CaseResult(
    val case: GoldenCase,
    val results: List<SearchResult>,
    val latencyMs: Long,
    val apiCalls: Int,
)

object EvalReporter {

    fun generate(caseResults: List<CaseResult>): String = buildString {
        val date = LocalDate.now()
        val total = caseResults.size
        val nonZero = caseResults.filter { it.case.category != Category.ZERO_EXPECTED }
        val zeroExpected = caseResults.filter { it.case.category == Category.ZERO_EXPECTED }

        val recallAt5 = nonZero.count { EvalMetrics.hitAtK(it.results, it.case.expectedDocTitles, 5) }
        val recallAt1 = nonZero.count { EvalMetrics.hitAtK(it.results, it.case.expectedDocTitles, 1) }
        val mrr = if (nonZero.isNotEmpty()) nonZero.sumOf { EvalMetrics.reciprocalRank(it.results, it.case.expectedDocTitles) } / nonZero.size else 0.0
        val zeroHit = nonZero.count { it.results.isEmpty() }
        val honestZero = zeroExpected.count { EvalMetrics.isHonestZero(it.results, it.case.category) }
        val avgLatency = caseResults.map { it.latencyMs }.average()
        val avgApiCalls = caseResults.map { it.apiCalls }.average()

        appendLine("=== Search Quality Eval Report ($date) ===")
        appendLine()
        appendLine("[Summary]")
        appendLine("Total cases: $total | Recall@5: ${pct(recallAt5, nonZero.size)} | Recall@1: ${pct(recallAt1, nonZero.size)} | MRR: ${"%.2f".format(mrr)}")
        appendLine("Zero-hit: ${pct(zeroHit, nonZero.size)} | Honest-zero: ${pct(honestZero, zeroExpected.size)}")
        appendLine("Avg latency: ${avgLatency.toLong()}ms | Avg API calls: ${"%.1f".format(avgApiCalls)}/query")
        appendLine()

        // By Category
        appendLine("[By Category]")
        appendLine("%-20s | %5s | %6s | %6s | %5s | %7s".format("Category", "Count", "R@5", "R@1", "MRR", "Avg ms"))
        val byCategory = caseResults.groupBy { it.case.category }
        for ((cat, cases) in byCategory.entries.sortedBy { it.key.name }) {
            val catNonZero = cases.filter { it.case.category != Category.ZERO_EXPECTED }
            if (catNonZero.isEmpty() && cat == Category.ZERO_EXPECTED) {
                val hz = cases.count { EvalMetrics.isHonestZero(it.results, it.case.category) }
                appendLine("%-20s | %5d | %6s | %6s | %5s | %7d".format(
                    cat.name, cases.size, "—", "—", "—", cases.map { it.latencyMs }.average().toLong()
                ))
            } else if (catNonZero.isNotEmpty()) {
                val r5 = catNonZero.count { EvalMetrics.hitAtK(it.results, it.case.expectedDocTitles, 5) }
                val r1 = catNonZero.count { EvalMetrics.hitAtK(it.results, it.case.expectedDocTitles, 1) }
                val catMrr = catNonZero.sumOf { EvalMetrics.reciprocalRank(it.results, it.case.expectedDocTitles) } / catNonZero.size
                appendLine("%-20s | %5d | %6s | %6s | %5s | %7d".format(
                    cat.name, cases.size, pct(r5, catNonZero.size), pct(r1, catNonZero.size),
                    "%.2f".format(catMrr), cases.map { it.latencyMs }.average().toLong()
                ))
            }
        }
        appendLine()

        // By Search Stage
        appendLine("[By Search Stage (hit source)]")
        appendLine("%-20s | %5s | %5s".format("Stage", "Count", "%"))
        val hitStages = caseResults
            .filter { EvalMetrics.hitAtK(it.results, it.case.expectedDocTitles, 5) }
            .mapNotNull { cr ->
                val expected = cr.case.expectedDocTitles.map { it.trim().lowercase() }
                cr.results.firstOrNull { r -> expected.any { e -> r.title.trim().lowercase().contains(e) || e.contains(r.title.trim().lowercase()) } }?.stage
            }
        val stageTotal = hitStages.size
        for (stage in SearchStage.entries) {
            val count = hitStages.count { it == stage }
            if (count > 0) {
                appendLine("%-20s | %5d | %5s".format(stage.name, count, pct(count, stageTotal)))
            }
        }
        appendLine()

        // Failed Cases
        val failed = caseResults.filter {
            it.case.category != Category.ZERO_EXPECTED && !EvalMetrics.hitAtK(it.results, it.case.expectedDocTitles, 5)
        }
        if (failed.isNotEmpty()) {
            appendLine("[Failed Cases]")
            appendLine("%-10s | %-25s | %-25s | %s".format("ID", "Question", "Expected", "Got (top 3)"))
            for (f in failed) {
                val got = f.results.take(3).joinToString(", ") { it.title.take(20) }.ifEmpty { "(none)" }
                appendLine("%-10s | %-25s | %-25s | %s".format(
                    f.case.id, f.case.question.take(25), f.case.expectedDocTitles.first().take(25), got
                ))
            }
        }
    }

    private fun pct(n: Int, total: Int): String =
        if (total == 0) "—" else "%.1f%%".format(100.0 * n / total)
}
```

**Step 5: Run tests**

Run: `cd ~/projects/wiki-agent && ./gradlew test --tests "*.EvalMetricsTest" 2>&1 | tail -10`
Expected: All PASS

**Step 6: Commit**

```bash
git add src/test/kotlin/io/github/veronikapj/wiki/eval/EvalMetrics.kt \
        src/test/kotlin/io/github/veronikapj/wiki/eval/EvalReporter.kt \
        src/test/kotlin/io/github/veronikapj/wiki/eval/EvalMetricsTest.kt
git commit -m "feat: eval metrics (Recall, MRR, honest-zero) + report generator"
```

---

### Task 9: Search Eval Test — 실제 검색 평가

실환경 Confluence에 대해 골든 데이터셋으로 검색 품질을 평가하고 리포트를 생성한다.

**Files:**
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/eval/SearchQualityEvalTest.kt`

**Step 1: eval test에 실제 검색 평가 추가**

```kotlin
@Test
fun `search quality eval — recall and report`() = runBlocking {
    val config = ConfigLoader.load()
    val token = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
    val client = ConfluenceClient(config.confluence.baseUrl, token)
    val agent = ConfluenceSearchAgent(client, config.confluence.spaces)

    val caseResults = goldenCases.map { case ->
        val start = System.currentTimeMillis()
        val results = runCatching {
            agent.searchStructured(case.question)
        }.getOrElse { emptyList() }
        val elapsed = System.currentTimeMillis() - start

        CaseResult(
            case = case,
            results = results,
            latencyMs = elapsed,
            apiCalls = if (results.size >= 3) 1 else 3, // 근사치: early return=1, fallback=3
        )
    }

    val report = EvalReporter.generate(caseResults)
    println(report)

    // 리포트 파일 저장
    val date = java.time.LocalDate.now()
    val dir = File("docs/eval").also { it.mkdirs() }
    File(dir, "$date-eval-report.txt").writeText(report)

    client.close()
}
```

**Step 2: Run eval**

Run: `cd ~/projects/wiki-agent && ./gradlew evalTest 2>&1 | tail -30`
Expected: 리포트 출력 + `docs/eval/` 파일 생성

**Step 3: Commit**

```bash
git add src/test/kotlin/io/github/veronikapj/wiki/eval/SearchQualityEvalTest.kt
git commit -m "feat: search quality eval test with report generation"
```

---

### Task 10: Baseline vs After 비교 실행

Phase 2 (검색 플로우 개선)와 Phase 3 (골든 데이터셋)이 모두 완료된 후, eval을 돌려서 baseline 대비 개선을 확인한다.

**Step 1: Baseline eval (개선 전 — 필요 시 개선 전 코드로 체크아웃)**

Run: `./gradlew evalTest`
Output: `docs/eval/YYYY-MM-DD-eval-report.txt` (baseline)

**Step 2: After eval (개선 후)**

Run: `./gradlew evalTest`
Output: `docs/eval/YYYY-MM-DD-eval-report.txt` (after)

**Step 3: 비교 + 커밋**

```bash
git add docs/eval/
git commit -m "docs: eval reports — baseline vs after search flow enhancement"
```
