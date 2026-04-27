# 리액션 피드백

## 핵심 질문

> 봇 답변에 👍/👎를 누르면 어떻게 처리되나요?

## 개요

봇이 전송한 메시지에 `👍` 또는 `👎` 리액션을 달면 로그에 기록됩니다.  
현재는 로깅 수준이지만, 향후 검색 품질 개선·파인튜닝 데이터로 활용할 수 있습니다.

## 동작 흐름

```
사용자가 봇 답변에 👍 리액션 추가
    ↓
reaction_added 이벤트 수신
    ↓
해당 메시지 ts가 botMessageTimestamps에 있는지 확인
    ↓ 있으면 (봇 답변이 맞음)
log.info("feedback: thumbsup channel={} ts={}", ...)
```

## 봇 메시지 식별

봇이 전송한 메시지의 ts를 `botMessageTimestamps`에 등록합니다:

```kotlin
val sendResult = slackClient.chatPostMessage { ... }
sendResult.ts?.let { botMessageTimestamps.add(it) }
```

리액션 이벤트 수신 시 이 집합에서 확인해 봇 메시지인지 판별합니다:

```kotlin
if (reaction in FEEDBACK_REACTIONS && messageTs in botMessageTimestamps) {
    log.info("feedback: {} channel={} ts={}", reaction, channel, messageTs)
}
```

## botMessageTimestamps 크기 제한

메모리 누수 방지를 위해 최대 **500개** 유지합니다 (LRU 방식):

```kotlin
private val botMessageTimestamps: MutableSet<String> = Collections.synchronizedSet(
    object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= MAX_BOT_MSG_CACHE) iterator().let { it.next(); it.remove() }
            return super.add(element)
        }
    }
)
```

## 피드백 안내 메시지

답변 하단에 리액션 유도 문구가 자동으로 표시됩니다:

```kotlin
const val FEEDBACK_GUIDE = ":thumbsup: 도움이 됐다면 | :thumbsdown: 아쉬웠다면 리액션을 남겨주세요"
```

## Slack App 권한

`reaction_added` 이벤트를 수신하려면 Slack App에 다음 설정이 필요합니다:

- **Event Subscriptions** → `reaction_added` 이벤트 구독
- **Bot Token Scopes** → `reactions:read`

---

> **Source:** [SlackBotGateway.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt)
