# :search

> 한 줄 요약: 질문에 답할 **근거를 찾아오는 검색/도구 레이어** — 오케스트레이터가 호출하는 Tool들과 각 소스(Confluence·GitHub·코드·벡터)별 검색 에이전트가 모여 있다.

## 이게 왜 필요한가 (그리고 왜 한 모듈로 묶였나)

`OrchestratorAgent`는 질문을 받으면 적절한 **Tool**(KnowledgeTool, ConfluenceTool, CodeSearchTool …)을 골라 실행한다. 이 Tool들과, Tool이 내부적으로 쓰는 **검색 에이전트**(`ConfluenceSearchAgent`, `GitHubWikiSearchAgent`), 그리고 검색 인덱스(`BM25Index`)·결과 모델(`SearchResult`)이 하나의 "검색 레이어"를 이룬다.

이들을 한 모듈로 묶은 이유는 **순환 의존성을 끊기 위해서**다:

- 기존엔 도구/검색이 `agent` 패키지 안에 오케스트레이터와 섞여 있었다.
- 그런데 `knowledge`·`onboarding`이 이 도구들(`SourceTracker`, `CodeSearchTool`, `ConfluenceTool`)을 거꾸로 참조 → `agent ⇄ knowledge`, `agent ⇄ onboarding` 순환 발생.
- 도구/검색 레이어를 더 낮은 `:search` 모듈로 내리면, 모든 의존이 **위(agent)에서 아래(search)로** 단방향이 되어 순환이 사라진다.

```
agent(OrchestratorAgent) ──► :search, knowledge, onboarding
knowledge                ──► :search   (SourceTracker, BM25Index)
onboarding               ──► :search   (SourceTracker, CodeSearchTool, ConfluenceTool)
:search                  ──► confluence, github, rag   (하위 클라이언트 모듈)
→ 순환 0
```

## 무엇이 들어 있나

**Tool (Koog `Tool` 구현)** — 오케스트레이터가 호출하는 단위
- `CodeSearchTool` — 코드 검색(BM25 + GitHub + 벡터 결합)
- `ConfluenceTool` / `GitHubWikiTool` — 검색 에이전트를 감싼 위키 검색
- `PrHistoryTool`, `CodeFlowTool`, `PersonalDataTool`, `ProgressAdvisorTool`
- `SourceTracker` — 답변 근거(출처) 추적 유틸 (여러 모듈이 공유)

**검색 에이전트** — 소스별 검색 로직
- `ConfluenceSearchAgent` (제목/본문/RAG 다단계 검색), `GitHubWikiSearchAgent`

**지원 타입**
- `SearchResult` / `SearchStage` — 검색 결과·가중치 단계
- `BM25Index` — SQLite FTS 기반 키워드 인덱스 (knowledge에서 이 모듈로 이동)

## 의존성

- 내부: `:confluence`, `:github`, `:rag` (검색이 호출하는 하위 클라이언트)
- 외부: `ai.koog:koog-agents`(Tool 추상화), `kotlinx-coroutines`, `kotlinx-serialization-json`, `sqlite-jdbc`(BM25Index), `slf4j-api`
- Koog 전용 Maven 저장소 + 루트와 동일한 jackson 버전 강제 적용

## 테스트

```bash
./gradlew :search:test
```

Tool 동작·파싱(`CodeSearchTool`, `ToolTest` 등), 검색 에이전트 로직(`ConfluenceSearchAgentTest`의 cleanQuery·키워드 추출 등), `SearchResult` 가중치 검증. 실제 네트워크는 mockk로 대체.

---

이 모듈은 wiki-agent 모듈화에서 **순환 의존성 해소** 단계로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
