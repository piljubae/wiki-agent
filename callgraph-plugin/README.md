# :callgraph-plugin

컴파일 시점에 코드의 **호출 그래프**(caller → callee)를 추출해 SQLite DB로 저장하는 Kotlin 컴파일러 플러그인.

코드 검색 기능이 "이 함수를 누가 호출하나 / 이 함수가 무엇을 호출하나"를 빠르게 답하기 위한 정적 인덱스를 제공한다.

## 동작

Kotlin 컴파일러의 IR(중간 표현) 생성 단계에 끼어들어, 각 함수 호출식을 방문하며 `(callerFqn, calleeFqn, callerFile)` 엣지를 수집해 SQLite에 기록한다.

```
kotlinc (IR gen)
   │
   ▼
CallGraphPluginRegistrar  ─ 플러그인 등록
   │
   ▼
CallGraphIrExtension      ─ IrGenerationExtension 진입점
   │
   ▼
CallGraphVisitor          ─ IR 순회, 호출식마다 CallEdge 수집
   │
   ▼
CallGraphDb               ─ SQLite(.db)에 엣지 upsert
```

## 구성 요소

| 파일 | 역할 |
|---|---|
| `CallGraphCommandLineProcessor.kt` | 플러그인 CLI 옵션(출력 DB 경로 등) 처리 |
| `CallGraphPluginRegistrar.kt` | `CompilerPluginRegistrar` — IR extension 등록 |
| `CallGraphIrExtension.kt` | `IrGenerationExtension` — IR 순회 시작, `CallGraphVisitor` 구동 |
| `CallGraphDb.kt` | `CallEdge(callerFqn, calleeFqn, callerFile)` 모델 + SQLite 영속화 |

## 의존성

- `org.jetbrains.kotlin:kotlin-compiler-embeddable` (compileOnly — 컴파일러 API)
- `org.xerial:sqlite-jdbc`

## 빌드 · 테스트

```bash
./gradlew :callgraph-plugin:test
./gradlew :callgraph-plugin:publishToMavenLocal   # 로컬 Maven에 게시
```

루트 모듈이 `implementation(project(":callgraph-plugin"))`로 사용한다.
