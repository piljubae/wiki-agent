# :callgraph-plugin

> 한 줄 요약: 코드를 **컴파일할 때** "어떤 함수가 어떤 함수를 부르는지"를 모두 뽑아 작은 데이터베이스(SQLite)에 적어두는 도구.

## 이게 왜 필요한가

wiki-agent에는 코드 검색 기능이 있는데, 그중 **"이 함수를 누가 호출하지?" / "이 함수는 뭘 호출하지?"** 같은 질문에 빠르게 답하려면 함수 간 호출 관계를 미리 정리해둬야 한다. 매번 코드를 다시 분석하면 느리기 때문이다.

그래서 이 모듈은 **Kotlin 컴파일러 플러그인** 형태로 만들어졌다. 즉, 우리 코드를 평소처럼 컴파일하는 그 순간에 같이 끼어들어, 호출 관계를 전부 수집해 DB로 저장한다. 나중에 코드 검색은 이 DB만 조회하면 된다.

## 컴파일러 플러그인이 뭔가 (배경)

Kotlin 컴파일러는 소스 코드를 바로 바이트코드로 바꾸지 않고, 중간에 **IR**(Intermediate Representation, 중간 표현)이라는 트리 구조를 만든다. 컴파일러 플러그인은 이 IR 단계에 훅을 걸어 코드를 분석하거나 변형할 수 있다. 이 플러그인은 **변형은 하지 않고 분석만** 한다 — IR을 쭉 훑으며 함수 호출을 발견할 때마다 기록한다.

## 동작 흐름

```
kotlinc 가 코드를 컴파일 (IR 생성 단계)
   │
   ▼
CallGraphPluginRegistrar    ← "이 플러그인을 IR 단계에 등록해줘"
   │
   ▼
CallGraphIrExtension        ← IR 트리 순회 시작점
   │
   ▼
CallGraphVisitor            ← 트리를 돌며 함수 호출식을 만날 때마다
   │                          (부른 쪽, 불린 쪽, 파일) 한 줄 기록
   ▼
CallGraphDb                 ← SQLite(.db) 파일에 저장
```

수집되는 한 건의 단위는 `CallEdge(callerFqn, calleeFqn, callerFile)` — "어떤 함수(`callerFqn`)가 어떤 함수(`calleeFqn`)를, 어느 파일(`callerFile`)에서 불렀다"는 뜻이다. (FQN = Fully Qualified Name, `패키지.클래스.함수` 형태의 전체 이름.)

## 파일별 역할

| 파일 | 역할 |
|---|---|
| `CallGraphCommandLineProcessor.kt` | 플러그인에 넘기는 옵션(예: 결과 DB를 어디에 저장할지) 처리 |
| `CallGraphPluginRegistrar.kt` | 컴파일러에 플러그인을 등록하는 진입점 |
| `CallGraphIrExtension.kt` | IR 순회를 시작하고 Visitor를 돌림 |
| `CallGraphDb.kt` | `CallEdge` 모델 정의 + SQLite에 읽고 쓰기 |

## 의존성

- `kotlin-compiler-embeddable` — 컴파일러 내부 API. 컴파일할 때만 필요하므로 `compileOnly`.
- `sqlite-jdbc` — 수집 결과를 저장할 SQLite 드라이버.

## 빌드 · 테스트

```bash
./gradlew :callgraph-plugin:test                 # 테스트
./gradlew :callgraph-plugin:publishToMavenLocal   # 로컬 Maven에 게시
```

루트(앱) 모듈이 `implementation(project(":callgraph-plugin"))`로 이 플러그인을 가져다 쓴다.
