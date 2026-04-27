# RAG Fallback

## 핵심 질문

> CQL 결과가 없을 때 자동으로 벡터 검색으로 넘어가나요?

## 개요

RAG는 CQL이 실패한 후 실행되는 sequential fallback이 **아닙니다.**  
`ConfluenceSearchAgent` 내부에서 제목 검색 결과가 부족할 때 text 검색·스페이스 확장과 **동시에** 실행되는 병렬 fallback입니다.

## 어디서 실행되나?

```
ConfluenceSearchAgent.searchStructured()
    │
    ├── 1단계: title 검색 → ≥ 3건? → 조기 반환 (RAG 실행 없음)
    │
    └── < 3건 → 병렬 실행 ─────────────────────────────────────┐
                    ├── text 검색 (Confluence REST)            │
                    ├── 스페이스 확장 검색 (Confluence REST)    │
                    └── RAG fallback (ChromaDB)  ◄─────────────┘
```

RAG는 `VectorSearchAgent`가 주입된 경우에만 실행됩니다(`rag.enabled = true` 시 DI).

## 코드

```kotlin
// ConfluenceSearchAgent.kt — 병렬 실행 블록
val ragDeferred = async {
    if (vectorSearchAgent != null)
        withTimeoutOrNull(RAG_TIMEOUT_MS) {
            vectorSearchAgent.searchStructured(query, topK)
        } ?: run {
            log.warn("RAG search timed out after {}ms", RAG_TIMEOUT_MS)
            emptyList()
        }
    else emptyList()
}
```

`RAG_TIMEOUT_MS = 5_000L` — text/title 검색과 동시에 시작하므로 전체 응답 시간에 추가되는 시간은 타임아웃 - max(text, expanded) 시간입니다.

## SearchStage 가중치

RAG 결과는 `SearchStage.RAG(score=0.5)`로 마킹됩니다.  
같은 `pageId`가 Confluence title/text 검색에도 나왔다면 **더 높은 stage가 유지**됩니다:

```kotlin
// combineAndRank: 먼저 등록된 것이 유지 (Confluence 결과 우선)
titleResults.forEach { if (seen.add(it.pageId)) deduplicated.add(it) }
ragResults.forEach  { if (seen.add(it.pageId)) deduplicated.add(it) }
```

| Stage | score | 우선순위 |
|-------|-------|---------|
| `TITLE_MATCH` | 1.0 | 1위 |
| `SPACE_EXPANSION` | 0.8 | 2위 |
| `TEXT_MATCH` | 0.6 | 3위 |
| `RAG` | 0.5 | 4위 (중복 시 덮어쓰이지 않음) |

## 활성화 조건

| 조건 | 설명 |
|------|------|
| `rag.enabled = true` | config.yml에서 RAG 활성화 |
| ChromaDB 실행 중 | `docker run -p 8000:8000 chromadb/chroma` |
| 인덱싱 완료 | `/wiki reindex` 실행 후 사용 가능 |

활성화되지 않으면 RAG는 완전히 스킵되고 title/text 검색만 사용됩니다.

## 타임아웃이 짧은 이유

5초 안에 응답이 없으면 RAG 없이 Confluence 결과만 반환합니다.  
ChromaDB가 느리거나 다운된 상황에서도 전체 검색 응답이 블로킹되지 않습니다.

→ [검색 플로우](Search-Flow) · [RAG 인덱싱](RAG-Indexing) · [ChromaDB 설정](ChromaDB-Setup) 참고

---

> **Source:** [ConfluenceSearchAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt) · [VectorSearchAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/VectorSearchAgent.kt)
