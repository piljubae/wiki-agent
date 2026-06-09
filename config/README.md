# :config

wiki-agent의 **설정 로딩**과 **시크릿 해석**을 담당하는 모듈.

모듈화 로드맵의 **Phase 2**. 내부 패키지 의존성이 0인 leaf이며 외부 의존은 로깅 파사드(slf4j-api)뿐이다. 다른 모듈(`:llm` 등)이 의존하는 공통 기반(shared kernel)이다.

전체 로드맵: [`docs/plans/2026-06-09-modularization-roadmap.md`](../docs/plans/2026-06-09-modularization-roadmap.md)

## 책임

- YAML 설정 파일 파싱 → `WikiConfig` (직접 파싱, 외부 YAML 라이브러리 미사용)
- 설정 저장/재로딩 round-trip
- 환경변수 / `.env` / 설정값 우선순위에 따른 시크릿 해석

## 공개 API

### `WikiConfig` 및 하위 설정 (`WikiConfig.kt`)

`WikiConfig` 루트 데이터 클래스와 하위 설정(`ModelConfig`, `ConfluenceConfig`, `SlackConfig`, `RagConfig`, `GithubConfig`, `CodeSearchConfig`, `PersonalDataConfig`, `CallGraphConfig`), 열거형(`ModelProvider`, `EmbeddingMode`, `PersonaType`).

### `ConfigLoader` (`ConfigLoader.kt`)

| 메서드 | 설명 |
|---|---|
| `fromString(yaml): WikiConfig` | YAML 문자열 파싱 (인라인 주석 `#` 제거, 기본값 적용) |
| `load(path): WikiConfig` | 파일에서 로드 |
| `save(config, path)` | 설정을 YAML 파일로 저장 |

### `SecretLoader` (`SecretLoader.kt`)

| 메서드 | 설명 |
|---|---|
| `resolve(envKey, configValue): String` | 우선순위 **환경변수 → `.env` → 설정값** |
| `resolveNullable(envKey, configValue?): String?` | nullable 버전 |
| `parseDotEnv(content): Map<String,String>` | `KEY=VALUE` 파싱 (첫 `=`만 구분자, 주석·빈 줄·빈 값 무시) |

## 의존성

- `org.slf4j:slf4j-api` (로깅 파사드; 백엔드는 앱 모듈이 제공)
- 내부 모듈 의존 **없음**

## 테스트

순수 파싱·파일 I/O라 외부 의존 없이 결정적으로 검증한다.

```bash
./gradlew :config:test
```

`ConfigLoaderTest`(12): provider별 로딩, 인라인 주석 제거, rag/github/router/personalData 파싱, 기본값, save/reload round-trip.
`SecretLoaderTest`(5): 우선순위 fallback, dotenv 파싱(주석·빈 줄·값 내 `=` 보존·빈 값 skip).
