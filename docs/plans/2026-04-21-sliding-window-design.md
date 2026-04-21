# Sliding Window 압축 설계

## 개요

대화가 10턴을 초과하면 오래된 턴을 LLM으로 요약하여 토큰을 절약하면서 전체 맥락을 유지한다.

## 동작

```
턴 1~10: 그대로 저장 + 최근 5턴만 프롬프트에 주입
턴 11 도착 시:
    1. 턴 1~6을 LLM으로 요약 → summary.md에 저장
    2. JSONL에서 턴 1~6 제거 (턴 7~10만 남김)
    3. 프롬프트: system(요약 + systemPrompt) → user/assistant(턴 7~10) → 현재 질문
```

## 상수

- COMPRESS_THRESHOLD = 10
- KEEP_RECENT = 4

## 파일 구조

```
.wiki/sessions/
    {threadTs}.jsonl       ← 대화 기록 (압축 후 최근 턴만)
    {threadTs}.summary.md  ← 이전 대화 요약
```

## 컴포넌트 변경

| 파일 | 변경 |
|------|------|
| Modify ConversationStore.kt | compress() 메서드, loadSummary(), JSONL 트리밍 |
| Modify OrchestratorAgent.kt | compress 호출 + 요약을 시스템 프롬프트에 주입 |

## 요약 프롬프트

```
다음은 Slack에서 사용자와 AI 어시스턴트의 대화입니다.
핵심 내용을 3-5줄로 요약하세요. 검색한 문서명과 주요 답변 내용을 포함하세요.
```

## 프롬프트 주입 순서

```kotlin
prompt("orchestrator") {
    system(buildString {
        append(systemPrompt)
        summary?.let { append("\n\n# 이전 대화 요약\n$it") }
    })
    for (turn in recentTurns) {
        user(turn.question)
        assistant(turn.answer)
    }
}
```
