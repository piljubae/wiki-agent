# 골든 데이터셋 평가 (evalTest)

## 핵심 질문

> 검색·답변 품질을 어떻게 자동으로 측정하나요?

## 개요

`golden-dataset.json`에 정의된 질문-기대 조건 쌍으로 실제 Confluence 연결 상태에서 검색 품질을 측정합니다.  
일반 유닛 테스트와 분리되어 `./gradlew evalTest`로만 실행됩니다.

## 실행 방법

```bash
# 일반 테스트 (evalTest 제외)
./gradlew test

# 평가 테스트 (실제 Confluence 연결 필요)
./gradlew evalTest
```

## 골든 케이스 구조

```kotlin
@Serializable
data class GoldenCase(
    val id: String,
    val question: String,
    val category: Category,
    val expectedDocTitles: List<String>,    // 검색 결과에 있어야 할 문서 제목
    val keyPoints: List<String> = emptyList(),      // 답변에 포함되어야 할 핵심 내용
    val negativePoints: List<String> = emptyList(), // 답변에 포함되면 안 되는 내용
    val questionType: QuestionType = QuestionType.DEFINITION,
    val expectedMinLines: Int = 3,
    val expectedMaxLines: Int = 8,
    val requiresSteps: Boolean = false,
    val requiresLink: Boolean = true,
    val sourcePageId: String? = null,
)
```

## golden-dataset.json 예시

```json
[
  {
    "id": "onboarding-001",
    "question": "신규 입사자 온보딩 절차는?",
    "category": "TITLE_BASED",
    "expectedDocTitles": ["신규 입사자 온보딩 가이드"],
    "keyPoints": ["환경 설정", "권한 요청", "담당자"],
    "questionType": "PROCEDURE",
    "requiresSteps": true,
    "requiresLink": true
  },
  {
    "id": "zero-001",
    "question": "외계인 채용 절차는?",
    "category": "ZERO_EXPECTED",
    "expectedDocTitles": [],
    "questionType": "ZERO"
  }
]
```

## 카테고리

| Category | 설명 |
|----------|------|
| `EXACT_MATCH` | 제목이 정확히 일치하는 케이스 |
| `SYNONYM_GAP` | 동의어 처리가 필요한 케이스 |
| `ABBREVIATION` | 약어 확장이 필요한 케이스 |
| `PARTIAL_MATCH` | 부분 매칭이 필요한 케이스 |
| `MULTI_DOC` | 여러 문서에 답이 분산된 케이스 |
| `ZERO_EXPECTED` | 관련 문서가 없어야 하는 케이스 (honest-zero 측정) |
| `TITLE_BASED` | Confluence 제목에서 자동 생성 |
| `LLM_GENERATED` | LLM이 생성한 질문 |
| `PARAPHRASE` | 기존 질문을 다른 표현으로 바꾼 케이스 |

## 질문 유형

| QuestionType | 설명 |
|-------------|------|
| `DEFINITION` | 정의·개념형 ("~이 뭔가요?") |
| `PROCEDURE` | 절차형 ("~하려면 어떻게 하나요?") |
| `COMPOSITE` | 복합형 (여러 문서 참조 필요) |
| `ZERO` | 답 없음형 (ZERO_EXPECTED 전용) |

## 평가 지표

```kotlin
object EvalMetrics {
    // Recall@K: 상위 K건 안에 정답 문서가 있으면 hit
    fun hitAtK(results, expectedTitles, k): Boolean

    // MRR: 첫 번째 정답 문서의 역수 순위 (1/rank)
    fun reciprocalRank(results, expectedTitles): Double

    // Honest-Zero: ZERO_EXPECTED에서 빈 결과 반환 시 성공
    fun isHonestZero(results, category): Boolean
}
```

**제목 매칭 방식** — 단순 문자열 비교가 아닌 두 단계 매칭:
1. 양방향 substring 포함 여부
2. 단어 겹침: 짧은 쪽 단어의 50% 이상이 긴 쪽에 포함되면 match

이 덕분에 "iOS Daily 26.04.27"과 "iOS Daily - 26.04.27"이 같은 문서로 인식됩니다.

## @Tag("eval") 분리 이유

```kotlin
@Tag("eval")
class SearchQualityEvalTest { ... }
```

```kotlin
// build.gradle.kts
tasks.test {
    useJUnitPlatform { excludeTags("eval") }  // 일반 테스트에서 제외
}
tasks.register<Test>("evalTest") {
    useJUnitPlatform { includeTags("eval") }  // 평가 테스트만 실행
}
```

평가 테스트는 실제 Confluence API를 호출하므로 CI/CD 일반 빌드에서는 제외됩니다.

## 골든 케이스 추가 방법

1. `src/test/resources/golden-dataset.json`에 새 케이스 추가
2. `./gradlew evalTest`로 검증
3. 통과하면 커밋

→ [검색 플로우](Search-Flow) · [CQL 검색 전략](CQL-Search-Strategy) 참고

---

> **Source:** [GoldenCase.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenCase.kt) · [EvalMetrics.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/test/kotlin/io/github/veronikapj/wiki/eval/EvalMetrics.kt) · [SearchQualityEvalTest.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/test/kotlin/io/github/veronikapj/wiki/eval/SearchQualityEvalTest.kt)
