# :context

> 한 줄 요약: 사용자와 봇이 주고받은 **대화 기록**과 프로젝트용 **메모**를 파일로 저장하고 다시 읽어오는 모듈.

## 이게 왜 필요한가

wiki-agent는 슬랙 챗봇이라, 사용자가 이어서 질문하면 **앞서 무슨 얘기를 했는지** 기억해야 자연스럽게 답할 수 있다. 그런데 대화가 길어질수록 이전 내용을 전부 LLM에 넣을 수 없다(토큰 비용·한도). 그래서 이 모듈이 두 가지를 한다:

1. **대화 이력 저장** — 질문/답변 한 쌍(`Turn`)을 세션별 파일에 차곡차곡 쌓는다.
2. **오래되면 요약** — 대화가 일정 길이를 넘으면 옛날 대화는 한 덩어리로 요약하고, 최근 몇 개만 원본으로 남긴다(`compress`).

여기에 더해, 프로젝트 단위로 기억해둘 메모를 관리하는 `ProjectMemory`도 들어 있다.

> **중요:** 이 모듈은 **LLM을 직접 호출하지 않는다.** 요약이 필요할 때 "요약하는 함수"를 밖에서 받아 쓴다(아래 `compress` 참고). 덕분에 이 모듈만 떼어서 LLM·네트워크 없이 테스트할 수 있다.

## 핵심 개념

- **세션(session)** — 대화 단위. 슬랙 채널/스레드/유저 ID 등이 세션 ID가 된다. 세션마다 별도 파일로 저장된다.
- **Turn** — 질문 하나와 그에 대한 답변 하나의 쌍. `data class Turn(question, answer)`.
- **요약 파일** — 압축된 옛 대화는 `<세션>.summary.md`에, 원본 대화는 `<세션>.jsonl`에 저장된다.

## 무엇을 제공하나 (공개 API)

### `ConversationStore(sessionsDir = ".wiki/sessions")`

대화 이력을 다루는 메인 클래스. `sessionsDir` 아래에 세션별 파일을 만든다.

| 하고 싶은 일 | 메서드 |
|---|---|
| 대화 한 턴 추가 | `append(sessionId, question, answer)` |
| 전체 대화 읽기 | `loadAll(sessionId)` |
| 최근 N턴만 읽기 | `load(sessionId, maxTurns = 5)` |
| 저장된 요약 읽기 | `loadSummary(sessionId)` (없으면 null) |
| 요약 저장 | `saveSummary(sessionId, summary)` |
| 최근 N턴만 남기고 정리 | `trimOldTurns(sessionId, keepRecent)` |
| 길어진 대화 압축 | `compress(sessionId, summarizer, ...)` |

**`compress`가 동작하는 방식** — 대화 턴 수가 `compressThreshold`(기본 10)를 넘을 때만 작동한다. 넘으면: 오래된 턴들을 모아 `summarizer` 함수에 넘겨 요약을 받고 → 그 요약을 저장하고 → 최근 `keepRecent`(기본 4)턴만 남긴다. 이전 요약이 있으면 새 요약 입력에 함께 넣어 맥락을 잇는다. 임계값 이하면 아무것도 하지 않는다(요약 함수도 호출 안 함).

```kotlin
val store = ConversationStore()

// 1. 대화가 오갈 때마다 기록
store.append("U12345", "온보딩 어떻게 시작해?", "먼저 ...")

// 2. 이어지는 질문에 답할 때 최근 맥락을 읽어 프롬프트에 넣음
val recent = store.load("U12345")   // 최근 5턴

// 3. 대화가 길어지면 압축 — 요약은 LLM 호출 함수를 주입
store.compress("U12345", summarizer = { prompt -> llm.complete(prompt) })
```

### `ProjectMemory(filePath = ".wiki/memory.md")`

프로젝트에 관해 기억해둘 메모를 한 줄씩 쌓는 간단한 저장소.

| 하고 싶은 일 | 메서드 |
|---|---|
| 메모 내용 읽기 | `load()` (없거나 비면 null) |
| 메모 한 줄 추가 | `add(content)` |
| 사람이 읽을 형태로 보기 | `show()` |
| 전부 지우기 | `clear()` |

## 의존성

- `kotlinx-serialization-json` — 대화를 JSON 한 줄(JSONL) 형식으로 안전하게 저장/파싱하기 위해 사용.
- **다른 내부 모듈에는 의존하지 않는다.** (가장 바깥 leaf 모듈)

## 테스트

파일 입출력 기반의 단순·결정적 로직이라, 임시 디렉터리만 있으면 네트워크나 LLM 없이 검증된다(요약 함수는 가짜 람다를 넣음).

```bash
./gradlew :context:test
```

주요 검증: 추가/조회 순서, 최근 N턴 자르기, 요약 임계값 경계(딱 임계값이면 작동 안 함), 특수문자(줄바꿈·따옴표·이모지)가 깨지지 않는지, 깨진 줄 무시 등.

---

이 모듈은 wiki-agent 모듈화의 **1단계**로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
