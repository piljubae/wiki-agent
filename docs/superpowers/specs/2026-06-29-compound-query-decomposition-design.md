# 검색 의도 이해 강화 설계 (Query Intent Understanding)

- 작성일: 2026-06-29
- 상태: 설계 확정 (두 실행 경로 커버로 확장)
- 범위: `OrchestratorAgent` 라우팅 레이어 + 검색 tool 레이어

## 1. 배경 / 문제

사용자 목적: **복합 질문이 들어오면 의도를 먼저 분석·분해하고, 각 조각을 알맞은 에이전트로
보내 각자 결과를 리턴받아, 섹션별로 합친 통합 답변을 만든다 — Confluence뿐 아니라 전 tool에 걸쳐.**

1차 진단(의도 파악 약점): ① 질문→키워드 변환 부정확, ② 키워드는 맞아도 CQL이 못 찾음(의미
격차), ③ 복합·다단계 질문 미분해. 본 설계는 **③ 과 ①** 을 해결한다. ②(의미/벡터 검색 도입)는
별도 후속 프로젝트로 분리한다.

### 1.1 실행 경로가 둘이라는 핵심 사실

`OrchestratorAgent.answer()`는 provider에 따라 두 경로로 갈린다 (`OrchestratorAgent.kt:77`).

| 경로 | 조건 (provider) | 동작 | 현재 약점 |
|------|----------------|------|-----------|
| **Koog 에이전트** (`answerWithKoogAgent`) | `GOOGLE` / `ANTHROPIC` | 자율 AIAgent가 루프 돌며 여러 tool 자율 호출. 시스템 프롬프트가 "2~3개로 쪼개라" 지시 → **분해는 이미 존재** | 각 tool 호출이 빈약. 특히 `ConfluenceTool.confluenceSearch(query)`가 **query만** 받고 synonyms·날짜·원문 재랭킹을 전부 버림 (`ConfluenceTool.kt:13-21`) |
| **수동 루프** (`answerWithManualLoop`) | `CLAUDE_CODE` / `GEMINI_CODE` / `ANTIGRAVITY_CODE` | 라우터 1회 → tool 1개 선택. `confluenceSearchSuspend`로 synonyms·날짜·재랭킹 사용 | 복합질문을 query 한 줄에 우겨넣음 → **멀티 에이전트 분해 없음** |

사용자는 **두 경로를 프로바이더 전환해가며 사용**한다. 따라서 개선은 두 경로 모두에서 동작해야 한다.

## 2. 접근 결정

의도 이해를 **검색/tool 레이어의 공유 컴포넌트**로 두어 두 경로가 모두 이득을 보게 한다.
작업을 공유 1개 + 경로별 2개로 나눈다.

| # | 작업 | Koog | 수동 | 성격 |
|---|------|------|------|------|
| **공유** | `QueryUnderstanding` — bare query → 정제쿼리 + synonyms + 날짜 | tool 내부 호출 | 폴백 enrich | 신규, 격리 |
| **W1** | Koog `confluenceSearch` tool이 `QueryUnderstanding`으로 enrich | ✅ | — | tool 레이어 |
| **W2** | 수동 루프 복합질문 분해 (경로 Y) + 병렬 재라우팅 + 섹션 통합 답변 | — | ✅ | 라우팅 레이어 |

라우터를 통째로 플래너로 교체(경로 X)하는 대신, 분해기를 한 겹 앞에 두고 기존 라우터를
sub-question마다 재사용(경로 Y)한다. 이유: 검증된 라우터를 재작성하지 않고 재사용하며, 신규
위험이 신규 컴포넌트로 격리된다. 실패 시 기존 동작으로 폴백한다.

## 3. 공유 컴포넌트: QueryUnderstanding

bare query 하나만 주어져도 검색 품질을 끌어올리는 enrich 프리미티브.

```kotlin
data class UnderstoodQuery(
    val cleanedQuery: String,
    val synonyms: List<String>,   // 3~6개
    val dateAfter: String?,       // "yyyy-MM-dd" 또는 null
    val dateBefore: String?,
)

class QueryUnderstanding(private val llm: LLMCaller) {
    suspend fun understand(rawQuery: String): UnderstoodQuery
}
```

- 구현: `QueryRewriter`와 동일한 `LLMCaller` fun interface 패턴. LLM 1회 호출.
- synonyms/날짜 생성 규칙은 현 라우터 프롬프트(`OrchestratorAgent.kt:216-247`)의 지침을 재사용.
- 출력 파싱은 `runCatching`으로 감싸고, 실패 시 `UnderstoodQuery(rawQuery, emptyList(), null, null)` 폴백.
- 작은 `routerModel`(Haiku/Flash) 재사용으로 비용·지연 최소화.

## 4. W1: Koog Confluence tool enrich

- `ConfluenceTool`에 `QueryUnderstanding?`(nullable) 주입. null이면 현재 동작(bare query) 유지.
- `confluenceSearch(query)` 흐름:
  1. `understand(query)` → `UnderstoodQuery`
  2. `searchAgent.search(cleanedQuery, synonyms, dateAfter=…, dateBefore=…, originalQuestion=query)`
- 효과: Koog 경로의 Confluence 검색이 수동 루프 수준의 풍부함을 얻는다.
- `Main.kt:159` 구성 지점에서 `routerExecutor`+`routerModel` 기반 `QueryUnderstanding`을 주입.

## 5. W2: 수동 루프 복합질문 분해 (경로 Y)

### 5.1 흐름
```
질문
 ├─ 프리라우트(기존): 인사·코칭·온보딩·none → 분해 없이 기존대로 처리
 ▼ (검색성 질문만)
[QueryDecomposer] 질문 → sub-question 1~N개 (단순질문이면 1개=원본). 중복 sub-question 제거
 ▼
[기존 라우터 재사용] 각 sub-question → tool+query+synonyms 결정 → 검색   ← 병렬
 ▼
[집계] sub-question별 결과 라벨링 (문서 중복 병합은 요약 LLM이 담당)
 ▼
[요약 LLM] 섹션별로 정리한 통합 답변 (같은 문서 반복 시 1회로 병합 지시)
```

### 5.2 (신규) QueryDecomposer
```kotlin
class QueryDecomposer(private val llm: LLMCaller) {
    suspend fun decompose(question: String, context: String = ""): List<String>
}
```
- 단순질문 → `[원본]` 1개. 복합 → 2~3개.
- JSON 배열 출력, `runCatching` 폴백 → `[question]`.
- 맥락 반영으로 "그거 코드도" 같은 후속 복합질문 지시어 해소(1-hop 한정).

### 5.3 (리팩터) routeAndRetrieve 추출
- 현 `answerWithManualLoop`의 라우팅→검색 부분(`OrchestratorAgent.kt:127-454` 영역, 단 특수 tool
  early-return 제외)을 `routeAndRetrieve(subQuestion, …): StepResult` 로 분리해 재사용·병렬화.
- 요약 단계(`OrchestratorAgent.kt:457-518`)는 그대로, 단일/복합 모두 마지막에 한 번만 탄다.

```kotlin
data class StepResult(
    val subQuestion: String,
    val toolName: String,
    val searchResult: String?,   // null = 못 찾음
)
```

### 5.4 매핑 규칙 (1 sub-question → 필요시 여러 에이전트)
- 각 sub-question은 기존 라우터가 tool 결정. 조합 tool(`confluenceSearch+codeSearch` 등)을
  고르면 그 조각이 자동으로 여러 에이전트로 fan-out. 별도 fan-out 기계장치 없음.
- sub-question이 특수 tool(`none`/`onboarding`/`progressAdvisor`)로 라우팅되면 그 조각은 드롭.

## 6. 실패·지연·격리 처리

- 분해기 JSON 깨짐/빈 결과 → `[원본질문]` 폴백 = 기존 동작 100% 보존.
- 전체 질문이 프리라우트에서 특수 tool로 잡히면 분해 자체를 건너뜀.
- 특정 sub-question 검색 실패 → 그 섹션만 "못 찾음", 나머지 정상.
- 단순질문(분해 1개) → 추가 라우팅 호출 없이 기존 단일 경로와 동일.
- N개 라우팅 병렬 → 지연 = (분해 1회) + (가장 느린 라우팅 1개).
- W1: `QueryUnderstanding` null 주입 시 기존 bare-query 동작 유지.

## 7. 테스트 (testing-strategy L1 중심)

- L1(Unit, mock LLMCaller):
  - `QueryUnderstanding`: 정상 파싱 / synonyms·날짜 추출 / 파싱 실패 폴백.
  - `QueryDecomposer`: 복합→N / 단순→1 / JSON 깨짐→`[원본]` 폴백.
  - 중복 sub-question 제거(distinct).
  - 섹션 조립: N개 StepResult → 섹션 라벨 + 결과 포함 요약 프롬프트 + 병합 지시 문구.
  - 특수 tool 드롭 규칙.
- 회귀:
  - 분해기가 1개 반환 시 단일질문 경로가 기존과 동일.
  - 프리라우트 특수 tool 경로 불변.
  - W1: `QueryUnderstanding` 주입 전후로 `ConfluenceTool` 기본 동작 보존.

## 8. 파일 영향 요약

| 파일 | 변경 |
|------|------|
| `search/.../QueryUnderstanding.kt` | 신규 (공유 컴포넌트). `:search` 모듈에 둔다 — `ConfluenceTool`(:search)이 소비하고 `:search`는 `:app`에 의존 불가하므로 |
| `src/main/.../agent/QueryDecomposer.kt` | 신규 (W2) |
| `search/.../tool/ConfluenceTool.kt` | `QueryUnderstanding?` 주입 + enrich (W1) |
| `src/main/.../agent/OrchestratorAgent.kt` | `routeAndRetrieve` 추출 + 분해·집계 배선 (W2) |
| `src/main/.../Main.kt` | `QueryUnderstanding`/`QueryDecomposer` 구성·주입 |

## 9. 범위 밖 (YAGNI)

- 라우터 출력 포맷 JSON 전면 교체(경로 X).
- 의미(벡터) 검색 도입(접근법 B) — 별도 후속.
- sub-question 간 의존(A 결과로 B 질문 생성) 등 다단계 추론 — 분해는 1-hop 병렬만.
- Koog 경로의 자율 분해 프롬프트 자체 개선 — 본 설계는 tool 품질(W1)만 다룸.
- Confluence 외 다른 Koog tool의 enrich — 본 설계는 Confluence(W1)만. 효과 확인 후 확장.
