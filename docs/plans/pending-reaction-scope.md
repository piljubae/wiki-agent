# 미결: reaction_added 이벤트 수신 안 됨

## 원인
Slack App OAuth 스코프에 `reactions:read` 누락.

## 해결 순서
1. Slack App 설정 → OAuth & Permissions → Bot Token Scopes → `reactions:read` 추가
2. Reinstall App (워크스페이스에 재설치)
3. wiki-agent 재시작

## 현재 상태
- `reaction_added` 이벤트 구독 추가 완료 (Event Subscriptions)
- 스코프 추가 + 재설치 미완료
- FeedbackStore/triggerRequery 구현은 완료 — 스코프만 추가하면 동작함
