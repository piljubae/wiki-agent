# config.yml — slack 섹션

## 설정

`.wikiq/config.yml`:

```yaml
slack: {}
```

`slack:` 섹션 자체는 비워도 됩니다. 토큰은 **반드시 `.env` 또는 환경변수**로 전달합니다.

## 필요한 토큰 2개

| 환경변수 | 형식 | 역할 |
|---------|------|------|
| `SLACK_BOT_TOKEN` | `xoxb-...` | 메시지 전송, 이벤트 구독 |
| `SLACK_APP_TOKEN` | `xapp-...` | Socket Mode WebSocket 연결 |

`.env`:

```
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
```

## Socket Mode 동작 방식

wiki-agent는 공개 HTTP 엔드포인트 없이 Slack과 통신합니다.

```
Slack 서버 ←──WebSocket──→ SocketModeApp (wiki-agent)
```

1. `SocketModeApp`이 Slack에 WebSocket 연결 요청
2. `SLACK_APP_TOKEN`으로 인증
3. Slack이 이벤트를 WebSocket으로 push
4. wiki-agent가 이벤트 처리 후 `SLACK_BOT_TOKEN`으로 메시지 전송

**장점:** 로컬 개발 환경에서 별도 터널(ngrok 등) 없이 실행 가능

## 코드 참조

```kotlin
// SlackBotGateway.kt
SocketModeApp(slackConfig.appToken, app).start()

// 멘션 이벤트 수신
app.event(AppMentionEvent::class.java) { payload, ctx ->
    val query = extractQuery(payload.event.text)  // "@봇ID" 제거
    val result = runBlocking { orchestrator.answer(query) }
    ctx.asyncClient().chatPostMessage { ... }
}
```

## 슬래시 커맨드

```kotlin
app.command("/wiki") { req, ctx ->
    val result = configHandler.handle("/wiki ${req.payload.text}")
    ctx.ack(result)
}
```

사용 가능한 슬래시 커맨드:

```
/wiki config space DEV,PM,HR    # 검색 스페이스 변경
/wiki config space show          # 현재 설정 확인
/wiki reindex                    # RAG 재인덱싱
/wiki reindex status             # 마지막 인덱싱 정보
```

---

> **Source:** [SlackBotGateway.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt)  
> **SDK:** `com.slack.api:bolt-socket-mode:1.46.0`
