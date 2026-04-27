# Orchestrator 역할

## 정의

Orchestrator는 **사용자 질문을 받아 어떤 Tool(Specialist)을 쓸지 결정**하는 에이전트입니다.  
직접 답변하지 않고, 교통 정리만 담당합니다.

## wiki-agent OrchestratorAgent 시스템 프롬프트

```kotlin
val systemPrompt = buildString {
    appendLine("당신은 Confluence 위키와 GitHub Wiki 검색 전문가입니다.")
    appendLine("사용자의 질문에 답하기 위해 반드시 제공된 Tool을 사용해 검색하세요.")
    appendLine("검색 없이 직접 답변하지 마세요.")
    if (vectorSearchTool != null) {
        appendLine("confluenceSearch로 먼저 검색하고, 결과가 부족하면 vectorSearch도 사용하세요.")
    }
    if (githubWikiTool != null) {
        appendLine("기술 문서나 코드 관련 질문은 githubWikiSearch도 사용하세요.")
    }
    appendLine("검색 결과를 바탕으로 요약과 링크를 함께 제공하세요.")
}
```

## "검색 없이 직접 답변하지 마세요"의 의미

LLM은 학습된 지식으로 직접 답변할 수 있습니다. 하지만 위키 봇의 목적은 **회사 내부 문서**를 찾아주는 것입니다.

- LLM 직접 답변 허용 시: 학습 데이터 기반 일반 지식 반환 (내부 문서 아님)
- Tool 강제 시: 항상 실제 위키에서 검색한 결과 반환

## Tool 선택 기준 (LLM이 판단)

| 질문 유형 | 선택되는 Tool |
|-----------|-------------|
| 사내 프로세스·정책 | `confluenceSearch` |
| 기술 문서·코드·API | `githubWikiSearch` |
| 의미 기반 유사 문서 | `vectorSearch` |

LLM이 `@LLMDescription`을 읽고 적합한 Tool을 선택합니다.

## Fallback 처리

```kotlin
val fallbackModels = listOf(AnthropicModels.Haiku_4_5, AnthropicModels.Sonnet_4)
for ((index, model) in fallbackModels.withIndex()) {
    val result = runCatching { buildAgent(model).run(question) }
    if (result.isSuccess) return result.getOrThrow()
    // 실패 시 다음 모델로 재시도
}
```

`Haiku_4_5`가 실패하면 자동으로 `Sonnet_4`로 재시도합니다.

---

> **Source:** [OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt)
