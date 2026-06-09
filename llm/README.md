# :llm

> 한 줄 요약: 설정(`ModelProvider`)에 맞는 **LLM 실행기**를 만들어 주고, 여러 LLM 제공자를 같은 인터페이스로 다루게 해주는 모듈.

## 이게 왜 필요한가

wiki-agent는 상황에 따라 다른 LLM을 쓴다 — Anthropic API, Google API, 또는 로컬 CLI 기반(Claude Code / Gemini Code / Antigravity). 이 모듈은 "설정에 적힌 provider를 보고 알맞은 클라이언트를 골라 조립"하는 일을 한 곳에 모아, 나머지 코드가 LLM 종류를 신경 쓰지 않고 쓰게 한다(Koog의 `LLMClient`/`PromptExecutor` 인터페이스로 추상화).

## 무엇을 제공하나 (공개 API)

### `LLMExecutorBuilder` — provider → 실행기 조립

설정(`ModelConfig`/`ModelProvider`)을 받아, 해당 provider에 맞는 Koog `PromptExecutor`(여러 클라이언트를 묶은 `MultiLLMPromptExecutor`)를 만들어 반환한다.

### 제공자별 `LLMClient` 구현

| 클라이언트 | 방식 |
|---|---|
| (Anthropic / Google) | Koog 기본 클라이언트(`AnthropicLLMClient`, `GoogleLLMClient`) 사용 |
| `ClaudeCodeLLMClient` | 로컬 `claude` CLI를 subprocess로 호출 |
| `GeminiCodeLLMClient` | 로컬 Gemini CLI 호출 |
| `AntigravityCodeLLMClient` | Antigravity CLI 호출 |

CLI 기반 클라이언트들은 Koog의 `LLMClient` 인터페이스를 구현해, API 기반과 동일하게 쓰인다.

## 의존성

- `:config` — `ModelConfig`/`ModelProvider`를 읽어 어떤 실행기를 만들지 결정.
- `ai.koog:koog-agents` + `prompt-executor-anthropic-client` + `prompt-executor-google-client` — LLM 클라이언트/실행기 추상화 및 기본 구현.
- `kotlinx-coroutines-core` — suspend 기반 호출.
- `slf4j-api` — 로깅 인터페이스.

> Koog 아티팩트는 JetBrains 전용 Maven 저장소(`packages.jetbrains.team/maven/p/koog/public`)에서 받으므로 이 모듈 `build.gradle.kts`에 해당 repository를 선언한다. 루트와 동일한 jackson 버전 강제(`resolutionStrategy.force`)도 적용한다.

## 테스트

```bash
./gradlew :llm:test
```

`ClaudeCodeLLMClientTest` — 실제 CLI를 실행하지 않고 클라이언트 구성/기본 동작을 검증.

---

이 모듈은 wiki-agent 모듈화의 일부로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
