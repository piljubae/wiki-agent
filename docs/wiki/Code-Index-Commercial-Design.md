# 상용 제품 설계와의 비교 및 개선 로드맵

Cursor, Sourcegraph Cody, Continue.dev 등 상용 코드 검색 도구의 설계를 분석하고,
wiki-agent 코드 인덱싱에 단계적으로 적용하는 로드맵입니다.

## 상용 제품은 어떻게 동작하나

### 공통 원칙

**LLM은 인덱싱 타임에 쓰지 않는다.** 모든 상용 도구가 공유하는 핵심 원칙입니다.

```
[틀린 접근]  파일 → LLM 요약 생성 → 요약을 임베딩 → DB 저장
[맞는 접근]  파일 → 코드 전용 임베딩 모델 → DB 저장
                                              ↓
             사용자 쿼리 → 임베딩 → 유사 청크 검색 → LLM으로 답변 생성
```

LLM은 검색 *후* 답변을 생성할 때만 사용합니다.

### Cursor

- **파서**: Tree-sitter AST → 함수/클래스 경계에서 청킹
- **청킹 단위**: 함수 레벨 (~500자)
- **임베딩**: 자체 서버 (모델 미공개, OpenAI 추정)
- **증분**: 파일 저장 시 즉시 해당 파일만 재인덱싱 (Merkle 해시 추적)
- **검색**: 벡터 유사도

### Sourcegraph Cody

- **파서**: 언어별 심볼 추출기
- **청킹 단위**: 파일 + 심볼 단위 혼합
- **임베딩**: OpenAI 또는 자체 모델
- **검색**: BM25 키워드 + 벡터 **하이브리드**
- **규모**: 300,000개+ 레포, 90GB 모노레포 처리

### Continue.dev

- **파서**: Tree-sitter
- **임베딩**: voyage-code-3, nomic-embed-text 등 사용자 선택
- **로컬 옵션**: Ollama로 완전 로컬 실행 가능

## 현재 wiki-agent와의 차이

| 항목 | wiki-agent (현재) | 상용 (Cursor 기준) |
|------|-----------------|------------------|
| 파서 | Regex (top-level만) | Tree-sitter AST |
| 청킹 단위 | 클래스 레벨 | **함수 레벨** (~500자) |
| LLM 인덱싱 | 클래스당 1회 (제거됨) | **없음** |
| 임베딩 | Google text-embedding-004 | 코드 전용 모델 |
| 증분 인덱싱 | git diff 기반 | 파일 저장 즉시 |
| 검색 방식 | 벡터만 | **BM25 + 벡터 하이브리드** |
| 중첩 클래스 | 누락 | 정확히 추출 |

## 개선 로드맵

### Step 1: 증분 인덱싱 ✅ (완료)

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

### Step 2: 함수 단위 청킹 ✅ (완료)

**문제**: 클래스 전체를 하나의 청크로 저장하면 관련 없는 코드가 LLM 컨텍스트에 포함됨.

```
"배너 클릭 처리 어디서?" 쿼리 →
이전: BannerViewModel 전체 반환 (200줄 포함)
이후: onBannerClick() 함수만 반환 (10~20줄)
```

**구현**: `CodeIndexAgent.extractFunctionChunks()` + `buildChunkDocument()`

```kotlin
// CodeIndexAgent.kt — CodeChunk 데이터 클래스
data class CodeChunk(
    val filePath: String,
    val className: String,      // "" = top-level 함수
    val functionName: String,
    val signature: String,      // "fun onBannerClick(bannerId: String): Unit"
    val body: String,           // 함수 바디 최대 500자
    val packageName: String,
)
```

**ChromaDB id 세분화**: `{repo}:{path}:{class}:{function}`

```kotlin
// 이전 (클래스 단위)
id = "$repo:$path:${cls.name}"

// 이후 (함수 단위)
id = "$repo:${chunk.filePath}:${chunk.className}:${chunk.functionName}"
```

**문서 포맷** (ChromaDB에 저장되는 텍스트):
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

**처리 흐름**:
```
Kotlin 파일 → extractFunctionChunks() → 함수별 CodeChunk
  → buildChunkDocument() → 임베딩 → ChromaDB upsert
```

**Regex 방식의 한계** (Step 4에서 해결 예정):
- 멀티라인 파라미터 지원 안 됨
- companion object 안 함수는 className이 외부 클래스로 기록됨
- extension function 처리 불완전

---

### Step 3: 하이브리드 검색 — BM25 + 벡터 (예정)

**문제**: 벡터 검색만으로는 정확한 키워드 검색이 약합니다.

```
"KMA-7275" 쿼리:
  벡터 검색 → 의미적으로 유사한 문서 (실제 KMA-7275 놓칠 수 있음)
  BM25 검색 → "KMA-7275" 문자열이 포함된 문서 (정확히 매칭)
  하이브리드 → 두 결과 통합 (RRF 알고리즘)
```

ChromaDB는 순수 벡터 DB라 BM25를 기본 지원하지 않습니다.
별도 구현 필요: 역인덱스 파일 또는 SQLite FTS5 활용.

---

### Step 4: Tree-sitter AST 파싱 (예정)

**문제**: 현재 Regex는 다음을 놓칩니다:
- 클래스 내부 companion object
- 중첩 클래스 / inner class
- extension function
- top-level function (클래스 밖)

**구현 방향**: `java-tree-sitter` 라이브러리 + Kotlin grammar

```kotlin
// 예시: Tree-sitter로 모든 선언 추출
val parser = Parser()
parser.setLanguage(Kotlin.INSTANCE)
val tree = parser.parse(content)
// AST 순회로 class_declaration, function_declaration, object_declaration 추출
```

Tree-sitter는 런타임 네이티브 바이너리가 필요해 JVM 환경에서 설정이 복잡합니다.
Step 2 함수 청킹을 먼저 regex 기반으로 구현하고, 이후 Tree-sitter로 교체하는 순서를 권장합니다.

---

## 검색 품질 영향도

각 Step이 실제 쿼리에 미치는 영향:

| 쿼리 예시 | 이전 | Step2 후 | Step3 후 |
|-----------|------|---------|---------|
| "BannerViewModel 어디있어?" | ✅ | ✅ | ✅ |
| "배너 클릭 이벤트 처리" | 클래스 전체 반환 | **함수 직접 반환** ✅ | ✅ |
| "KMA-7275" | 운에 맡김 | 운에 맡김 | 정확히 매칭 |
| "panelCode 어디서 쓰여?" | 보통 | ✅ | ✅ |
| companion object 안 함수 | 누락 | 누락 (Step4 후 ✅) | Step4 후 ✅ |

---

## 참고 자료

- [Build Real-Time Codebase Indexing for AI Code Generation (CocoIndex)](https://cocoindex.io/blogs/index-code-base-for-rag/)
- [cAST: Enhancing Code RAG with Structural Chunking via AST (arXiv 2506.15655)](https://arxiv.org/html/2506.15655v1)
- [How Cursor Actually Indexes Your Codebase](https://towardsdatascience.com/how-cursor-actually-indexes-your-codebase/)
- [How Cody provides remote repository context (Sourcegraph)](https://sourcegraph.com/blog/how-cody-provides-remote-repository-context/)
- [Continue.dev Embedding Documentation](https://docs.continue.dev/customize/model-roles/embeddings)

---

> **관련 문서:** [Code-Index-Architecture.md](Code-Index-Architecture.md) · [Code-Index-Feasibility.md](Code-Index-Feasibility.md)
