# wiki-agent 세컨드 브레인 확장 설계

**작성일:** 2026-06-01
**상태:** 설계 완료, MVP 구현 대기

---

## 배경

wiki-agent를 Confluence/GitHub 검색 봇을 넘어 개인화 에이전트로 확장.
업무 일정 관리, 성과 추적, 팀 채널 자동 응답까지 커버하는 "세컨드 브레인"으로 진화.

## 전체 비전 (단계별 확장)

| Phase | 기능 | 상태 |
|-------|------|------|
| **MVP** | PersonalDataTool — progress.json 성과 추적 | 구현 대기 |
| Phase 2 | DailySummaryTool — work-activity 일 단위 회고/검색 | 설계 완료 |
| Phase 3 | ChannelMonitor — 팀 채널 자동 감지 + 스레드 응답 | 설계 완료 |
| Phase 4 | 대리 응답 — 팀원 질문 대리 답변 (코드/프로세스/일정/히스토리/온보딩) | 요구사항 수집 완료 |
| Future | 의사결정 지원 — 과거 결정/맥락 기반 추천 | 보류 |

## 아키텍처 결정

**Router 확장 방식** 채택 — 기존 OrchestratorAgent에 Tool만 추가.

```
SlackBotGateway
  ├── 기존 트리거 (멘션/DM) ← 유지
  └── [Phase 3] ChannelMonitor (팀 채널 자동 감지)
        └── QuestionClassifier
              └── OrchestratorAgent (기존)
                    ├── 기존 Tools (Confluence, GitHub, Knowledge, Code...)
                    ├── [MVP] PersonalDataTool → progress.json
                    └── [Phase 2] DailySummaryTool → work-activity/*.md
```

듀얼 에이전트 방식은 기각: 질문 경계가 애매한 경우가 많아 단일 라우터가 여러 Tool 조합하는 게 자연스러움.

---

## MVP: PersonalDataTool (progress.json)

### 데이터 소스

`~/Documents/Claude Cowork/성과목표-2026/progress.json`
- 성과 목표 4개, 지표별 current/target
- keywords 필드로 의미 매칭 가능
- dashboard.html과 동일 데이터

### 답변 가능한 질문

- "목표 2 진척도 어때?"
- "올해 내가 뭐 달성했지?"
- "AI Skill 몇 개 만들었어?"
- "이번 분기 해야 할 것"

### 프라이버시

- **레이어 1:** `allowedUsers` — 본인 userId만 Tool 호출 가능
- 성과 데이터는 리더 공개 범위이므로 Phase 4 대리 응답 시에도 제한 유지

### Tool 인터페이스

```kotlin
class PersonalDataTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
) {
    fun getProgressSummary(): String
    fun queryGoal(keyword: String): String
}
```

### 설정

```yaml
personalData:
  enabled: true
  progressFile: "/Users/pilju.bae/Documents/Claude Cowork/성과목표-2026/progress.json"
  allowedUsers:
    - "U01XXXXX"
```

### 라우터 연동

OrchestratorAgent 라우터 프롬프트에 PersonalDataTool 추가.
"성과", "목표", "진척도", "KPI" 등의 키워드가 포함된 질문일 때 선택.

---

## Phase 2: DailySummaryTool (work-activity)

### 데이터 소스

`~/Documents/Claude Cowork/daily-summaries/work-activity/YYYY-MM-DD-work-activity.md`
- daily-summary-env에서 자동 생성 (PR #16)
- 업무 활동만 포함: 미팅, Jira, Claude 세션(제목+목표), 커밋, Slack 토픽 제목, Cowork, Firebender
- 개인 데이터 제외: CLI 이력, 방문 사이트, 앱 사용 시간, 대화 전문

### 두 가지 모드

| 모드 | 질문 예시 | 동작 |
|------|----------|------|
| 회고 | "지난주에 뭐 했지?" | 날짜 범위 → 해당 파일들 읽기 → LLM 요약 |
| 검색 | "KMA-1234 언제 작업했어?" | 전체 파일 grep → 매칭 날짜 + 컨텍스트 반환 |

### 프라이버시

- **레이어 1:** `allowedUsers` — 본인만
- **레이어 2:** work-activity 파일 자체가 이미 민감 데이터 제외

### 설정

```yaml
personalData:
  workActivityDir: "/Users/pilju.bae/Documents/Claude Cowork/daily-summaries/work-activity"
```

---

## Phase 3: ChannelMonitor

### 동작

1. 팀 채널 메시지 이벤트 수신
2. 필터링: 봇 메시지 무시, 스레드 답글 무시, 짧은 메시지(< 10자) 무시
3. QuestionClassifier (Haiku 4.5): WIKI_QUESTION / PERSONAL_QUESTION / NOT_QUESTION
4. 질문이면 → OrchestratorAgent → 스레드 응답

### 응답 범위

- 코드/PR/Confluence 기반 기술 질문
- 업무 프로세스 질문 ("배포 어떻게 해?", "온보딩 절차")

### 설정

```yaml
channelMonitor:
  enabled: true
  channels:
    - "#team_프로덕트앱개발"
    - "#team_프로덕트앱개발_android"
  minMessageLength: 10
```

---

## Phase 4: 대리 응답 (미설계)

### 요구사항 수집 완료

팀원이 필주에게 물어올 수 있는 질문 범위:
- (A) 코드/기술 — "이 모듈 누가 만들었어?", "이 API 왜 이렇게?"
- (B) 프로세스/절차 — "배포 어떻게 해?", "코드 리뷰 기준?"
- (C) 일정/상태 — "필주 지금 뭐 해?", "이거 언제 끝나?"
- (D) 히스토리 — "이 PR 왜 리버트?", "저번 이슈 어떻게 해결?"
- (E) 온보딩 — "팀 합류, 뭐부터?", "개발환경 세팅?"

### 미결정 사항

- PersonalContextTool 설계 (데이터 소스 조합 방식)
- 프라이버시 경계 (팀 채널에서 어디까지 노출)
- 대리 응답 한계선 ("필주한테 직접 물어보세요" 기준)
- 응답 브랜딩 (봇 이름, 대리 응답 명시 방식)
- 오탐/노이즈 방지 (옵트아웃 메커니즘)
- 피드백 루프 (FeedbackStore 리액션 확장?)

---

## 관련 변경

### daily-summary-env

- PR #16: work-activity-only report formatter 추가
  - `formatters/work_activity.py` 신규
  - 출력: `daily-summaries/work-activity/YYYY-MM-DD-work-activity.md`
