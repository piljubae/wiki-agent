# 피드백 수집 + 🔄 LLM 재검색 설계

## 배경

현재 wiki-agent는 👍/👎 리액션을 감지하지만 로그만 출력하고 끝난다.
(query, answer) 역추적도 불가하고 개선 파이프라인도 없다.

---

## 목표

- **Phase 1 (지금)**: 피드백 영속 저장 + 🔄 트리거 LLM 재검색
- **Phase 2 (나중)**: 쌓인 데이터 분석 → 검색 파라미터 튜닝

---

## 실패 유형 분류

재검색 전 실패 유형 진단이 핵심이다. 유형이 다르면 처방도 달라진다.

| 실패 유형 | 원인 | 처방 |
|----------|------|------|
| 어휘 불일치 | "회원가입" → `SignUpActivity` BM25 못 찾음 | 영문 코드 용어 확장 |
| 도구 라우팅 오류 | Confluence로 라우팅했지만 답은 코드에 | 추가 도구 실행 |
| 인덱스 부재 | 소스 자체가 없음 | 재검색 스킵 |

---

## 1. 데이터 캡처

### In-memory

`botMessageTimestamps` (Set) → `feedbackStore` (Map)으로 교체:

```kotlin
data class FeedbackEntry(
    val query: String,
    val answer: String,
    val usedTools: List<String>,
    val ts: String,
)
// ConcurrentHashMap<String, FeedbackEntry>  (messageTs → entry)
```

### SQLite 영속 저장 (`.wiki/feedback.db`)

```sql
CREATE TABLE IF NOT EXISTS feedback (
    ts           TEXT PRIMARY KEY,
    query        TEXT NOT NULL,
    answer       TEXT NOT NULL,
    used_tools   TEXT NOT NULL,   -- comma-separated
    reaction     TEXT,            -- thumbsup / thumbsdown / null
    requery      TEXT,            -- LLM 확장 쿼리 (BM25용)
    requery_vec  TEXT,            -- LLM 재표현 쿼리 (벡터용)
    requery_answer TEXT,
    stage        INTEGER,         -- 1 or 2
    created_at   INTEGER NOT NULL
);
```

---

## 2. 리액션 핸들러

```kotlin
val FEEDBACK_REACTIONS = listOf("+1", "-1", "thumbsup", "thumbsdown")
val RETRY_REACTIONS    = listOf("repeat", "arrows_counterclockwise")

when {
    reaction in FEEDBACK_REACTIONS -> feedbackStore.saveReaction(messageTs, reaction)
    reaction in RETRY_REACTIONS    -> triggerRequery(messageTs, channel, threadTs)
}
```

### Footer 업데이트

```
:thumbsup: 도움됐다면 | :thumbsdown: 아쉬웠다면 | :repeat: 다시 검색해드릴게요
```

---

## 3. QueryRewriter

LLM 1회 호출로 3가지 출력 생성:

```
원래 질문: {query}
첫 검색에 사용한 도구: {usedTools}

아래 3가지를 각각 한 줄로 출력하세요:
BM25: [한국어 동의어 + 영문 클래스명/메서드명 패턴, 공백 구분]
VECTOR: [같은 의미의 다른 표현으로 재작성한 자연어 문장]
TOOLS: [추가로 시도해야 할 도구 목록 (confluence/code_search/bm25 중), 없으면 SAME]
```

예시:
```
입력: "회원가입 화면 어떻게 구현돼 있어?"  usedTools: [confluence]
출력:
BM25: signUp register join SignUpActivity SignUpViewModel SignUpFragment 회원가입 가입화면
VECTOR: 사용자가 앱에 처음 계정을 만드는 화면의 구조와 구현 방식
TOOLS: code_search
```

---

## 4. 2단계 재검색 플로우

```
[🔄 감지]
    ↓
feedbackStore에서 (query, usedTools, stage) 조회
    ↓
QueryRewriter → (bm25Query, vectorQuery, additionalTools)
    ↓
Stage 1: bm25Query + vectorQuery → (usedTools + additionalTools)
    ↓
스레드에 "🔄 다른 방식으로 찾아봤어요\n{answer}" 전송
feedback.db 업데이트 (requery, requery_answer, stage=1)
    ↓ (Stage 1 후 또 🔄이면)
Stage 2: 전체 도구 병렬 실행 (forceAllTools=true)
feedback.db 업데이트 (stage=2)
```

---

## 5. 파일 구조

```
src/main/kotlin/.../
  slack/
    SlackBotGateway.kt       -- 리액션 핸들러 수정
    FeedbackStore.kt         -- NEW: in-memory + SQLite 저장
  agent/
    QueryRewriter.kt         -- NEW: LLM 쿼리 확장
    OrchestratorAgent.kt     -- forceAllTools 파라미터 추가
```

---

## 6. Phase 2 (데이터 쌓인 후)

- `feedback.db`에서 `reaction=thumbsdown` 케이스 추출
- 쿼리 패턴 클러스터링 → "이런 유형이 👎 많음" 리포트
- 동의어 사전 보강, 라우터 프롬프트 수정 등 튜닝
- 구체적 방향은 데이터 보고 결정
