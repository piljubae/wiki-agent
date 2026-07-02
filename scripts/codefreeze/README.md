# 코드 프리징 리마인더 (codefreeze)

지정한 Slack 채널에 정기 배포 전 **코드 프리징**을 자동으로 리마인드한다.

## 동작 방식

- 배포 일정 캘린더를 secret iCal(.ics)로 인증 없이 읽는다.
- `[앱] ... 배포` 이벤트 중 **정기 배포만** 대상으로 한다(핫픽스 / 비정기 제외).
- 코드 프리징 시각 = **배포일의 전주 목요일 10:00 KST** (`배포일 - (요일 + 4)일`, 화요일 배포면 5일 전).
- 향후 120일 내 대상들을 Slack `chat.scheduleMessage`로 **미리 예약**한다. 발화는 Slack이 하므로 이 맥/봇이 꺼져 있어도 정시에 발송된다.
- **멱등**: 이미 예약된 건은 `post_at`으로 판별해 건너뛴다. 매달 다시 돌려도 중복이 생기지 않는다.
- 신규 예약 / 오류가 있으면 `CODEFREEZE_REPORT_USER_ID` 에게 DM으로 보고한다.

## 파일

| 파일 | 역할 |
|------|------|
| `seed_freeze_reminders.py` | 씨딩 본체 (표준 라이브러리만 사용) |
| `codefreeze-seed.sh` | launchd 래퍼. `.env`에서 설정 로드 후 실행 |
| `com.pilju.codefreeze-seed.plist.example` | launchd 등록용 plist 템플릿 |

## 설정 (`wiki-agent/.env`, gitignore됨)

```
SLACK_BOT_TOKEN=xoxb-...              # chat:write 권한, 채널에 초대돼 있어야 함
CODEFREEZE_ICS_URL=https://calendar.google.com/calendar/ical/.../private-XXXX/basic.ics
CODEFREEZE_CHANNEL=C0XXXXXXXXX        # 공지 채널 ID
CODEFREEZE_REPORT_USER_ID=U0XXXXXXXXX # (선택) 결과 DM 받을 member ID
```

- `CODEFREEZE_ICS_URL`: Google 캘린더 → 해당 캘린더 설정 → "iCal 형식의 비공개 주소". 캘린더 전체 읽기 권한이 있는 값이므로 **절대 커밋하지 말 것**.

## 설치 (macOS launchd, 매월 1일 09:07)

```sh
sed "s/USERNAME/$(whoami)/g" scripts/codefreeze/com.pilju.codefreeze-seed.plist.example \
  > ~/Library/LaunchAgents/com.pilju.codefreeze-seed.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.pilju.codefreeze-seed.plist
```

제거:

```sh
launchctl bootout gui/$(id -u)/com.pilju.codefreeze-seed
```

## 수동 실행 / 로그

```sh
zsh scripts/codefreeze/codefreeze-seed.sh   # 즉시 1회 씨딩
cat ~/.config/codefreeze/seed.log           # 실행 로그
```

정상 출력 예: `완료 신규0 실패0 대상7 기존7` (이미 다 예약된 상태).

## 한계

- 맥이 실행 시각에 꺼져 있으면 wake 후 catch-up 실행. 1회 예약이 약 120일(정기배포 ~7건)을 커버하므로 한 달을 걸러도 리마인더가 비지 않는다.
- 예약 후 배포일이 바뀌면 옛 예약은 자동 삭제되지 않는다(`chat.scheduledMessages.list`가 본문을 주지 않아 우리 메시지를 특정할 수 없음). 새 날짜는 추가되지만 옛 날짜도 남는다. 배포 일정이 자주 바뀌면 재조정 로직을 별도로 넣어야 한다.
