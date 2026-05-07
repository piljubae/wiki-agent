# 코드 흐름 분석 설계 — Kotlin IR 컴파일러 플러그인 기반 콜 그래프

## 배경

wiki-agent의 codeSearch는 함수 **정의**만 인덱싱한다. 아래 세 가지 질문에 답하지 못한다:

- **A. 역방향 참조**: "이 함수가 어디서 호출돼?"
- **B. 레이어 체인**: "ViewModel에서 Repository까지 흐름 보여줘"
- **C. 임팩트 분석**: "이 코드 바꾸면 어디가 영향받아?"

세 질문 모두 **콜 그래프(call graph)** 가 있으면 해결된다.

---

## 기술 선택 과정

| 방식 | 정확도 | 빌드 필요 | 채택 이유 |
|------|--------|----------|----------|
| Regex | ~80% | 없음 | 주석·문자열 오탐, 멀티라인 취약 |
| Tree-sitter | ~90% | 없음 | JNI 바인딩 필요, Kotlin 전용 아님 |
| Detekt PSI | ~90% | 없음 (type res는 필요) | type resolution 시 Kotlin IR과 유사 |
| KSP | — | 없음 | 함수 바디 미노출, 콜 그래프 불가 |
| **Kotlin IR 플러그인** | **100%** | **있음** | **FQN 완전 해상, IrCall 직접 접근** |

**Kotlin IR 플러그인을 선택한 이유:**
- IR 단계는 타입 체크 완료 후라 `IrCall.symbol`이 완전한 FQN을 제공한다
- `const val`은 IR에서 인라인되지만, 해당 검색은 기존 ChromaDB const val 인덱스로 커버한다
- wiki-agent 전용 클론에 `init.gradle`로 플러그인을 주입하므로 Kurly Android 소스 수정이 불필요하다

---

## 아키텍처

```
[wiki-agent 전용 클론]
  kurly-android/
    init.gradle          ← 플러그인 주입 (미커밋, 소스 수정 없음)
    app/ features/ ...   ← 원본 소스 그대로

[CallGraphPlugin]        ← 별도 Gradle 플러그인 프로젝트
  IrGenerationExtension
    visitCall(IrCall)
      → caller FQN + callee FQN 추출
      → call_graph.db upsert

[call_graph.db] (SQLite)
  call_edges: caller_fqn → callee_fqn, caller_file
  functions:  fqn → class_name, file_path, layer

[CallGraphIndexAgent]    ← wiki-agent 내 신규 컴포넌트
  ./gradlew compileDebugKotlin (증분)
  → 플러그인이 DB 자동 갱신

[CodeFlowTool]           ← 신규 Slack 툴
  findCallers(fn)   → SELECT caller FROM call_edges WHERE callee = fn
  traceChain(fn)    → BFS forward, 최대 5홉
  findImpact(fn)    → BFS reverse, 전이 클로저
```

---

## 데이터 모델

```sql
CREATE TABLE call_edges (
    caller_fqn  TEXT NOT NULL,
    callee_fqn  TEXT NOT NULL,
    caller_file TEXT,
    PRIMARY KEY (caller_fqn, callee_fqn)
);

CREATE TABLE functions (
    fqn        TEXT PRIMARY KEY,
    class_name TEXT,
    file_path  TEXT,
    layer      TEXT   -- "viewmodel" | "usecase" | "repository" | "api" | "other"
);

CREATE INDEX idx_callee ON call_edges(callee_fqn);
CREATE INDEX idx_caller ON call_edges(caller_fqn);
```

---

## 업데이트 주기

기존 60분 폴링 사이클에 통합:

```
[매 60분]
1. LocalRepoSync.sync()              git fetch + reset --hard origin/develop
2. changedKtFiles(lastCommit)        변경 파일 감지
3. ChromaDB 재임베딩                 기존 코드 인덱스 갱신
4. ./gradlew compileDebugKotlin      증분 빌드 (변경 모듈만)
   → CallGraphPlugin 자동 실행       call_graph.db 부분 갱신
5. lastCommit SHA 저장
```

변경 없으면 Gradle `UP-TO-DATE` → 수초 이내 종료.

---

## init.gradle 주입 방식

```groovy
// kurly-android 클론 루트/init.gradle (커밋 안 함)
allprojects {
    apply plugin: 'io.github.veronikapj.callgraph'
    extensions.findByName('callGraph')?.configure {
        outputPath = "/path/to/wiki-agent/call_graph.db"
    }
}
```

Kurly Android 소스를 건드리지 않는다.

---

## Slack 쿼리 예시

```
"ProductDetailViewModel.loadProduct 어디서 불려?"
→ findCallers: call_edges WHERE callee_fqn LIKE '%loadProduct%'
→ ProductDetailFragment.onViewCreated, DeepLinkHandler.handleProductLink

"상품 상세 로드 흐름 보여줘"
→ traceChain: ProductDetailViewModel.loadProduct
→ → GetProductDetailUseCase.invoke
→ → → ProductRepositoryImpl.getProduct
→ → → → ProductApi.getProduct

"panelCode 타입 바꾸면 어디 영향?"
→ findImpact: SectionMapper.mapPanelCode (역방향 BFS)
→ SectionAdapter.bind, HomeViewModel.processSections, ...
```

---

## 파일 변경 범위

| 파일 | 작업 |
|------|------|
| `callgraph-plugin/` (신규 프로젝트) | Kotlin IR 플러그인 구현 |
| `kurly-android-clone/init.gradle` | 플러그인 주입 (미커밋) |
| `src/main/kotlin/.../knowledge/CallGraphIndexAgent.kt` | Gradle 실행 + DB 관리 |
| `src/main/kotlin/.../agent/tool/CodeFlowTool.kt` | findCallers / traceChain / findImpact |
| `src/main/kotlin/.../agent/OrchestratorAgent.kt` | 라우터에 코드 흐름 쿼리 추가 |
| `src/main/kotlin/.../Main.kt` | 폴링 사이클에 CallGraphIndexAgent 통합 |
