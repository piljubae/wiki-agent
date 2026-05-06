# ChromaDB v2 API 마이그레이션

ChromaDB v1.0 이후 REST API 구조가 크게 바뀌었습니다. v2에서는 클라이언트가 임베딩 벡터를 직접 만들어서 전달해야 합니다.

## v1 vs v2 엔드포인트 비교

| 항목 | v1 | v2 |
|------|----|----|
| 기본 경로 | `/api/v1/` | `/api/v2/tenants/{tenant}/databases/{database}/` |
| 컬렉션 생성 | `POST /api/v1/collections` | `POST /api/v2/.../collections` |
| 문서 추가 | `POST /api/v1/collections/{id}/add` | `POST /api/v2/.../collections/{id}/upsert` |
| 검색 | `POST /api/v1/collections/{id}/query` | `POST /api/v2/.../collections/{id}/query` |

기본값: `tenant = default_tenant`, `database = default_database`

```bash
# v2 컬렉션 목록 조회
curl http://localhost:8001/api/v2/tenants/default_tenant/databases/default_database/collections
```

## 가장 큰 변경: 서버 사이드 임베딩 제거

**v1** — ChromaDB 서버가 텍스트를 직접 임베딩해줌

```json
// 텍스트만 넘기면 서버가 all-MiniLM-L6-v2 로 벡터화
{
  "query_texts": ["MainHomeViewModel 초기화 어디서?"],
  "n_results": 5
}
```

**v2** — 클라이언트가 float 배열을 직접 전달해야 함

```json
// query_texts 미지원 → query_embeddings 필수
{
  "query_embeddings": [[0.008, -0.029, 0.091, ...]],
  "n_results": 5
}
```

upsert도 마찬가지로 `embeddings` 필드가 필수입니다. 없으면 즉시 에러 반환:

```json
{"error":"ChromaError","message":"missing field `embeddings`"}
```

## GOOGLE_EMBEDDING 모드가 하는 일

v2에서 서버 사이드 임베딩이 없어졌기 때문에, wiki-agent는 `GOOGLE_EMBEDDING` 모드에서 클라이언트가 직접 Gemini API를 호출해 벡터를 생성합니다.

**인덱싱 흐름**

```
함수 코드(텍스트)
    ↓
GoogleEmbeddingClient.embed(text)
    ↓  Gemini API 호출
gemini-embedding-001:embedContent
    ↓
[0.012, -0.034, ...] ← 3072차원 float 벡터
    ↓
ChromaDB upsert (embeddings 필드에 전달)
```

**검색 흐름**

```
질문 텍스트
    ↓
GoogleEmbeddingClient.embed(query)  ← 인덱싱과 동일한 모델 사용
    ↓
float 벡터
    ↓
ChromaDB query (query_embeddings 필드에 전달)
    ↓
코사인 유사도로 가장 가까운 청크 반환
```

인덱스 시점과 검색 시점에 **동일한 모델**을 써야 유사도 계산이 의미 있습니다. wiki-agent는 두 경우 모두 `gemini-embedding-001`을 사용합니다.

## 임베딩 모드 구분

코드 인덱싱과 문서(Confluence/GitHub) 인덱싱은 모드를 분리합니다.

```yaml
rag:
  embeddingMode: LLM_EXPAND       # Confluence/GitHub 문서용

github:
  codeSearch:
    embeddingMode: GOOGLE_EMBEDDING  # 코드 인덱싱 전용
```

`LLM_EXPAND`는 LLM이 문서를 확장(동의어·키워드 보강)한 텍스트를 ChromaDB에 저장하는 방식으로, 문서 검색 품질 향상에 사용됩니다.

## 기동 시 주의: IPv4/IPv6 바인딩

```bash
# 잘못된 방법 — ::1 (IPv6 only) 바인딩, Ktor CIO가 127.0.0.1로 연결 시도 → Connection refused
uvx chroma run --port 8001

# 올바른 방법 — IPv4/IPv6 모두 바인딩
uvx --from chromadb chroma run --host 0.0.0.0 --port 8001
```

Ktor의 CIO HTTP 클라이언트는 기본적으로 IPv4(`127.0.0.1`)로 연결합니다. ChromaDB 기본 바인딩이 IPv6 전용(`::1`)이기 때문에 `--host 0.0.0.0`이 필수입니다.

## gemini-embedding-001 모델

| 항목 | 값 |
|------|----|
| 모델명 | `gemini-embedding-001` |
| 벡터 차원 | 3072 |
| API 엔드포인트 | `v1beta/models/gemini-embedding-001:embedContent` |
| 이전 모델 | `text-embedding-004` (v1beta 지원 종료) |

---

> **Reference:** [ChromaDB Migration Guide](https://docs.trychroma.com/migration) · [Gemini Embedding API](https://ai.google.dev/api/embeddings)
> **Source:** [ChromaClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/ChromaClient.kt) · [EmbeddingClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/EmbeddingClient.kt)
