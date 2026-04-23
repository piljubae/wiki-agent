# 검색 품질 개선 설계

## 개요

CQL 검색의 누락(recall)과 노이즈(precision)를 동시에 개선한다.
핵심 전략: **LLM 동의어를 CQL에 실제 반영 + RAG fallback + 골든 데이터셋 기반 품질 측정**.

## 현재 문제

| 문제 | 원인 | 영향 |
|------|------|------|
| SYNONYMS가 CQL에 미반영 | `executeFromDecision`에서 동의어별 개별 API 호출 | N+1 호출, 느림, 중복 |
| 2자 미만 단어 필터링 | `filter { it.length >= 2 }` | "UI", "QA", "앱" 검색 불가 |
| CQL injection | `"` 만 이스케이프 | CQL 예약어 주입 가능 |
| 검색 실패 시 할루시네이션 | "알고 있는 내용으로 답변하세요" 프롬프트 | 거짓 답변 |
| 검색 품질 테스트 부재 | 품질 메트릭 없음 | 회귀 감지 불가 |
| 검색 결과 비구조화 | 포맷된 문자열만 반환 | 자동 평가 불가 |

## 설계

### 1. SYNONYMS → 단일 CQL OR 절

현재: 동의어 N개 → N회 API 호출 → distinct 합산
변경: 동의어를 CQL OR 절에 합쳐 **1회 호출**

```
(title ~ "원본쿼리" OR text ~ "원본쿼리" OR text ~ "동의어1" OR text ~ "동의어2")
AND space IN ("ProductApp")
```

- OR 절 상한: **5개** (Confluence API 500 에러 방지)
- `allTerms = (listOf(query) + synonyms).take(5)`

**변경 파일:**
- `ConfluenceClient.kt` — `buildCqlSearchUrl`에 `synonyms: List<String>` 파라미터 추가
- `ConfluenceSearchAgent.kt` — `search`에 synonyms 전달
- `ConfluenceTool.kt` — tool 시그니처에 synonyms 추가 (Koog agent 모드)
- `OrchestratorAgent.kt` — `executeFromDecision`에서 N회 호출 → 단일 호출로 변경

### 2. Stopword 기반 필터링

현재: `words.filter { it.length >= 2 }`
변경: `words.filter { it.length >= 1 && it !in STOPWORDS }`

```kotlin
private val STOPWORDS = setOf("의", "를", "은", "는", "이", "가", "에", "도", "로", "와", "과", "을")
```

**변경 파일:** `ConfluenceClient.kt`

### 3. CQL 특수문자 이스케이프

현재: `query.replace("\"", "\\\"")`
변경: CQL 예약어/특수문자 전체 이스케이프

```kotlin
private fun escapeCql(input: String): String {
    return input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("(", "\\(")
        .replace(")", "\\)")
}
```

CQL 연산자(`AND`, `OR`, `NOT`)가 사용자 입력에 포함된 경우 따옴표로 감싸져 있으므로 CQL 파서가 연산자로 해석하지 않음. 괄호만 추가 이스케이프.

**변경 파일:** `ConfluenceClient.kt`

### 4. SearchResult data class 도입

현재: `ConfluenceSearchAgent.search()` → 포맷된 String 반환
변경: 구조화된 결과 반환 + 포맷은 별도 함수

```kotlin
data class SearchResult(
    val pageId: String,
    val title: String,
    val url: String,
    val snippet: String,
    val source: Source,  // CQL, RAG
)

enum class Source { CQL, RAG }
```

- `search()` → `List<SearchResult>` 반환
- `formatForSlack(results: List<SearchResult>)` → String (기존 포맷 로직)
- Tool 호출 시 `formatForSlack(search(...))` 로 조합

**변경 파일:**
- 신규: `SearchResult.kt`
- `ConfluenceSearchAgent.kt` — 반환 타입 변경
- `ConfluenceTool.kt` — format 호출 추가

### 5. RAG fallback + graceful degradation

CQL 결과 부족 시(3건 미만) RAG 벡터 검색으로 보충.

- CQL 결과 우선 배치, RAG 결과 append
- pageId 기준 중복 제거
- RAG 타임아웃: **5초**
- ChromaDB 장애 시 CQL 결과만으로 응답 (에러 로그만 남김)

**변경 파일:**
- `OrchestratorAgent.kt` — Manual loop에서 CQL 결과 부족 시 vectorSearch fallback 추가
- `VectorSearchTool.kt` — 타임아웃 설정, `withTimeoutOrNull` 적용
- `.wikiq/config.yml` — `rag.enabled: true`

### 6. 검색 실패 시 할루시네이션 방지

현재 프롬프트: "검색 결과가 없으면 알고 있는 내용으로 답변하세요"
변경: "검색 결과가 없으면 '관련 문서를 찾지 못했습니다'라고 답변하고, 검색한 스페이스 목록을 안내하세요"

**변경 파일:** `OrchestratorAgent.kt` — summaryPrompt 수정

### 7. 골든 데이터셋 + 평가 테스트

```kotlin
// src/test/resources/golden-dataset.json
[
  {
    "id": "GC-001",
    "question": "배포 가이드",
    "category": "EXACT_MATCH",
    "expectedDocTitles": ["배포 프로세스 가이드"],
    "keyPoints": ["배포 절차", "승인 프로세스"],
    "negativePoints": []
  }
]
```

카테고리:
- `EXACT_MATCH` — 제목 정확 매칭
- `SYNONYM_GAP` — 용어 갭 ("신입" vs "신규 입사자")
- `ABBREVIATION` — 약어 ("PR" → "Pull Request")
- `PARTIAL_MATCH` — 부분 키워드 매칭
- `MULTI_DOC` — 여러 문서 종합
- `ZERO_EXPECTED` — 관련 문서 없음

카테고리별 3-5건, 총 20-30건으로 시작. `SYNONYM_GAP`과 `ABBREVIATION` 비중 높게.

```kotlin
@Tag("eval")
class SearchQualityEvalTest {
    @ParameterizedTest
    @MethodSource("goldenCases")
    fun `recall at 5`(case: GoldenCase) { ... }
}
```

- `./gradlew test -PincludeTags=eval`로 별도 실행
- 일반 CI에서 제외

**신규 파일:**
- `src/test/resources/golden-dataset.json`
- `src/test/kotlin/.../eval/SearchQualityEvalTest.kt`
- `src/test/kotlin/.../eval/GoldenCase.kt`

### 8. Slack 리액션 피드백 수집

답변 메시지에 안내: "도움이 됐다면 :thumbsup:, 아니라면 :thumbsdown:"

`reactionAdded` 이벤트 핸들러로 로깅:
```
{timestamp, question, answer, reaction, userId}
```

thumbsdown 케이스를 골든 데이터셋 후보로 축적.

**변경 파일:** `SlackBotGateway.kt` — 리액션 핸들러 추가, 답변 메시지에 안내 텍스트 추가

## 컴포넌트 변경 요약

| 파일 | 변경 |
|------|------|
| `ConfluenceClient.kt` | CQL OR절 합치기, stopword 필터, 특수문자 이스케이프 |
| `ConfluenceSearchAgent.kt` | `List<SearchResult>` 반환, synonyms 전달 |
| `ConfluenceTool.kt` | synonyms 파라미터, format 분리 |
| `OrchestratorAgent.kt` | 단일 CQL 호출, RAG fallback, 할루시네이션 방지 프롬프트 |
| `VectorSearchTool.kt` | 타임아웃 설정 |
| `SlackBotGateway.kt` | 리액션 피드백 수집 |
| 신규 `SearchResult.kt` | data class |
| 신규 `golden-dataset.json` | 골든 데이터셋 |
| 신규 `SearchQualityEvalTest.kt` | 평가 테스트 |
| 신규 `GoldenCase.kt` | 테스트 모델 |

## 검증 메트릭

| 메트릭 | 목표 | 측정 방법 |
|--------|------|----------|
| Recall@5 | >= 0.6 | 골든 데이터셋 expectedDocTitles 매칭 |
| Zero-hit rate | < 10% | ZERO_EXPECTED 외 케이스에서 결과 0건 비율 |
| 응답 시간 | < 15초 | `measureTimeMillis` |
| CQL 호출 횟수 | 1회/질문 | 로그 카운트 |
| 출처 유효성 | 100% | URL HEAD 요청 |
