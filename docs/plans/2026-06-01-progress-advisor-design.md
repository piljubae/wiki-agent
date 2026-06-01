# ProgressAdvisorTool Design

> 성과 목표 진척도 데이터를 기반으로, 팀장/부문장 두 관점의 1:1 코칭 피드백을 생성하는 도구.

## Context

기존 PersonalDataTool은 progress.json에서 숫자를 읽어 보여주는 조회 도구.
사용자가 "조언해줘", "피드백 줘", "1:1 해줘" 같은 명시적 요청을 했을 때,
LLM이 진척도 데이터를 분석하고 두 관점의 코칭을 제공한다.

## Architecture

```
PersonalDataTool (기존)     ProgressAdvisorTool (신설)
  - 데이터 조회 전담            - LLM 기반 코칭 전담
  - progress.json → 숫자       - progress.json 전체 → LLM → 조언
  - "진척도 알려줘"             - "조언해줘" / "1:1 해줘"
```

두 Tool은 완전 분리. 같은 config(progressFile, allowedUsers)를 공유하지만 독립 동작.

## ProgressAdvisorTool 구조

```kotlin
class ProgressAdvisorTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
    private val tracker: SourceTracker? = null,
)
```

### Tool 메서드

```kotlin
@Tool("progressAdvisor")
@LLMDescription("성과 목표에 대한 조언/피드백/1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘' 질문에 사용.")
fun advise(userId: String): String
```

- progress.json 전체를 읽어 현재 날짜와 함께 LLM에 전달
- 두 페르소나 관점의 피드백을 한 응답에 생성

## 페르소나 정의

코드에 하드코딩. progress.json에 넣지 않음.

### 팀장 (앱개발팀장) — "이번 달 뭘 끝낼 수 있어?"

- 지표 숫자를 보고 **실행 속도** 판단
- 0인 지표가 있으면 "이거 왜 안 움직여?" 라고 물음
- 구체적 액션 제안: "이번 주에 Skill 하나 팀 공유하자"
- 비유: **스프린트 리뷰** 느낌

### 부문장 (클라이언트 부문장) — "연말 평가에서 이걸로 점수를 받을 수 있어?"

- 가중치 배분이 적절한지, 숫자로 증명할 수 있는지 확인
- indicator 없는 목표에 "이거 어떻게 설명할 거야?" 라고 물음
- G7 기대역할이 실제 활동으로 뒷받침되는지 체크
- 비유: **반기 성과 면담** 느낌

### LLM 출력 형식

```
## 팀장 피드백
(스프린트 리뷰 관점의 조언)

## 부문장 피드백
(성과 면담 관점의 조언)
```

## 라우터 연동

- `knownTools` / `availableTools` / `toolOptions`에 `"progressAdvisor"` 추가
- 라우터 프롬프트 규칙: `"progressAdvisor: 성과 목표 조언·피드백·1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘', '어떻게 하면 좋을까' 질문."`
- `when` 디스패치: `progressAdvisor` → `advisorTool!!.advise(userId ?: "")`

## Config 연동

PersonalDataConfig를 그대로 재사용.
- PersonalDataTool이 활성화되면 ProgressAdvisorTool도 함께 생성
- 별도 on/off 설정 없음

## Slack 표시

`toolDisplayNames`에 `"progressAdvisor" to "성과 코칭"` 추가.

## 사용자 흐름

```
"올해 성과 진척도 알려줘"
  → 라우터: personalProgress
  → PersonalDataTool.getProgressSummary()
  → 숫자 데이터 응답

"성과 목표 조언해줘" / "1:1 해줘"
  → 라우터: progressAdvisor
  → ProgressAdvisorTool.advise()
  → progress.json 전체 + 현재 날짜 → LLM
  → 팀장 피드백 + 부문장 피드백 응답
```
