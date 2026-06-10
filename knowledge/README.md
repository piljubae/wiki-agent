# :knowledge

> 한 줄 요약: 봇이 검색할 **지식베이스를 구축·관리**하는 모듈 — URL/문서를 받아 저장(ingest)하고, 코드·PR·콜그래프를 인덱싱하며, 로컬 지식베이스를 검색한다.

## 이게 왜 필요한가

검색이 잘 되려면 먼저 **검색할 데이터가 쌓여 있어야** 한다. 이 모듈은 그 "쌓는" 쪽을 담당한다: 사람이 넣은 URL을 LLM으로 정리해 저장하고, GitHub 코드/PR을 주기적으로 인덱싱하고, 콜그래프 DB를 만든다. 그리고 로컬 지식베이스(`.wiki/knowledge/`)를 직접 검색하는 `KnowledgeTool`도 제공한다(검색 우선순위 1순위).

검색 *실행* 레이어(`:search`)와 짝을 이루지만 역할이 다르다 — `:knowledge`는 **데이터를 만들고**, `:search`는 **질의 시 찾는다**.

## 무엇이 들어 있나

| 구성 요소 | 역할 |
|---|---|
| `KnowledgeStore` | 지식베이스 파일 저장/조회 (path traversal 방지 포함) |
| `KnowledgeTool` | 로컬 지식베이스 키워드 검색 (Koog `@Tool`) |
| `IngestAgent` | URL/텍스트 → LLM 정리 → 저장 + 벡터 인덱싱 |
| `CodeIndexAgent` | GitHub 코드 → 청크 → 벡터 + BM25 인덱싱 |
| `PrIndexAgent` | PR 이력 인덱싱 |
| `CallGraphIndexAgent` | 저장소 클론 후 콜그래프 DB 생성 |
| `LintAgent` | 지식베이스 모순·고아 문서 감지 |
| `LocalRepoSync` | 로컬 체크아웃 동기화 |

## 의존성

공개(`api`) — 생성자/`@Tool`에 타입이 노출되어 소비자에게 전파:
- `:search` (`BM25Index`, `SourceTracker`), `:github` (`GitHubCodeClient`), `:rag` (`ChromaClient`)
- `ai.koog:koog-agents` (`KnowledgeTool`의 `@Tool` API)

내부(`implementation`):
- `:callgraph-plugin` (`CallGraphIndexAgent`가 dbPath만 받고 `CallGraphDb`는 내부 사용)
- `io.ktor:ktor-client-cio` (URL ingest), `kotlinx-serialization-json`, `kotlinx-coroutines-core`, `slf4j-api`

> `@Serializable` 인덱스 상태 모델을 위해 `kotlin("plugin.serialization")` 적용. Koog 저장소 + jackson 버전 강제 포함.

## 테스트

```bash
./gradlew :knowledge:test
```

store/tool/index/lint 로직을 mockk로 GitHub·Chroma·LLM을 대체해 검증한다. `CodeIndexIntegrationTest`(`@Tag("integration")`)는 빈/localhost 클라이언트로 **로컬 인덱싱 로직만** 검증하므로 외부 서비스 없이 실행·통과한다. test 태스크는 루트 정책과 동일하게 `eval`/`generate`/`smoke` 태그를 제외한다(현재 knowledge엔 해당 태그 없음).

---

이 모듈은 wiki-agent 모듈화의 일부로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
