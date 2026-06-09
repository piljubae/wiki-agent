# :confluence

> 한 줄 요약: Confluence REST API를 호출해 **위키 페이지를 검색·조회**하는 HTTP 클라이언트 모듈.

## 이게 왜 필요한가

wiki-agent가 답의 근거로 삼는 1차 자료가 사내 **Confluence 위키**다. 이 모듈은 그 Confluence와 통신하는 창구로, 제목·본문 검색(CQL)과 페이지 본문 가져오기를 담당한다. 어떤 검색어로 무엇을 찾을지 같은 판단은 상위(`ConfluenceSearchAgent`)가 하고, 이 모듈은 "요청 보내고 응답 파싱" 만 한다.

검색 우선순위 중 **2순위**(로컬 지식베이스 다음)에 해당한다.

## 무엇을 제공하나 (공개 API)

### `ConfluenceClient(baseUrl, token, ...)`

| 하고 싶은 일 | 설명 |
|---|---|
| 제목으로 페이지 검색 | 설정된 스페이스에서 제목 매칭 (CQL) |
| 본문 텍스트 검색 | 본문 키워드로 검색 |
| 페이지 원본 HTML 조회 | 페이지 ID로 본문 raw HTML 가져오기 |

> 인증은 `baseUrl`·`token`을 생성자로 받아 처리하고, 응답 JSON은 `kotlinx-serialization`의 JsonElement DOM으로 필요한 필드만 뽑는다. `@Serializable` 데이터 클래스를 쓰지 않으므로 직렬화 컴파일러 플러그인은 불필요하다.

## 의존성

- `ktor-client-cio` — Confluence REST API 호출용 HTTP 클라이언트.
- `kotlinx-serialization-json` — JSON 응답 파싱(런타임 DOM API).
- `slf4j-api` — 로깅 인터페이스.
- **다른 내부 모듈에는 의존하지 않는다.** (leaf 모듈)

## 테스트

테스트는 두 종류로 나뉜다:

- **단위 테스트** (`ConfluenceClientTest`, `ConfluenceClientSearchTest`) — 실제 접속 없이 응답 파싱·검색어 정제(CQL 특수문자 제거 등) 로직만 검증. 항상 실행된다.
- **통합 테스트** (`ConfluenceClientIntegrationTest`) — 실제 Confluence에 접속한다. `@Tag("eval")`로 표시돼 **기본 테스트 실행에서는 제외**된다(`build.gradle.kts`의 `excludeTags("eval")`).

```bash
./gradlew :confluence:test        # 단위 테스트만 (통합테스트 제외)
```

---

이 모듈은 wiki-agent 모듈화의 일부로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
