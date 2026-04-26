# 프로젝트 메모리 설계

## 개요

프로젝트 특성을 `.wiki/memory.md`에 저장하고 시스템 프롬프트에 주입하여 검색/답변 품질을 높인다.

## 저장소

- 단일 파일: `.wiki/memory.md`
- 글로벌 (모든 채널 공유)
- 줄 단위 bullet list

## 커맨드

| 커맨드 | 동작 |
|--------|------|
| `/wiki memory add <내용>` | 메모리에 항목 추가 |
| `/wiki memory show` | 현재 저장된 메모리 표시 |
| `/wiki memory clear` | 메모리 전체 초기화 |

## 시스템 프롬프트 주입 순서

1. 기존 시스템 프롬프트 (역할 + 규칙)
2. 프로젝트 메모리
3. 이전 대화 요약 (Sliding Window)
4. 이전 대화 원본 (prompt DSL user/assistant)

## 컴포넌트

| 파일 | 변경 |
|------|------|
| Create ProjectMemory.kt | load/add/clear |
| Modify SlackConfigHandler.kt | /wiki memory 커맨드 처리 |
| Modify OrchestratorAgent.kt | 메모리 시스템 프롬프트 주입 |
| Modify Main.kt | 와이어링 |
