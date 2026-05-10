# Google Embedding API

wiki-agent에서 코드 인덱싱/검색에 사용하는 Google Generative Language API 임베딩 가이드.

---

## 사용 가능한 모델

`generativelanguage.googleapis.com` 기준 임베딩 모델:

| 모델 | 차원 | 상태 |
|------|------|------|
| `gemini-embedding-001` | 3,072 | **현재 사용 중** |
| `gemini-embedding-2` | - | stable, 미적용 |
| `gemini-embedding-2-preview` | - | preview |

> **`text-embedding-004`는 이 API에서 지원하지 않습니다.**  
> `generativelanguage.googleapis.com`(Gemini API)이 아닌 **Vertex AI 전용**입니다.  
> 시도 시 `404 NOT_FOUND` 반환.

---

## 엔드포인트

```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key={API_KEY}
```

- **반드시 `v1beta`** — `v1`은 404 반환
- Request body:
  ```json
  {
    "model": "models/gemini-embedding-001",
    "content": { "parts": [{ "text": "..." }] }
  }
  ```

---

## Free vs Paid Tier

| 항목 | Free Tier | Paid Tier |
|------|-----------|-----------|
| 요청 한도 | **100 req/day** | 수천~수만 req/day |
| 인덱싱 가능 여부 | 불가 (5000+ 파일 처리 불가) | 가능 |
| 설정 방법 | 기본 | Google AI Studio → 결제 수단 등록 |

kurly-android (~5,000파일, ~17,000 청크) 전체 인덱싱에는 **Paid Tier 필수**.

---

## API 키 분리 구조

wiki-agent는 용도별로 API 키를 분리합니다:

```
GOOGLE_API_KEY         → RAG 지식 임베딩 + Gemini Flash 라우팅 + 공용 fallback
GOOGLE_INDEX_API_KEY   → CodeIndexAgent (대량 인덱싱)  ← 없으면 GOOGLE_API_KEY 사용
GOOGLE_SEARCH_API_KEY  → CodeSearchTool (실시간 검색)  ← 없으면 GOOGLE_API_KEY 사용
```

분리하는 이유: 인덱싱(대량·배치)과 검색(소량·실시간)의 쿼터 소비 패턴이 달라
별도 프로젝트/키로 관리하면 쿼터 고갈 시 영향 범위를 격리할 수 있습니다.

`.env` 설정:
```
GOOGLE_API_KEY=AIza...           # 필수
GOOGLE_INDEX_API_KEY=AIza...     # 선택 (없으면 GOOGLE_API_KEY fallback)
GOOGLE_SEARCH_API_KEY=AIza...    # 선택 (없으면 GOOGLE_API_KEY fallback)
```

---

## 구현 위치

| 파일 | 역할 |
|------|------|
| `rag/EmbeddingClient.kt` | `GoogleEmbeddingClient` — API 호출, 응답 파싱 |
| `Main.kt` | 키 분리 로직 — `indexEmbeddingFn` / `searchEmbeddingFn` 빌드 |
| `config/WikiConfig.kt` | `CodeSearchConfig.indexApiKey` / `searchApiKey` 필드 |

---

> **관련 문서:** [Code-Embedding-Models.md](Code-Embedding-Models.md) · [RAG-Embedding-Modes.md](RAG-Embedding-Modes.md) · [Secret-Loader.md](Secret-Loader.md)
