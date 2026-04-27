# 실시간 검색 진행 표시

## 핵심 질문

> 검색 중에 Slack에 "검색 중..." 메시지가 뜨는 원리는 무엇인가요?

## 사용자 경험

```
[사용자] @wiki 배포 프로세스 알려줘

[봇] :mag: Confluence 검색 중...   ← 검색 시작 즉시 전송
         (검색 완료)
[봇] (진행 메시지 삭제)
[봇] 배포는 Jenkins에서 ...         ← 최종 답변
     📋 Confluence 3건 · RAG 1건    ← 소스 푸터
```

## SearchProgressListener

Koog AIAgent의 Tool 호출 시점을 콜백으로 전달하는 인터페이스입니다:

```kotlin
interface SearchProgressListener {
    suspend fun onSearchStarted(toolName: String)
    suspend fun onSearchCompleted(toolName: String)
}
```

## SearchProgressFeature (Koog 연동)

`AIAgentGraphFeature`를 구현해 Koog의 Tool 호출 파이프라인을 가로챕니다:

```kotlin
private class SearchProgressFeature(
    private val listener: SearchProgressListener,
) : AIAgentGraphFeature<SearchProgressFeature.Config, Unit> {

    override fun install(config: Config, pipeline: AIAgentGraphPipeline) {
        pipeline.interceptToolCallStarting(this) { ctx ->
            listener.onSearchStarted(ctx.toolName)
        }
        pipeline.interceptToolCallCompleted(this) { ctx ->
            listener.onSearchCompleted(ctx.toolName)
        }
    }
}
```

AIAgent 생성 시 `installFeatures` 람다로 등록합니다:

```kotlin
AIAgent(
    ...
    installFeatures = {
        if (listener != null) install(SearchProgressFeature(listener))
    },
)
```

## Slack 메시지 업데이트 패턴

```kotlin
override suspend fun onSearchStarted(toolName: String) {
    val msg = ":mag: ${toolDisplayNames[toolName]} 검색 중..."
    if (progressMessageTs == null) {
        // 첫 Tool → 새 메시지 전송
        progressMessageTs = slackClient.chatPostMessage { ... }.ts
    } else {
        // 두 번째 Tool 이후 → 기존 메시지 업데이트
        slackClient.chatUpdate { req -> req.ts(progressMessageTs).text(msg) }
    }
}
```

답변 완료 후 진행 메시지를 삭제합니다:

```kotlin
progressMessageTs?.let { ts ->
    slackClient.chatDelete { it.channel(channel).ts(ts) }
}
```

## SourceTracker (소스 푸터)

검색 소스별 건수를 집계해 답변 하단에 푸터를 추가합니다:

```kotlin
class SourceTracker {
    private val _counts = mutableMapOf<String, Int>()

    fun record(source: String) { _counts[source] = (_counts[source] ?: 0) + 1 }

    fun formatFooter(): String =
        "📋 " + _counts.entries.joinToString(" · ") { "${it.key} ${it.value}건" }
}
// 출력 예: 📋 Confluence 3건 · GitHub Wiki 1건
```

---

> **Source:** [SearchProgressListener.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/SearchProgressListener.kt) · [SlackBotGateway.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt) · [SourceTracker.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/tool/SourceTracker.kt)
