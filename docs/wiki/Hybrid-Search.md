# 하이브리드 검색 (Hybrid Search)

벡터 검색(의미 기반)과 BM25(키워드 기반) 두 결과를 합쳐서 각각의 약점을 보완하는 검색 방식.
Sourcegraph Cody와 같은 방식입니다.

---

## 왜 합치나

| | 벡터 검색만 | BM25만 |
|-|-----------|--------|
| "배너 클릭 이벤트" | ✅ | 약함 |
| "KMA-7275" | ❌ | ✅ |
| "panelCode" | 약함 | ✅ |
| "배너클릭이벤트" (한국어 붙여쓰기) | ✅ | ❌ |

어느 하나만으론 커버 못 하는 케이스가 있습니다. 합치면 둘 다 잡힙니다.

---

## 합치는 방법 — RRF (Reciprocal Rank Fusion)

점수를 직접 더하지 않습니다. 단위가 달라서 더할 수 없기 때문입니다.
(벡터 유사도: 0.0~1.0 / BM25: FTS5 기준 음수)

대신 **순위**를 기반으로 점수를 새로 계산합니다.

```
RRF 점수 = 1 / (60 + 순위)
```

| 순위 | RRF 점수 |
|------|---------|
| 1위 | 1/61 = 0.0164 |
| 2위 | 1/62 = 0.0161 |
| 10위 | 1/70 = 0.0143 |
| 100위 | 1/160 = 0.0063 |

순위가 낮아질수록 점수가 급격히 떨어집니다.
60은 낮은 순위 결과의 영향을 줄이는 완충 상수 — 1970년대부터 사용된 업계 표준값입니다.

---

## 구체적 예시

쿼리: `"panelCode"`

```
벡터 검색 결과:           BM25 결과:
1위. BannerViewModel     1위. PanelCodeMapper
2위. PanelCodeMapper     2위. SiteFilterType
3위. HomeViewModel       3위. BannerViewModel
```

RRF 점수 계산:

```
PanelCodeMapper  =  1/(60+2) + 1/(60+1)  =  0.0161 + 0.0164  =  0.0325  ← 1위
BannerViewModel  =  1/(60+1) + 1/(60+3)  =  0.0164 + 0.0154  =  0.0318  ← 2위
SiteFilterType   =  0        + 1/(60+2)  =  0      + 0.0161  =  0.0161  ← 3위
HomeViewModel    =  1/(60+3) + 0         =  0.0154 + 0        =  0.0154  ← 4위
```

두 검색 모두 hit한 `PanelCodeMapper`가 자연스럽게 1위가 됩니다.

---

## wiki-agent 구현

```kotlin
// BM25Index.kt
fun mergeRRF(vectorIds: List<String>, bm25Ids: List<String>): List<String> {
    val scores = mutableMapOf<String, Double>()
    vectorIds.forEachIndexed { rank, id ->
        scores[id] = (scores[id] ?: 0.0) + 1.0 / (60 + rank + 1)
    }
    bm25Ids.forEachIndexed { rank, id ->
        scores[id] = (scores[id] ?: 0.0) + 1.0 / (60 + rank + 1)
    }
    return scores.entries.sortedByDescending { it.value }.map { it.key }
}
```

어느 한쪽에만 있는 결과도 포함(Union)됩니다.
양쪽 다 있으면 RRF 점수가 자연스럽게 높아져 상위권으로 올라옵니다.

```kotlin
// CodeSearchTool.kt
val vectorResults = chromaClient.query(collectionId, queryTexts = listOf(expandedQuery), nResults = 10)
val vectorIds = vectorResults.map { resultToId(it) }

val orderedIds = if (bm25Index != null) {
    val bm25Ids = bm25Index.search(query, limit = 10)
    BM25Index.mergeRRF(vectorIds, bm25Ids)    // RRF 병합
} else {
    vectorIds                                  // BM25 없으면 벡터 순서 그대로
}

val topIds = orderedIds.take(5)   // 최종 5개 표시
```

---

## 검색 타입별 Slack 출력 표시

```
*"panelCode"* 관련 코드 [하이브리드(벡터+BM25), 5건]:
*"배너 클릭 이벤트"* 관련 코드 [벡터, 5건]:   // BM25 미설정 시
```

---

## 참고 자료

- [Reciprocal Rank Fusion — 원본 논문 (Cormack et al., 2009)](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)
- [Hybrid Search 설명 — Pinecone](https://www.pinecone.io/learn/hybrid-search-intro/)
- [How Cody provides repository context — Sourcegraph](https://sourcegraph.com/blog/how-cody-provides-remote-repository-context/) — 같은 방식 사용

---

> **관련 문서:** [Vector-Search.md](Vector-Search.md) · [BM25.md](BM25.md) · [Cosine-Similarity.md](Cosine-Similarity.md) · [Code-Index-Commercial-Design.md](Code-Index-Commercial-Design.md)
