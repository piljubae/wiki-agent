# 대화 이력 (ConversationStore)

## 핵심 질문

> 대화 이력은 어떻게 저장되고, 오래된 내용은 어떻게 처리하나요?

## 개요

`ConversationStore`는 Slack 스레드별 대화를 JSONL 파일로 저장합니다.  
LLM 프롬프트에 과거 문맥을 포함해 이전 대화를 이어받을 수 있게 합니다.

## 세션 ID 규칙

| 진입점 | sessionId |
|--------|-----------|
| 채널 멘션 (`@배필주2`) | Slack 메시지 ts (스레드 단위) |
| DM | `dm-{channelId}` (채널 전체가 하나의 세션) |

## 저장 구조

```
.wiki/
└── sessions/
    ├── {sessionId}.jsonl       ← 대화 이력
    └── {sessionId}.summary.md  ← 압축 요약 (10턴 초과 시 생성)
```

각 줄은 JSON 한 줄:

```json
{"ts":"2026-04-26T10:00:00Z","role":"user","content":"배포 프로세스 알려줘"}
{"ts":"2026-04-26T10:00:01Z","role":"assistant","content":"배포는 ..."}
```

## 컨텍스트 윈도우

LLM 프롬프트에는 최근 **5턴**만 포함됩니다:

```kotlin
fun load(sessionId: String, maxTurns: Int = 5): List<Turn> =
    loadAll(sessionId).takeLast(maxTurns)
```

## 슬라이딩 윈도우 압축

대화가 **10턴**을 초과하면 오래된 내용을 LLM으로 요약하고 최근 **4턴**만 보존합니다:

```
10턴 초과 감지
    ↓
오래된 턴 + 기존 요약 → LLM 요약
    ↓
summary.md 저장
    ↓
JSONL에서 오래된 턴 삭제 → 최근 4턴만 유지
```

다음 호출부터 요약이 시스템 프롬프트 앞에 자동 삽입됩니다:

```kotlin
summary?.let {
    appendLine("# 이전 대화 요약")
    appendLine(it)
}
```

## 임계값 상수

```kotlin
companion object {
    const val COMPRESS_THRESHOLD = 10  // 이 턴 수 초과 시 압축
    const val KEEP_RECENT        = 4   // 압축 후 보존할 최근 턴 수
}
```

## OrchestratorAgent 연결

```kotlin
OrchestratorAgent(
    confluenceTool    = confluenceTool,
    conversationStore = ConversationStore(".wiki/sessions"),
    ...
)
```

`answer(question, sessionId = threadTs)` 호출 시 자동으로 이력을 로드·저장합니다.

---

> **Source:** [ConversationStore.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/context/ConversationStore.kt) · [OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt)
