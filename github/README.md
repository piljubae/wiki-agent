# :github

> 한 줄 요약: GitHub REST API를 호출해 **코드·PR·위키**를 가져오는 HTTP 클라이언트 모듈.

## 이게 왜 필요한가

wiki-agent는 질문에 답할 때 사내 Confluence뿐 아니라 **GitHub 저장소의 코드와 위키, PR 이력**도 근거로 쓴다. 이 모듈은 그 GitHub 쪽 데이터를 가져오는 창구다. 검색·요약 같은 판단은 하지 않고, "GitHub에 요청을 보내고 응답을 우리 모델 객체로 바꿔주는" 일만 한다.

## 무엇을 제공하나 (공개 API)

### `GitHubCodeClient` — 코드·PR 조회

| 하고 싶은 일 | 대략의 메서드 |
|---|---|
| 저장소에서 코드 검색 | 코드 검색 API 호출 → `GithubCodeResult` 목록 |
| 특정 PR 정보 가져오기 | PR 번호로 조회 → `GithubPrInfo` |
| PR 목록 가져오기 | 저장소의 PR 리스트 → `GithubPrInfo` 목록 |

### `GitHubWikiClient` — 위키 조회

| 하고 싶은 일 | 설명 |
|---|---|
| 위키의 `.md` 문서 목록 | 위키 저장소 트리에서 마크다운 파일 경로 추출 |
| 문서 내용 가져오기 | 개별 위키 페이지 본문 |

> **참고:** JSON 응답을 직렬화 라이브러리 없이 **정규식으로 파싱**한다(`parsePrJson`, `parseMdFilePaths` 등). 필요한 필드만 가볍게 뽑아 쓰기 때문이며, 이 덕분에 모듈 의존성이 가볍고 파싱 함수를 네트워크 없이 단위 테스트할 수 있다.

## 의존성

- `ktor-client-cio` — HTTP 요청을 보내는 Ktor 클라이언트(CIO 엔진). 응답 지연 대비 `HttpTimeout`만 설정해 쓴다.
- `slf4j-api` — 로깅 인터페이스.
- **다른 내부 모듈에는 의존하지 않는다.** (leaf 모듈)

## 테스트

실제 GitHub에 접속하지 않는다. 응답 JSON 문자열을 직접 넣어 **파싱 함수(`internal`)의 결과만** 검증하므로 빠르고 안정적이다.

```bash
./gradlew :github:test
```

주요 검증: 코드 검색 결과 파싱, PR 단건/목록 파싱, 위키 트리에서 `.md` 경로 추출, 에러 응답(`"message"` 포함) 처리.

---

이 모듈은 wiki-agent 모듈화의 일부로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
