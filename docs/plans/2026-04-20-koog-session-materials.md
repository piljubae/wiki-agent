# Koog 세션 자료 작성 계획

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** "내가 원하는 에이전트, 직접 만들 수 있다" 세션의 슬라이드 목차 + 각 슬라이드 핵심 내용을 마크다운 문서로 완성한다.

**Architecture:** 설계 문서(`2026-04-20-koog-session-design.md`)를 기반으로 파트별로 슬라이드 내용을 작성한다. 최종 산출물은 `docs/session/koog-agent-session.md` 단일 파일.

**Tech Stack:** Markdown, wiki-agent 코드 예시 (docs/plans/2026-04-20-slack-confluence-qa-bot.md 참조)

---

## Task 1: 세션 파일 초기화 + 표지

**Files:**
- Create: `docs/session/koog-agent-session.md`

**Step 1: `docs/session/` 디렉터리 생성 후 파일 작성**

```markdown
# 내가 원하는 에이전트, 직접 만들 수 있다
## Koog로 배우는 AI 에이전트 설계

**대상:** AI 에이전트 개발이 막연했던 개발자 / Koog를 처음 접하는 개발자
**시간:** 2시간
**예시 프로젝트:** wiki-agent (Slack + Confluence Q&A 봇)

---

## 이 세션에서 얻어가는 것

| 레벨 | 얻어가는 것 |
|------|------------|
| AI 입문 (ChatGPT만 써봤음) | 에이전트 구성 요소 이해, 설계 방법론 |
| API 경험자 (OpenAI API 써봤음) | 프롬프트 설계 원칙, 단순 호출과 에이전트의 차이 |
| 프레임워크 경험자 (LangChain 등) | Koog 아키텍처 비교, Kotlin-native 에이전트 패턴 |

---
```

**Step 2: 커밋**

```bash
git add docs/session/koog-agent-session.md
git commit -m "docs: 세션 자료 파일 초기화"
```

---

## Task 2: 1부 — 에이전트, 만들어본 적 있나요? (슬라이드 1-3)

**Files:**
- Modify: `docs/session/koog-agent-session.md`

**Step 1: 슬라이드 1-3 내용 추가**

```markdown
---

# 1부. 에이전트, 만들어본 적 있나요?

---

## 슬라이드 1: 에이전트의 3가지 구성 요소

에이전트 = **LLM + Tool + Loop**

```
[사용자 질문]
    ↓
  LLM (판단)
    ↓
  Tool 호출? ──Yes──→ Tool 실행 → 결과 → LLM (재판단)
    ↓ No
  최종 답변 반환
```

- **LLM**: 판단하는 두뇌. 다음에 뭘 할지 결정
- **Tool**: 실제 행동. 파일 읽기, API 호출, 검색 등
- **Loop**: 완료될 때까지 반복. 한 번 호출이 아님

> 단순 API 호출과의 차이: API는 한 번 묻고 끝. 에이전트는 목표 달성까지 스스로 반복.

---

## 슬라이드 2: 데모 — wiki-agent 실제 동작

**시나리오 A:** `@wiki 배포 프로세스 알려줘`

```
[슬랙 멘션 수신]
    ↓
OrchestratorAgent (Koog AIAgent) — 질문 의도 파악 + Tool 선택
    ├── confluenceSearch → ConfluenceSearchAgent → CQL 검색 결과
    └── vectorSearch (RAG 활성화 시) → VectorSearchAgent → ChromaDB 의미 검색
    ↓
검색 결과 요약 + 페이지 링크 → [슬랙 스레드로 답변]
```

**시나리오 B (GitHub Wiki 활성화 시):** `@wiki 백엔드 API 설계 가이드`

```
OrchestratorAgent
    ├── confluenceSearch → 내부 위키
    └── githubWikiSearch → GitHub 레포 Wiki
```

LLM이 질문 유형에 따라 적절한 소스를 선택하거나 두 소스 모두 활용

**포인트:** OrchestratorAgent가 LLM을 통해 "어떤 Tool을 쓸지" 직접 결정한다.

*데모 영상 또는 라이브 시연*

---

## 슬라이드 3: 이게 Koog로 만들어졌다

- JetBrains가 만든 **Kotlin-native 에이전트 프레임워크**
- 위 데모 전체가 **~300줄** Kotlin 코드
- 오늘 이 구조를 이해하고, 내 케이스에 적용하는 방법을 가져간다

---
```

**Step 2: 커밋**

```bash
git add docs/session/koog-agent-session.md
git commit -m "docs: 세션 1부 슬라이드 작성"
```

---

## Task 3: 2부 — Koog가 뭔가 (슬라이드 4-8, 15분)

**Files:**
- Modify: `docs/session/koog-agent-session.md`

**Step 1: 슬라이드 4-8 내용 추가**

```markdown
# 2부. Koog가 뭔가

---

## 슬라이드 4: Koog 소개

**Koog** (읽기: 쿠그)

- JetBrains 개발, Kotlin-first 에이전트 프레임워크
- 버전: 0.8.0 (2025 기준 활발히 개발 중)
- GitHub: github.com/JetBrains/koog
- 특징:
  - Kotlin coroutine 기반 비동기 처리
  - A2A (Agent-to-Agent) 프로토콜 내장
  - 멀티 LLM provider 지원 (Anthropic, Google, OpenAI)

---

## 슬라이드 5: 다른 프레임워크와 비교

| | LangChain | LlamaIndex | Koog |
|--|-----------|-----------|------|
| 언어 | Python | Python | Kotlin |
| 에이전트 간 통신 | 직접 구현 | 직접 구현 | A2A 내장 |
| 멀티 LLM | ✅ | ✅ | ✅ |
| JVM 생태계 | ❌ | ❌ | ✅ |
| 타입 안전성 | 낮음 | 낮음 | 높음 |

> 백엔드/Android 개발자에게 가장 자연스러운 선택

---

## 슬라이드 6: 핵심 개념 3가지

**① AIAgent**
```kotlin
AIAgent(
    promptExecutor = executor,
    agentConfig = AIAgentConfig(
        prompt = prompt("orchestrator") { system(systemPrompt) },
        model = AnthropicModels.Haiku_4_5,
        maxAgentIterations = 10,
    ),
    toolRegistry = ToolRegistry {
        tool(confluenceTool::confluenceSearch)
    },
)
```

**② Tool — `@Tool` 어노테이션으로 등록**
```kotlin
class ConfluenceTool(private val searchAgent: ConfluenceSearchAgent) {

    @Tool("confluenceSearch")
    @LLMDescription("Confluence 위키에서 CQL로 검색합니다.")
    fun confluenceSearch(
        @LLMDescription("검색 키워드") query: String
    ): String = runBlocking { searchAgent.search(query) }
}
```

**③ A2A Protocol**
- 에이전트끼리 HTTP로 통신
- OrchestratorAgent → SpecialistAgent 호출
- 독립적으로 확장, 교체 가능

---

## 슬라이드 7: Provider 교체 한 줄

`config.yml` 한 줄만 바꾸면 LLM 교체:

```yaml
# 개발자: Claude Code (API 키 불필요)
model:
  provider: CLAUDE_CODE

# 팀 서버 배포: Gemini (전사 비개발자용)
model:
  provider: GOOGLE
  apiKey: AIza...
  name: gemini-2.0-flash

# 또는 Claude API
model:
  provider: ANTHROPIC
  apiKey: sk-ant-...
  name: claude-sonnet-4-6
```

> **우리 회사 시나리오:** 개발자가 Claude Code로 로컬 개발 → 팀 서버에 Gemini로 배포 → 전사 비개발 직군도 사용

---

## 슬라이드 8: Orchestrator + Specialist 패턴

```
OrchestratorAgent (AIAgent)
├── 역할: 의도 파악, 어떤 Tool을 쓸지 LLM이 결정
└── 직접 답변 안 함

Tool (Koog @Tool)
├── ConfluenceTool → ConfluenceSearchAgent (항상)
├── GitHubWikiTool → GitHubWikiSearchAgent (github.enabled=true 시)
└── VectorSearchTool → VectorSearchAgent (rag.enabled=true 시)
```

**코드로 보면 이게 전부:**
```kotlin
toolRegistry = ToolRegistry {
    tool(confluenceTool::confluenceSearch)
    if (githubWikiTool != null) tool(githubWikiTool::githubWikiSearch)
    if (vectorSearchTool != null) tool(vectorSearchTool::vectorSearch)
}
```

- **Orchestrator**: 교통정리만 — LLM이 Tool 선택
- **Specialist**: 하나의 도메인에 집중
- Tool 추가: config.yml 한 줄 → 코드 한 줄 → LLM이 알아서 선택

---
```

**Step 2: 커밋**

```bash
git add docs/session/koog-agent-session.md
git commit -m "docs: 세션 2부 슬라이드 작성"
```

---

## Task 4: 3부 — 에이전트 설계 (슬라이드 9-13, 75분)

**Files:**
- Modify: `docs/session/koog-agent-session.md`

**Step 1: 슬라이드 9-13 내용 추가**

```markdown
# 3부. 내 에이전트 어떻게 설계하나

---

## 슬라이드 9: 에이전트 설계 3단계

**① 반복업무 찾기**
> "매주 같은 질문을 받는다", "같은 문서를 여러 번 찾는다"

wiki-agent 예시:
- "우리 팀 배포 프로세스가 어떻게 돼?" → 매번 위키 찾아줌
- → ConfluenceSearchAgent로 자동화

**② 필요한 Tool 정의**
> "에이전트가 무엇에 접근해야 하나?"

wiki-agent 예시:
- Confluence API (CQL 검색, 페이지 내용 조회)
- Slack API (답변 전송)

**③ 프롬프트 설계**
> "LLM에게 어떤 역할을 주고, 어떤 형식으로 출력받을 것인가?"

---

## 슬라이드 10: 프롬프트 설계 원칙 1 — 역할과 출력 형식 분리

**나쁜 프롬프트:**
```
사용자 질문에 답해줘: {question}
```

**좋은 프롬프트 (wiki-agent OrchestratorAgent 실제 코드):**
```kotlin
buildString {
    appendLine("당신은 Confluence 위키 검색 전문가입니다.")
    appendLine("사용자의 질문에 답하기 위해 반드시 제공된 Tool을 사용해 검색하세요.")
    appendLine("검색 없이 직접 답변하지 마세요.")
    if (ragEnabled) {
        appendLine("confluenceSearch로 먼저 검색하고, 결과가 부족하면 vectorSearch도 사용하세요.")
    }
    appendLine("검색 결과를 바탕으로 요약과 링크를 함께 제공하세요.")
}
```

> 핵심: 역할(누구인지) + Tool 호출 강제 + 출력 형식(링크 포함)을 명확히 분리

---

## 슬라이드 11: 프롬프트 설계 원칙 2 — 컨텍스트 범위 제어

**문제:** 위키 전체를 다 읽히면 토큰 낭비 + 노이즈

**해결:**
```yaml
confluence:
  spaces:
    - DEV   # 검색 범위를 스페이스로 제한
    - PM
```

```
검색 범위: DEV, PM 스페이스만
검색 결과: 상위 5개 페이지만
페이지당 요약: 300자 이내
```

> 핵심: 컨텍스트가 많을수록 좋은 게 아님. 관련 있는 것만 정확히 줄 것.

---

## 슬라이드 12: 프롬프트 설계 원칙 3 — Tool 호출 유도 vs 직접 답변

**언제 Tool을 쓰게 할 것인가?**

```
// Tool 호출 유도 (wiki-agent 스타일)
"답변하기 전에 반드시 Confluence를 검색하세요.
검색 결과 없이 답변하지 마세요."

// 직접 답변 허용
"알고 있는 경우 바로 답하고,
모르는 경우 Confluence를 검색하세요."
```

| 상황 | 전략 |
|------|------|
| 항상 최신 정보 필요 | Tool 호출 강제 |
| 일반 지식도 OK | 직접 답변 허용 |
| 정확도 중요 | Tool 호출 강제 + 출처 포함 |

---

## 슬라이드 13: Before / After 프롬프트 (wiki-agent 실제 예시)

**Before (나쁜 예):**
```
Confluence에서 "{query}"를 찾아서 알려줘.
```

문제:
- 역할 없음 → LLM이 마음대로 해석
- 출력 형식 없음 → 매번 다른 형태로 옴
- 컨텍스트 범위 없음 → 전체 위키 탐색 시도

---

**After (wiki-agent 실제 프롬프트):**
```
당신은 Confluence 문서 검색 전문가입니다.

다음 질문에 답하기 위해 Confluence를 검색하세요: {query}

검색 조건:
- 스페이스: {spaces}
- 결과: 최대 5개

출력 형식 (반드시 준수):
1. **{문서 제목}**
   링크: {url}
   요약: {1-2문장}

문서를 찾지 못한 경우:
"'{query}' 관련 문서를 {spaces}에서 찾을 수 없습니다."
```

---
```

**Step 2: 커밋**

```bash
git add docs/session/koog-agent-session.md
git commit -m "docs: 세션 3부 슬라이드 9-13 작성"
```

---

## Task 5: 3부 — 아이디어 + 워크숍 (슬라이드 14-15)

**Files:**
- Modify: `docs/session/koog-agent-session.md`

**Step 1: 슬라이드 14-15 내용 추가**

```markdown
## 슬라이드 14: 직군별 에이전트 아이디어

wiki-agent에서 Tool만 바꾸면 이런 에이전트가 된다:

| 직군 | 에이전트 | Tool |
|------|----------|------|
| **전 직군** | 회의록 → 액션아이템 슬랙 발송 | 파일 읽기 + Slack API |
| **PM** | 스프린트 종료 → 완료/미완료 보고서 | Jira API + Slack API |
| **HR** | 이력서 폴더 추가 → 1차 요약 DM | 파일 읽기 + Slack API |
| **개발자** | 빌드 실패 → 로그 분석 + 원인 추정 | 파일 읽기 + GitHub API |
| **마케터** | 캠페인 데이터 → 성과 요약 초안 | 파일 읽기 + Slack API |

**wiki-agent 확장 예시 (이미 구현됨):**
- `github.repos: [myorg/backend]` → GitHub Wiki에서 API 설계 가이드 검색
- 온보딩 봇: Confluence(사내 프로세스) + GitHub Wiki(기술 문서) 동시 검색

> 핵심: Orchestrator + Specialist 구조는 같다. Tool과 프롬프트만 바꾼다.

---

## 슬라이드 15: 워크숍 — wiki-agent 직접 실행해보기 (35분)

**준비물 (미리 배포):**
- wiki-agent 레포 클론
- Claude Code CLI 설치 (API 키 불필요)
- Slack Bot 토큰 + App 토큰
- Confluence API 토큰 (base64: `echo -n "email:token" | base64`)

**실습 순서:**

**① 시크릿 설정 (3분)**
```bash
cp .env.example .env
# .env 파일 열어서 토큰 입력
```
```
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
CONFLUENCE_TOKEN=<base64 email:api-token>
```

**② config.yml 설정 (2분)**
```yaml
model:
  provider: CLAUDE_CODE
confluence:
  baseUrl: https://yourcompany.atlassian.net
  spaces:
    - DEV
```

**③ 실행 (1분)**
```bash
./gradlew run
```

**④ 슬랙에서 질문 (5분)**
```
@wiki 배포 프로세스 알려줘
@wiki 온보딩 체크리스트
/wiki config space DEV,PM
```

**⑤ (심화 A, 선택) GitHub Wiki 연결 (추가 5분)**
`.env`에 `GITHUB_TOKEN=ghp_...` 추가 (public repo는 없어도 됨)
`config.yml`에서:
```yaml
github:
  enabled: true
  repos:
    - myorg/myproject
```
재시작 후 기술 문서 관련 질문 비교

**⑥ (심화 B, 선택) RAG 활성화 (추가 5분)**
```bash
docker run -p 8000:8000 chromadb/chroma
```
`config.yml`에서 `rag.enabled: true` → 재시작 → `/wiki reindex` → 같은 질문 다시 해보기

**⑦ 결과 공유 + 토론 (10분)**
- 소스별 검색 결과 차이?
- 프롬프트를 어떻게 바꾸면 더 나아질까?
- 내 팀에서 이 구조로 만들고 싶은 에이전트는?

---
```

**Step 2: 커밋**

```bash
git add docs/session/koog-agent-session.md
git commit -m "docs: 세션 3부 슬라이드 14-15 작성"
```

---

## Task 6: 4부 — 마무리 (슬라이드 16-17)

**Files:**
- Modify: `docs/session/koog-agent-session.md`

**Step 1: 슬라이드 16-17 내용 추가**

```markdown
# 4부. 마무리

---

## 슬라이드 16: 로컬에서 시작하는 방법

**필요한 것:**
1. Claude Code CLI 설치 (https://claude.ai/code)
2. wiki-agent 레포 클론
3. `.env.example` → `.env` 복사 후 토큰 입력
4. `.wikiq/config.yml` — baseUrl, spaces 설정
5. `./gradlew run`

**(선택 A) GitHub Wiki 연결:**
6. `.env`에 `GITHUB_TOKEN` 추가
7. `config.yml`에 `github.enabled: true` + `repos` 설정

**(선택 B) RAG까지 쓰려면:**
8. `docker run -p 8000:8000 chromadb/chroma`
9. `config.yml`에서 `rag.enabled: true`
10. `/wiki reindex`

**3가지 실행 모드:**

| 모드 | 설정 | 비용 |
|------|------|------|
| 로컬 개인 | `CLAUDE_CODE` | Claude 구독만 있으면 무료 |
| 팀 서버 (Claude) | `ANTHROPIC` + `ANTHROPIC_API_KEY` | Anthropic API 비용 |
| 팀 서버 (Gemini) | `GOOGLE` + `GOOGLE_API_KEY` | Google API 비용 |

**다음 단계:**
- wiki-agent 레포: [링크]
- Koog 공식 문서: github.com/JetBrains/koog
- 세션 자료: [링크]

---

## 슬라이드 17: Q&A

**자주 나오는 질문:**

**Q. Kotlin 모르면 못 쓰나요?**
A. config.yml 수정 + 프롬프트 교체 수준은 Kotlin 몰라도 됩니다. 새 Tool 추가부터는 Kotlin 필요.

**Q. API 비용이 얼마나 드나요?**
A. Claude Code 구독자는 로컬에서 무료. Gemini Flash는 토큰당 매우 저렴 ($0.075/1M tokens).

**Q. A2A가 뭔가요?**
A. Agent-to-Agent. 에이전트끼리 HTTP로 통신하는 프로토콜. Orchestrator가 Specialist를 부를 때 씁니다.

**Q. LangChain이랑 뭐가 다른가요?**
A. JVM/Kotlin 생태계에 자연스럽고, 타입 안전성이 높습니다. Python 팀엔 LangChain, Kotlin/Java 팀엔 Koog.

---

*감사합니다*
```

**Step 2: 최종 빌드 확인 — 문서 전체 검토**

파일을 열어 목차 → 내용 흐름이 자연스러운지 확인:
- 1부 (15분) → 2부 (15분) → 3부 (75분) → 4부 (15분) = 총 120분
- 슬라이드 1~17 모두 존재하는지 확인

**Step 3: 최종 커밋**

```bash
git add docs/session/koog-agent-session.md
git commit -m "docs: 세션 자료 전체 완성"
```
