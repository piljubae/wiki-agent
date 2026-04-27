# ToolRegistry — Tool 등록

`ToolRegistry`는 AIAgent가 사용할 수 있는 Tool 목록을 정의합니다.

## 기본 사용법

```kotlin
import ai.koog.agents.core.tools.ToolRegistry

toolRegistry = ToolRegistry {
    tool(confluenceTool::confluenceSearch)
}
```

`tool(obj::method)` 형태로 어노테이션이 붙은 메서드를 등록합니다.

## 조건부 Tool 등록 (wiki-agent 실제 코드)

```kotlin
toolRegistry = ToolRegistry {
    tool(confluenceTool::confluenceSearch)                              // 항상

    if (githubWikiTool != null)                                        // github.enabled=true 시
        tool(githubWikiTool::githubWikiSearch)

    if (vectorSearchTool != null)                                      // rag.enabled=true 시
        tool(vectorSearchTool::vectorSearch)
}
```

`config.yml`에서 `github.enabled: true` 또는 `rag.enabled: true`로 설정하면 해당 Tool이 추가됩니다.  
코드 변경 없이 Tool을 추가/제거할 수 있습니다.

## Tool 선택은 LLM이 담당

등록된 Tool이 여러 개일 때, **어떤 Tool을 호출할지는 LLM이 결정**합니다.

- LLM은 각 Tool의 `@LLMDescription`을 참고
- 질문에 가장 적합한 Tool 선택
- 필요하면 여러 Tool을 순서대로 호출

## Tool이 없을 때

Tool이 하나도 등록되지 않으면 AIAgent는 LLM 직접 답변만 수행합니다.  
wiki-agent는 `confluenceSearch`가 항상 등록되므로 최소 1개 Tool이 보장됩니다.

---

> **Source:** [OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt)  
> **Reference:** [Koog ToolRegistry](https://docs.koog.ai)
