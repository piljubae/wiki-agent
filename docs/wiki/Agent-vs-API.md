# 에이전트 vs 단순 API 호출

## 핵심 차이

| | 단순 API 호출 | 에이전트 |
|--|--------------|---------|
| 실행 흐름 | 요청 1회 → 응답 1회 | 목표 달성까지 반복 |
| 판단 | 코드가 분기 결정 | LLM이 다음 행동 결정 |
| Tool 선택 | 호출할 함수를 코드에 하드코딩 | LLM이 상황에 따라 선택 |
| 확장 | 새 기능 = 새 코드 작성 | 새 기능 = Tool 하나 추가 |

## 단순 API 호출 예시

```kotlin
// 항상 Confluence만 검색, 다른 소스는 고려 안 함
val result = confluenceClient.search(query)
return formatResult(result)
```

**문제:** 항상 Confluence만 검색합니다. GitHub Wiki가 더 적합한 질문이어도 모릅니다.

## 에이전트 방식 예시 (wiki-agent)

```kotlin
// OrchestratorAgent — LLM이 Tool을 선택
toolRegistry = ToolRegistry {
    tool(confluenceTool::confluenceSearch)
    tool(githubWikiTool::githubWikiSearch)
    tool(vectorSearchTool::vectorSearch)
}
```

**결과:**
- "배포 프로세스 알려줘" → LLM이 `confluenceSearch` 선택
- "API 설계 가이드" → LLM이 `githubWikiSearch` 선택
- 결과 부족 시 → LLM이 다른 Tool 추가 호출 결정

## 에이전트가 필요한 상황

- **도구 선택이 동적**일 때: 질문 유형에 따라 다른 소스를 검색해야 할 때
- **여러 단계가 필요**할 때: 검색 → 결과 부족 → 추가 검색 → 요약
- **반복 업무**일 때: "매번 같은 패턴의 작업"을 자동화할 때

---

> **Reference:** [wiki-agent OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt)
