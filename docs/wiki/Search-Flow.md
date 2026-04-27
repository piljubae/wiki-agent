# 검색 플로우 (ConfluenceSearchAgent)

## 핵심 질문

> Confluence 검색이 내부적으로 어떤 단계를 거치고, 결과는 어떻게 합산되나요?

## 개요

`ConfluenceSearchAgent`는 단순한 CQL 래퍼가 아닙니다. 질문 전처리부터 결과 랭킹까지 3단계 파이프라인으로 동작합니다.

```
질문 입력
    │
    ▼
1. cleanQuery()         대화형 접미사 제거 + CQL 특수문자 정리
    │
    ▼
2. 1단계: title 검색    설정 스페이스 내 제목 매칭
    │
    ├── ≥ sufficientThreshold(3)?  ──► 조기 반환 (API 1회)
    │
    └── < 3건  ──► 병렬 실행 (2단계)
                        ├── text 검색 (설정 스페이스 본문)
                        ├── 전체 스페이스 제목 확장
                        └── RAG fallback (5초 타임아웃)
    │
    ▼
3단계: combineAndRank()
    pageId 중복 제거 → SearchStage 가중치 정렬 → topK 반환
```

## 1단계: 쿼리 전처리 (cleanQuery)

CQL은 특수문자에 민감합니다. 사용자 입력을 그대로 넘기면 API 오류가 납니다.

```kotlin
internal fun cleanQuery(query: String): String {
    // 1. 대화형 접미사 제거
    for (s in SUFFIXES) {
        if (q.endsWith(s)) { q = q.removeSuffix(s).trimEnd(); break }
    }
    // 2. CQL을 깨뜨리는 특수문자 → 공백
    q = q.replace(Regex("[\\[\\]|~{}()]"), " ")
    // 3. 언더스코어 → 공백 (제목 구분자로 쓰임)
    q = q.replace('_', ' ')
    // 4. 연속 공백 정리
    q = q.replace(Regex("\\s+"), " ").trim()
    return q.ifBlank { query.trim() }
}
```

**제거되는 접미사 예시:**  
`"알려줘"`, `"알려주세요"`, `"어디서 봐?"`, `"어디 있어?"`, `"어떻게 돼?"`, `"뭐야?"`, `"찾아줘"`, `"보여줘"`, `"설명해줘"`, `"정리해줘"`

**효과:**

| 사용자 입력 | cleanQuery 결과 |
|------------|----------------|
| `iOS Daily - 26.04.27 알려줘` | `iOS Daily - 26.04.27` |
| `[XP/핀테크트라이브]주간보고_260417 알려줘` | `XP 핀테크트라이브 주간보고 260417` |
| `배포 프로세스 어디서 봐?` | `배포 프로세스` |

## 2단계: 조기 반환 (Early Return)

제목 검색 결과가 `sufficientThreshold`(기본 3) 이상이면 추가 검색 없이 즉시 반환합니다.

```kotlin
if (titleResults.size >= sufficientThreshold) {
    return titleResults.take(topK).map { it.toSearchResult(SearchStage.TITLE_MATCH) }
}
```

제목에서 바로 찾히는 질문은 대부분 정확도가 높습니다. 추가 검색으로 노이즈를 늘리는 것보다 빠르게 반환하는 게 낫습니다.

**API 호출 수 비교:**

| 경로 | API 호출 |
|------|---------|
| 조기 반환 | 1회 |
| 병렬 fallback | 최대 3회 (text + 확장 + RAG) |

## 2단계: 병렬 Fallback

제목 매칭이 부족하면 3가지 검색을 동시에 실행합니다:

```kotlin
val (textResults, expandedResults, ragResults) = coroutineScope {
    val textDeferred = async {
        confluenceClient.searchByText(cleaned, spaces, synonyms, topK)
    }
    val expandedDeferred = async {
        if (spaces.isNotEmpty())
            confluenceClient.searchByTitle(cleaned, emptyList(), synonyms, topK)
        else emptyList()
    }
    val ragDeferred = async {
        if (vectorSearchAgent != null)
            withTimeoutOrNull(RAG_TIMEOUT_MS) {
                vectorSearchAgent.searchStructured(query, topK)
            } ?: run {
                log.warn("RAG search timed out after {}ms", RAG_TIMEOUT_MS)
                emptyList()
            }
        else emptyList()
    }
    Triple(textDeferred.await(), expandedDeferred.await(), ragDeferred.await())
}
```

**각 검색의 역할:**

| 검색 | 대상 | 특징 |
|------|------|------|
| text 검색 | 설정 스페이스 본문 | 제목에 없지만 내용에 있는 문서 |
| 스페이스 확장 | 전체 스페이스 제목 | 검색 범위 밖에 있는 연관 문서 |
| RAG fallback | ChromaDB 벡터 | 의미적으로 유사하지만 키워드가 다른 문서 |

RAG 타임아웃(`RAG_TIMEOUT_MS = 5_000`)은 응답 지연 방지용입니다. 타임아웃 시 로그를 남기고 빈 리스트를 반환합니다.

## 3단계: SearchStage 랭킹

결과를 합산할 때 출처(stage)에 따른 가중치로 정렬합니다:

```kotlin
enum class SearchStage(val score: Double) {
    TITLE_MATCH(1.0),       // 설정 스페이스 제목 직접 매칭
    SPACE_EXPANSION(0.8),   // 전체 스페이스 제목 확장
    TEXT_MATCH(0.6),        // 본문 텍스트 검색
    RAG(0.5),               // ChromaDB 벡터 유사도
}
```

같은 `pageId`가 여러 소스에서 나오면 **먼저 등록된 것(더 높은 stage)이 유지**됩니다:

```kotlin
titleResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(TITLE_MATCH)) }
// 이후 동일 pageId는 seen.add()가 false → 스킵
ragResults.forEach { if (seen.add(it.pageId)) deduplicated.add(it) }
```

예: 같은 페이지가 title 검색과 RAG에 모두 등장하면 `TITLE_MATCH`(score=1.0)로 기록됩니다.

## 실제 성능

147개 골든 케이스 기준:

| 지표 | cleanQuery 이전 | cleanQuery 이후 |
|------|----------------|----------------|
| Recall@5 전체 | 12.2% | **40.2%** |
| TITLE_BASED R@5 | 30.6% | **91.8%** |
| TITLE_MATCH 히트율 | — | 79.2% |
| 평균 API 호출 | 3.0 | **2.9** |

→ [검색 품질 평가](Golden-Dataset-Eval) · [CQL 검색 전략](CQL-Search-Strategy) 참고

---

> **Source:** [ConfluenceSearchAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt) · [SearchResult.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/SearchResult.kt)
