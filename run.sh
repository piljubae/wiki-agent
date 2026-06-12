#!/usr/bin/env bash
#
# wiki-agent supervisor
#
# 슬랙에서 `/askpj restart`(관리자 전용) 실행 시 앱은 `.wiki/.restart-signal` 마커를
# 기록하고 종료(exit 0)한다. 이 루프가 마커를 감지하면 재빌드 후 재실행한다.
# 마커가 없으면(= `/askpj stop` 또는 정상 종료) 루프를 끝낸다.
#
# 사용법:
#   ./run.sh            # supervisor 모드로 실행 (재시작 가능)
#
# 관리자 지정: 환경변수 WIKI_ADMIN_USERS 에 쉼표로 구분된 Slack user ID 를 설정
#   export WIKI_ADMIN_USERS="U01ABC,U02DEF"
#
set -uo pipefail
cd "$(dirname "$0")"

MARKER=".wiki/.restart-signal"
export WIKI_SUPERVISED=1

# 시작 시 잔여 마커 제거
rm -f "$MARKER"

while true; do
  echo "[supervisor] $(date '+%F %T') starting wiki-agent…"
  ./gradlew run --console=plain
  code=$?

  if [ -f "$MARKER" ]; then
    rm -f "$MARKER"
    echo "[supervisor] $(date '+%F %T') restart signal detected — rebuilding & relaunching…"
    continue
  fi

  echo "[supervisor] $(date '+%F %T') wiki-agent exited (code $code), no restart signal — supervisor stopping."
  break
done
