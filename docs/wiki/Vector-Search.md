# 벡터 검색 (Vector Search)

텍스트를 임베딩(숫자 배열)으로 변환한 뒤, 쿼리와 가장 가까운 벡터를 찾는 검색 방식.
의미가 비슷한 문서를 찾는 데 강합니다.

---

## 전체 흐름

```
[인덱싱]
함수 청크 문서 → 임베딩 모델 → 벡터 [0.23, -0.81, ...] → ChromaDB 저장

[검색]
사용자 쿼리 → 임베딩 모델 → 쿼리 벡터 → ChromaDB에서 코사인 유사도 계산 → top-K 반환
```

키워드가 정확히 일치하지 않아도 **의미가 비슷하면** 찾아줍니다.

---

## ChromaDB 내부에서 일어나는 일

32,000개 벡터를 매번 전부 비교하면 너무 느립니다. ChromaDB는 **HNSW** 인덱스를 씁니다.

```
[전체 탐색]  쿼리 벡터 vs 32,000개 전부 계산  →  O(n),  느림
[HNSW]      그래프 구조로 탐색 경로 단축      →  O(log n), 빠름
```

HNSW(Hierarchical Navigable Small World)는 "이 동네에서 가장 가까운 이웃들"을 미리 연결해둔 그래프입니다.
지도 앱이 전체 도로를 다 보지 않고 경로를 찾는 것과 같은 원리입니다.

결과는 근사값(approximate)이지만 실용적으로 충분히 정확합니다.

---

## 잘 되는 쿼리 vs 안 되는 쿼리

**잘 됨 — 의미 기반 질문**

```
"배너가 나타나는 시점 언제야?"    →  BannerVisibility, showBanner() 찾음
"사용자 정보 어떻게 저장해?"      →  UserRepository, saveUser() 찾음
"클릭하면 화면 이동 어디서 해?"   →  Navigation 관련 함수 찾음
```

**안 됨 — 정확한 키워드 매칭이 필요한 경우**

```
"KMA-7275"         →  티켓 번호는 의미 없음, 벡터 공간에서 방향이 없음
"panelCode"        →  프로젝트 고유 용어, 임베딩 모델 학습 데이터에 없었을 가능성
"NullPointerException 나는 라인"  →  에러 위치는 의미 검색으로 못 찾음
```

이 빈틈을 BM25가 채웁니다. → [BM25.md](BM25.md)

---

## wiki-agent에서의 사용

```kotlin
// CodeSearchTool.kt
val vectorResults = chromaClient.query(
    collectionId,
    queryTexts = listOf(expandedQuery),
    nResults = 10
)
```

- `nResults = 10` — 상위 10개 반환, 이후 RRF 병합 후 최종 5개 표시
- `expandedQuery` — LLM_EXPAND 모드일 때 쿼리를 동의어/관련 개념으로 확장 후 검색

---

## 벡터 검색만으로 부족한 이유

| 상황 | 문제 |
|------|------|
| 코드 고유 용어 (`panelCode`, `KMA-7275`) | 임베딩 모델이 모름 |
| 정확한 함수명 검색 (`onBannerClick`) | 의미보다 철자 매칭이 더 정확 |
| 새로 만들어진 클래스명 | 학습 데이터에 없음 |

→ BM25 키워드 검색과 결합해서 두 결과를 RRF로 합칩니다. → [Hybrid-Search.md](Hybrid-Search.md)

---

## 참고 자료

- [What is Vector Search? — Pinecone](https://www.pinecone.io/learn/vector-search/) — 개념 + 시각화 (추천)
- [HNSW 알고리즘 설명 — Pinecone](https://www.pinecone.io/learn/series/faiss/hnsw/) — ChromaDB 내부 인덱스 원리
- [ChromaDB 공식 문서](https://docs.trychroma.com/) — wiki-agent에서 사용하는 벡터 DB

---

> **관련 문서:** [Embedding.md](Embedding.md) · [Cosine-Similarity.md](Cosine-Similarity.md) · [BM25.md](BM25.md) · [Hybrid-Search.md](Hybrid-Search.md)
