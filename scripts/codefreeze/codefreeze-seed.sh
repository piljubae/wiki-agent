#!/bin/zsh
# 코드프리징 리마인더 자동 씨딩 (launchd 매월 실행)
# 모든 설정/시크릿은 wiki-agent/.env 단일 소스에서 로드 (.env는 gitignore됨).
set -e
ENV_FILE="$HOME/projects/wiki-agent/.env"
env_get() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d "\"'"; }

export SLACK_BOT_TOKEN="$(env_get SLACK_BOT_TOKEN)"
export CODEFREEZE_ICS_URL="$(env_get CODEFREEZE_ICS_URL)"
export CODEFREEZE_CHANNEL="$(env_get CODEFREEZE_CHANNEL)"
export CODEFREEZE_REPORT_USER_ID="$(env_get CODEFREEZE_REPORT_USER_ID)"

LOG_DIR="$HOME/.config/codefreeze"
mkdir -p "$LOG_DIR"
/usr/bin/python3 "$(dirname "$0")/seed_freeze_reminders.py" >> "$LOG_DIR/seed.log" 2>&1
