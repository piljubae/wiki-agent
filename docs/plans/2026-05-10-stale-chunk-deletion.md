# Stale Chunk Deletion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 파일 삭제/함수 삭제 시 ChromaDB·BM25에 남아있는 stale 청크를 재임베딩 비용 없이 surgical하게 제거한다.

**Architecture:** `git diff --diff-filter` 로 삭제/수정 파일을 분리 → 삭제 파일은 전체 청크 delete, 수정 파일은 기존 ID와 새 ID를 비교해 사라진 함수 청크만 delete. 새 청크는 기존 cache hit 로직(`getExistingIds`) 그대로 유지 — 재임베딩 없음.

**Tech Stack:** Kotlin, ChromaDB v2 REST API, SQLite FTS5 (BM25Index), git diff

---

## Context

핵심 파일:
- `src/main/kotlin/.../rag/ChromaClient.kt` — `getOrCreateCollection`, `getExistingIds`, `upsertDocuments` 구현
- `src/main/kotlin/.../knowledge/BM25Index.kt` — `upsert(id, content, filePath)`, `delete(id)` 구현. `file_path` 컬럼 있음
- `src/main/kotlin/.../knowledge/LocalRepoSync.kt` — `changedKtFiles(sinceCommit)`: `git diff --name-only`만 사용
- `src/main/kotlin/.../knowledge/CodeIndexAgent.kt`
  - `syncAndIndexChanged()` (라인 ~187): `changedKtFiles()` 결과를 `indexFiles()`에 바로 넘김
  - `indexFiles()` (라인 ~477): 파일 파싱 → `getExistingIds`로 cache hit → 새 청크만 embed + upsert
- `src/test/.../rag/ChromaClientTest.kt` — 단위 테스트 (HTTP mock 없이 내부 파싱 테스트)
- `src/test/.../knowledge/CodeIndexAgentTest.kt` — 단위 테스트

ChromaDB delete API (v2):
```
POST /api/v2/tenants/{tenant}/databases/{db}/collections/{id}/delete
Body: {"ids": ["id1", "id2"]}
```

ChromaDB get by metadata (v2):
```
POST /api/v2/tenants/{tenant}/databases/{db}/collections/{id}/get
Body: {
  "where": {"$and": [{"repo": {"$eq": "repo"}}, {"file_path": {"$eq": "path"}}]},
  "include": []
}
Response: {"ids": [["id1", "id2", ...]], ...}
```

---

## Task 1: ChromaClient에 `getIdsByFilePath` + `deleteByIds` 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/rag/ChromaClient.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/rag/ChromaClientTest.kt`

### Step 1: 파싱 테스트 먼저 작성

`ChromaClientTest.kt`에 추가:

```kotlin
@Test
fun `parseGetIdsResponse extracts id list from get response`() {
    val json = """{"ids":[["id1","id2","id3"]],"documents":[[]],"metadatas":[[]],"embeddings":null}"""
    val result = ChromaClient("http://localhost").parseGetIdsResponse(json)
    assertEquals(listOf("id1", "id2", "id3"), result)
}

@Test
fun `parseGetIdsResponse returns empty list when ids array is empty`() {
    val json = """{"ids":[[]],"documents":[[]],"metadatas":[[]]}"""
    val result = ChromaClient("http://localhost").parseGetIdsResponse(json)
    assertEquals(emptyList<String>(), result)
}
```

### Step 2: 테스트 실패 확인

```bash
./gradlew :test --tests "*.ChromaClientTest.parseGetIdsResponse*" 2>&1 | tail -10
```
Expected: FAIL — `parseGetIdsResponse` 없음

### Step 3: `ChromaClient`에 구현 추가

`ChromaClient.kt`에서 `getExistingIds` 바로 아래에 추가:

```kotlin
/** file_path 메타데이터 기준으로 해당 파일의 모든 청크 ID 반환 */
suspend fun getIdsByFilePath(collectionId: String, repo: String, filePath: String): List<String> {
    val where = """{"${"$"}and":[{"repo":{"${"$"}eq":"$repo"}},{"file_path":{"${"$"}eq":"${filePath.escapeJson()}"}}]}"""
    val body = """{"where":$where,"include":[]}"""
    val response = httpClient.post("$apiBase/collections/$collectionId/get") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }.bodyAsText()
    return parseGetIdsResponse(response)
}

/** 지정한 ID 목록을 ChromaDB에서 삭제 */
suspend fun deleteByIds(collectionId: String, ids: List<String>) {
    if (ids.isEmpty()) return
    val idsJson = ids.joinToString(",") { "\"${it.escapeJson()}\"" }
    val body = """{"ids":[$idsJson]}"""
    httpClient.post("$apiBase/collections/$collectionId/delete") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    log.debug("deleteByIds: deleted {} chunks", ids.size)
}

internal fun parseGetIdsResponse(json: String): List<String> {
    val matched = Regex("\"ids\"\\s*:\\s*\\[\\[([^]]*)]").find(json)
        ?.groupValues?.get(1) ?: return emptyList()
    if (matched.isBlank()) return emptyList()
    return Regex("\"([^\"]+)\"").findAll(matched).map { it.groupValues[1] }.toList()
}
```

### Step 4: 테스트 통과 확인

```bash
./gradlew :test --tests "*.ChromaClientTest" 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

### Step 5: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/rag/ChromaClient.kt \
        src/test/kotlin/io/github/veronikapj/wiki/rag/ChromaClientTest.kt
git commit -m "feat: add getIdsByFilePath and deleteByIds to ChromaClient"
```

---

## Task 2: BM25Index에 `deleteByFilePath` 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/BM25Index.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgentTest.kt`

### Step 1: 테스트 먼저 작성

`CodeIndexAgentTest.kt`에 추가 (BM25Index 직접 테스트):

```kotlin
@Test
fun `BM25Index deleteByFilePath removes all chunks for given file`() {
    val index = BM25Index(":memory:")  // in-memory SQLite
    index.upsert("repo:FileA.kt:Foo:bar:abc", "fun bar()", "FileA.kt")
    index.upsert("repo:FileA.kt:Foo:baz:def", "fun baz()", "FileA.kt")
    index.upsert("repo:FileB.kt:Foo:qux:ghi", "fun qux()", "FileB.kt")

    index.deleteByFilePath("FileA.kt")

    val results = index.search("bar baz qux", limit = 10)
    assertFalse(results.any { it.contains("FileA") })
    assertTrue(results.any { it.contains("repo:FileB") })
    index.close()
}
```

### Step 2: 테스트 실패 확인

```bash
./gradlew :test --tests "*.BM25Index deleteByFilePath*" 2>&1 | tail -10
```
Expected: FAIL — `deleteByFilePath` 없음

### Step 3: `BM25Index`에 구현 추가

`BM25Index.kt`의 `delete(id)` 바로 아래에:

```kotlin
/** 특정 파일 경로에 속하는 모든 청크를 삭제합니다. */
fun deleteByFilePath(filePath: String) {
    conn.prepareStatement("DELETE FROM code_chunks WHERE file_path = ?").use {
        it.setString(1, filePath)
        it.execute()
    }
}
```

### Step 4: 테스트 통과 확인

```bash
./gradlew :test --tests "*.BM25Index deleteByFilePath*" 2>&1 | tail -10
```
Expected: PASS

### Step 5: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/BM25Index.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgentTest.kt
git commit -m "feat: add deleteByFilePath to BM25Index"
```

---

## Task 3: LocalRepoSync에 `diffKtFiles` 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/LocalRepoSync.kt`

### Step 1: `DiffResult` data class 추가 + `diffKtFiles` 구현

`LocalRepoSync.kt`에 추가:

```kotlin
data class DiffResult(
    val modified: List<String>,   // 추가·수정된 파일 (재인덱싱 대상)
    val deleted: List<String>,    // 삭제된 파일 (청크 삭제 대상)
)

/**
 * sinceCommit 이후 변경된 .kt 파일을 수정/삭제로 분리해 반환.
 * git diff --diff-filter:
 *   A = Added, M = Modified  → modified
 *   D = Deleted               → deleted
 */
fun diffKtFiles(sinceCommit: String): DiffResult {
    val modified = runGit("diff", "--name-only", "--diff-filter=AM", "$sinceCommit..HEAD", "--", "*.kt")
        ?.lines()
        ?.filter { it.endsWith(".kt") && !it.contains("/build/") && !it.contains("/generated/") && !it.contains("Test") && !it.contains("kpds-compose") }
        ?: emptyList()

    val deleted = runGit("diff", "--name-only", "--diff-filter=D", "$sinceCommit..HEAD", "--", "*.kt")
        ?.lines()
        ?.filter { it.endsWith(".kt") && !it.contains("/build/") && !it.contains("/generated/") && !it.contains("Test") && !it.contains("kpds-compose") }
        ?: emptyList()

    return DiffResult(modified = modified, deleted = deleted)
}
```

### Step 2: 컴파일 확인

```bash
./gradlew compileKotlin 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

### Step 3: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/LocalRepoSync.kt
git commit -m "feat: add DiffResult and diffKtFiles to LocalRepoSync"
```

---

## Task 4: CodeIndexAgent — 삭제 처리 + 수정 파일 stale 청크 제거

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgentTest.kt`

### Step 1: 테스트 먼저 작성

`CodeIndexAgentTest.kt`에 추가:

```kotlin
@Test
fun `syncAndIndexChanged deletes chunks for deleted files`() = runBlocking {
    // BM25에 삭제될 파일 청크 미리 삽입
    val bm25 = BM25Index(":memory:")
    bm25.upsert("repo:deleted/Old.kt:OldClass:oldFun:aaa", "fun oldFun()", "deleted/Old.kt")

    // ChromaClient mock — getIdsByFilePath 에서 기존 ID 반환
    val chroma = mockk<ChromaClient>(relaxed = true)
    coEvery { chroma.getOrCreateCollection(any()) } returns "col-id"
    coEvery { chroma.getIdsByFilePath("col-id", any(), "deleted/Old.kt") } returns listOf("repo:deleted/Old.kt:OldClass:oldFun:aaa")

    val localRepoSync = mockk<LocalRepoSync>()
    every { localRepoSync.diffKtFiles(any()) } returns DiffResult(
        modified = emptyList(),
        deleted = listOf("deleted/Old.kt"),
    )
    every { localRepoSync.currentCommit() } returns "newsha"

    val agent = CodeIndexAgent(
        chromaClient = chroma,
        repos = listOf("repo"),
        branch = "develop",
        bm25Index = bm25,
        localRepoPath = "/tmp/repo",
        localRepoSync = localRepoSync,
    )

    agent.syncAndIndexChanged("repo")

    // ChromaDB delete 호출됐는지 검증
    coVerify { chroma.deleteByIds("col-id", listOf("repo:deleted/Old.kt:OldClass:oldFun:aaa")) }
    // BM25에서 삭제됐는지 검증
    assertTrue(bm25.search("oldFun", limit = 5).isEmpty())
    bm25.close()
}
```

### Step 2: 테스트 실패 확인

```bash
./gradlew :test --tests "*.syncAndIndexChanged deletes*" 2>&1 | tail -10
```
Expected: FAIL

### Step 3: `syncAndIndexChanged` 수정

`CodeIndexAgent.kt`의 `syncAndIndexChanged()` 전체 교체:

```kotlin
suspend fun syncAndIndexChanged(repo: String, branch: String = this.branch): Int {
    if (localRepoSync == null) {
        log.warn("syncAndIndexChanged called but localRepoSync is null — skipping")
        return 0
    }

    val state = loadIndexState()
    val lastCommit = state["lastCommit"]

    if (lastCommit == null) {
        log.info("No previous index state — running full index")
        val count = indexAll()
        saveIndexState(localRepoSync.currentCommit() ?: return count)
        return count
    }

    val diff = localRepoSync.diffKtFiles(lastCommit)
    if (diff.modified.isEmpty() && diff.deleted.isEmpty()) {
        log.info("No changed .kt files since {} — skipping", lastCommit.take(8))
        return 0
    }

    val collectionId = chromaClient.getOrCreateCollection(collectionName)

    // 1. 삭제된 파일 — ChromaDB + BM25에서 청크 제거
    if (diff.deleted.isNotEmpty()) {
        log.info("Removing chunks for {} deleted files", diff.deleted.size)
        diff.deleted.forEach { filePath ->
            val ids = chromaClient.getIdsByFilePath(collectionId, repo, filePath)
            if (ids.isNotEmpty()) {
                chromaClient.deleteByIds(collectionId, ids)
                log.info("Deleted {} chunks for removed file: {}", ids.size, filePath)
            }
            bm25Index?.deleteByFilePath(filePath)
        }
    }

    // 2. 수정/추가된 파일 — stale 청크 제거 후 재인덱싱
    val count = if (diff.modified.isNotEmpty()) {
        log.info("Incremental index: {} modified files since {}", diff.modified.size, lastCommit.take(8))
        indexFilesWithStaleDeletion(diff.modified, repo, collectionId)
    } else 0

    saveIndexState(localRepoSync.currentCommit() ?: lastCommit)
    return count
}
```

### Step 4: `indexFilesWithStaleDeletion` 추가

`CodeIndexAgent.kt`의 `indexFiles()` 바로 위에 새 private 함수 추가:

```kotlin
/**
 * 수정된 파일 인덱싱 — 파일 내 삭제된 함수의 stale 청크를 먼저 제거 후 upsert.
 * 변경 없는 함수는 getExistingIds cache hit으로 재임베딩 없음.
 */
private suspend fun indexFilesWithStaleDeletion(
    filePaths: List<String>,
    repo: String,
    collectionId: String,
): Int {
    val localRoot = localRepoPath?.let { File(it) }
        ?: run { log.warn("indexFiles called but localRepoPath is null"); return 0 }
    var total = 0

    filePaths.chunked(50).forEach { batch ->
        // Phase 1: 청크 수집 + stale ID 계산
        val entries = mutableListOf<ChunkEntry>()
        batch.forEach { path ->
            runCatching {
                val content = File(localRoot, path).takeIf { it.exists() }?.readText()
                    ?: run {
                        // 파일이 없으면 삭제된 것 — BM25 정리 (ChromaDB는 syncAndIndexChanged에서 처리)
                        bm25Index?.deleteByFilePath(path)
                        return@forEach
                    }

                val chunks = extractFunctionChunks(content, path)
                if (chunks.isEmpty()) return@forEach

                // stale 청크 제거: 현재 ChromaDB IDs - 새 파싱 IDs
                val newIds = chunks.map { chunkId(repo, it) }.toSet()
                val existingIds = chromaClient.getIdsByFilePath(collectionId, repo, path)
                val staleIds = existingIds.filter { it !in newIds }
                if (staleIds.isNotEmpty()) {
                    chromaClient.deleteByIds(collectionId, staleIds)
                    staleIds.forEach { bm25Index?.delete(it) }
                    log.info("Removed {} stale chunks from {}", staleIds.size, path)
                }

                chunks.forEach { chunk ->
                    val doc = buildChunkDocument(chunk)
                    val id = chunkId(repo, chunk)
                    val sigHash = chunk.signature.hashCode().and(0xFFFFFF).toString(16)
                    entries += ChunkEntry(
                        id = id,
                        doc = doc,
                        meta = mapOf(
                            "repo" to repo,
                            "file_path" to chunk.filePath,
                            "class_name" to chunk.className,
                            "function_name" to chunk.functionName,
                            "sig_hash" to sigHash,
                            "branch" to branch,
                            "chunk_type" to chunk.chunkType,
                        ),
                    )
                    bm25Index?.upsert(id, doc, chunk.filePath)
                    total++
                }
            }.onFailure { log.warn("Failed to read {}/{}: {}", repo, path, it.message) }
        }

        if (entries.isEmpty()) return@forEach

        // Phase 2: 이미 인덱싱된 ID 확인 → 새 것만 임베딩 (재임베딩 없음)
        val existingIds = chromaClient.getExistingIds(collectionId, entries.map { it.id })
        val newEntries = entries.filter { it.id !in existingIds }

        val embeddings: List<List<Float>> = if (embeddingFn != null && newEntries.isNotEmpty()) {
            coroutineScope {
                newEntries.map { entry ->
                    async {
                        embeddingSemaphore.withPermit {
                            runCatching { embeddingFn(entry.doc) }.getOrElse { emptyList() }
                        }
                    }
                }.awaitAll()
            }
        } else emptyList()

        val validPairs = if (embeddings.size == newEntries.size) {
            newEntries.zip(embeddings).filter { (_, emb) -> emb.isNotEmpty() }
        } else emptyList()

        if (validPairs.isEmpty()) return@forEach
        chromaClient.upsertDocuments(
            collectionId,
            validPairs.map { it.first.id },
            validPairs.map { it.first.doc },
            embeddings = validPairs.map { it.second },
            metadatas = validPairs.map { it.first.meta },
        )
    }

    return total
}
```

### Step 5: 테스트 통과 확인

```bash
./gradlew :test --tests "*.CodeIndexAgentTest" 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

### Step 6: 전체 테스트 확인

```bash
./gradlew :test 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL

### Step 7: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgentTest.kt
git commit -m "feat: surgical stale chunk deletion on incremental sync"
```

---

## 완료 기준

- [ ] `ChromaClientTest` 모두 PASS (새 파싱 테스트 포함)
- [ ] `BM25Index deleteByFilePath` 테스트 PASS
- [ ] `syncAndIndexChanged deletes chunks for deleted files` 테스트 PASS
- [ ] `./gradlew :test` 전체 PASS
- [ ] 봇 재시작 후 실제 파일 삭제 커밋 반영 시 로그에 `Deleted N chunks for removed file` 확인
