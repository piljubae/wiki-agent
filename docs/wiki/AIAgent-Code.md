# AIAgent 코드 구조

## 기본 구조

wiki-agent에서 실제로 사용하는 AIAgent 생성 코드입니다.

```kotlin
@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor

fun buildAgent(model: LLModel): AIAgent<String, String> {
    return AIAgent(
        promptExecutor = executor,          // MultiLLMPromptExecutor
        agentConfig = AIAgentConfig(
            prompt = prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
                system(systemPrompt)
            },
            model = model,
            maxAgentIterations = 10,        // 최대 Tool 호출 반복 횟수
        ),
        toolRegistry = ToolRegistry {
            tool(confluenceTool::confluenceSearch)
        },
    )
}
```

## 주요 파라미터

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `promptExecutor` | `MultiLLMPromptExecutor` | LLM 실행기 (provider 추상화) |
| `agentConfig` | `AIAgentConfig` | 프롬프트·모델·반복 설정 |
| `toolRegistry` | `ToolRegistry` | 사용 가능한 Tool 목록 |

## AIAgentConfig 파라미터

| 파라미터 | 설명 |
|---------|------|
| `prompt` | 시스템 프롬프트 (DSL로 작성) |
| `model` | 사용할 LLModel (예: `AnthropicModels.Haiku_4_5`) |
| `maxAgentIterations` | Tool 호출 최대 반복 횟수 (기본값 없음, 명시 필요) |

## 실행

```kotlin
val result: String = agent.run(question)
```

`run()`은 `suspend` 함수입니다. Tool 호출이 필요하면 `maxAgentIterations`까지 반복 후 최종 답변을 반환합니다.

## 주의사항

- `@file:OptIn(ExperimentalAgentsApi::class)` 필수 (0.8.0 기준 실험적 API)
- `MultiLLMPromptExecutor`는 여러 LLM client를 등록하고 모델별로 라우팅

---

> **Source:** [OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt)  
> **Reference:** [Koog AIAgent Docs](https://docs.koog.ai)
