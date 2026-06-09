# wiki-agent 모듈화 로드맵

작성일: 2026-06-09
목표: 단일 Gradle 모듈(`wiki-agent`)을 클린 아키텍처 의존성 방향에 맞춰 **독립·테스트 쉬운 것부터** 점진적으로 Gradle 멀티모듈로 분리한다.

원칙:
1. **leaf부터** — 내부 의존성이 0인 패키지가 가장 먼저 떨어진다.
2. **한 번에 한 모듈** — 추출 → 빌드 → 테스트 → 커밋. 폭발 반경 최소화.
3. **패키지명 유지** — `io.github.veronikapj.wiki.*` 경로를 그대로 두면 호출처 코드 수정이 0줄에 가깝다.
4. **순환 의존성은 추출 전에 끊는다** — 모듈은 순환을 허용하지 않으므로 사전 리팩터링 필수.

---

## 1. 현재 의존성 그래프

`src/main/kotlin/io/github/veronikapj/wiki/` 패키지 간 `import` 기준.

| 패키지 | LOC | 나가는 내부 의존 (outgoing) | 받는 의존 (incoming) |
|---|---:|---|---:|
| `config` | 399 | — (leaf) | 5 |
| `confluence` | 357 | — (leaf) | 4 |
| `context` | 145 | — (leaf) | 4 |
| `github` | 402 | — (leaf) | 6 |
| `rag` | 369 | — (leaf) | 5 |
| `llm` | 537 | config | 1 |
| `knowledge` | 1563 | agent, github, rag | 3 |
| `onboarding` | 902 | agent, confluence, github | 3 |
| `agent` | 2215 | confluence, context, github, knowledge, onboarding, rag | 7 |
| `slack` | 1413 | agent, config, confluence, context, onboarding | 1 |

```
            slack ─────────────────────────────┐
              │                                 │
              ▼                                 ▼
            agent ◄──────────► knowledge      config ◄── llm
           ╱  │  ╲ ◄──────► onboarding          ▲
          ╱   │   ╲             │               │
   confluence │  github ◄───────┘          (모두가 의존)
         context  rag
```

**leaf 노드(추출 1순위):** `config`, `confluence`, `context`, `github`, `rag`
**최상위 조립부(추출 마지막):** `slack`(앱 진입), `agent`(오케스트레이터)

---

## 2. 핵심 발견 — 순환 의존성 2개

모듈로 떼기 전에 반드시 끊어야 한다.

### Cycle A: `agent ↔ knowledge`
- `agent/OrchestratorAgent.kt` → `knowledge.KnowledgeTool`
- `agent/tool/CodeSearchTool.kt` → `knowledge.BM25Index`
- `knowledge/KnowledgeTool.kt` → `agent.tool.SourceTracker`  ← **역방향**

### Cycle B: `agent ↔ onboarding`
- `agent/OrchestratorAgent.kt` → `onboarding.OnboardingTool`
- `onboarding/OnboardingTool.kt` → `agent.tool.{CodeSearchTool, ConfluenceTool, SourceTracker}`  ← **역방향**

### 진단
역방향 화살표의 출발점이 전부 **`agent/tool/`** 다. 이 패키지는 사실 두 종류가 섞여 있다:

- **저수준 공유 기본요소** (`SourceTracker`, `CodeSearchTool`, `ConfluenceTool` …) — 다른 피처가 가져다 쓰는 도구
- **고수준 오케스트레이터** (`agent/OrchestratorAgent.kt`) — 위 도구들을 조립

→ **해결책:** `agent/tool/`를 별도 모듈 `:tool`로 떼어내면 양쪽 순환이 동시에 풀린다.
- `knowledge → :tool` (단방향)
- `onboarding → :tool` (단방향)
- `agent(orchestrator) → :tool, knowledge, onboarding` (단방향)

이게 Phase 3의 핵심 작업이다.

---

## 3. 목표 모듈 구조

```
:config          ← 설정 로딩/스키마 (shared kernel, 의존 0)
:context         ← 대화 이력 / 프로젝트 메모리 (의존 0)
:confluence      ← Confluence REST 클라이언트 (의존 0)
:github          ← GitHub REST 클라이언트 (의존 0)
:rag             ← ChromaDB 클라이언트 (의존 0)
:llm             ← LLM executor 빌더            → :config
:tool            ← Tool 계약 + 공유 기본요소     → :confluence :github :rag :context
:knowledge       ← 지식베이스/코드 인덱스        → :tool :github :rag
:onboarding      ← 온보딩 (clean arch 레이어링)  → :tool :confluence :github
:agent           ← OrchestratorAgent (조립)      → :tool :knowledge :onboarding :context …
:app (root)      ← Slack 게이트웨이 + Main        → 전부
```

의존성은 **항상 위에서 아래로만** 흐른다. (`:config`/`:context`가 가장 안쪽, `:app`이 가장 바깥)

---

## 4. 단계별 추출 순서

각 Phase는 독립적으로 머지 가능한 단위. 위험도/노력/테스트 레이어 표기.

### Phase 0 — 멀티모듈 기반 세팅 (선행 1회)
- 루트 `build.gradle.kts`를 `subprojects {}`/`allprojects {}` 공통 설정으로 분리하거나 buildSrc/version catalog 도입
- `settings.gradle.kts`에 모듈 include 추가 준비
- 위험: 낮음 / 테스트: 빌드 통과 확인

### Phase 1 — `:context` ⭐ 첫 모듈 (가장 쉬움)
- **이동:** `context/ConversationStore.kt`, `context/ProjectMemory.kt` (+ `Turn`)
- **의존:** `kotlinx-serialization`, slf4j (내부 의존 0)
- **호출처:** `OrchestratorAgent`, `SlackBotGateway`, `SlackConfigHandler`, `Main` (각 build에 `implementation(project(":context"))` 한 줄)
- **왜 1순위:** 내부 의존 0 + 네트워크/LLM 없음. `compress(summarizer: suspend (String)->String)`로 이미 DI 완료 → 람다 주입만으로 테스트.
- **테스트 (L1):** append→load 순서, `load(maxTurns)` takeLast, `compress` 임계값(THRESHOLD=10) 경계, `trimOldTurns` 보존, `ProjectMemory` 라운드트립. `@TempDir` 사용.
- 위험: **매우 낮음** / 노력: S

### Phase 2 — `:config`
- **이동:** `config/ConfigLoader.kt`, `config/WikiConfig.kt` (+ 관련)
- **의존:** `File`, slf4j (내부 의존 0)
- **호출처:** `llm`, `SlackBotGateway`, `SlackConfigHandler`, `UserPersonaStore`, `Main`
- **왜 2순위:** leaf이면서 다른 모듈들의 공통 기반. 일찍 떼면 `:llm` 등 후속 모듈이 깔끔히 의존.
- **테스트 (L1):** fixture 파일/문자열 파싱, 기본값·필수값 누락 처리, env override.
- 위험: 낮음 / 노력: S

### Phase 3 — `:tool` (순환 해소) ⚠️ 핵심
- **이동:** `agent/tool/` 전체 (`SourceTracker`, `CodeSearchTool`, `ConfluenceTool`, `GitHubWikiTool`, `PersonalDataTool`, `PrHistoryTool`, `ProgressAdvisorTool`, `CodeFlowTool`)
- **선행:** `CodeSearchTool → knowledge.BM25Index` 의존 정리. BM25Index를 먼저 `:knowledge`(Phase 4)로 보내거나, 인터페이스(port)로 역전. → **Phase 3과 4는 함께 진행 권장.**
- **효과:** Cycle A·B 동시 해소
- **테스트 (L1):** 각 Tool의 입력 파싱/출력 포맷. 외부 클라이언트는 mock 주입.
- 위험: **중간** (순환 해소 리팩터링 동반) / 노력: M

### Phase 4 — `:confluence`, `:github`, `:rag` (네트워크 클라이언트)
- **이동:** 각 패키지 그대로
- **의존:** Ktor / HTTP (내부 의존 0이지만 테스트에 MockWebServer 필요)
- **테스트 (L2성격):** MockWebServer로 요청 경로·응답 파싱·에러 핸들링 검증
- 위험: 낮음(코드 이동), 중간(테스트 인프라 도입) / 노력: M (3개)

### Phase 5 — `:llm`
- **이동:** `llm/` 전체 → `:config`에만 의존
- **테스트 (L1):** executor 빌더 분기(provider 선택), 모델 매핑
- 위험: 낮음 / 노력: S

### Phase 6 — `:knowledge`
- **이동:** `knowledge/` 전체 (`CodeIndexAgent`, `PrIndexAgent`, `BM25Index`, `KnowledgeStore`, `IngestAgent` …)
- **의존:** `:tool`, `:github`, `:rag` (Phase 3 이후 단방향)
- **테스트:** `BM25Index` rrfScore/mergeRRF는 순수 L1, 인덱싱은 L1+fixture
- 위험: 중간 (큰 패키지, 1563 LOC) / 노력: L

### Phase 7 — `:onboarding` (clean architecture 레이어링)
- **이동 + 재구조화:** 단순 이동이 아니라 domain/application/infrastructure/presentation 레이어로 분해 (별도 설계 문서 참조)
- **의존:** `:tool`, `:confluence`, `:github`
- **테스트:** 도메인 규칙(레벨별 스텝, 현재 스텝) L1, 프리젠터 L1, 인프라 어댑터 L2
- 위험: 중간~높음 (God class 분해 동반) / 노력: L

### Phase 8 — `:agent`
- **이동:** `agent/OrchestratorAgent`, `ConfluenceSearchAgent`, `GitHubWikiSearchAgent`, `QueryRewriter`, `SearchResult` …
- **의존:** `:tool`, `:knowledge`, `:onboarding`, `:context`, `:confluence`, `:github`, `:rag`
- 위험: 중간 / 노력: L

### Phase 9 — `:app` (root 잔류)
- `slack/`, `Main.kt`는 루트(앱) 모듈에 남아 모든 모듈을 조립하는 Composition Root 역할
- 위험: 낮음 / 노력: S

---

## 5. Gradle 모듈 추출 템플릿 (Phase 1 기준)

```kotlin
// settings.gradle.kts
rootProject.name = "wiki-agent"
include("callgraph-plugin")
include(":context")            // 추가
```

```kotlin
// context/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:<버전>")
    implementation("org.slf4j:slf4j-api:<버전>")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:<버전>")
}
```

```kotlin
// 루트 build.gradle.kts dependencies {}
implementation(project(":context"))
```

이동: `src/main/.../wiki/context/*.kt` → `context/src/main/.../wiki/context/*.kt` (패키지 선언 그대로 유지).

---

## 6. 추적 체크리스트

- [ ] Phase 0 — 멀티모듈 빌드 기반 세팅
- [ ] Phase 1 — `:context` 추출 + L1 테스트
- [ ] Phase 2 — `:config` 추출 + L1 테스트
- [ ] Phase 3 — `:tool` 추출 (순환 해소)
- [ ] Phase 4 — `:confluence` / `:github` / `:rag` 추출 + MockWebServer
- [ ] Phase 5 — `:llm` 추출
- [ ] Phase 6 — `:knowledge` 추출
- [ ] Phase 7 — `:onboarding` 추출 + clean architecture 레이어링
- [ ] Phase 8 — `:agent` 추출
- [ ] Phase 9 — `:app` Composition Root 정리

각 Phase 완료 기준: ① 전체 빌드 통과 ② 신규 모듈 테스트 통과 ③ 단방향 의존성 유지(순환 0).
