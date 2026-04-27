# Fallback 모델 체인

## 개요

wiki-agent는 LLM 호출 실패 시 자동으로 다음 모델로 재시도합니다.

## 구현 (OrchestratorAgent)

```kotlin
val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)

for ((index, model) in fallbackModels.withIndex()) {
    val result = runCatching { buildAgent(model).run(question) }
    val ex = result.exceptionOrNull()
    if (ex == null) return result.getOrThrow()
    if (index < fallbackModels.lastIndex) {
        log.warn("Retrying with {}", fallbackModels[index + 1].id)
        continue
    }
    log.error("All models failed: {}", ex.message)
    return "검색 중 오류가 발생했습니다: ${ex.message}"
}
```

## Fallback 순서

| 순서 | 모델 | 상수명 | 특징 |
|------|------|--------|------|
| 1차 | claude-haiku-4-5 | `AnthropicModels.Haiku_4_5` | 빠름, 저렴 |
| 2차 | claude-sonnet-4 | `AnthropicModels.Sonnet_4` | 더 강력 |

## 모델 상수 위치

```kotlin
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels

AnthropicModels.Haiku_4_5   // claude-haiku-4-5-20251001
AnthropicModels.Sonnet_4    // claude-sonnet-4-20250514
```

Google provider 사용 시 기본 모델:

```kotlin
import ai.koog.prompt.executor.clients.google.GoogleModels

GoogleModels.Gemini2_0Flash  // gemini-2.0-flash
```

## Fallback이 발생하는 상황

- API 일시적 오류 (5xx)
- Rate limit 초과
- 토큰 한도 초과
- 네트워크 타임아웃

## provider별 기본 모델 (LLMExecutorBuilder)

```kotlin
fun defaultModel(config: ModelConfig): LLModel =
    when (config.provider) {
        CLAUDE_CODE -> AnthropicModels.Sonnet_4
        ANTHROPIC   -> AnthropicModels.Sonnet_4
        GOOGLE      -> GoogleModels.Gemini2_0Flash
    }
```

---

> **Source:** [OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt) · [LLMExecutorBuilder.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/llm/LLMExecutorBuilder.kt)
