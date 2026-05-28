# 첫 실행 가이드

Confluence 없이 **GitHub Wiki만으로도 실행 가능**합니다.

## 사전 요건

- JDK 17 이상 (`java -version` 으로 확인)
  - 없는 경우: `brew install --cask temurin` 또는 [Adoptium Temurin](https://adoptium.net)
- Slack App 토큰 2개 ([Slack-App-Setup](Slack-App-Setup) 참고)
- Claude Code CLI 설치 (provider=CLAUDE_CODE 시, API 키 불필요)
- Node.js 18 이상 — `provider: GEMINI_CODE` 사용 시에만 필요 (`brew install node`)
- `agy` CLI — `provider: ANTIGRAVITY_CODE` 사용 시 필요 (Antigravity CLI 설치)

## 1. 레포 클론

```bash
git clone https://github.com/Veronikapj/wiki-agent.git
cd wiki-agent
```

## 2. .env 파일 작성

```bash
cp .env.example .env
```

`.env` 파일 편집:

```
# 필수
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...

# GitHub Wiki 사용 시 (public 레포는 없어도 됨)
GITHUB_TOKEN=ghp_...

# Confluence 사용 시 (선택)
CONFLUENCE_TOKEN=<base64 email:api-token>

# LLM provider=ANTHROPIC 시
ANTHROPIC_API_KEY=sk-ant-...
```

## 3. config.yml 최소 설정

`.wikiq/config.yml`:

```yaml
model:
  provider: CLAUDE_CODE   # API 키 불필요

confluence:
  baseUrl: ""             # Confluence 없으면 빈 값

slack: {}

github:
  enabled: true
  repos:
    - Veronikapj/wiki-agent   # 이 위키가 있는 레포
```

## 4. 실행

```bash
./gradlew run
```

## 5. Slack에서 테스트

봇을 채널에 초대한 후:

```
@배필주2 에이전트가 뭔가요?
@배필주2 Koog 소개
@배필주2 배포 방법
```

## 실행 모드 비교

| 모드 | provider 설정 | 필요한 것 | 비용 |
|------|--------------|---------|------|
| 로컬 (Claude Code) | `CLAUDE_CODE` | Claude Code CLI | 구독 요금만 |
| 로컬 (Antigravity) | `ANTIGRAVITY_CODE` | `agy` CLI | 무료 (Google 계정) |
| Claude API | `ANTHROPIC` | `ANTHROPIC_API_KEY` | API 사용량 |
| Gemini API | `GOOGLE` | `GOOGLE_API_KEY` | API 사용량 |

## 트러블슈팅

### `Warning: True color (24-bit) support not detected`
`provider: GEMINI_CODE` 또는 `provider: ANTIGRAVITY_CODE` 사용 시 표시될 수 있는 정상 경고입니다. 동작에 영향 없습니다.

### `gemini: command not found`
```bash
npm install -g @google/gemini-cli
export PATH="$(npm root -g)/../.bin:$PATH"
```

### `agy: command not found`
Antigravity CLI 공식 설치 스크립트를 사용하거나, headless 환경에서는 환경변수를 추가로 설정합니다:
```bash
# ANTIGRAVITY_API_KEY 환경변수 설정 (Google AI Studio 키 사용)
export ANTIGRAVITY_API_KEY=AIza...
```

### Gradle 빌드 실패
```bash
./gradlew build --refresh-dependencies
```

---

> **Source:** [wiki-agent Main.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/Main.kt)
