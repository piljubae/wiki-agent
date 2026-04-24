# 답변 품질 개선 설계

## 개요

LLM 답변의 포맷, 구조, 정확성을 개선한다.
핵심 전략: **질문 유형별 프롬프트 + HTML→Slack mrkdwn 직접 변환 + grounding 지시 + 자동 검증**.

## 현재 문제

| 문제 | 원인 | 영향 |
|------|------|------|
| 답변 구조 불일정 | "요약과 링크를 포함해 답변하세요" 한 줄 지시만 존재 | 모든 질문에 동일 패턴 |
| 길이 제어 없음 | 길이 가이드라인 부재 | 단순 질문에 과도한 답변 |
| Markdown 오염 | `convertHtmlToMarkdown`이 `#`, `**` 생성 → LLM이 복사 | Slack에서 포맷 깨짐 |
| 할루시네이션 | grounding 지시 없음, snippet 300자 제한 | 검색 결과 밖 내용 생성 |
| 프롬프트 불일치 | Manual loop / Koog agent 각각 별도 프롬프트 | 경로별 답변 품질 차이 |
| 답변 품질 측정 불가 | 포맷/구조 검증 테스트 없음 | 회귀 감지 불가 |

## 설계

### 1. summaryPrompt 질문 유형별 답변 구조

Manual loop의 summaryPrompt에 질문 유형 판별 + 유형별 구조 지시를 추가한다.

```
위 검색 결과를 바탕으로 답변하세요. 답변 전 질문 유형을 판별하고, 유형에 맞는 구조로 작성하세요.

# 질문 유형별 답변 구조

*정의형* ("~이 뭐야?", "~란?")
→ 한 줄 정의 + 부연 1-2문장. 총 3-5줄.

*절차형* ("~어떻게 해?", "~하는 방법")
→ 단계별 번호 리스트(1. 2. 3.). 각 단계는 1-2문장. 필요시 10줄+.

*비교형* ("A와 B 차이", "~대신 ~")
→ 항목별 비교. 공통점/차이점 구분.

*목록형* ("~종류", "~목록", "~어떤 것들")
→ 불릿(•) 리스트. 각 항목 간결하게.

*기타/복합*
→ 핵심 답변 먼저, 세부사항은 그 아래. 단순하면 3-5줄, 복합이면 10줄+.

# 출처 표기 규칙
- 각 문서의 정보를 언급할 때 해당 문장 안에 <URL|문서제목> 형태로 인라인 링크를 넣으세요.
- 예: 배포 절차는 <https://wiki.example.com/pages/123|배포 가이드>에 정리되어 있습니다.
- 별도 "출처" 섹션을 만들지 말고, 본문 흐름 안에 자연스럽게 넣으세요.
- 검색 결과에 URL이 없으면 링크 없이 답변하세요. URL을 추측하지 마세요.

# Slack mrkdwn (Markdown 아님)
사용 가능: *굵게* _기울임_ ~취소선~ `코드` ```코드블록``` <URL|텍스트> • 불릿 1. 번호
사용 금지: # ## **굵게** [텍스트](URL) - 대시불릿
```

**변경 파일:** `OrchestratorAgent.kt` — summaryPrompt 블록

### 2. systemPrompt 압축 버전

Koog agent의 systemPrompt에 동일 규칙의 압축 버전을 추가한다. 매 턴 반복되므로 토큰 효율을 위해 간결하게.

```
검색 결과를 바탕으로 답변하세요. 답변 전 질문 유형을 판별하고 유형에 맞게 작성하세요.

질문 유형: 정의형(3-5줄) / 절차형(번호 리스트) / 비교형(항목별) / 목록형(불릿) / 기타(핵심 먼저)
출처: 문장 안에 <URL|문서제목> 인라인. 별도 출처 섹션 금지. URL 없으면 링크 생략.
Slack mrkdwn만 사용. # ## **굵게** [텍스트](URL) 금지.
```

**변경 파일:** `OrchestratorAgent.kt` — buildAgent systemPrompt 블록

### 3. Grounding 지시

양쪽 프롬프트에 할루시네이션 방지 grounding 지시를 추가한다.

```
검색 결과에 명시적으로 포함된 정보만 사용하세요.
확실하지 않으면 "해당 문서에서 정확한 내용을 확인해주세요"로 안내하세요.
검색 결과에 없는 내용을 추측하거나 지어내지 마세요.
```

**변경 파일:** `OrchestratorAgent.kt` — summaryPrompt + systemPrompt 양쪽

### 4. `convertHtmlToMarkdown` → `convertHtmlToSlackMrkdwn`

현재 HTML→Markdown 변환이 `#`, `**`를 생성하여 LLM 컨텍스트를 오염시킨다.
Slack mrkdwn으로 직접 변환하도록 변경한다.

| HTML | 현재 (Markdown) | 변경 (Slack mrkdwn) |
|------|----------------|-------------------|
| `<h1>제목</h1>` | `# 제목` | `*제목*\n` |
| `<h2>제목</h2>` | `## 제목` | `*제목*\n` |
| `<h3>제목</h3>` | `### 제목` | `*제목*\n` |
| `<strong>굵게</strong>` | `**굵게**` | `*굵게*` |
| `<b>굵게</b>` | `**굵게**` | `*굵게*` |
| `<li>항목</li>` | `- 항목` | `• 항목` |
| 나머지 | 동일 | 동일 |

**변경 파일:** `ConfluenceClient.kt` — `convertHtmlToMarkdown` 함수명 변경 + 변환 규칙 수정

### 5. 공통 프롬프트 빌더 함수 추출

summaryPrompt와 systemPrompt에 중복되는 규칙(질문 유형, 출처 표기, mrkdwn DO/DON'T, grounding)을 공통 함수로 추출한다.

```kotlin
companion object {
    fun buildAnswerGuidelines(verbose: Boolean = true): String = buildString {
        // verbose=true: summaryPrompt용 (상세 예시 포함)
        // verbose=false: systemPrompt용 (압축 버전)
    }
}
```

**변경 파일:** `OrchestratorAgent.kt` — companion object에 함수 추가

### 6. GoldenCase 답변 품질 필드 확장

```kotlin
@Serializable
data class GoldenCase(
    // 기존 필드
    val id: String,
    val question: String,
    val category: Category,
    val expectedDocTitles: List<String>,
    val keyPoints: List<String> = emptyList(),
    val negativePoints: List<String> = emptyList(),
    // 답변 품질 필드 (신규)
    val questionType: QuestionType = QuestionType.DEFINITION,
    val expectedMinLines: Int = 3,
    val expectedMaxLines: Int = 8,
    val requiresSteps: Boolean = false,
    val requiresLink: Boolean = true,
)

@Serializable
enum class QuestionType {
    DEFINITION,   // 정의형
    PROCEDURE,    // 절차형
    COMPOSITE,    // 복합
    ZERO,         // 검색 결과 없음
}
```

**변경 파일:**
- `src/test/kotlin/io/github/veronikapj/wiki/eval/GoldenCase.kt`
- `src/test/resources/golden-dataset.json` — 기존 6건에 답변 품질 필드 추가

### 7. AnswerQualityEvalTest 자동 검증

```kotlin
@Tag("eval")
class AnswerQualityEvalTest {

    @Test fun `no Markdown patterns in answer`()  // # ## ** [text](url) 검출
    @Test fun `inline links present`()             // <https://...|제목> 패턴 존재
    @Test fun `line count within range`()          // expectedMinLines ~ expectedMaxLines
    @Test fun `step structure when required`()     // requiresSteps일 때 1. 또는 • 존재
    @Test fun `negative points not in answer`()    // negativePoints 미포함
    @Test fun `key points in answer`()             // keyPoints 1개 이상 포함
}
```

**신규 파일:** `src/test/kotlin/io/github/veronikapj/wiki/eval/AnswerQualityEvalTest.kt`

## 컴포넌트 변경 요약

| 파일 | 변경 |
|------|------|
| `OrchestratorAgent.kt` | 질문 유형별 프롬프트, grounding 지시, 공통 빌더 함수 |
| `ConfluenceClient.kt` | `convertHtmlToSlackMrkdwn` 변환 규칙 변경 |
| `GoldenCase.kt` | `QuestionType`, 답변 품질 필드 추가 |
| `golden-dataset.json` | 기존 6건에 품질 필드 추가 |
| 신규 `AnswerQualityEvalTest.kt` | 포맷/구조 자동 검증 |
