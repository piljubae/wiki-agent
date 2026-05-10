# Slack App 설정

wiki-agent는 Slack **Socket Mode** + **App Assistant**로 동작합니다. 공개 HTTP 엔드포인트 없이 WebSocket으로 이벤트를 수신합니다.

## 1. App 생성

1. [api.slack.com/apps](https://api.slack.com/apps) → **Create New App** → **From scratch**
2. App Name 입력 (예: `배필주2`), 워크스페이스 선택 → **Create App**

## 2. Socket Mode 활성화

1. 좌측 메뉴 **Settings → Socket Mode** → **Enable Socket Mode** 토글 ON
2. **App-Level Token** 생성:
   - Token Name: 임의 이름 (예: `wiki-agent-token`)
   - Scope: `connections:write` 선택
   - **Generate** → 토큰 복사 (`xapp-` 로 시작) → `SLACK_APP_TOKEN`에 저장

## 3. OAuth 권한 설정

**Features → OAuth & Permissions → Scopes → Bot Token Scopes**에 추가:

| Scope | 용도 |
|-------|------|
| `app_mentions:read` | `@배필주2` 멘션 수신 |
| `chat:write` | 메시지 전송 |
| `commands` | `/askpj` 슬래시 커맨드 |
| `channels:read` | 채널 정보 조회 |
| `assistant:write` | AI 패널 상태·추천 프롬프트 설정 |
| `reactions:read` | 👍👎🔁 리액션 피드백 수신 |
| `im:history` | DM 이력 조회 |

## 4. App Assistant 활성화

**Features → App Assistant** → 활성화 토글 ON

Agent or Assistant Overview에 봇 설명을 입력합니다.

## 5. App Home 활성화

**Features → App Home** → **Home Tab** 토글 ON

## 6. 슬래시 커맨드 등록

**Features → Slash Commands → Create New Command**:

| 항목 | 값 |
|------|-----|
| Command | `/askpj` |
| Request URL | (Socket Mode라 불필요, 빈칸 또는 임의 URL 입력) |
| Short Description | 안드로이드 팀 AI 검색 — 문서·코드·PR을 한 번에 |

## 7. 이벤트 구독

**Features → Event Subscriptions** → **Enable Events** ON  
**Subscribe to bot events**에 추가:

| 이벤트 | 용도 |
|--------|------|
| `app_mention` | 채널 멘션 수신 |
| `assistant_thread_started` | AI 패널 열릴 때 추천 프롬프트 표시 |
| `assistant_thread_context_changed` | 채널 컨텍스트 변경 감지 |
| `app_home_opened` | Home 탭 렌더링 |
| `reaction_added` | 리액션 피드백 수집 |

## 8. 앱 설치 및 토큰 발급

1. **Settings → Install App** → **Install to Workspace**
2. OAuth 동의 → Bot User OAuth Token 복사 (`xoxb-` 로 시작) → `SLACK_BOT_TOKEN`에 저장

## 최종 필요 토큰

| 환경변수 | 형식 | 발급 위치 |
|---------|------|---------|
| `SLACK_BOT_TOKEN` | `xoxb-...` | OAuth & Permissions → Bot Token |
| `SLACK_APP_TOKEN` | `xapp-...` | Settings → Socket Mode → App-Level Token |

---

> **Reference:** [Slack Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode) · [Slack Bolt SDK](https://github.com/slackapi/bolt-java)  
> **SDK 버전:** `com.slack.api:bolt-socket-mode:1.46.0`
