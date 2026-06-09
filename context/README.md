# :context

Slack 대화 세션의 **대화 이력**과 프로젝트 단위 **메모리**를 파일로 영속화하는 모듈.

wiki-agent 모듈화 로드맵의 **Phase 1**으로 분리된 첫 독립 모듈이다. 내부 패키지 의존성이 0이고 네트워크·LLM에 의존하지 않아 순수 단위 테스트(L1)만으로 검증된다.

전체 로드맵: [`docs/plans/2026-06-09-modularization-roadmap.md`](../docs/plans/2026-06-09-modularization-roadmap.md)

## 책임

- 세션별 대화 turn(질문·답변) 추가 / 조회
- 대화 이력 요약(compress)과 오래된 turn 정리(trim)
- 프로젝트 메모리(메모 누적·조회·초기화)

LLM 호출은 이 모듈의 책임이 아니다. 요약은 `compress(summarizer: ...)`에 **함수를 주입**받아 수행하므로 모듈은 LLM 클라이언트를 모른다.

## 공개 API

### `ConversationStore(sessionsDir: String = ".wiki/sessions")`

세션별로 `<sessionId>.jsonl`(대화 turn)과 `<sessionId>.summary.md`(요약)를 디렉터리에 저장한다.

| 메서드 | 설명 |
|---|---|
| `append(sessionId, question, answer)` | turn 1개(user/assistant 2줄)를 JSONL로 추가 |
| `loadAll(sessionId): List<Turn>` | 전체 turn 조회 (파싱 불가 라인은 skip) |
| `load(sessionId, maxTurns = 5): List<Turn>` | 최근 `maxTurns`개만 조회 |
| `loadSummary(sessionId): String?` | 저장된 요약 조회 (없으면 null) |
| `saveSummary(sessionId, summary)` | 요약 저장 |
| `trimOldTurns(sessionId, keepRecent)` | 최근 `keepRecent`개만 남기고 정리 |
| `suspend compress(sessionId, summarizer, compressThreshold = 10, keepRecent = 4)` | turn이 임계값을 **초과**하면 오래된 turn을 `summarizer`로 요약 후 trim |

- `data class Turn(question: String, answer: String)`
- 상수: `COMPRESS_THRESHOLD = 10`, `KEEP_RECENT = 4`

`compress`는 `allTurns.size <= compressThreshold`이면 아무것도 하지 않는다(요약 호출 없음). 직전 요약이 있으면 새 요약 프롬프트에 포함한다.

### `ProjectMemory(filePath: String = ".wiki/memory.md")`

| 메서드 | 설명 |
|---|---|
| `load(): String?` | 메모리 내용 (없거나 비면 null) |
| `add(content)` | 한 줄(`- content`) 추가, 부모 디렉터리 자동 생성 |
| `show(): String` | 사람용 메시지 포맷 (비면 안내 문구) |
| `clear()` | 메모리 파일 삭제 (없어도 안전) |

## 의존성

- `org.jetbrains.kotlinx:kotlinx-serialization-json` (JSONL 직렬화)
- 내부 모듈 의존 **없음**

## 사용 예

```kotlin
val store = ConversationStore()
store.append("U123", "온보딩 어떻게 시작해?", "...")

// 이력이 길어지면 LLM 요약기를 주입해 압축
store.compress("U123", summarizer = { prompt -> llm.complete(prompt) })

val recent = store.load("U123")          // 최근 5 turn
```

## 테스트

순수 파일 I/O 기반 결정적 로직이라 `@TempDir`/임시 경로만으로 검증한다. 네트워크·LLM mock 불필요(요약기는 람다로 주입).

```bash
./gradlew :context:test
```

`ConversationStoreTest`(16), `ProjectMemoryTest`(7) — compress 임계값 경계, 특수문자 JSONL round-trip, malformed 라인 skip, trim no-op 등 커버.
