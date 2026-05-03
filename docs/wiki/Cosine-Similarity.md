# 코사인 유사도 (Cosine Similarity)

두 벡터가 얼마나 "같은 방향"을 가리키는지 측정하는 방법.
임베딩된 텍스트 간의 유사도를 계산할 때 사용합니다.

---

## 왜 단순 거리(유클리드)를 안 쓰나

```
"배너 클릭"                              →  [0.3, 0.9]
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

## 방향이 왜 의미를 나타내나

768개 숫자 각각이 어떤 의미 축의 강도를 나타냅니다.

```
              [UI 강도, 액션 강도, 네트워크 강도, ...]

"클릭"       →  [0.9,   0.9,     0.0,  ...]   // UI + 액션이 강함
"탭"         →  [0.88,  0.88,    0.0,  ...]   // 거의 같은 비율
"API 호출"   →  [0.1,   0.4,     0.95, ...]   // 네트워크가 강함
```

"클릭"과 "탭"은 각 축의 **비율**이 비슷합니다 → 방향이 같습니다 → 유사도 높음.
"클릭"과 "API 호출"은 비율이 다릅니다 → 방향이 다릅니다 → 유사도 낮음.

---

## 내적(dot product)이 하는 일

```kotlin
val dot = a.indices.sumOf { a[it] * b[it] }
```

두 벡터에서 같은 위치의 숫자를 곱해서 더합니다.

```
a = [0.9, 0.9, 0.0]    // "클릭"
b = [0.88, 0.88, 0.0]  // "탭"

dot = (0.9 × 0.88) + (0.9 × 0.88) + (0.0 × 0.0)
    = 0.792 + 0.792 + 0
    = 1.584   ← 같은 축이 함께 강할수록 커짐
```

같은 축이 **동시에 강할수록** 내적이 커집니다.
이것이 "공통된 의미 축이 많다"는 뜻입니다.

---

## 크기로 나누는 이유

```
"클릭"                        →  [0.9, 0.9]    크기 = 1.27
"클릭 클릭 클릭 클릭 클릭 클릭" →  [5.4, 5.4]    크기 = 7.6
```

같은 의미인데 반복만 많아서 숫자가 커졌습니다.
크기로 나눠서 정규화하면 둘 다 같은 방향 → 유사도 1.0.

```kotlin
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    val dot = a.indices.sumOf { a[it] * b[it] }
    val normA = sqrt(a.sumOf { it * it.toDouble() })
    val normB = sqrt(b.sumOf { it * it.toDouble() })
    return (dot / (normA * normB)).toFloat()   // 크기 제거, 방향만 남김
}
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
"배너 클릭" vs "버튼 클릭"          →  0.92  (매우 유사)
"배너 클릭" vs "BannerViewModel"    →  0.74  (관련 있음)
"배너 클릭" vs "장바구니 결제"       →  0.21  (관련 없음)
```

---

## 참고 자료

- [Cosine Similarity — Wikipedia](https://en.wikipedia.org/wiki/Cosine_similarity)
- [Understanding Vector Similarity — Pinecone](https://www.pinecone.io/learn/vector-similarity/) — 시각적 설명 포함 (추천)
- [Dot Product 시각화 — 3Blue1Brown](https://www.youtube.com/watch?v=LyGKycYT2v0)

---

> **관련 문서:** [Embedding.md](Embedding.md) · [Vector-Search.md](Vector-Search.md) · [BM25.md](BM25.md)
