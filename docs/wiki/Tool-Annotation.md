# @Tool / @LLMDescription 어노테이션

Koog에서 Tool을 정의하는 가장 간단한 방법은 어노테이션 기반 방식입니다.

## 어노테이션 임포트 경로

```kotlin
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
```

## 실제 코드 예시 — ConfluenceTool

```kotlin
class ConfluenceTool(private val searchAgent: ConfluenceSearchAgent) {

    @Tool("confluenceSearch")
    @LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
    fun confluenceSearch(
        @LLMDescription("검색할 질문 또는 키워드 (한국어 가능)")
        query: String,
    ): String = runBlocking { searchAgent.search(query) }
}
```

## 어노테이션 역할

| 어노테이션 | 위치 | 역할 |
|-----------|------|------|
| `@Tool("이름")` | 메서드 | Tool 이름 지정. LLM이 이 이름으로 Tool을 식별 |
| `@LLMDescription("설명")` | 메서드 | LLM에게 이 Tool이 어떤 기능인지 설명 |
| `@LLMDescription("설명")` | 파라미터 | LLM에게 이 파라미터에 무엇을 넣어야 하는지 설명 |

## 설계 원칙

**`@LLMDescription`은 LLM이 읽는 문서**입니다. 사람이 아니라 LLM이 Tool을 선택할 때 이 설명을 참고합니다.

좋은 설명:
```kotlin
@LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
```

나쁜 설명:
```kotlin
@LLMDescription("검색 함수")  // 너무 짧아서 LLM이 언제 써야 할지 판단 못 함
```

## Description 품질이 Tool 선택에 미치는 영향

description이 모호하거나 여러 Tool 간 설명이 겹치면 LLM이 잘못된 Tool을 선택하거나 아무것도 선택하지 않을 수 있습니다.

### 연구 및 공식 문서 근거

| 출처 | 핵심 내용 |
|------|----------|
| [Learning to Rewrite Tool Descriptions for Reliable LLM-Agent Tool Use](https://arxiv.org/abs/2602.20426) (2026) | tool description이 agent 성능의 주요 병목. 100개 이상 Tool 환경에서 description 품질이 선택 정확도에 직접 영향 |
| [MetaTool Benchmark](https://arxiv.org/abs/2310.03128) (2023) | LLM이 Tool을 사용할지, 어떤 Tool을 쓸지 결정하는 벤치마크. description 명확도와 선택 정확도 간 직접적 상관관계 측정 |
| [ToolLLM: Facilitating LLMs to Master 16000+ Real-world APIs](https://arxiv.org/abs/2307.16789) (2023) | 16,464개 API 대상 실험. 잘 정의된 description일수록 pass rate 향상 |
| [Anthropic Tool Use 공식 문서](https://docs.anthropic.com/en/docs/tool-use) | tool selection이 description 기반으로 작동함을 명시 |
| [OpenAI Function Calling 가이드](https://platform.openai.com/docs/guides/function-calling) | "overlapping or vague descriptions → wrong tool or no tool called" 명시 |

## runBlocking 사용 이유

Tool 메서드는 일반 함수(`fun`)여야 하지만, 내부 구현은 `suspend` 함수를 호출합니다.  
이 간극을 `runBlocking`으로 해소합니다.

```kotlin
fun confluenceSearch(query: String): String =
    runBlocking { searchAgent.search(query) }  // suspend → blocking 브릿지
```

---

> **Source:** [ConfluenceTool.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/tool/ConfluenceTool.kt)  
> **Reference:** [Koog Annotation-based Tools](https://docs.koog.ai)
