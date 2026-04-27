# 에이전트란 무엇인가

## 정의

에이전트(Agent)는 **LLM + Tool + Loop** 세 가지로 구성된 자율 실행 시스템입니다.

```
[사용자 입력]
    ↓
  LLM — 다음에 무엇을 할지 판단
    ↓
  Tool 호출 필요? ──Yes──→ Tool 실행 → 결과를 LLM에 전달
    ↓ No                        ↑
  최종 답변 반환 ←──────────────┘ (필요하면 반복)
```

## 구성 요소

### LLM (Large Language Model)
판단하는 두뇌. 사용자 입력을 받아 다음 행동을 결정합니다.
- "어떤 Tool을 쓸지" 결정
- "Tool 결과로 충분한지" 판단
- "언제 답변을 반환할지" 결정

### Tool
실제 행동. LLM이 접근할 수 있는 외부 시스템 또는 기능입니다.
- 파일 읽기·쓰기
- API 호출 (Confluence, GitHub, Slack 등)
- 데이터베이스 검색
- 코드 실행

### Loop
완료될 때까지 반복. 한 번의 LLM 호출로 끝나지 않습니다.
- LLM이 "목표 달성"을 판단할 때까지 Tool 호출과 추론을 반복
- wiki-agent의 경우 `maxAgentIterations = 10` (최대 10회)

## wiki-agent에서의 구성

| 요소 | 구현 |
|------|------|
| LLM | Claude (Haiku 4.5 → Sonnet 4 fallback) 또는 Gemini 2.0 Flash |
| Tool | `confluenceSearch`, `githubWikiSearch`, `vectorSearch` |
| Loop | Koog `AIAgent` 내부 루프 (`maxAgentIterations = 10`) |

---

> **Reference:** [Koog AIAgent](https://github.com/JetBrains/koog) · [wiki-agent OrchestratorAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt)
