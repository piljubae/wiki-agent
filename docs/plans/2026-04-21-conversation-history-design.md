# 대화 기록 영속화 설계

## 개요

Slack 스레드 단위로 대화를 JSONL 파일에 저장하고, koogAgent 모드에서 이전 대화를 Koog prompt DSL(user/assistant 메시지)로 주입하여 맥락을 유지한다.

## 범위

- `answerWithKoogAgent` 모드에만 적용 (manualLoop는 기존 in-memory 5턴 유지)
- Slack 스레드 단위 세션 (`threadTs`가 키)
- 최근 5턴 유지

## 저장소

- JSONL 파일: `.wiki/sessions/{threadTs}.jsonl`
- 각 줄: `{"ts":"...","role":"user|assistant","content":"..."}`

## 히스토리 주입

Koog `prompt DSL`의 `user()`/`assistant()` 메서드로 이전 대화를 실제 메시지로 주입:

```kotlin
prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
    system(systemPrompt)
    for ((q, a) in history) {
        user(q)
        assistant(a)
    }
}
```

## 동작 흐름

```
사용자: @wiki 배포 프로세스 알려줘 (threadTs=1234)
    ↓
SlackBotGateway: threadTs를 OrchestratorAgent에 전달
    ↓
OrchestratorAgent.answerWithKoogAgent:
    1. ConversationStore.load("1234") → 최근 5턴
    2. buildAgent() 시 prompt DSL에 이전 대화 주입
    3. agent.run(currentQuestion)
    4. ConversationStore.append("1234", question, answer)
    ↓
SlackBotGateway: 최종 답변 전송
```

## 컴포넌트 변경

| 파일 | 변경 |
|------|------|
| Create `ConversationStore.kt` | JSONL 읽기/쓰기. load(sessionId), append(sessionId, question, answer) |
| Modify `OrchestratorAgent.kt` | answer()에 sessionId 파라미터 추가. buildAgent()에서 히스토리를 prompt DSL로 주입 |
| Modify `SlackBotGateway.kt` | threadTs를 sessionId로 전달 |
| Modify `Main.kt` | ConversationStore 생성 + 와이어링 |

## 데이터 모델

```kotlin
data class Turn(val question: String, val answer: String)
```

## JSONL 포맷

```jsonl
{"ts":"2026-04-21T07:00:00Z","role":"user","content":"배포 프로세스 알려줘"}
{"ts":"2026-04-21T07:00:05Z","role":"assistant","content":"배포 프로세스는..."}
```
