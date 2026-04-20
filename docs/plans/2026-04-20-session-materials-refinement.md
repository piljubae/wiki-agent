# 세션 자료 다듬기 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** koog-session-design.md와 koog-session-materials.md의 팩트 오류 수정, A2A를 별도 심화 슬라이드로 분리, 슬라이드 번호 재정렬, 코드 예시를 실제 코드에 정렬한다.

**Architecture:** design 문서(목차/구조)를 먼저 수정하고, materials 문서(상세 내용)를 design에 맞춰 수정한다. 총 18 슬라이드로 재정렬.

**Tech Stack:** Markdown 문서 편집

---

## Task 1: design 문서 — 팩트 오류 + A2A 분리 + 슬라이드 재정렬

**Files:**
- Modify: `docs/plans/2026-04-20-koog-session-design.md`

**Step 1: 전체 수정 적용**

변경 사항:

1. **2부 시간**: `(15분)` → `(18분)`
2. **슬라이드 6**: `핵심 개념 — AIAgent / Tool / A2A Protocol` → `핵심 개념 — AIAgent / Tool`
   - `A2A (Agent-to-Agent) 프로토콜 내장` 특징은 유지하되 핵심 개념에서 제외
3. **슬라이드 9 (신규)**: 슬라이드 8 뒤에 삽입 — "심화 — Tool 호출 vs A2A Protocol"
   - ToolRegistry 방식 (현재) vs A2A 방식 코드 비교
   - Google A2A Protocol 표준 (a2a-protocol.org)
   - "오늘은 ToolRegistry로 충분. 독립 배포/스케일링 필요 시 전환"
4. **3부 시간**: `(75분)` → `(72분)`
5. **슬라이드 번호 재정렬**: 기존 9-17 → 10-18
6. **슬라이드 3 (materials)**: `~300줄` → `~1,300줄`
7. **4부 시간**: `(15분)` 유지

**Step 2: 커밋**

```bash
git add docs/plans/2026-04-20-koog-session-design.md
git commit -m "docs: design 문서 — A2A 분리 + 팩트 수정 + 슬라이드 재정렬"
```

---

## Task 2: materials 문서 — 헤더 정리 + 슬라이드 3 팩트 수정

**Files:**
- Modify: `docs/plans/2026-04-20-koog-session-materials.md`

**Step 1: 수정 적용**

1. **헤더**: `> **For Claude:** REQUIRED SUB-SKILL: ...` 지시문 제거
2. **Goal/Architecture**: 슬라이드 18개, 120분으로 업데이트
3. **Task 2 슬라이드 3**: `~300줄` → `~1,300줄`

**Step 2: 커밋**

```bash
git add docs/plans/2026-04-20-koog-session-materials.md
git commit -m "docs: materials 헤더 정리 + 슬라이드 3 팩트 수정"
```

---

## Task 3: materials 문서 — 슬라이드 4-5 수정 (Koog 소개 + 비교표)

**Files:**
- Modify: `docs/plans/2026-04-20-koog-session-materials.md`

**Step 1: 수정 적용**

1. **슬라이드 4 특징**: `A2A (Agent-to-Agent) 프로토콜 내장` → `Google A2A Protocol 지원 (a2a-protocol.org)`
2. **슬라이드 5 비교표**: `에이전트 간 통신` 행을 다음으로 변경:

| | LangChain | LlamaIndex | Koog |
|--|-----------|-----------|------|
| 언어 | Python | Python | Kotlin |
| Google A2A | ❌ | ❌ | ✅ |
| 멀티 LLM | ✅ | ✅ | ✅ |
| JVM 생태계 | ❌ | ❌ | ✅ |
| 타입 안전성 | 낮음 | 낮음 | 높음 |

**Step 2: 커밋**

```bash
git add docs/plans/2026-04-20-koog-session-materials.md
git commit -m "docs: materials 슬라이드 4-5 A2A 표기 수정"
```

---

## Task 4: materials 문서 — 슬라이드 6 핵심 개념 수정 (3가지 → 2가지)

**Files:**
- Modify: `docs/plans/2026-04-20-koog-session-materials.md`

**Step 1: 수정 적용**

1. **제목**: `핵심 개념 3가지` → `핵심 개념 2가지`
2. **① AIAgent 코드**: 실제 `OrchestratorAgent.kt:55-69` 기반으로 업데이트 (fallback은 생략, 세션용 단순화)

```kotlin
AIAgent(
    promptExecutor = executor,
    agentConfig = AIAgentConfig(
        prompt = prompt("orchestrator", params = AnthropicParams(maxTokens = 2048)) {
            system(systemPrompt)
        },
        model = AnthropicModels.Haiku_4_5,
        maxAgentIterations = 10,
    ),
    toolRegistry = ToolRegistry {
        tool(confluenceTool::confluenceSearch)
        if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
        if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
    },
)
```

3. **② Tool 코드**: 실제 `ConfluenceTool.kt` 반영 — `@LLMDescription` 파라미터 설명 추가

```kotlin
class ConfluenceTool(private val searchAgent: ConfluenceSearchAgent) {

    @Tool("confluenceSearch")
    @LLMDescription("Confluence 위키에서 질문과 관련된 문서를 CQL로 검색합니다. 키워드나 질문 형태로 입력하세요.")
    fun confluenceSearch(
        @LLMDescription("검색할 질문 또는 키워드 (한국어 가능)") query: String,
    ): String = runBlocking { searchAgent.search(query) }
}
```

4. **③ A2A Protocol 섹션 전체 삭제**

**Step 2: 커밋**

```bash
git add docs/plans/2026-04-20-koog-session-materials.md
git commit -m "docs: materials 슬라이드 6 핵심 개념 — A2A 제거 + 코드 정렬"
```

---

## Task 5: materials 문서 — 슬라이드 9 (신규) A2A 심화 슬라이드 삽입

**Files:**
- Modify: `docs/plans/2026-04-20-koog-session-materials.md`

**Step 1: Task 3 (기존 슬라이드 8) 뒤에 새 슬라이드 삽입**

슬라이드 8 커밋 후, 새로운 내용 추가:

```markdown
## 슬라이드 9: 심화 — Tool 호출 vs A2A Protocol

**현재 wiki-agent (같은 프로세스, ToolRegistry):**

```
┌─────────────────────────────────────────┐
│ OrchestratorAgent (단일 프로세스)          │
│   └── ToolRegistry {                    │
│         tool(::confluenceSearch)  ← 함수 호출  │
│         tool(::githubWikiSearch)         │
│         tool(::vectorSearch)             │
│       }                                 │
└─────────────────────────────────────────┘
```

**A2A Protocol로 전환하면 (독립 프로세스, HTTP):**

```
┌──────────────┐  HTTP(A2A)  ┌─────────────────────┐
│ Orchestrator │ ───────────→│ ConfluenceAgent     │
│ (A2A Client) │             │ (A2A Server :9001)  │
│              │ ───────────→│ GitHubWikiAgent     │
│              │             │ (A2A Server :9002)  │
│              │ ───────────→│ VectorSearchAgent   │
│              │             │ (A2A Server :9003)  │
└──────────────┘             └─────────────────────┘
```

**A2A 서버 코드 (Koog):**
```kotlin
val a2aServer = A2AServer(
    agentExecutor = ConfluenceAgentExecutor(searchAgent),
    agentCard = AgentCard(
        name = "ConfluenceSearch",
        url = "http://localhost:9001/confluence",
        skills = listOf(AgentSkill(id = "search", name = "Search", description = "CQL 검색")),
    ),
)
HttpJSONRPCServerTransport(a2aServer).start(port = 9001, path = "/confluence")
```

**언제 전환?**

| 현재 (ToolRegistry) | A2A 전환 시 |
|---------------------|------------|
| 단일 프로세스, 간단 | 에이전트별 독립 배포 |
| 로컬/소규모 팀 적합 | 대규모 팀, MSA 구조 |
| 시작이 쉬움 | 다른 언어 에이전트 연결 가능 |

- **Google A2A Protocol** (a2a-protocol.org) 표준 — Koog가 구현
- 오늘은 ToolRegistry로 충분. **필요할 때 전환.**

---
```

**Step 2: 커밋**

```bash
git add docs/plans/2026-04-20-koog-session-materials.md
git commit -m "docs: materials 슬라이드 9 A2A 심화 슬라이드 추가"
```

---

## Task 6: materials 문서 — 슬라이드 번호 재정렬 (10-18)

**Files:**
- Modify: `docs/plans/2026-04-20-koog-session-materials.md`

**Step 1: 기존 슬라이드 9-17 → 10-18로 번호 변경**

매핑:
- 기존 `슬라이드 9` (에이전트 설계 3단계) → `슬라이드 10`
- 기존 `슬라이드 10` (프롬프트 원칙 1) → `슬라이드 11`
- 기존 `슬라이드 11` (프롬프트 원칙 2) → `슬라이드 12`
- 기존 `슬라이드 12` (프롬프트 원칙 3) → `슬라이드 13`
- 기존 `슬라이드 13` (Before/After) → `슬라이드 14`
- 기존 `슬라이드 14` (직군별 아이디어) → `슬라이드 15`
- 기존 `슬라이드 15` (워크숍) → `슬라이드 16`
- 기존 `슬라이드 16` (로컬 시작) → `슬라이드 17`
- 기존 `슬라이드 17` (Q&A) → `슬라이드 18`

Task 4-6 제목도 슬라이드 번호에 맞게 업데이트:
- `Task 4: 3부 — 에이전트 설계 (슬라이드 9-13, 75분)` → `Task 4: 3부 — 에이전트 설계 (슬라이드 10-14, 72분)`
- `Task 5: 3부 — 아이디어 + 워크숍 (슬라이드 14-15)` → `Task 5: 3부 — 아이디어 + 워크숍 (슬라이드 15-16)`
- `Task 6: 4부 — 마무리 (슬라이드 16-17)` → `Task 6: 4부 — 마무리 (슬라이드 17-18)`

**Step 2: 슬라이드 11 (프롬프트 원칙 1) 코드를 실제 OrchestratorAgent.kt:42-53 반영**

```kotlin
buildString {
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

기존에는 `ragEnabled` 변수를 사용했으나, 실제 코드는 `vectorSearchTool != null`과 `githubWikiTool != null` 분기. GitHub Wiki 관련 프롬프트도 포함.

**Step 3: 슬라이드 18 (Q&A) A2A 답변 보강**

기존:
```
A. Agent-to-Agent. 에이전트끼리 HTTP로 통신하는 프로토콜. Orchestrator가 Specialist를 부를 때 씁니다.
```

변경:
```
A. Google A2A Protocol (a2a-protocol.org). 에이전트끼리 HTTP로 통신하는 표준 프로토콜입니다.
wiki-agent는 현재 ToolRegistry(함수 호출)로 구현되어 있고, 독립 배포가 필요해지면 A2A로 전환할 수 있습니다.
슬라이드 9에서 비교한 내용을 참고하세요.
```

**Step 4: 최종 빌드 확인 — 슬라이드 1~18 모두 존재하는지 + 시간 합계 120분 확인**

**Step 5: 커밋**

```bash
git add docs/plans/2026-04-20-koog-session-materials.md
git commit -m "docs: materials 슬라이드 번호 재정렬 + 프롬프트 코드 정렬 + Q&A 보강"
```
