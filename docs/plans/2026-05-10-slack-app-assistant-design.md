# Slack App Assistant + Home Tab 설계

**날짜:** 2026-05-10  
**상태:** 승인됨

---

## 목표

- Slack AI 패널(App Assistant)을 Q&A 메인 진입점으로 전환
- 기존 DM 핸들러 제거, 관리 명령어는 `/wiki` 슬래시 커맨드 전담
- App Home 탭에 상태 확인 + 관리 버튼 구성

---

## 구현 방식

`SlackBotGateway`에 `registerAssistantHandler()`, `registerHomeHandler()` 메서드 인라인 추가.  
기존 핸들러 등록 패턴(`registerMentionHandler`, `registerDmHandler` 등) 그대로 유지.

---

## App Assistant 이벤트 처리

| 이벤트 | 시점 | 동작 |
|---|---|---|
| `AssistantThreadStartedEvent` | AI 패널 열릴 때 | 추천 프롬프트 4개 세팅 |
| `AssistantThreadContextChangedEvent` | 채널 컨텍스트 변경 | no-op |
| `MessageEvent` (im, assistant thread) | 사용자 메시지 | `assistantThreadsSetStatus` → orchestrator → 응답 |

### 추천 프롬프트

| 버튼 | 전송 텍스트 |
|---|---|
| Confluence에서 검색 | 무엇을 Confluence에서 찾을까요? |
| 코드에서 찾기 | 어떤 코드를 찾고 있나요? |
| PR 히스토리 보기 | 어떤 PR 히스토리가 궁금하세요? |
| 문서 인제스트 | 인제스트할 URL을 입력해주세요. |

### 진행 상태 표시

- 현재: 메시지 post → update → delete
- 변경: `assistantThreadsSetStatus("🔍 Confluence 검색 중...")` (API 한 줄)

---

## DM 핸들러 변경

- `registerDmHandler()` 전체 제거
- admin 커맨드(`reindex-code`, `reindex`, `ingest`, `lint`, `config`)는 `/wiki` 슬래시 커맨드로 일원화 (이미 구현됨)

---

## App Home 탭

`AppHomeOpenedEvent` 핸들러 + `views.publish` 로 Block Kit 렌더링.

### 섹션 구성

1. **상태** — 봇 연결 상태(🟢), 마지막 코드 인덱싱 시각, 연결된 Confluence 스페이스
2. **빠른 액션** — 코드 재인덱싱 / Confluence 재인덱싱 / 메모리 보기 버튼
3. **사용법 요약** — AI 패널 Q&A 방법, URL 인제스트, 피드백 리액션 안내

### 버튼 액션

버튼 클릭 → `block_actions` 이벤트 → `configHandler.handle()` 로 라우팅.

---

## Slack 앱 설정 (대시보드)

| 항목 | 위치 |
|---|---|
| App Assistant 활성화 | `api.slack.com/apps/A0AVARFDU2U/app-assistant` |
| Home Tab 활성화 | `api.slack.com/apps/A0AVARFDU2U/app-home` |
| `assistant:write` 스코프 추가 | OAuth & Permissions |
| `reactions:read` 스코프 추가 | OAuth & Permissions |
| `assistant_thread_started` 이벤트 구독 | Event Subscriptions |
| `assistant_thread_context_changed` 이벤트 구독 | Event Subscriptions |
| 앱 재설치 | Install App |

---

## 변경 파일

- `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt` — 핸들러 추가/제거
