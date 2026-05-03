# 코사인 유사도 (Cosine Similarity)

두 벡터가 얼마나 "같은 방향"을 가리키는지 측정하는 방법.
임베딩된 텍스트 간의 유사도를 계산할 때 사용합니다.

---

## 왜 단순 거리(유클리드)를 안 쓰나

```
"배너 클릭"                        →  [0.3, 0.9]
"배너 클릭 이벤트 처리 방법을 알고 싶습니다"  →  [0.6, 1.8]
```

두 번째 문장은 같은 의미지만 길어서 숫자가 전체적으로 2배 큽니다.
유클리드 거리(좌표 간 직선 거리)로 재면 멀게 나옵니다.

코사인 유사도는 크기를 무시하고 **방향만** 비교합니다.

```
[0.3, 0.9]  →  방향: 오른쪽 위 72도
[0.6, 1.8]  →  방향: 오른쪽 위 72도  ← 같음 → 유사도 1.0
```

---

## 값의 범위와 의미

| 값 | 의미 |
|----|------|
| `1.0` | 완전히 같은 방향 (동일한 의미) |
| `0.0` | 90도 (관련 없음) |
| `-1.0` | 반대 방향 (반대 의미) |

실제 검색 예시:

```
"배너 클릭" vs "버튼 클릭"         →  0.92  (매우 유사)
"배너 클릭" vs "BannerViewModel"   →  0.74  (관련 있음)
"배너 클릭" vs "장바구니 결제"      →  0.21  (관련 없음)
```

---

## 계산 방식

```kotlin
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    val dot = a.indices.sumOf { a[it] * b[it] }       // 내적 (dot product)
    val normA = sqrt(a.sumOf { it * it.toDouble() })   // a의 크기
    val normB = sqrt(b.sumOf { it * it.toDouble() })   // b의 크기
    return (dot / (normA * normB)).toFloat()
}
```

내적을 두 벡터의 크기로 나눠서 크기의 영향을 제거합니다.

---

## ChromaDB에서의 동작

```
사용자 쿼리: "배너 클릭 이벤트 처리"
    ↓ 임베딩
쿼리 벡터: [0.23, -0.81, 0.44, ..., 0.12]
    ↓
ChromaDB: 저장된 32,000개 벡터와 코사인 유사도 일괄 계산
    ↓
유사도 높은 순 top-10 반환
```

ChromaDB는 내부적으로 HNSW(Hierarchical Navigable Small World) 인덱스를 사용해서
32,000개 전체를 일일이 비교하지 않고 빠르게 근사 최근접 벡터를 찾습니다.

---

## 벡터 검색의 한계

코사인 유사도가 높다고 항상 원하는 결과가 나오지는 않습니다.

```
"KMA-7275 panelCode 변경"  쿼리
  → 벡터 검색: 의미적으로 유사한 문서 반환 (실제 KMA-7275 놓칠 수 있음)
  → BM25 검색: "KMA-7275" 문자열이 포함된 문서 정확히 매칭
```

정확한 키워드 매칭이 필요한 경우 BM25를 함께 사용합니다. → [BM25.md](BM25.md)

---

## 참고 자료

- [Cosine Similarity — Wikipedia](https://en.wikipedia.org/wiki/Cosine_similarity)
- [Understanding Cosine Similarity — Pinecone](https://www.pinecone.io/learn/vector-similarity/) — 시각적 설명 포함 (추천)
- [HNSW 알고리즘 (ChromaDB 내부 인덱스)](https://www.pinecone.io/learn/series/faiss/hnsw/) — 대규모 벡터 검색을 빠르게 하는 방법

---

> **관련 문서:** [Embedding.md](Embedding.md) · [BM25.md](BM25.md) · [ChromaDB-Setup.md](ChromaDB-Setup.md)
