# wiki-agent Wiki

wiki-agent는 Slack에서 `@wiki 질문` 하면 Confluence · GitHub Wiki · ChromaDB(RAG) 에서 관련 문서를 찾아 답변하는 Slack 봇입니다.  
JetBrains [Koog](https://github.com/JetBrains/koog) 0.8.0 기반 Kotlin 에이전트 프레임워크로 구현되었습니다.

---

## 목차 — 기초부터 심화 순서

### 🟢 기초

| 페이지 | 핵심 질문 |
|--------|-----------|
| [What-Is-Agent](What-Is-Agent) | 에이전트가 뭔가요? |
| [Agent-vs-API](Agent-vs-API) | API 호출과 뭐가 다른가요? |
| [Wiki-Agent-Overview](Wiki-Agent-Overview) | wiki-agent는 어떻게 동작하나요? |
| [Slack-App-Setup](Slack-App-Setup) | Slack App은 어떻게 만드나요? |
| [First-Run](First-Run) | 어떻게 실행하나요? |

### 🟡 중급

| 페이지 | 핵심 질문 |
|--------|-----------|
| [Koog-Introduction](Koog-Introduction) | Koog 프레임워크가 뭔가요? |
| [AIAgent-Code](AIAgent-Code) | AIAgent는 어떻게 만드나요? |
| [Tool-Annotation](Tool-Annotation) | @Tool은 어떻게 쓰나요? |
| [ToolRegistry](ToolRegistry) | Tool은 어떻게 등록하나요? |
| [A2A-Protocol](A2A-Protocol) | A2A Protocol이 뭔가요? |
| [Orchestrator-Role](Orchestrator-Role) | Orchestrator는 무슨 역할인가요? |
| [Specialist-Role](Specialist-Role) | Specialist는 무슨 역할인가요? |
| [Fallback-Model](Fallback-Model) | 모델 fallback은 어떻게 동작하나요? |
| [Prompt-Role-Format](Prompt-Role-Format) | 프롬프트 역할과 출력 형식 어떻게 분리하나요? |
| [Prompt-Context-Scope](Prompt-Context-Scope) | 컨텍스트 범위는 어떻게 제어하나요? |
| [Prompt-Tool-Forcing](Prompt-Tool-Forcing) | Tool 호출을 강제해야 하는 상황은? |
| [Conversation-Store](Conversation-Store) | 대화 이력은 어떻게 저장·압축되나요? |
| [Project-Memory](Project-Memory) | 프로젝트 메모리는 어떻게 사용하나요? |
| [Onboarding-Flow](Onboarding-Flow) | DM 온보딩 마법사는 어떻게 동작하나요? |
| [Search-Progress](Search-Progress) | 검색 중 Slack에 진행 메시지가 뜨는 원리는? |
| [Reaction-Feedback](Reaction-Feedback) | 👍/👎 리액션 피드백은 어떻게 수집하나요? |

### 🟠 심화

| 페이지 | 핵심 질문 |
|--------|-----------|
| [Config-Model](Config-Model) | LLM provider는 어떻게 설정하나요? |
| [Config-Confluence](Config-Confluence) | Confluence는 어떻게 연결하나요? |
| [Config-Slack](Config-Slack) | Slack 설정은 무엇이 필요한가요? |
| [Secret-Loader](Secret-Loader) | 시크릿은 어디에 넣어야 하나요? |
| [GitHub-Wiki-Connection](GitHub-Wiki-Connection) | GitHub Wiki는 어떻게 연결하나요? |
| [GitHub-Wiki-How-It-Works](GitHub-Wiki-How-It-Works) | GitHub Wiki 검색은 어떻게 동작하나요? |
| [Search-Flow](Search-Flow) | Confluence 검색이 내부적으로 어떤 단계를 거치나요? |
| [CQL-Search-Strategy](CQL-Search-Strategy) | CQL 2단계 검색과 동의어 병합은 어떻게 동작하나요? |
| [Golden-Dataset-Eval](Golden-Dataset-Eval) | 검색·답변 품질은 어떻게 평가하나요? |

### 🔴 심화 (RAG · 검색 원리)

| 페이지 | 핵심 질문 |
|--------|-----------|
| [Embedding](Embedding) | 임베딩이란 무엇인가요? 텍스트가 숫자가 되는 원리는? |
| [Cosine-Similarity](Cosine-Similarity) | 유사도는 어떻게 계산하나요? 내적과 방향의 의미는? |
| [Vector-Search](Vector-Search) | 벡터 검색은 어떻게 동작하나요? 잘 되는 것 vs 안 되는 것은? |
| [BM25](BM25) | BM25란 무엇인가요? 벡터 검색과 어떻게 다른가요? |
| [Hybrid-Search](Hybrid-Search) | 두 검색을 어떻게 합치나요? RRF란 무엇인가요? |
| [Chunk](Chunk) | 청크란 무엇인가요? 함수 단위로 나누는 기준은? |
| [AST](AST) | AST와 Tree-sitter란 무엇인가요? |
| [Sentence-Transformers](Sentence-Transformers) | ChromaDB 기본 임베딩 모델은 무엇인가요? |
| [Code-Embedding-Models](Code-Embedding-Models) | 코드 전용 임베딩 모델이 왜 필요한가요? |
| [LLM-Indexing-Principle](LLM-Indexing-Principle) | 왜 LLM을 인덱싱 타임에 쓰지 않나요? |
| [ChromaDB-Setup](ChromaDB-Setup) | ChromaDB는 어떻게 실행하나요? |
| [ChromaDB-v2-Migration](ChromaDB-v2-Migration) | ChromaDB v2 API 변경점은 무엇인가요? 임베딩은 어떻게 달라졌나요? |
| [RAG-Embedding-Modes](RAG-Embedding-Modes) | 임베딩 모드는 어떻게 선택하나요? |
| [RAG-Indexing](RAG-Indexing) | 인덱싱은 어떻게 하나요? |
| [RAG-Fallback](RAG-Fallback) | CQL 결과 없을 때 자동 벡터 검색은 어떻게 동작하나요? |

| [PR-History-Indexing](PR-History-Indexing) | PR 변경 이력은 어떻게 검색하나요? |

### 🔴 심화 (코드 인덱싱)

| 페이지 | 핵심 질문 |
|--------|-----------|
| [Code-Index-Architecture](Code-Index-Architecture) | 코드 인덱싱 파이프라인은 어떻게 동작하나요? |
| [Git-Ls-Files-Indexing](Git-Ls-Files-Indexing) | 파일 목록을 어떻게 정확히 가져오나요? .worktrees 제외 원리는? |
| [Code-Index-Feasibility](Code-Index-Feasibility) | 5,000개 파일 인덱싱이 실용적인가요? |
| [Code-Index-Commercial-Design](Code-Index-Commercial-Design) | Cursor·Cody와 비교해 무엇이 다른가요? |

---

> **Source:** [github.com/Veronikapj/wiki-agent](https://github.com/Veronikapj/wiki-agent)  
> **Framework:** [Koog 0.8.0](https://github.com/JetBrains/koog) by JetBrains
