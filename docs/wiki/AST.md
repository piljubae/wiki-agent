# AST와 Tree-sitter

## AST (Abstract Syntax Tree)

코드를 줄 단위 텍스트가 아닌 **구조(트리)**로 파싱한 결과.
컴파일러, 린터, 코드 인덱서가 코드를 "이해"하는 방식입니다.

---

### 텍스트 파싱 vs AST 파싱

**텍스트(라인 기반)**

```kotlin
fun onBannerClick(bannerId: String): Unit {
    _events.send(BannerEvent.Navigate(bannerId))
}
```

→ `{`, `}`, `fun` 키워드를 문자로 찾아서 경계를 추정합니다.

**AST**

```
FunctionDeclaration
├── name: "onBannerClick"
├── parameters:
│   └── Parameter
│       ├── name: "bannerId"
│       └── type: "String"
├── returnType: "Unit"
└── body: BlockExpression
    └── CallExpression
        ├── receiver: "_events"
        └── argument: CallExpression("BannerEvent.Navigate", "bannerId")
```

→ 함수 이름, 파라미터, 반환 타입, 바디가 **노드**로 정확히 분리됩니다.

---

### AST가 더 정확한 경우

```kotlin
// 문자열 리터럴 안에 중괄호가 있는 경우
val sql = """
    CREATE TABLE {
        id INTEGER PRIMARY KEY
    }
"""
fun realFunction() { }
```

- **라인 기반 파서**: 문자열 안의 `{`, `}`도 카운트 → 깊이 계산 틀림 → `realFunction` 못 찾을 수 있음
- **AST 파서**: 문자열 리터럴 내부는 코드가 아님을 이미 앎 → 정확히 파싱

실제로 이런 극단적인 케이스가 아니면 라인 기반도 99% 동일하게 처리합니다.

---

## Tree-sitter

AST를 만들어주는 파서 라이브러리. Cursor, GitHub Copilot, Continue.dev, Neovim이 사용합니다.

```
Kotlin 소스코드 → Tree-sitter (Kotlin grammar) → AST → 함수/클래스 경계 정확히 추출
```

- 100개 이상 언어 지원 (Kotlin, Swift, Go, Python, Java...)
- C로 구현되어 매우 빠름
- 증분 파싱 지원 — 파일 일부 수정 시 전체 재파싱 없이 변경 부분만 업데이트

---

### wiki-agent가 Tree-sitter를 쓰지 않는 이유

JVM에서 실행하려면 native 바이너리가 필요합니다.

```
문제:
  - 플랫폼별 바이너리 배포 필요 (Linux x86, ARM, macOS...)
  - Docker 이미지에 .so 파일 포함
  - JNI(Java Native Interface) 연동 코드 작성
  - 의존성 관리 복잡도 증가

결론:
  라인 기반 파서로 99% 케이스 처리 가능
  Slack bot 목적으로는 트레이드오프상 도입 불필요
```

---

## 참고 자료

- [Tree-sitter 공식 문서](https://tree-sitter.github.io/tree-sitter/) — 파서 원리 및 언어 목록
- [AST Explorer](https://astexplorer.net/) — 코드를 입력하면 AST를 실시간으로 시각화 (강력 추천)
- [cAST: Enhancing Code RAG with AST Chunking (arXiv)](https://arxiv.org/html/2506.15655v1) — AST 기반 청킹 연구

---

> **관련 문서:** [Chunk.md](Chunk.md) · [Code-Index-Commercial-Design.md](Code-Index-Commercial-Design.md)
