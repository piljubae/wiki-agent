# 증분 인덱싱 (Incremental Indexing)

전체 재인덱싱 대신 변경된 파일만 재처리하는 방식입니다.

## 왜 필요한가

전체 재인덱싱은 5,043 파일 × ~3 청크 = 15,417개를 매번 처리합니다. 하루에 파일 20개만 바뀌어도 동일한 비용을 치러야 하죠.

| 방식 | 처리 청크 수 | 소요 시간 |
|------|------------|---------|
| 전체 재인덱싱 | 15,417개 | ~10분 |
| 증분 인덱싱 (20파일 변경) | ~60개 | 수초 |

## 동작 흐름

```
기동 시 / 60분 주기 자동 실행
    ↓
.wiki/code-index-state.json 에서 lastCommit 읽기
    ↓ (없으면 전체 재인덱싱 → 이후 저장)
git diff lastCommit..HEAD --name-only *.kt
    ↓ (변경 없으면 종료)
변경된 파일만 청킹 + 임베딩 + ChromaDB upsert
    ↓
현재 HEAD SHA를 lastCommit으로 저장
```

## 핵심 코드

```kotlin
// CodeIndexAgent.syncAndIndexChanged()
val state = loadIndexState()
val lastCommit = state["lastCommit"]

if (lastCommit == null) {
    // 최초 실행 — 전체 인덱싱
    val count = indexAll()
    saveIndexState(localRepoSync.currentCommit() ?: return count)
    return count
}

val changedFiles = localRepoSync.changedKtFiles(lastCommit)
if (changedFiles.isEmpty()) {
    log.info("No changed .kt files since {} — skipping", lastCommit.take(8))
    return 0
}

val count = indexFiles(changedFiles, repo)
saveIndexState(localRepoSync.currentCommit() ?: lastCommit)
```

```kotlin
// LocalRepoSync.changedKtFiles()
fun changedKtFiles(sinceCommit: String): List<String> {
    val output = runGit("diff", "--name-only", "$sinceCommit..HEAD", "--", "*.kt")
        ?: return emptyList()
    return output.lines()
        .filter { it.endsWith(".kt") }
        .filter { !it.contains("/build/") && !it.contains("/generated/") && !it.contains("Test") }
}
```

## 상태 파일

```json
// .wiki/code-index-state.json
{ "lastCommit": "a3f9c12b..." }
```

- 원자적 저장: `.tmp` 파일에 먼저 쓰고 rename — 중간에 프로세스가 죽어도 이전 상태가 유지됨
- 별도 클론(`~/.wiki/index/kurly-android`)을 기준으로 저장되므로 로컬 브랜치와 무관

## 별도 클론과의 관계

증분 인덱싱은 `~/.wiki/index/kurly-android`(별도 클론)를 기준으로 동작합니다.

```kotlin
// LocalRepoSync.sync()
fun sync(branch: String = "develop"): String? {
    runGit("fetch", "origin") ?: return null
    runGit("reset", "--hard", "origin/$branch") ?: return null
    return currentCommit()
}
```

60분마다 `origin/develop`으로 reset 후 `changedKtFiles()`로 diff를 추출합니다. 개발자의 로컬 작업 브랜치와 섞이지 않습니다.

---

> **Source:** [LocalRepoSync.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/LocalRepoSync.kt) · [CodeIndexAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt)  
> **관련:** [Git-Ls-Files-Indexing](Git-Ls-Files-Indexing) · [Code-Index-Architecture](Code-Index-Architecture)
