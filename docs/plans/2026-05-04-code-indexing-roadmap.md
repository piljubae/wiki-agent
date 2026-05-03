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

## Step 3: 하이브리드 검색 — BM25 + 벡터 ✅ (완료)

**문제**: 벡터 검색만으로는 정확한 키워드 검색이 약함.

```
"KMA-7275" 쿼리:
  벡터 검색 → 의미적으로 유사한 문서 (실제 KMA-7275 놓칠 수 있음)
  BM25 검색 → "KMA-7275" 문자열이 포함된 문서 (정확히 매칭)
  하이브리드 → 두 결과 RRF 병합
```

**구현**: `BM25Index` (SQLite FTS5 + unicode61 tokenizer), `BM25Index.mergeRRF()`

```kotlin
// RRF: score = 1 / (60 + rank + 1), k=60은 표준값
fun mergeRRF(vectorIds: List<String>, bm25Ids: List<String>): List<String>
```

**한계**: unicode61은 한국어 형태소 분리 미지원 → 한국어 쿼리는 벡터만 사용, 영어/코드 키워드에서 BM25 효과 발휘.

---

## Step 4: 라인 기반 파서 ✅ (완료, Tree-sitter 대체)

**문제**: Regex는 다음을 놓침:
- companion object 내 함수
- 중첩 클래스 / inner class
- extension function (`fun ViewModel.getString()`)
- 멀티라인 파라미터

**결정**: Tree-sitter 대신 순수 JVM 라인 기반 파서 — native 바이너리 의존성 없이 99% 케이스 처리.

**핵심 구조**: `ClassFrame` 스택 + 중괄호 깊이 추적
```kotlin
data class ClassFrame(val name: String, val entryDepth: Int)
val classStack = ArrayDeque<ClassFrame>()
var braceDepth = 0
// companion object → ClassFrame("(companion)", depth) 로 구별
// className = classStack.lastOrNull { it.name != "(companion)" }?.name
```

**버그픽스 (코드 리뷰 발견)**:
- ChromaDB 이중 쿼리 제거 → `vectorResults` 상단에서 1회만 호출
- 오버로드 함수 ID 충돌 → 시그니처 해시 suffix 추가 (`{repo}:{path}:{class}:{fn}:{sigHash}`)

---

## 비용/시간 추정 (kurly-android 기준)

**규모**

| 모듈 | .kt 파일 |
|------|---------|
| features | 1,762 |
| domain | 847 |
| data | 609 |
| app | 332 |
| core + kotlin-core | 172 |
| 테스트 제외 소스 합계 | **~2,700** |

인덱싱 대상: **~32,000 함수 청크** (2,700파일 × 평균 12함수)

**비용**

| 항목 | 수치 |
|------|------|
| 청크당 문서 크기 | ~200자 / ~70토큰 |
| 임베딩 총 토큰 | ~2.2M |
| Google text-embedding-004 | ~$0.06 |
| LLM 인덱싱 타임 호출 | **없음** (검색 시만 사용) |
| BM25 SQLite | API 없음, $0 |

ChromaDB 기본 임베딩(all-MiniLM-L6-v2) 사용 시 비용 $0, 모두 로컬 처리.

**시간**

| | 처음 (전체) | 증분 (PR당 ~20파일) |
|--|------------|-------------------|
| 파싱 | ~2분 | 수 초 |
| ChromaDB 임베딩 + 저장 | ~10~15분 | ~30초 |
| BM25 SQLite | ~1분 | 수 초 |
| **합계** | **~15~20분** | **~30~60초** |

---

## 상용 도구 비교 (Step 4 완료 후 기준)

| 항목 | wiki-agent | Cursor | Sourcegraph Cody | Continue.dev |
|------|-----------|--------|-----------------|-------------|
| 파서 | 라인 기반 (ClassFrame 스택) | Tree-sitter AST | 언어별 심볼 추출기 | Tree-sitter |
| 청킹 단위 | 함수 레벨 (~500자) | 함수 레벨 (~500자) | 파일+심볼 혼합 | 함수 레벨 |
| LLM 인덱싱 | **없음** | **없음** | **없음** | **없음** |
| 검색 방식 | **BM25 + 벡터 하이브리드** | 벡터만 | **BM25 + 벡터 하이브리드** | 벡터만 |
| 임베딩 | Google text-embedding-004 | 자체 코드 전용 | 코드 전용 모델 | voyage-code-3 등 |
| 증분 인덱싱 | git diff / PR merge webhook | 파일 저장 즉시 | 파일 변경 즉시 | 파일 저장 즉시 |
| 오버로드 함수 | 시그니처 해시 구별 | AST 정확히 구별 | 정확히 구별 | 정확히 구별 |

**검색 방식은 Cody와 동일 수준(하이브리드)**. 파서는 Tree-sitter 대비 정확도 약간 낮으나 네이티브 의존성 없고 Slack bot 목적에는 충분.

---

## 검색 품질 영향도

| 쿼리 예시 | Step1 전 | Step2 후 | Step3 후 | Step4 후 |
|-----------|---------|---------|---------|---------|
| "BannerViewModel 어디있어?" | ✅ | ✅ | ✅ | ✅ |
| "배너 클릭 이벤트 처리" | 클래스 전체 반환 | 함수 직접 반환 ✅ | ✅ | ✅ |
| "KMA-7275" | 운에 맡김 | 운에 맡김 | 정확히 매칭 ✅ | ✅ |
| `fun load()` + `fun load(id)` 공존 | 나중이 앞 덮어씀 | 나중이 앞 덮어씀 | 나중이 앞 덮어씀 | 시그니처 해시로 구별 ✅ |
| companion object 안 함수 | 누락 | 누락 | 누락 | 정확히 추출 ✅ |
