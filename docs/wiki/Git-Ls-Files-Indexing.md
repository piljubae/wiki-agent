# git ls-files 기반 파일 탐색

코드 인덱싱에서 `.kt` 파일을 찾을 때 `File.walk()` 대신 `git ls-files`를 사용하는 이유와 원리입니다.

## 문제: File.walk()는 너무 많이 걷는다

`File.walk()`로 로컬 repo를 재귀 탐색하면 Git이 추적하지 않는 파일까지 모두 방문합니다.

```
kurly-android/
├── .worktrees/          ← git worktree 23개 (각각 전체 소스 복사본)
│   ├── KMA-7275/
│   ├── KMA-7040/
│   └── ...
├── build/               ← 빌드 아티팩트 (.gitignore 대상)
├── .gradle/             ← Gradle 캐시 (.gitignore 대상)
└── app/src/...          ← 실제 소스
```

결과: **5,043개** 파일이 **145,679개**로 부풀어 오름

경로 필터(`!contains("/build/")` 등)로 어느 정도 막을 수 있지만, `.worktrees/` 안에는 동일한 경로 구조가 그대로 있어서 필터를 통과해버립니다.

## 해결: git ls-files

`git ls-files`는 현재 워킹트리에서 **Git이 추적(track)하는 파일만** 반환합니다.

```bash
git ls-files '*.kt'
```

Git이 추적한다는 것은 `git add`로 스테이징된 적이 있거나 이미 커밋된 파일을 의미합니다.

### .gitignore 파일이 자동 제외되는 이유

`.gitignore`에 등록된 경로는 `git add`가 무시하므로 Git의 인덱스(index)에 올라가지 않습니다. `git ls-files`는 Git 인덱스를 기준으로 목록을 반환하기 때문에 `.gitignore` 대상은 결과에 나오지 않습니다.

### .worktrees/ 파일이 자동 제외되는 이유

`git worktree add`로 만든 워킹트리는 메인 repo와 **별도의 워킹트리**입니다. 메인 repo의 Git 인덱스에는 `.worktrees/` 하위 파일이 등록되지 않기 때문에 `git ls-files`는 이 경로를 완전히 무시합니다.

## 실제 코드

```kotlin
// LocalRepoSync.kt
fun allKtFiles(): List<String> {
    val output = runGit("ls-files", "*.kt") ?: return emptyList()
    return output.lines()
        .filter { it.endsWith(".kt") && !it.contains("Test") }
}

private fun runGit(vararg args: String): String? {
    return runCatching {
        ProcessBuilder("git", *args)
            .directory(File(repoPath))
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText()
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}
```

`ProcessBuilder`로 git을 직접 실행하고 stdout을 한 줄씩 파싱합니다.

## File.walk() vs git ls-files 비교

| | `File.walk()` | `git ls-files` |
|--|--------------|----------------|
| `.gitignore` 적용 | ❌ 직접 필터 필요 | ✅ 자동 |
| `.worktrees/` 제외 | ❌ 경로 필터로 힘듦 | ✅ 자동 |
| build/, .gradle/ 제외 | ❌ 직접 필터 필요 | ✅ 자동 |
| 실행 방식 | JVM 파일 시스템 API | git 프로세스 실행 |

---

> **Source:** [LocalRepoSync.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/LocalRepoSync.kt)  
> **관련:** [Code-Index-Architecture](Code-Index-Architecture) · [Incremental-Indexing](Incremental-Indexing)
