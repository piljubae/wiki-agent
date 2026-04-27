# config.yml — model 섹션

## 설정 위치

`.wikiq/config.yml`의 `model:` 섹션

## 전체 옵션

```yaml
model:
  provider: CLAUDE_CODE   # 필수. CLAUDE_CODE | ANTHROPIC | GOOGLE
  name: null              # 선택. provider별 모델명 오버라이드
  apiKey: null            # 선택. .env에 넣는 것을 권장
```

## provider별 동작

### CLAUDE_CODE (기본값)

```yaml
model:
  provider: CLAUDE_CODE
```

- Claude Code CLI를 통해 LLM 실행
- **API 키 불필요** — Claude Code 구독으로 무료 사용
- 로컬 개발·세션 실습에 적합
- 기본 모델: `AnthropicModels.Sonnet_4`

### ANTHROPIC

```yaml
model:
  provider: ANTHROPIC
  name: claude-sonnet-4-20250514   # 선택. 기본값: Sonnet_4
```

`.env`:
```
ANTHROPIC_API_KEY=sk-ant-...
```

- Anthropic API 직접 호출
- 팀 서버 배포 시 사용

### GOOGLE

```yaml
model:
  provider: GOOGLE
  name: gemini-2.0-flash   # 선택. 기본값: Gemini2_0Flash
```

`.env`:
```
GOOGLE_API_KEY=AIza...
```

- Google AI API 직접 호출
- 팀 서버 배포 시 사용 (전사 Gemini 환경)

## 시크릿 우선순위

`apiKey`는 다음 순서로 로드됩니다:
1. 환경변수 (`ANTHROPIC_API_KEY` 또는 `GOOGLE_API_KEY`)
2. `.env` 파일
3. `config.yml`의 `apiKey` 값

`.env`에 넣는 것을 권장합니다. `config.yml`은 git에 커밋되므로 시크릿을 직접 넣으면 안 됩니다.

## 코드 참조

```kotlin
// LLMExecutorBuilder.kt
fun defaultModel(config: ModelConfig): LLModel =
    when (config.provider) {
        CLAUDE_CODE -> AnthropicModels.Sonnet_4
        ANTHROPIC   -> AnthropicModels.Sonnet_4
        GOOGLE      -> GoogleModels.Gemini2_0Flash
    }
```

---

> **Source:** [WikiConfig.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt) · [LLMExecutorBuilder.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/llm/LLMExecutorBuilder.kt)
