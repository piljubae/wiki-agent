# LLM은 인덱싱 타임에 쓰지 않는다

RAG 시스템에서 가장 중요한 설계 원칙. Cursor, Sourcegraph Cody, Continue.dev 등 모든 상용 도구가 공유합니다.

---

## 틀린 접근 vs 맞는 접근

```
[틀린 접근]
파일 → LLM으로 요약 생성 → 요약을 임베딩 → DB 저장
         ↑ 여기가 문제

[맞는 접근]
파일 → 임베딩 모델 → DB 저장          ← 인덱싱 (LLM 없음)
쿼리 → 임베딩 → 유사 청크 검색 → LLM  ← 검색 후 답변 생성 (LLM은 여기서만)
```

---

## 왜 LLM을 인덱싱 타임에 쓰면 안 되나

**비용**

```
kurly-android: 32,000 함수 청크

LLM 요약 방식:
  32,000 청크 × LLM 1회 호출 = 32,000번 API 호출
  GPT-4 기준: 32,000 × $0.01 = $320

임베딩 모델 방식:
  32,000 청크 → 임베딩 API 1배치
  text-embedding-004 기준: ~$0.06
```

**시간**

```
LLM 요약 방식:
  32,000 청크 × 평균 2초 = 17시간

임베딩 모델 방식:
  32,000 청크 배치 처리 = 15~20분
```

**증분 인덱싱**

PR 하나당 변경 파일 20개, 약 240개 청크.

```
LLM 요약 방식: 240 × LLM 호출 → 수 분 + 비용
임베딩 방식:  240 청크 → 30초 + $0.0005
```

---

## LLM이 실제로 쓰이는 위치

```
1. 쿼리 확장 (선택적)
   "배너 클릭" → LLM → "배너 클릭 BannerClick onClick 이벤트 핸들러"
   검색 recall을 높이기 위한 보조 역할. 쿼리 1건당 1회.

2. 답변 생성 (필수)
   검색된 청크 5개 → LLM → 사용자에게 설명
   이게 LLM의 본래 역할.
```

---

## wiki-agent 코드에서 확인

```kotlin
// CodeIndexAgent.kt — 인덱싱 루프 안에 LLM 호출 없음
chunks.forEach { chunk ->
    val doc = buildChunkDocument(chunk)   // 단순 문자열 조합
    val embedding = embeddingFn?.invoke(doc)  // 임베딩 모델만 호출
    chromaClient.upsertDocuments(...)
    bm25Index?.upsert(id, doc)
}

// CodeSearchTool.kt — 검색 시에만 LLM 관여
val expandedQuery = llmExpandClient?.expandQuery(query) ?: query  // 쿼리 확장 (선택적)
val results = chromaClient.query(...)  // 임베딩 검색
// → 이후 OrchestratorAgent가 LLM으로 답변 생성
```

---

## 참고 자료

- [Build Real-Time Codebase Indexing for AI Code Generation — CocoIndex](https://cocoindex.io/blogs/index-code-base-for-rag/)
- [RAG vs Fine-tuning — Pinecone](https://www.pinecone.io/learn/retrieval-augmented-generation/) — RAG 전체 개념

---

> **관련 문서:** [Embedding.md](Embedding.md) · [Code-Embedding-Models.md](Code-Embedding-Models.md) · [Code-Index-Commercial-Design.md](Code-Index-Commercial-Design.md)
