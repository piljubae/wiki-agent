# 임베딩 캐시

이미 인덱싱된 청크는 재임베딩하지 않는 방법입니다.

## 문제

병렬화로 10분으로 줄였어도, 재인덱싱할 때마다 15,417개 전부 Gemini API에 보내면:

- 무료 일일 한도 1,000건 → 첫 배치에서 소진
- 유료라도 코드가 거의 안 바뀌었는데 전부 재임베딩 → 낭비

## 캐시 키 = 청크 ID

별도 캐시 저장소가 필요 없습니다. 청크 ID 구조를 보면:

```
{repo}:{파일경로}:{클래스명}:{함수명}:{sigHash}
```

`sigHash`는 함수 시그니처의 해시입니다. **함수가 바뀌면 sigHash가 달라지고, ID도 달라집니다.**

**ID가 같다 = 함수 내용이 같다 = 재임베딩 불필요**

## 동작 흐름

```
배치 50 파일 → 청크 ~150개 수집
    ↓
ChromaDB에 이 150개 ID가 있는지 조회
    ↓
없는 ID → Gemini API 호출 (신규/변경 함수)
있는 ID → 스킵 (이미 임베딩 완료)
    ↓
신규 청크만 upsert
```

두 번째 실행부터는 변경된 함수만 임베딩하므로 API 호출이 거의 0에 수렴합니다.

## 구현

```kotlin
// Phase 2: 이미 인덱싱된 ID 확인 → 새 것만 임베딩
val existingIds = chromaClient.getExistingIds(collectionId, entries.map { it.id })
val newEntries = entries.filter { it.id !in existingIds }

val embeddings = if (embeddingFn != null && newEntries.isNotEmpty()) {
    coroutineScope {
        newEntries.map { entry ->
            async { embeddingSemaphore.withPermit { embeddingFn(entry.doc) } }
        }.awaitAll()
    }
} else emptyList()
```

```kotlin
// ChromaClient.getExistingIds()
suspend fun getExistingIds(collectionId: String, ids: List<String>): Set<String> {
    val body = """{"ids":[${ids.joinToString(",") { "\"$it\"" }}],"include":[]}"""
    val response = httpClient.post("$apiBase/collections/$collectionId/get") { ... }.bodyAsText()
    // response에서 "ids" 배열 파싱 → HashSet 반환
}
```

`include: []`를 지정하면 ChromaDB가 document/embedding 본문을 제외하고 ID만 반환해 응답이 가볍습니다.

## 증분 인덱싱과의 관계

| 방식 | 대상 | 작동 조건 |
|------|------|---------|
| 증분 인덱싱 | 파일 단위 필터 | `git diff`로 변경된 파일만 처리 |
| 임베딩 캐시 | 청크 단위 필터 | ChromaDB에 ID가 없는 청크만 임베딩 |

두 방식은 독립적으로 동작하며 중첩 적용됩니다.
증분 인덱싱이 파일 목록을 줄이고, 임베딩 캐시가 그 안에서 청크를 한 번 더 필터링합니다.

---

> **Source:** [CodeIndexAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt) · [ChromaClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/ChromaClient.kt)  
> **관련:** [Parallel-Embedding](Parallel-Embedding) · [Incremental-Indexing](Incremental-Indexing)
