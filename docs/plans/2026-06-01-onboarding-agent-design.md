# Onboarding Agent Design

> last_updated: 2026-06-01
> version: 2 (페르소나 리뷰 반영)

## Overview

kurly-android 신규 입사자를 위한 온보딩 가이드 에이전트.
wiki-agent 프로젝트 안에 하이브리드 방식(OnboardingTool + OnboardingSession 상태 레이어)으로 통합한다.

## 핵심 결정 사항

| 항목 | 결정 |
|------|------|
| 대상 프로젝트 | kurly-android |
| 위치 | wiki-agent 내 새 Tool로 추가 |
| 접근 방식 | 하이브리드 — OrchestratorAgent + OnboardingTool + OnboardingSession |
| 진입점 | DM 스레드 SUGGESTED_PROMPTS 5번째 항목 |
| 콘텐츠 소스 | 구조는 수동 정의(YAML) + 상세는 static MD 우선, Confluence/코드는 보충 |
| 상태 관리 | 사용자별 MD 파일 (`.wiki/onboarding/sessions/{userId}.md`) |
| 호출 방식 | Manual loop 전용 (Koog agent 경로 등록 안 함, ProgressAdvisorTool 패턴) |
| 완료 기준 | 커리큘럼 전체 완료 = 온보딩 완료 |

## 커리큘럼

### Phase 1 — 환경 & 기본기 (Day 1)
1. 개발 환경 세팅
2. 앱 빌드 & 실행 (Gradle 트러블슈팅, 빌드 시간 단축 팁)
3. 프로젝트 구조 & 모듈 맵 (피처 코드 찾아가는 법)
4. 앱 개발자 모드 활용법
   - 진입: 홈 로고 길게 탭 (Debug/Beta 빌드만, Store 빌드 비활성)
   - 그 외 진입점: 마이컬리, 알림 설정
   - 주요 기능: 호스트(서버 환경) 전환, 딥링크 테스트, JWT/계정 정보, 피처플래그(GrowthBook), Loupe(디자인 인스펙터), 버그 리포트 등 14개 섹션
   - 코드 위치: `:features:developer` 모듈 (`DeveloperMenuData`, `DeveloperModeActivity`, `DeveloperModeNavHost`)
   - 신규 입사자 추천: 네트워크 > 호스트 전환, 링크 > 딥링크 테스트 먼저 해보기

### Phase 2 — 도메인 & 코드 이해 (Day 2)
4. 도메인 용어 사전 (코드 읽기 전 필수 선행)
5. 아키텍처 패턴 (MVVM, UiState, UseCase 흐름)
6. 주요 공통 모듈 (KPDS 디자인 시스템, ApiCaller, 네트워킹)
   - 트라이벌 놀리지: `ApiCaller.callApi {}`는 사실상 no-op
7. Compose 전환 현황 & 컨벤션
   - 트라이벌 놀리지: `Column + forEachIndexed` 의도적 패턴, `ImpressionCapturable` vs `AnalyticsEventProvider`

### Phase 3 — 프로세스 (Day 3)
8. 브랜치 전략 & PR 컨벤션
9. QA / 배포 프로세스
10. 모니터링 & 장애 대응

### Phase 4 — 실전 (Day 4~5)
11. 첫 PR 가이드
12. 코드 리뷰 문화 & 체크포인트
13. 테스트 작성 가이드 (L1 Unit / L2 Instrument / L3 E2E 분류 기준)
14. 첫 피처 티켓 워크플로우 (티켓 분석 → 코드 탐색 → 구현 → PR까지 상세 안내, 실제 티켓 번호 입력 시 맞춤 가이드)

### Phase 5 — 스킬 가이드 (Day 5)
15. Claude Code 프로젝트 스킬 활용 (프로젝트 레벨에서 공유된 스킬만 대상)
16. CI/CD 자동화 도구 & 린트 룰

## 진입점 & 라우팅

- `SUGGESTED_PROMPTS`에 5번째 항목 추가: "온보딩 가이드 시작"
- 라우터가 "온보딩", "신규 입사" 등 키워드를 `OnboardingTool`로 포워딩
- **세션 활성 체크**: SlackBotGateway에서 해당 사용자의 온보딩 세션 MD가 존재하고 미완료 상태면 `forceTool = "onboarding"`으로 바인딩 (라우터 LLM 안 거침)
- 온보딩 외 질문은 기존 검색 Tool로 위임 후 "온보딩으로 돌아가려면 '다음'을 입력하세요" 안내

## 레벨 체크 (커리큘럼 필터링)

온보딩 시작 시 Block Kit `static_select` 드롭다운 3개를 한 메시지에 묶어 제공한다.

```
1. Android 개발 경험:        (A) 처음  (B) 1~2년  (C) 3년 이상
2. Compose 프로덕션 배포 경험: (A) 없음  (B) 있음
3. 커머스 도메인 경험:         (A) 없어요  (B) 있어요
```

**필터링 규칙:**
- 레벨에 따라 일반적인 단계만 스킵 (환경 세팅, 아키텍처 패턴 등)
- **kurly 고유 항목은 레벨 무관 필수** (`skippable: false`): 모듈 구조, KPDS, 브랜치 컨벤션, 도메인 용어
- 스킵된 단계도 "건너뛴 항목 보기"로 언제든 열람 가능

## 콘텐츠 소스

**Single Source of Truth: Confluence 위키 한 페이지**

- URL: https://kurly0521.atlassian.net/wiki/spaces/ProductApp/pages/5912232879/Android
- 페이지 ID: `5912232879`
- 모든 Phase 콘텐츠가 이 한 페이지에 있음 (Phase별 H1 헤딩으로 구분)
- 팀원 누구나 직접 편집 가능

**OnboardingTool 콘텐츠 수집 방식:**
1. 온보딩 시작 시 위키 페이지를 fetch
2. 현재 Phase에 해당하는 H1 섹션을 추출
3. 추출된 콘텐츠를 LLM에 전달하여 가이드 생성

**static 파일(`.wiki/onboarding/steps/*.md`)은 제거** — 위키 한 곳에서만 관리하여 싱크 이슈 방지

## 커리큘럼 구조 파일

`.wiki/onboarding/curriculum.yaml`에 단계를 정의한다. sources는 위키 페이지의 섹션 매핑만 담당.

```yaml
last_updated: 2026-06-05

contentSource:
  type: confluence
  pageId: "5912232879"
  url: "https://kurly0521.atlassian.net/wiki/spaces/ProductApp/pages/5912232879/Android"

phases:
  - id: env-setup
    name: "개발 환경 세팅"
    phase: 1
    day: "Day 1"
    skippable: true
    levelFilter:
      skipWhen: { android: "C" }
    wikiSection: "Phase 1"  # 위키 H1 헤딩과 매칭

  - id: project-structure
    name: "프로젝트 구조 & 모듈 맵"
    phase: 1
    day: "Day 1"
    skippable: false
    wikiSection: "Phase 1"

  - id: compose-convention
    name: "Compose 컨벤션"
    phase: 2
    day: "Day 2"
    skippable: true
    levelFilter:
      skipWhen: { compose: "B" }
    wikiSection: "Phase 2"
```

## 온보딩 세션 상태 관리

`.wiki/onboarding/sessions/{userId}.md`:

```markdown
# 온보딩 — U12345

## 프로필
- Android: B (1~2년)
- Compose: A (없음)
- 도메인: A (없어요)
- 시작일: 2026-06-01

## 진행 현황
- [x] 개발 환경 세팅 (2026-06-01)
- [x] 앱 빌드 & 실행 (2026-06-01)
- [ ] 프로젝트 구조 & 모듈 맵 ← 현재
- [ ] 도메인 용어 사전
...

## 메모
- 프로젝트 구조 단계에서 "data 모듈과 domain 모듈 차이" 질문 → 답변 요약
- Gradle 빌드 시 OOM 발생 → heap 4G로 해결
```

**역할 분리:**
- 이 MD 파일: 커리큘럼 진행 상태 + 메모만 담당
- 대화 히스토리(질문/답변): 기존 `ConversationStore`에 저장 (후속 질문 맥락 유지)

**사용자 인터랙션:**
- "다음" / "넘어가기" → 현재 단계 완료 + 다음 단계 가이드 전달
- "건너뛰기" → skip + 다음 단계
- "진행률 보여줘" → Phase 단위 현황 요약
- "OO 다시 보여줘" → 특정 단계 재열람
- 일반 질문 → 현재 단계 컨텍스트 내에서 대화 지속
- "다음"을 명시적으로 말해야만 다음 단계로 전환
- 새 스레드에서 "온보딩 이어가기" → MD 읽어서 현재 단계부터 재개

**세션 복구:**
- 커리큘럼 업데이트로 step이 삭제된 경우 → 다음 존재하는 step으로 자동 이동 + 안내 메시지

## OnboardingTool 설계

- 이름: `onboarding`
- 라우터 라벨: "온보딩 가이드"
- **Manual loop 전용**: Koog agent ToolRegistry에 등록하지 않음. OrchestratorAgent에서 ProgressAdvisorTool 패턴처럼 직접 호출.
- 내부에서 `ConfluenceTool`, `CodeSearchTool`을 생성자 주입받아 일반 함수 호출로 사용 (Tool-in-Tool)
- 동작 흐름:
  1. 세션 MD 로드 (없으면 생성, 레벨 체크부터 시작)
  2. 메시지 의도 분류: 시작 / 다음 / 건너뛰기 / 진행률 / 재열람 / 질문
  3. 해당 단계의 sources에서 콘텐츠 수집 (static 우선)
  4. LLM이 수집된 콘텐츠 + 온보딩 컨텍스트로 자연어 가이드 생성
  5. 세션 MD 업데이트

**프롬프트 구성:**
- 시스템: 온보딩 페르소나 (친절한 시니어 동료 톤)
- 컨텍스트: 현재 단계 정보 + sources 결과 + 대화 히스토리
- 지시: Slack mrkdwn 포맷, 한 번에 너무 많은 정보를 주지 말 것

## Slack UI

**가이드 메시지** (Block Kit):
```
:books: *[Phase 1: 2/3] 프로젝트 구조 & 모듈 맵* (Day 1)

(LLM이 생성한 가이드 내용)

━━━
:speech_balloon: 궁금한 점이 있으면 질문해주세요

[다음] [건너뛰기] [진행률]    ← Block Kit 버튼
```

- 매 단계 하단에 `[다음]` `[건너뛰기]` `[진행률]` Block Kit 버튼 3개
- 텍스트 입력도 병행 지원 (자유 질문은 텍스트로)

**진행률 메시지** (Phase 단위):
```
:bar_chart: *온보딩 진행 현황*

*Phase 1 — 환경 & 기본기* (2/3 완료)
  :white_check_mark: 개발 환경 세팅
  :white_check_mark: 앱 빌드 & 실행
  :large_blue_circle: 프로젝트 구조 & 모듈 맵 ← 현재

*Phase 2 — 도메인 & 코드 이해* (0/4)
  :white_large_square: 도메인 용어 사전
  ...

현재 Phase 진행률: 2/3 (67%)
```

**레벨 체크 메시지** (Block Kit static_select):
- 드롭다운 3개를 한 메시지에 묶어 클릭 3번으로 완료

**완료 메시지:**
- 모든 단계 완료 시 축하 메시지 + 세션 아카이브

## 운영 가이드

- curriculum.yaml과 static 가이드 파일에 `last_updated` 명시
- 오래된 콘텐츠(N개월 이상) 가이드 시 "이 내용은 N개월 전 작성되었습니다" 경고
- 커리큘럼은 분기 1회 리뷰 권장

## v1 스코프 외 (추후 검토)

- 리드용 전체 현황 대시보드 / 메트릭 추적
- 비활성 사용자 nudge / 리마인더
- HR 온보딩 체크리스트 연동
