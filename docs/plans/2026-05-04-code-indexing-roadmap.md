# 코드 인덱싱 상용 설계 적용 로드맵

wiki-agent 코드 인덱싱을 Cursor/Cody 수준으로 개선하는 단계별 구현 일지.

---

## Step 1: 증분 인덱싱 ✅ (완료)

**구현**: `LocalRepoSync` + `CodeIndexAgent.syncAndIndexChanged()`

```
이전: /wiki reindex-code → 5,000파일 전체 처리 (7~10분)
이후: polling/webhook → git diff → 변경 파일만 처리 (수십 초)
```

**핵심 코드**:
```kotlin
// LocalRepoSync.kt
fun changedKtFiles(sinceCommit: String): List<String> {
    val output = runGit("diff", "--name-only", "$sinceCommit..HEAD", "--", "*.kt")
    return output.lines().filter { it.endsWith(".kt") && ...필터... }
}

// CodeIndexAgent.kt
suspend fun syncAndIndexChanged(repo: String): Int {
    val lastCommit = loadIndexState()["lastCommit"]
        ?: return indexAll()  // 최초 실행 시 전체 인덱싱
    val changedFiles = localRepoSync.changedKtFiles(lastCommit)
    val count = indexFiles(changedFiles, repo)
    saveIndexState(localRepoSync.currentCommit())
    return count
}
```

**상태 저장**: `.wiki/code-index-state.json`
```json
{"lastCommit": "abc1234..."}
```

---

## Step 2: 함수 단위 청킹 ✅ (완료)

**문제**: 클래스 전체를 하나의 청크로 저장하면 관련 없는 코드가 LLM 컨텍스트에 포함됨.

```
"배너 클릭 처리 어디서?" 쿼리 →
이전: BannerViewModel 전체 반환 (200줄 포함)
이후: onBannerClick() 함수만 반환 (10~20줄)
```

**구현**: `CodeIndexAgent.extractFunctionChunks()` + `buildChunkDocument()`

```kotlin
data class CodeChunk(
    val filePath: String,
    val className: String,      // "" = top-level 함수
    val functionName: String,
    val signature: String,      // "fun onBannerClick(bannerId: String): Unit"
    val body: String,           // 함수 바디 최대 500자
    val packageName: String,
)
```

**ChromaDB id**: `{repo}:{path}:{class}:{function}` (이전: `{repo}:{path}:{class}`)

**ChromaDB 저장 문서 포맷**:
```
package com.kurly.feature.banner
file: features/banner/BannerViewModel.kt
class: BannerViewModel

fun onBannerClick(bannerId: String): Unit {
    viewModelScope.launch {
        _events.send(BannerEvent.Navigate(bannerId))
    }
}
```

**현재 Regex 한계** (Step 4에서 해결):
- 멀티라인 파라미터 미지원
- companion object 안 함수는 className이 외부 클래스로 기록
- extension function 처리 불완전

---

## Step 3: 하이브리드 검색 — BM25 + 벡터 (예정)

**문제**: 벡터 검색만으로는 정확한 키워드 검색이 약함.

```
"KMA-7275" 쿼리:
  벡터 검색 → 의미적으로 유사한 문서 (실제 KMA-7275 놓칠 수 있음)
  BM25 검색 → "KMA-7275" 문자열이 포함된 문서 (정확히 매칭)
  하이브리드 → 두 결과 통합 (RRF 알고리즘)
```

ChromaDB는 순수 벡터 DB → BM25 별도 구현 필요.
옵션: 역인덱스 파일 또는 SQLite FTS5.

---

## Step 4: Tree-sitter AST 파싱 (예정)

**문제**: Regex는 다음을 놓침:
- companion object 내 함수
- 중첩 클래스 / inner class
- extension function
- top-level function (클래스 밖)

**구현 방향**: `java-tree-sitter` 라이브러리 + Kotlin grammar

```kotlin
val parser = Parser()
parser.setLanguage(Kotlin.INSTANCE)
val tree = parser.parse(content)
// AST 순회: class_declaration, function_declaration, object_declaration
```

JVM에서 네이티브 바이너리 연동 필요 — Step 3 이후 진행 권장.

---

## 검색 품질 영향도

| 쿼리 예시 | Step1 전 | Step2 후 | Step3 후 | Step4 후 |
|-----------|---------|---------|---------|---------|
| "BannerViewModel 어디있어?" | ✅ | ✅ | ✅ | ✅ |
| "배너 클릭 이벤트 처리" | 클래스 전체 반환 | 함수 직접 반환 ✅ | ✅ | ✅ |
| "KMA-7275" | 운에 맡김 | 운에 맡김 | 정확히 매칭 ✅ | ✅ |
| companion object 안 함수 | 누락 | 누락 | 누락 | 정확히 추출 ✅ |
