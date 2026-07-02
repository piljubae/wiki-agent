#!/usr/bin/env python3
# 배포 캘린더(ICS)를 읽어 정기배포 5일 전(전주 목) 10:00 KST 코드프리징 리마인더를
# Slack chat.scheduleMessage로 선등록. 이미 있는 건 post_at으로 중복 판별해 건너뜀(멱등).
#
# 필요한 환경변수 (wiki-agent/.env 에서 로드):
#   SLACK_BOT_TOKEN            봇 토큰 (chat:write)
#   CODEFREEZE_ICS_URL         배포 캘린더 secret iCal URL (private- 토큰 포함)
#   CODEFREEZE_CHANNEL         공지 채널 ID
#   CODEFREEZE_REPORT_USER_ID  결과 DM 받을 Slack member ID (선택)
import json, os, sys, urllib.request, urllib.parse
from datetime import date, datetime, timezone, timedelta

ICS_URL = os.environ["CODEFREEZE_ICS_URL"]
TOKEN   = os.environ["SLACK_BOT_TOKEN"]
CHANNEL = os.environ["CODEFREEZE_CHANNEL"]
REPORT  = os.environ.get("CODEFREEZE_REPORT_USER_ID", "").strip()
KST = timezone(timedelta(hours=9)); SEND_HOUR = 10


def slack(m, p):
    r = urllib.request.Request("https://slack.com/api/" + m, data=json.dumps(p).encode(),
        headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json; charset=utf-8"})
    return json.load(urllib.request.urlopen(r))


def slack_get(m, p):
    u = "https://slack.com/api/" + m + "?" + urllib.parse.urlencode(p)
    return json.load(urllib.request.urlopen(urllib.request.Request(u, headers={"Authorization": "Bearer " + TOKEN})))


def log(s):
    print(f"[{datetime.now(KST):%Y-%m-%d %H:%M:%S}] {s}", flush=True)


def unfold(t):
    o = []
    for ln in t.splitlines():
        if ln[:1] in (" ", "\t") and o:
            o[-1] += ln[1:]
        else:
            o.append(ln)
    return o


def is_regular(s):
    return "[앱]" in s and "배포" in s and "핫픽스" not in s and "비정기" not in s


def to_date(v):
    d = v.split("T")[0][-8:]
    return date(int(d[:4]), int(d[4:6]), int(d[6:8]))


def ver_of(s):
    for t in s.split():
        if t.startswith("v") and t[1:2].isdigit():
            return t
    return "정기 배포"


def text(ver, dep, fr):
    return (":snowflake: *코드 프리징 안내*\n"
            f"*{ver} 배포({dep.month}/{dep.day}, 화)* 대상 코드는 "
            f"*오늘({fr.month}/{fr.day}, 목) 17:00까지 머지 완료*해주세요.\n"
            "이후 머지분은 다음 배포로 넘어갑니다.")


def main():
    today = datetime.now(KST).date(); horizon = today + timedelta(days=118)
    with urllib.request.urlopen(ICS_URL, timeout=30) as r:
        txt = r.read().decode("utf-8")
    ev = {}; cur = {}
    for l in unfold(txt):
        if l.startswith("BEGIN:VEVENT"):
            cur = {}
        elif l.startswith("SUMMARY"):
            cur['s'] = l.split(":", 1)[1]
        elif l.startswith("DTSTART"):
            cur['d'] = l.split(":", 1)[1].strip()
        elif l.startswith("END:VEVENT") and cur.get('s') and cur.get('d'):
            if is_regular(cur['s']):
                try:
                    dep = to_date(cur['d'])
                except Exception:
                    continue
                fr = dep - timedelta(days=dep.weekday() + 4)  # 전주 목요일
                if today < fr <= horizon:
                    pa = int(datetime(fr.year, fr.month, fr.day, SEND_HOUR, 0, tzinfo=KST).timestamp())
                    ev[pa] = (ver_of(cur['s']), dep, fr)
    existing = set(); cur2 = None
    while True:
        p = {"channel": CHANNEL, "limit": 200}
        if cur2:
            p["cursor"] = cur2
        r = slack_get("chat.scheduledMessages.list", p)
        if not r.get("ok"):
            log(f"list 실패 {r.get('error')}"); break
        existing |= {m["post_at"] for m in r.get("scheduled_messages", [])}
        cur2 = r.get("response_metadata", {}).get("next_cursor")
        if not cur2:
            break
    added = []; failed = []
    for pa in sorted(ev):
        if pa in existing:
            continue
        v, dep, fr = ev[pa]
        r = slack("chat.scheduleMessage", {"channel": CHANNEL, "post_at": pa, "text": text(v, dep, fr)})
        if r.get("ok"):
            added.append((fr, v)); log(f"예약 OK {fr} {v}")
        else:
            failed.append((fr, v, r.get("error"))); log(f"예약 실패 {fr} {v}: {r.get('error')}")
    log(f"완료 신규{len(added)} 실패{len(failed)} 대상{len(ev)} 기존{len(existing)}")
    if REPORT and (added or failed):
        m = ["*코드프리징 자동 씨딩 결과*"]
        if added:
            m.append("신규: " + ", ".join(f"{f} {v}" for f, v in added))
        if failed:
            m.append("실패: " + ", ".join(f"{f} {v}({e})" for f, v, e in failed))
        slack("chat.postMessage", {"channel": REPORT, "text": "\n".join(m)})


try:
    main()
except Exception as e:
    log(f"오류 {e}")
    if REPORT:
        try:
            slack("chat.postMessage", {"channel": REPORT, "text": f":warning: 코드프리징 씨딩 오류: {e}"})
        except Exception:
            pass
    sys.exit(1)
