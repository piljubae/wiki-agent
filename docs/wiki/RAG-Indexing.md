# RAG 인덱싱

RAG를 사용하려면 Confluence 페이지를 ChromaDB에 먼저 인덱싱해야 합니다.

## 전체 인덱싱

Slack에서:
```
/askpj reindex
```

- Confluence의 `confluence.spaces`에 설정된 스페이스 전체 페이지를 ChromaDB에 저장
- 기존 데이터는 덮어씁니다

## 인덱싱 상태 확인

```
/askpj reindex status
```

- 마지막 인덱싱 시각
- 인덱싱된 문서 수

## 인덱싱 과정

```
Confluence REST API → 페이지 목록 조회
    ↓ (배치 10개씩)
임베딩 생성 (LLM_EXPAND 또는 GOOGLE_EMBEDDING)
    ↓
ChromaDB에 저장 (collection: wiki_pages)
```

```kotlin
// VectorIndexAgent.kt
suspend fun indexAll(): Int {
    val collectionId = chromaClient.getOrCreateCollection(collectionName)
    var total = 0
    pages.chunked(10).forEach { batch ->
        // 배치별 임베딩 생성 + ChromaDB 저장
        total += batch.size
    }
    return total
}
```

## 재인덱싱이 필요한 시점

| 상황 | 권장 여부 |
|------|---------|
| 처음 RAG 활성화 시 | 필수 |
| Confluence 문서 대량 업데이트 후 | 권장 |
| `confluence.spaces` 변경 후 | 권장 |
| 임베딩 모드 변경 후 | 필수 |

## ChromaDB가 실행 중이지 않으면

`/askpj reindex` 실행 시 연결 오류가 발생합니다. ChromaDB를 먼저 실행하세요:

```bash
docker run -p 8000:8000 chromadb/chroma
```

---

> **Source:** [VectorIndexAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/VectorIndexAgent.kt) · [SlackConfigHandler.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt)
