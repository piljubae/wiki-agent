# ✅ 완료: reaction_added 이벤트 수신 안 됨 → 해결

## 원인
Slack App OAuth 스코프에 `reactions:read` 누락.

## 해결 순서
1. Slack App 설정 → OAuth & Permissions → Bot Token Scopes → `reactions:read` 추가
2. Reinstall App (워크스페이스에 재설치)
3. wiki-agent 재시작

## 현재 상태 (2026-05-10 완료)
- `reaction_added` 이벤트 구독 추가 완료 (Event Subscriptions)
- ✅ `reactions:read` 스코프 추가 + 앱 재설치 완료
- ✅ 재검색(triggerRequery) 동작 확인
