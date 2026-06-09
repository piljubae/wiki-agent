# :rag

> 한 줄 요약: 문서를 **벡터로 바꾸고**(임베딩), 그 벡터를 **벡터 DB(ChromaDB)에 저장·검색**하는 클라이언트 모듈.

## 이게 왜 필요한가

키워드가 정확히 일치하지 않아도 "의미가 비슷한" 문서를 찾으려면, 문장을 숫자 벡터(임베딩)로 바꿔 벡터끼리 거리를 비교해야 한다. 이게 **RAG**(Retrieval-Augmented Generation, 검색 보강 생성)의 검색 부분이다.

wiki-agent에서 이 모듈은 검색 우선순위 중 마지막 단계(벡터 유사도 fallback)를 담당한다. 제목·본문 키워드 검색으로 못 찾았을 때, 의미 기반으로 한 번 더 찾아본다. (`rag.enabled=true`일 때만 사용)

두 가지 일을 한다:
1. **임베딩 생성** — 텍스트를 벡터로 (`EmbeddingClient`, 예: Google 임베딩 API)
2. **벡터 저장·검색** — 그 벡터를 ChromaDB에 넣고 유사 문서를 조회 (`ChromaClient`)

## 무엇을 제공하나 (공개 API)

### `ChromaClient(baseUrl)` — 벡터 DB 연동

ChromaDB(별도 Docker로 띄우는 벡터 DB) REST API를 호출한다.

| 하고 싶은 일 | 설명 |
|---|---|
| 컬렉션에 문서 벡터 추가 | 문서 ID·임베딩·메타데이터 업서트 |
| 유사 벡터 검색 | 쿼리 벡터로 가장 가까운 문서들 조회 |
| 저장된 ID 조회 | 컬렉션의 문서 ID 목록 (`parseGetIdsResponse`) |

### `EmbeddingClient` / `GoogleEmbeddingClient(apiKey)` — 임베딩 생성

| 하고 싶은 일 | 설명 |
|---|---|
| 텍스트 → 벡터 | 문장을 임베딩 API에 보내 float 벡터로 변환 |

> JSON 응답은 `kotlinx-serialization`의 JsonElement DOM(`jsonObject`/`jsonArray`/`floatOrNull`)으로 필요한 필드만 직접 뽑는다. `@Serializable` 데이터 클래스를 쓰지 않으므로 직렬화 **컴파일러 플러그인은 불필요**하고 런타임 라이브러리만 의존한다.

## 의존성

- `ktor-client-cio` — ChromaDB·임베딩 API 호출용 HTTP 클라이언트.
- `kotlinx-serialization-json` — JSON 응답 파싱(런타임 DOM API만 사용).
- `slf4j-api` — 로깅 인터페이스.
- **다른 내부 모듈에는 의존하지 않는다.** (leaf 모듈)

## 테스트

실제 ChromaDB나 임베딩 API에 접속하지 않는다. 클라이언트는 baseUrl/apiKey만 받아 생성하고, 테스트는 **응답 JSON 파싱 로직만** 검증하므로 외부 서버 없이 돈다.

```bash
./gradlew :rag:test
```

주요 검증: ChromaDB 응답(ID 목록 등) 파싱, 임베딩 응답에서 벡터 추출.

---

이 모듈은 wiki-agent 모듈화의 일부로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
