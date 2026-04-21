# EventHandler (ReAct 시각화) 설계

## 개요

Koog AIAgent 모드에서 Tool 호출 과정을 Slack 스레드에 실시간으로 보여주고, 최종 답변에 출처 footer를 추가한다.

## 범위

- `answerWithKoogAgent` 모드에만 적용 (manualLoop는 로그 유지, 나중에 확장 가능)
- Koog `installFeatures { install(EventHandler) }` DSL 사용

## 동작 흐름

```
사용자: @wiki 배포 프로세스 알려줘
    ↓
SlackBotGateway: 스레드에 "🔍 Confluence 검색 중..." 임시 메시지 전송
    ↓
OrchestratorAgent (Koog AIAgent):
    EventHandler.onToolCallStarting → 콜백으로 Slack 임시 메시지 업데이트
    EventHandler.onToolCallCompleted → SourceTracker에 기록
    ↓
최종 답변 생성
    ↓
SlackBotGateway:
    1. 임시 메시지 삭제 (chat.delete)
    2. 최종 답변 + footer 전송: "... 📋 Confluence 3건 · GitHub Wiki 1건"
```

## 컴포넌트 변경

| 파일 | 변경 |
|------|------|
| `OrchestratorAgent.kt` | `buildAgent()`에 `installFeatures { install(EventHandler) }` 추가. Tool 시작/완료 시 콜백 호출 |
| `SlackBotGateway.kt` | 임시 메시지 전송/삭제 로직. 최종 답변에 footer 부착 |
| `SourceTracker.kt` | 기존 활용 — Tool 완료 시 소스별 건수 집계 |

## 콜백 인터페이스

```kotlin
interface SearchProgressListener {
    suspend fun onSearchStarted(toolName: String)
    suspend fun onSearchCompleted(toolName: String, resultCount: Int)
}
```

`OrchestratorAgent`가 이 인터페이스를 생성자로 받고, `SlackBotGateway`가 구현한다.
나중에 manualLoop 확장 시에도 같은 인터페이스를 사용한다.

## 중간 메시지 라이프사이클

1. Tool 호출 시작 → Slack 스레드에 임시 메시지 전송 (`chat.postMessage`)
2. 다른 Tool 시작 시 → 같은 메시지를 `chat.update`로 업데이트
3. 최종 답변 생성 완료 → 임시 메시지 삭제 (`chat.delete`) + 최종 답변 전송

## footer 형식

- 복수 소스: `📋 Confluence 3건 · GitHub Wiki 1건`
- 단일 소스: `📋 Confluence 3건`
- 결과 없음: footer 생략
