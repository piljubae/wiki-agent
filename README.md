# wiki-agent

슬랙에서 `@wiki 질문` 하면 Confluence 위키를 CQL로 검색해 요약 + 링크를 스레드로 답변하는 Slack 봇입니다.

## 아키텍처

```
Slack (mention / slash command)
    │
    ▼
SlackBotGateway (Bolt Socket Mode)
    │
    ▼
ConfluenceSearchAgent
    │  CQL 검색
    ▼
ConfluenceClient ──► Confluence REST API
```

Socket Mode를 사용해 공개 URL 없이 로컬에서도 실행 가능합니다.

## 기술 스택

| 항목 | 버전 |
|------|------|
| Kotlin | 2.3 |
| Koog | 0.8.0 |
| Slack Bolt SDK | 1.46 |
| Ktor Client | 3.1.2 |
| Logback | 1.5.13 |

## 사전 준비

### 1. Slack App 설정

1. [api.slack.com/apps](https://api.slack.com/apps) → **Create New App** → From scratch
2. **Socket Mode** 탭 → Enable → App-Level Token 생성 (`connections:write` scope) → `SLACK_APP_TOKEN` 메모
3. **OAuth & Permissions** → Bot Token Scopes 추가:
   - `app_mentions:read`, `chat:write`, `commands`, `channels:read`
4. **Slash Commands** → `/wiki` 추가 (Socket Mode라 Request URL 불필요)
5. **Event Subscriptions** → Enable → Subscribe to bot events: `app_mention`
6. **Install App** → 워크스페이스 설치 → `SLACK_BOT_TOKEN` 메모

### 2. Confluence API 토큰

[Atlassian 계정 보안 설정](https://id.atlassian.com/manage-profile/security/api-tokens)에서 토큰 생성 후 Base64 인코딩:

```bash
echo -n "your@email.com:your-api-token" | base64
```

## 설정

`.wikiq/config.yml` 파일을 수정합니다:

```yaml
model:
  provider: CLAUDE_CODE  # CLAUDE_CODE | ANTHROPIC | GOOGLE
  # name: claude-sonnet-4-6  # ANTHROPIC/GOOGLE 사용 시
  # apiKey: sk-ant-...

confluence:
  baseUrl: https://yourcompany.atlassian.net
  token: <base64 인코딩된 email:api-token>
  spaces:
    - DEV
    - PM

slack:
  botToken: xoxb-...
  appToken: xapp-...
```

### LLM 프로바이더 전환

| 모드 | `provider` 설정 |
|------|----------------|
| 로컬 (Claude Code CLI) | `CLAUDE_CODE` |
| Claude API | `ANTHROPIC` + `apiKey` + `name` |
| Gemini API | `GOOGLE` + `apiKey` + `name` |

## 실행

```bash
# Gradle로 실행
./gradlew run

# 또는 fat JAR 빌드 후 실행
./gradlew shadowJar
java -jar build/libs/wiki-agent-1.0.0-all.jar
```

## 사용법

슬랙에서:

```
@wiki 배포 프로세스 알려줘
```

봇이 Confluence를 검색해 관련 문서 목록과 링크를 스레드로 답변합니다.

슬래시 커맨드로 검색 스페이스를 설정할 수 있습니다:

```
/wiki config space DEV,PM,HR   # 검색 스페이스 설정
/wiki config space show         # 현재 설정 확인
```

## 테스트

```bash
./gradlew test
```

## 프로젝트 구조

```
src/main/kotlin/io/github/veronikapj/wiki/
├── Main.kt                          # 진입점
├── agent/
│   └── ConfluenceSearchAgent.kt     # Confluence 검색 및 결과 포맷
├── config/
│   ├── WikiConfig.kt                # 설정 데이터 클래스
│   └── ConfigLoader.kt              # YAML 설정 로더
├── confluence/
│   └── ConfluenceClient.kt          # Confluence REST API 클라이언트 (CQL)
├── llm/
│   ├── ClaudeCodeLLMClient.kt       # Claude Code CLI 기반 LLM 클라이언트
│   └── LLMExecutorBuilder.kt        # LLM 프로바이더 팩토리
└── slack/
    ├── SlackBotGateway.kt           # Bolt Socket Mode 게이트웨이
    └── SlackConfigHandler.kt        # 슬래시 커맨드 핸들러
```
