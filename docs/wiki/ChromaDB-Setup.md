# ChromaDB 설정

ChromaDB는 wiki-agent의 RAG(벡터 검색) 기능에 사용하는 오픈소스 벡터 데이터베이스입니다.

## Docker로 실행

```bash
docker run -p 8000:8000 chromadb/chroma
```

- 기본 포트: **8000**
- 데이터는 컨테이너 재시작 시 초기화됩니다 (개발용)

데이터 영속화가 필요하면:

```bash
docker run -p 8000:8000 -v ./chroma-data:/chroma/chroma chromadb/chroma
```

## config.yml 설정

```yaml
rag:
  enabled: true
  chromaUrl: http://localhost:8000
  embeddingMode: LLM_EXPAND
```

## ChromaDB REST API 엔드포인트 (wiki-agent 사용분)

| 메서드 | 경로 | 용도 |
|--------|------|------|
| `POST` | `/api/v1/collections` | 컬렉션 생성 또는 조회 (`get_or_create: true`) |
| `POST` | `/api/v1/collections/{id}/add` | 문서 + 임베딩 추가 |
| `POST` | `/api/v1/collections/{id}/query` | 유사 문서 검색 |

## 기본 컬렉션 이름

```kotlin
// VectorIndexAgent.kt / VectorSearchAgent.kt
private val collectionName = "wiki_pages"
```

## 확인 방법

```bash
# 컬렉션 목록 조회
curl http://localhost:8000/api/v1/collections

# 특정 컬렉션 조회
curl http://localhost:8000/api/v1/collections/wiki_pages
```

---

> **Reference:** [ChromaDB 공식 문서](https://docs.trychroma.com) · [Docker Hub chromadb/chroma](https://hub.docker.com/r/chromadb/chroma)  
> **Source:** [ChromaClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/ChromaClient.kt)
