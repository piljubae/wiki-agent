# 온보딩 위키-우선 2-tier 응답 설계

- 날짜: 2026-06-29
- 대상 모듈: `:onboarding` (`OnboardingTool`, `ContentGatherer`, `OnboardingSession`), `Main.kt` 배선
- 선행 작업: `fix/onboarding-forcetool-koog-path` (Koog 경로에서 forceTool=onboarding 처리) — 본 작업의 전제

## 배경 / 문제

온보딩 중 사용자가 입력하면 `OnboardingTool.handleQuestion` → `ContentGatherer.gatherForQuestion`이
**항상 `codeSearch` + `confluenceSearch`를 발화**한다. 그 결과 위키 SSOT 중심이어야 할 온보딩 경험에
코드/일반 Confluence 결과(비위키)가 섞여 보인다.

런타임 로그(2026-06-29)에서 확인된 직접 사례:
- `온보딩 초기화` 입력 → `classifyIntent`가 RESET 의도를 모름 → `QUESTION`으로 분류 →
  `codeSearch`/`confluenceSearch` 발화. (그런데 완료 세션 안내문은 "온보딩 초기화 입력하세요"라고 안내 → 동작 불일치)

단계 가이드(`generateGuide` → `gather(step)`)는 `curriculum.yaml`의 모든 source가 `confluence-page`라
**이미 위키 SSOT만** 수집한다. 따라서 본 설계는 가이드가 아니라 **질문 경로(`handleQuestion`)**를 고친다.

## 목표

- 온보딩 중 질문은 기본적으로 **위키 SSOT만** 사용해 답한다 (Tier 1).
- 사용자가 더 파고들기를 원한다는 **키워드 신호**가 있을 때만 코드 + PR + 추가 Confluence를 연결한다 (Tier 2).
- `온보딩 초기화/리셋`은 세션을 지우고 **곧바로 다시 시작**(레벨 체크)한다 — 안내 메시지만 띄우지 않는다.

비목표(YAGNI): 단계 가이드 동작 변경, LLM 기반 tier 판정, PR 미연결 시 강제 연결.

## 설계

### 1. RESET 의도 추가 — `OnboardingTool.classifyIntent` (OnboardingTool.kt:60)

`START` 판정보다 먼저 RESET을 본다(START이 "온보딩"+"시작/이어"라 "초기화"와 안 겹치지만 명시적으로 우선):

- `message`가 "초기화" 또는 "리셋"을 포함하면 → `Intent.RESET`
  - (단독 "초기화"/"리셋"도, "온보딩 초기화"/"온보딩 리셋"도 포함)

### 2. `handleReset` (신규) + `OnboardingSessionStore.delete` (신규)

- `OnboardingSessionStore.delete(userId): Boolean` — 세션 파일 삭제. 없으면 false.
- `handleReset(userId)` = `delete(userId)` 후 **`handleStart(userId)`를 그대로 호출**.
  - 세션이 사라졌으므로 `handleStart`는 `LEVEL_CHECK_MESSAGE`(레벨 질문)를 반환 → 바로 새 온보딩 시작.

### 3. `handleQuestion` 2-tier 분기 (OnboardingTool.kt:207)

- `wantsDeepDive(message): Boolean` — 아래 키워드 중 하나라도 포함 시 true:
  `코드`, `소스`, `구현`, `예시`, `예제`, `샘플`, `PR`, `풀리퀘`, `커밋`, `더 자세히`, `자세히`,
  `실제로`, `동작 방식`, `어떻게 동작`, `깊이`
- `gatherForQuestion(message, currentStep, includeDeep = wantsDeepDive(message))` 호출.
- 프롬프트:
  - Tier 1(`includeDeep=false`): "아래 위키 자료만으로 답하라. 자료에 없으면 모른다고 안내."
    답변 끝에 한 줄: "_코드·PR까지 보려면 '코드 보여줘'처럼 다시 물어보세요._"
  - Tier 2(`includeDeep=true`): 기존 멘토 프롬프트 유지(위키+코드+PR 종합).

### 4. `ContentGatherer.gatherForQuestion(question, step, includeDeep: Boolean)` (ContentGatherer.kt:60)

- **항상 (Tier 1 포함):**
  - 현재 step의 `CONFLUENCE_PAGE` 위키 섹션 (기존)
  - **질문 키워드와 매칭되는 SSOT H2 섹션** (신규): `loadWikiSections()` 결과에서
    제목이 질문 토큰(길이≥2)을 포함하는 섹션을 최대 3개 추가. 현재 단계 섹션과 dedup.
    → 현재 단계 밖 주제 질문도 SSOT 안에서 답 가능, 여전히 위키-only.
- **`includeDeep`일 때만 추가:**
  - `codeContent(question)` (기존)
  - `prContent(question)` (신규: `prHistoryTool?.prHistory(question)`)
  - `confluenceContent(question)` (기존, "추가 위키")

### 5. 배선

- `ContentGatherer` 생성자에 `prHistoryTool: PrHistoryTool? = null` 추가.
- `OnboardingTool` 생성자에 `prHistoryTool: PrHistoryTool? = null` 추가 → `gatherer` 생성 시 전달.
- `Main.kt`의 `OnboardingTool(...)`에 `prHistoryTool = prHistoryTool` 주입.
- `prHistoryTool == null`이면 `prContent`는 null 반환(graceful skip).

## 데이터 흐름

```
입력 → classifyIntent
  ├ RESET    → delete(session) → handleStart → LEVEL_CHECK_MESSAGE
  ├ START/NEXT/SKIP/JUMP/LEVEL → generateGuide (위키 SSOT only, 변경 없음)
  └ QUESTION → wantsDeepDive?
        ├ no  → gatherForQuestion(includeDeep=false) → 위키 SSOT only → Tier1 프롬프트
        └ yes → gatherForQuestion(includeDeep=true)  → 위키+코드+PR+Confluence → Tier2 프롬프트
```

## 테스트 (TDD, `:onboarding` 모듈)

`ContentGatherer` (MockK로 confluenceClient/codeSearchTool/prHistoryTool 주입):
- `gatherForQuestion(includeDeep=false)`는 codeSearch/prHistory/confluenceSearch를 **호출하지 않는다**.
- `gatherForQuestion(includeDeep=false)`는 질문 키워드와 매칭되는 SSOT 섹션을 포함한다.
- `gatherForQuestion(includeDeep=true)`는 code + PR + confluence를 포함한다.
- `prHistoryTool=null`이면 deep여도 PR 없이 나머지를 수집한다.

`OnboardingTool` / `classifyIntent`:
- "온보딩 초기화" / "초기화" / "리셋" → `Intent.RESET`.
- RESET 처리 시 `OnboardingSessionStore.delete` 호출 후 레벨 체크 메시지 반환.
- `wantsDeepDive`: "코드 보여줘"=true, "도메인 용어 설명해줘"=false.

`OnboardingSessionStore`:
- `delete(userId)`가 세션 파일을 제거하고, 이후 `load`가 null을 반환한다.

## 리스크 / 메모

- `.claude/worktrees/feat+onboarding-content-enhancement-2/`에 온보딩 content 병행 작업 존재.
  같은 파일(`ContentGatherer`, `OnboardingTool`)을 건드리므로 머지 충돌 가능 — main 기준으로 작업하고 충돌은 병합 시 해소.
- 키워드 기반 tier는 오분류 가능(예: "동작" 단독). 보수적으로 시작하고 운영 로그로 키워드 조정.
