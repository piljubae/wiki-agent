# 코드 전용 임베딩 모델

일반 텍스트 임베딩 모델과 코드 전용 모델의 차이, 그리고 선택 기준입니다.

---

## 왜 코드 전용 모델이 필요한가

```
일반 모델 (뉴스·위키·책으로 학습)
  "onClick"  →  잘 모름, 벡터 품질 낮음
  "StateFlow" →  잘 모름
  "fun onBannerClick(bannerId: String)"  →  의미 파악 어려움

코드 전용 모델 (GitHub 수억 개 저장소로 학습)
  "onClick"  →  클릭 이벤트 핸들러라는 것을 앎
  "StateFlow" →  코루틴 기반 상태 관리라는 것을 앎
  "fun onBannerClick(bannerId: String)"  →  배너 클릭 처리 함수임을 파악
```

코드는 일반 텍스트와 다른 패턴(변수명, 함수명, 타입 시스템)을 가집니다.
전용 모델이 이 맥락을 더 잘 이해합니다.

---

## 주요 모델 비교

| 모델 | 개발사 | 차원 | 특징 |
|------|--------|------|------|
| `voyage-code-3` | Voyage AI | 1024 | 코드 검색 SOTA, Cursor 채택 |
| `text-embedding-3-large` | OpenAI | 3072 | 코드+텍스트 혼합 강점 |
| `text-embedding-004` | Google | 768 | 다국어 강점, wiki-agent 사용 |
| `all-MiniLM-L6-v2` | Microsoft | 384 | 무료 로컬, 코드 품질 보통 |

차원이 높을수록 일반적으로 더 정확하지만, 벡터 저장 공간과 검색 속도에 영향을 줍니다.

---

## wiki-agent 선택 기준

```
Korean + Code 혼합 쿼리가 많음
  → text-embedding-004 (Google, 다국어 + 코드 균형)

비용 최소화 / 빠른 시작
  → all-MiniLM-L6-v2 (ChromaDB 기본, 무료)

코드 검색 정확도 최우선
  → voyage-code-3 (SOTA, 유료 API)
```

현재 wiki-agent: `embeddingMode: GOOGLE_EMBEDDING` 설정 시 `text-embedding-004` 사용.

---

## 실제 비용 (kurly-android 기준)

| 모델 | 32,000 청크 인덱싱 비용 |
|------|----------------------|
| `all-MiniLM-L6-v2` | $0 (로컬) |
| `text-embedding-004` | ~$0.06 |
| `voyage-code-3` | ~$0.32 |
| `text-embedding-3-large` | ~$0.64 |

---

## 참고 자료

- [voyage-code-3 — Voyage AI](https://docs.voyageai.com/docs/embeddings) — Cursor가 채택한 코드 전용 모델
- [text-embedding-004 — Google](https://ai.google.dev/gemini-api/docs/models/gemini#text-embedding) — wiki-agent 사용 모델
- [Embeddings Leaderboard — MTEB](https://huggingface.co/spaces/mteb/leaderboard) — 임베딩 모델 벤치마크 순위 (추천)

---

> **관련 문서:** [Embedding.md](Embedding.md) · [Sentence-Transformers.md](Sentence-Transformers.md) · [RAG-Embedding-Modes.md](RAG-Embedding-Modes.md)
