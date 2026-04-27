# 프롬프트 설계 원칙 1 — 역할과 출력 형식 분리

## 원칙

프롬프트에는 두 가지를 명확히 분리해야 합니다.

1. **역할 (Role):** LLM이 누구인지, 무엇을 해야 하는지
2. **출력 형식 (Format):** 어떤 형태로 답변을 줄지

## Before — 나쁜 프롬프트

```
사용자 질문에 답해줘: {question}
```

**문제점:**
- 역할 없음 → LLM이 마음대로 해석
- 출력 형식 없음 → 매번 다른 형태로 응답
- 검색을 해야 하는지 직접 답해도 되는지 모름

## After — wiki-agent OrchestratorAgent 실제 프롬프트

```kotlin
val systemPrompt = buildString {
    appendLine("당신은 Confluence 위키와 GitHub Wiki 검색 전문가입니다.")  // 역할
    appendLine("사용자의 질문에 답하기 위해 반드시 제공된 Tool을 사용해 검색하세요.")  // 행동 지침
    appendLine("검색 없이 직접 답변하지 마세요.")  // 제약
    appendLine("검색 결과를 바탕으로 요약과 링크를 함께 제공하세요.")  // 출력 형식
}
```

## 역할 선언의 효과

| 역할 선언 | 없을 때 | 있을 때 |
|----------|---------|---------|
| LLM 행동 | 일반 AI 어시스턴트처럼 동작 | 검색 전문가로 동작 |
| Tool 사용 | 필요 없으면 직접 답변 | 항상 Tool 먼저 호출 |
| 답변 형식 | 매번 다름 | 요약 + 링크 형식 유지 |

## 출력 형식 지정 예시

**명확한 형식 지정:**
```
출력 형식:
1. **{문서 제목}**
   링크: {url}
   요약: {1-2문장}

문서를 찾지 못한 경우:
"'{query}' 관련 문서를 찾을 수 없습니다."
```

**형식 없을 때 문제:** 답변이 때론 목록, 때론 산문, 때론 표 형태로 와서 Slack 메시지가 불안정해집니다.

---

> **Source:** [OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt)
