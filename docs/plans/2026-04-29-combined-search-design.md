# Combined Search Design: Confluence + 지식베이스 종합 답변

**Date:** 2026-04-29  
**Status:** Approved

## Problem

`answerWithManualLoop()` (GEMINI_CODE / CLAUDE_CODE provider에서 사용)는 라우터 LLM이 선택한 단일 tool만 실행한다. 지식베이스 히트 시 Confluence 미검색, 반대도 마찬가지. 두 소스의 결과가 합산되지 않는다.

## Decision

두 소스를 동등하게 취급하여 항상 병렬 검색하고 결과를 합산한다.

## Approach: 항상 병렬 검색 (Approach A)

라우터 LLM의 TOOL 선택을 `knowledgeSearch`/`confluenceSearch` 범위에서 제거. QUERY + SYNONYMS 생성 역할만 유지. 두 tool을 coroutine으로 병렬 실행, 결과를 섹션 구분(`---`)으로 합산 후 summary LLM에 전달.

`githubWikiSearch`는 라우터가 여전히 명시적으로 선택 (기술문서 분기 유지).

## Changes

### 1. `OrchestratorAgent.kt`

**라우터 프롬프트 변경**
- `TOOL:` 행 제거 (githubWikiSearch 분기는 별도 처리)
- `QUERY:` + `SYNONYMS:` 2행만 출력

**`executeParallel()` 신규 함수 추가**
```
suspend fun executeParallel(query, synonyms): String?
  - knowledgeTool.knowledgeSearch(query) + confluenceTool.confluenceSearch(query) 병렬 실행
  - 둘 다 결과 있으면: "[지식베이스]\n...\n\n---\n\n[Confluence]\n..." 합산
  - 한 쪽 실패 시: 나머지 결과만 반환
  - 둘 다 "찾을 수 없습니다" → null 반환 → 기존 executeDefault() fallback
```

**`answerWithManualLoop()` 호출 순서 변경**
```
기존: executeFromDecision(decision)
신규: 
  1. githubWikiSearch 선택 시 → executeFromDecision() 유지
  2. 그 외 → executeParallel(query, synonyms)
```

### 2. `ConfluenceTool.kt`

`suspend fun confluenceSearchSuspend(query): String` 추가.  
내부에서 `searchAgent.search(query)` 직접 호출.  
기존 `confluenceSearch()`(runBlocking 래퍼)는 Koog tool 용으로 유지.

`OrchestratorAgent.executeParallel()`은 `confluenceSearchSuspend()`를 사용해 deadlock 방지.

### 3. Summary 프롬프트

섹션 레이블 포함 결과를 그대로 전달 (추가 변경 없음).  
기존 `buildAnswerGuidelines()` 유지.

## Error Handling

| 상황 | 처리 |
|------|------|
| 지식베이스만 실패 | Confluence 결과만으로 답변 |
| Confluence만 실패 | 지식베이스 결과만으로 답변 |
| 둘 다 실패 | `executeDefault()` fallback (기존 동작 유지) |

## Non-Changes (scope out)

- `answerWithKoogAgent()` — Koog native tool calling은 변경 없음
- `ConfluenceSearchAgent` 내부 검색 로직 — 변경 없음
- GitHub Wiki 분기 — 변경 없음
- RAG / vectorSearch — 변경 없음
