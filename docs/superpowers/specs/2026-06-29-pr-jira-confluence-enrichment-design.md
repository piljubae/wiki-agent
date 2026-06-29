# PR 검색 → Jira 티켓 → Confluence 기획서 보강 설계

- 날짜: 2026-06-29
- 대상: 신규 `:jira` 모듈, `:search`(PrHistoryTool), `Main.kt` 배선
- 적용 범위: `prHistory` 도구 한 곳 보강 → 일반 PR 검색·`prHistory+codeSearch`·온보딩 Tier2(prContent) 자동 커버

## 배경 / 목표

`prHistory` 검색은 현재 PR 메타데이터 + 문서 3줄 스니펫만 반환한다. 사용자는 PR에 연결된 Jira 티켓(본문·댓글)과, 그 티켓에 연결된 Confluence 기획서까지 읽어 답변에 정리해주길 원한다.

**체인:** `prHistory(query)` → 반환된 상위 PR들에서 Jira 키 추출 → 상위 3개 티켓 fetch(필드+본문+최근 댓글) → 각 티켓의 대표 Confluence 페이지 1개 fetch(발췌) → PR 결과 문자열 뒤에 보강 블록 추가.

**query-time 보강**: PR 인덱싱(ChromaDB)은 건드리지 않는다. 항상 최신, 반환된 PR에 대해서만 fetch.

비목표(YAGNI): PR 재인덱싱, 별도 `jira:` config 섹션(Confluence 설정 재사용), Jira 캐싱(TTL), generateGuide 변경.

## 결정된 파라미터

- 티켓 읽기 깊이: 핵심 필드(summary/status/type/assignee) + description 본문 + **최근 댓글 3개**.
- 보강 티켓 수: 반환 PR들의 고유 Jira 키 **상위 3개**.
- Confluence: **티켓당 대표 페이지 1개**, 본문 **발췌(truncate)**. (최대 3페이지)
- 인증: Confluence와 같은 Atlassian 인스턴스(`config.confluence.baseUrl`) + 토큰. env `JIRA_TOKEN`으로 오버라이드 가능, 없으면 `CONFLUENCE_TOKEN` 재사용.

## 설계

### 1. 신규 `:jira` 모듈 + `JiraClient`

`jira/build.gradle.kts`는 `:confluence`를 미러(ktor-client-cio, kotlinx-serialization-json, slf4j, junit, mockk, coroutines). `settings.gradle.kts`에 `include(":jira")` 추가. `:jira`는 다른 프로젝트 모듈에 의존하지 않는다(순수 Jira REST).

데이터 모델:
```kotlin
data class JiraConfluenceRef(val pageId: String, val title: String, val url: String)
data class JiraIssue(
    val key: String,
    val summary: String,
    val status: String,
    val type: String,
    val assignee: String,        // 없으면 "" (미할당)
    val description: String,     // plain text (truncate 전)
    val recentComments: List<String>,   // 최근 3개, 작성자/본문
    val confluenceRefs: List<JiraConfluenceRef>,
)

class JiraClient(private val baseUrl: String, private val token: String) {
    suspend fun getIssue(key: String): JiraIssue?   // 실패 시 null
}
```

REST 호출 (Jira Cloud v2 — description/comment가 문자열이라 ADF 파싱 불필요):
- 이슈: `GET {baseUrl}/rest/api/2/issue/{key}?fields=summary,status,issuetype,assignee,description`
  - `fields.summary`(String), `fields.status.name`, `fields.issuetype.name`, `fields.assignee.displayName`(nullable), `fields.description`(String, wiki markup — 그대로 사용, null 가능)
- 댓글: `GET {baseUrl}/rest/api/2/issue/{key}/comment?orderBy=-created&maxResults=3`
  - `comments[].author.displayName` + `comments[].body`(String) → `"작성자: 본문"` 형태
- 원격 링크: `GET {baseUrl}/rest/api/2/issue/{key}/remotelinks`
  - 배열의 `object.url`/`object.title`. url에 `/wiki/`가 포함된 항목만.
- 인증: `header("Authorization", "Basic $token")`, `Accept: application/json`. HttpTimeout는 ConfluenceClient와 동일.

Confluence ref 추출:
- description 문자열에서 정규식 `/wiki/[^\s)\]]*?/pages/(\d+)` 로 pageId 수집 (title은 빈 문자열로).
- remotelinks의 `/wiki/` url에서 같은 정규식으로 pageId + title.
- 합쳐서 pageId 기준 dedup. `confluenceRefs`로 노출(순서 보존 = 대표는 첫 번째).

### 2. PrHistoryTool 보강 (`:search`)

생성자에 추가(둘 다 nullable, 기본 null):
```kotlin
class PrHistoryTool(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val tracker: SourceTracker? = null,
    private val collectionName: String = "code_prs",
    private val embeddingFn: (suspend (String) -> List<Float>)? = null,
    private val jiraClient: JiraClient? = null,
    private val confluenceClient: ConfluenceClient? = null,
)
```

`prHistory`의 기존 `buildString { ... }.trim()` 결과(PR 목록)를 만든 뒤, `jiraClient != null`이면 보강 블록을 append:

1. **키 추출**: 위에서 만든 `results`(최대 5 PR)에서, 각 PR의 `metadata["ticket"]` + `r.document`에 정규식 `[A-Z]+-\d+` 적용 → 전체 union → 등장 순서 유지 dedup → 상위 3개. 0개면 보강 없음(기존 결과 그대로).
2. **티켓 fetch (병렬)**: `runBlocking` 내 `async`로 3개 `jiraClient.getIssue(key)`. null(실패)은 제외.
3. **Confluence fetch**: 각 티켓의 `confluenceRefs.firstOrNull()`이 있고 `confluenceClient != null`이면 `confluenceClient.fetchPageRawHtml(pageId)` → `htmlToExcerpt()`(태그 제거 + 엔티티 디코딩 + `take(PAGE_EXCERPT)`). 실패/없음이면 페이지 생략.
4. **출력 append**:
```
=== 🎫 연결된 Jira 티켓 ===

🎫 {key} ({type}, {status}{assignee 있으면 ", 담당: {assignee}"})
요약: {summary}
내용: {description.take(DESC_EXCERPT)}
최근 코멘트:
- {comment1.take(COMMENT_EXCERPT)}
- {comment2 ...}
📄 기획서: {page.title 또는 키} ({url})
   {pageExcerpt}
```
- 티켓 블록 사이 빈 줄. description/comment/page 비면 해당 줄 생략.

상수(프롬프트 비대화 방지): `MAX_TICKETS=3`, `MAX_COMMENTS=3`, `DESC_EXCERPT=600`, `COMMENT_EXCERPT=200`, `PAGE_EXCERPT=800`.

`htmlToExcerpt(html)`: PrHistoryTool 내 private. `<…>` 제거 + `&lt;`/`&gt;`/`&amp;`/`&quot;`/`&#39;`/`&nbsp;` 디코딩 + 공백 정리 + `take(PAGE_EXCERPT)`. (온보딩 ContentGatherer와 동일 아이디어, 모듈 분리상 소규모 재구현)

### 3. Graceful degradation

- `jiraClient == null`(미설정) → 보강 전체 생략, PR 결과만.
- 키 0개 → 생략.
- `getIssue` 예외/404 → 해당 티켓 제외(로그 warn), 나머지 진행.
- `confluenceClient == null` 또는 페이지 fetch 실패 → 기획서 줄만 생략, 티켓 정보는 유지.
- 보강 전체를 `runCatching`으로 감싸 어떤 실패도 PR 결과 반환을 막지 않는다.

### 4. 배선 (`Main.kt`)

- `:search`/`build.gradle.kts`에 `api(project(":jira"))`.
- `Main.kt`: Confluence 토큰 로드 직후
  ```kotlin
  val jiraToken = SecretLoader.resolveNullable("JIRA_TOKEN", null) ?: confluenceToken
  var jiraClient: JiraClient? = null
  if (config.confluence.baseUrl.isNotBlank() && jiraToken.isNotBlank()) {
      jiraClient = JiraClient(baseUrl = config.confluence.baseUrl, token = jiraToken)
      log.info("Jira enabled: baseUrl={}", config.confluence.baseUrl)
  }
  ```
- `PrHistoryTool(...)` 생성에 `jiraClient = jiraClient, confluenceClient = confluenceClient` 추가.

## 데이터 흐름

```
prHistory(query)
  → ChromaDB 검색 → results(≤5 PR)  [기존]
  → PR 목록 문자열 빌드            [기존]
  → jiraClient != null?
      → 키 추출(ticket 메타 + document 정규식) → union → 상위 3
      → parallel getIssue(key)  (null 제외)
      → 각 티켓: confluenceRefs.first → confluenceClient.fetchPageRawHtml → htmlToExcerpt
      → "=== 🎫 연결된 Jira 티켓 ===" 블록 append
  → 최종 문자열 반환
```

## 테스트 (TDD)

**`:jira` — JiraClientTest** (MockEngine로 ktor 응답 주입):
- 이슈 JSON 파싱: summary/status/type/assignee/description 매핑, assignee null → "".
- 댓글 JSON 파싱: 최근 3개 author+body.
- description 내 `/wiki/.../pages/123/` → confluenceRefs에 pageId 123.
- remotelinks의 `/wiki/.../pages/456` url → ref 추가, pageId dedup.
- 404/에러 → getIssue null.

**`:search` — PrHistoryToolTest** (chromaClient/jiraClient/confluenceClient mockk):
- 반환 PR document/ticket에서 키 추출 → getIssue 호출(상위 3, 중복 제거), 결과에 "🎫 {key}" + summary 포함.
- 티켓에 confluenceRef 있으면 confluenceClient.fetchPageRawHtml 호출 + "📄 기획서" 발췌 포함.
- `jiraClient = null`이면 보강 블록 없음(기존 PR 결과만).
- getIssue가 예외/ null이면 해당 티켓 생략하고 PR 결과는 정상 반환.
- confluence fetch 실패 → 기획서 줄 생략, 티켓 정보 유지.

## 리스크 / 메모

- Jira Cloud v2 description은 wiki markup 문자열(매크로 토큰 일부 포함 가능) — 발췌라 허용. ADF(v3) 파싱은 비목표.
- `htmlToExcerpt`가 온보딩 ContentGatherer의 HTML 처리와 중복 → 추후 공용 유틸로 추출 가능(현재는 모듈 경계상 소규모 재구현, [[project_modularization_roadmap]] 후보).
- `.claude/worktrees/feat+onboarding-content-enhancement-2/` 병행 작업과 파일 충돌 없음(PrHistoryTool·신규 모듈 중심).
