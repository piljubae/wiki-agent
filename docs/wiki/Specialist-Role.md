# Specialist 역할

## 정의

Specialist는 **하나의 도메인에만 집중**하는 에이전트 또는 Tool입니다.  
Orchestrator에게 호출당해 특정 작업만 수행합니다.

## wiki-agent의 Specialist 목록

| Specialist | 담당 도메인 | 활성 조건 |
|-----------|------------|---------|
| `ConfluenceSearchAgent` | Confluence CQL 검색 + 결과 포맷 | 항상 |
| `GitHubWikiSearchAgent` | GitHub Wiki 검색 + 결과 포맷 | `github.enabled: true` |
| `VectorSearchAgent` | ChromaDB 의미 검색 | `rag.enabled: true` |

## ConfluenceSearchAgent 역할

- Confluence REST API CQL(`text~"키워드"`) 로 검색
- `config.yml`의 `confluence.spaces` 범위 내에서만 검색
- 결과: 제목 + 링크 + 요약 텍스트 포맷

## GitHubWikiSearchAgent 역할

- GitHub Search API (`/search/code?q=...+repo:{owner}/{repo}.wiki`) 호출
- 검색된 페이지의 raw 콘텐츠를 `raw.githubusercontent.com/wiki/...` 에서 가져옴
- 결과: 제목 + 레포 + 링크 + 콘텐츠 스니펫 포맷

## VectorSearchAgent 역할

- ChromaDB에 저장된 임베딩 벡터에서 유사 문서 검색
- 임베딩 모드에 따라 쿼리 방식 결정:
  - `LLM_EXPAND`: LLM으로 쿼리 확장 → ChromaDB에 텍스트로 검색
  - `GOOGLE_EMBEDDING`: Google `text-embedding-004` API로 벡터 생성 → 벡터로 검색

## Specialist 설계 원칙

1. **단일 책임:** 한 가지 도메인 검색만 담당
2. **독립적 개선 가능:** Orchestrator 코드 변경 없이 각 Specialist 개선
3. **Tool 인터페이스:** `@Tool` 어노테이션으로 Orchestrator에 노출

---

> **Source:** [ConfluenceSearchAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt) · [GitHubWikiSearchAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/GitHubWikiSearchAgent.kt)
