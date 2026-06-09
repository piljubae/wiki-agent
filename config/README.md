# :config

> 한 줄 요약: 앱 설정 파일(YAML)을 읽어 코틀린 객체로 만들고, 토큰 같은 **비밀값**을 안전하게 찾아주는 모듈.

## 이게 왜 필요한가

wiki-agent는 어떤 LLM을 쓸지, Confluence 주소가 뭔지, 슬랙 토큰은 무엇인지 등 **여러 설정**으로 동작이 달라진다. 이런 값을 코드에 박아두면 안 되니까 설정 파일(`config.yml`)과 환경변수로 빼둔다. 이 모듈이 그걸 읽어서 앱이 쓰기 좋은 형태(`WikiConfig` 객체)로 만들어 준다.

비밀값(API 토큰 등)은 파일에 평문으로 두기 위험하므로, **환경변수 → `.env` 파일 → 설정 파일** 순서로 찾아주는 별도 로직(`SecretLoader`)도 제공한다.

이 모듈은 거의 모든 다른 모듈이 가져다 쓰는 **공통 기반(shared kernel)** 이라, 모듈화에서 일찍 떼어냈다.

## 무엇을 제공하나 (공개 API)

### `WikiConfig` — 설정 전체를 담는 데이터 클래스

설정 파일의 구조를 그대로 코틀린 객체로 옮긴 것. 영역별로 하위 객체로 나뉜다.

| 하위 설정 | 담는 내용 |
|---|---|
| `ModelConfig` | 어떤 LLM(provider/모델명/API 키)을 쓸지 |
| `ConfluenceConfig` | Confluence 주소·토큰·검색할 스페이스 |
| `SlackConfig` | 슬랙 토큰 등 |
| `RagConfig` | 벡터 검색(ChromaDB) 사용 여부·주소 |
| `GithubConfig` | GitHub 연동 여부·대상 저장소 |
| `PersonalDataConfig` | 개인화 데이터 기능 설정 |

관련 열거형: `ModelProvider`(ANTHROPIC, GOOGLE, CLAUDE_CODE …), `EmbeddingMode`, `PersonaType`.

### `ConfigLoader` — 설정 읽고 쓰기

| 하고 싶은 일 | 메서드 |
|---|---|
| YAML 문자열을 설정 객체로 | `fromString(yaml)` |
| 파일에서 설정 읽기 | `load(path)` |
| 설정을 파일로 저장 | `save(config, path)` |

> 참고: YAML 파싱을 외부 라이브러리 없이 **직접 구현**했다. 그래서 `# 주석`을 떼고 기본값을 채우는 동작이 이 모듈 안에 들어 있고, 의존성이 매우 가볍다.

### `SecretLoader` — 비밀값 우선순위 해석

```kotlin
// CONFLUENCE_TOKEN 환경변수가 있으면 그걸, 없으면 .env, 그것도 없으면 설정 파일 값
val token = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
```

| 메서드 | 설명 |
|---|---|
| `resolve(envKey, configValue)` | 환경변수 → `.env` → 설정값 순으로 찾아 첫 값을 반환 |
| `resolveNullable(envKey, configValue?)` | 위와 같지만 null 허용 |
| `parseDotEnv(content)` | `.env`의 `KEY=VALUE` 줄을 맵으로 (첫 `=`만 구분자, 주석·빈 줄 무시) |

## 의존성

- `slf4j-api` — 로깅 인터페이스만 사용(실제 로그 출력 구현체는 앱 모듈이 제공).
- **다른 내부 모듈에는 의존하지 않는다.** (leaf 모듈)

## 테스트

파싱·파일 입출력이라 외부 의존 없이 결정적으로 검증된다.

```bash
./gradlew :config:test
```

주요 검증: provider별 설정 로딩, 인라인 주석 제거, 각 영역(rag/github/router/개인화) 파싱, 기본값 적용, 저장 후 다시 읽기(round-trip), `.env`에서 값에 `=`가 들어가도 안 깨지는지 등.

---

이 모듈은 wiki-agent 모듈화의 **2단계**로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
