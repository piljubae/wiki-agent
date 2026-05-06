# ChromaDB 설정

ChromaDB는 wiki-agent의 RAG(벡터 검색) 기능에 사용하는 오픈소스 벡터 데이터베이스입니다.

## 실행 방법

### uvx (권장)

```bash
# IPv4/IPv6 모두 바인딩 — Ktor CIO가 127.0.0.1(IPv4)로 연결하므로 필수
uvx --from chromadb chroma run --host 0.0.0.0 --port 8001
```

> **주의:** `uvx chroma run`만 쓰면 `::1`(IPv6 only)에 바인딩돼서 Ktor CIO가 연결 거부됩니다.
> 반드시 `--host 0.0.0.0`을 붙여야 합니다.

### Docker

```bash
# 기본 (데이터 휘발)
docker run -p 8001:8000 chromadb/chroma

# 데이터 영속화
docker run -p 8001:8000 -v ./chroma-data:/chroma/chroma chromadb/chroma
```

## config.yml 설정

```yaml
rag:
  enabled: true
  chromaUrl: http://localhost:8001
  embeddingMode: LLM_EXPAND       # Confluence/GitHub 문서용

github:
  codeSearch:
    embeddingMode: GOOGLE_EMBEDDING  # 코드 인덱싱 전용
```

## ChromaDB v2 REST API (현재 사용)

v2부터 URL에 tenant/database가 포함됩니다. 기본값: `default_tenant` / `default_database`.

| 메서드 | 경로 | 용도 |
|--------|------|------|
| `POST` | `/api/v2/tenants/{t}/databases/{d}/collections` | 컬렉션 생성 |
| `POST` | `/api/v2/.../collections/{id}/upsert` | 문서 + 임베딩 저장 |
| `POST` | `/api/v2/.../collections/{id}/query` | 유사 문서 검색 |
| `POST` | `/api/v2/.../collections/{id}/get` | ID로 문서 조회 |

> v2는 서버 사이드 임베딩이 없습니다. `embeddings` 필드를 클라이언트가 직접 전달해야 합니다.
> 자세한 내용은 [ChromaDB-v2-Migration](ChromaDB-v2-Migration) 참고.

## 컬렉션 확인

```bash
# 컬렉션 목록
curl http://localhost:8001/api/v2/tenants/default_tenant/databases/default_database/collections

# code_index 문서 수
COLLECTION_ID=$(curl -s .../collections | python3 -c "import sys,json; [print(c['id']) for c in json.load(sys.stdin) if c['name']=='code_index']")
curl http://localhost:8001/api/v2/.../collections/$COLLECTION_ID/count
```

---

> **Reference:** [ChromaDB 공식 문서](https://docs.trychroma.com) · [ChromaDB-v2-Migration](ChromaDB-v2-Migration)  
> **Source:** [ChromaClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/ChromaClient.kt)
