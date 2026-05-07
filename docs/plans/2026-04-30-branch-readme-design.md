# 브랜치별 README 설계

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:writing-plans to create an implementation plan.

**Goal:** 8개 step 브랜치(step-2 ~ step-9, step-bonus)의 README.md를 각 단계 전용으로 교체. 참가자가 `git checkout step-N` 후 README만 보면 실습 진행과 정답 확인이 모두 가능하도록.

**main 브랜치:** 현재 전체 개요 README 유지 (변경 없음)

---

## 각 브랜치 README 구조

```
[헤더]        레포 소개 + 빠른 시작 (모든 브랜치 동일)
[이전 단계들]  Step 1 ~ N-1 ✅ 완료 (한 줄씩)
[현재 단계]   ━━━━━━━━━━━━━━━━ (아래 상세 구조 참고)
[다음 힌트]   ▶ Step N+1 — 한 줄 미리보기
[푸터]        브랜치 정답지 표 + 참고 문서 (모든 브랜치 동일)
```

### 현재 단계 섹션 내부 구성

```markdown
## ━━ Step N — [제목]

> **핵심 메시지:** [이 단계에서 배우는 핵심 인사이트 한 줄]

**2부 연결:** [슬라이드 개념명]

---

### 개념

[내부 동작 원리 설명 — WorkshopAgent 코드 중 이 단계와 직접 연관된 부분 제시]
[Before(빈 상태) / After(완성 상태) 차이 설명]

---

### 기본 액션

[편집할 파일 경로]

**Before:**
[비어있는 코드]

**After (예시):**
[채워진 코드]

---

### 실험

> 💭 **질문:** [개념을 체험으로 발견하게 하는 질문]

[실험 절차 — 구체적 입력값 + 관찰할 로그/동작]

**무엇을 발견했나요?**
[관찰 → 개념 연결 설명]

---

### ✅ 확인 포인트
- [ ] [로그 또는 동작 기반 체크 항목]
- [ ] ...

---

### (선택) 챌린지

[기본 액션보다 한 단계 더 나아가는 선택 과제]

---

### 이 브랜치에서 변경된 것
| 파일 | 변경 내용 |
|------|---------|
| `파일명` | 설명 |
```

---

## 브랜치별 상세 설계

### step-2 — @LLMDescription 작성

**이전:** Step 1 ✅  
**다음 힌트:** Step 3 — 설명 품질이 라우팅에 미치는 영향

**핵심 메시지:** LLM은 설명을 읽고 Tool을 고른다  
**2부 연결:** Tool이란?

**개념:**
- WorkshopAgent가 Java reflection으로 @LLMDescription 값을 읽어 routing prompt 조립
- 비어있으면 tool이 routing prompt에 포함 안 됨 → LLM이 Tool 선택 불가
- 관련 코드: `WorkshopAgent.kt`의 `toolDescription()` + routing prompt 빌더

**기본 액션:**
- `KnowledgeTool.kt` @LLMDescription 채우기
- `GitHubWikiTool.kt` @LLMDescription 채우기

**실험:**
- 질문: "Description이 짧으면 LLM이 어떻게 반응할까요?"
- 절차: `@LLMDescription("검색")`으로 바꾸고 같은 질문 → 로그 확인
- 발견: "Description은 사람에게 쓰는 주석이 아니라 LLM이 읽는 판단 근거"

**확인 포인트:**
- 로그에 `TOOL: knowledgeSearch` 출력
- 로그에 `TOOL: githubWikiSearch` 출력
- 두 질문에서 서로 다른 Tool 선택됨

**챌린지:** PersonaTool.kt에도 @LLMDescription 채우고 WorkshopAgent에 등록해보기

**변경 파일:** `KnowledgeTool.kt`, `GitHubWikiTool.kt`

---

### step-4 — system-prompt.txt 역할 + 출력 형식

**이전:** Step 1 ✅, Step 2 ✅, Step 3 ✅  
**다음 힌트:** Step 5 — Tool 호출 강제

**핵심 메시지:** 역할과 출력 형식이 답변의 일관성을 만든다  
**2부 연결:** 프롬프트 원칙 1 — 역할과 출력 형식 분리

**개념:**
- 시스템 프롬프트가 없으면 LLM은 매번 다른 구조로 답변
- 역할 정의("당신은 Koog 전문 봇")와 출력 형식을 분리해서 작성
- 관련 코드: `WorkshopAgent.kt`의 `buildSummaryPrompt()` — systemPrompt가 어떻게 주입되는지

**기본 액션:**
- `prompts/system-prompt.txt` 작성 (역할 + 출력 형식)

**실험:**
- 질문: "같은 질문, 같은 Tool — 시스템 프롬프트만 다르면 달라질까?"
- 절차: system-prompt.txt를 비운 상태 → 질문 → 응답 저장. 역할+형식 추가 → 같은 질문 → 비교
- 발견: "역할과 형식이 없으면 답변 구조가 매번 다르다. 시스템 프롬프트가 봇의 일관성을 결정한다"

**확인 포인트:**
- 정의형 질문 → 한 줄 정의 + 부연 구조로 답변
- 절차형 질문 → 번호 리스트로 답변

**챌린지:** 언어 제약 추가 ("모든 답변은 반드시 한국어로")

**변경 파일:** `prompts/system-prompt.txt`

---

### step-5 — Tool 호출 강제

**이전:** Step 1~4 ✅  
**다음 힌트:** Step 6 — 내 문서 주입

**핵심 메시지:** 강제하지 않으면 LLM은 저장된 문서 대신 학습 데이터로 답한다  
**2부 연결:** 프롬프트 원칙 3 — Tool 호출 유도

**개념:**
- LLM은 Tool 없이도 답변 가능 — 학습 데이터에서 직접 생성
- 내부 문서 봇의 목적은 "실제 저장된 문서"를 찾아주는 것
- 강제 줄이 없으면 일반 지식 질문은 그냥 직접 답변

**기본 액션:**
- `prompts/system-prompt.txt`에 한 줄 추가: "검색 없이 직접 답변하지 마세요. 반드시 knowledgeSearch 또는 githubWikiSearch를 사용하세요."

**실험:**
- 질문: "검색을 강제하지 않으면 봇이 항상 Tool을 쓸까요?"
- 절차: 강제 줄 제거 → "배포 프로세스 알려줘" 입력 → 로그 확인 (Tool 호출 없음)
- 발견: "LLM은 아는 걸 그냥 말한다. 강제하지 않으면 지식베이스를 거치지 않는다"

**확인 포인트:**
- "안녕"같은 인사에도 Tool 호출 시도하는지 확인
- 강제 전/후 같은 질문 로그 비교

**챌린지:** "가능하면 검색" (중간 강도)으로 바꾸고 강제 vs 권장 차이 비교

**변경 파일:** `prompts/system-prompt.txt`

---

### step-6 — 내 문서 주입

**이전:** Step 1~5 ✅  
**다음 힌트:** Step 7 — 검색 범위 조정

**핵심 메시지:** 봇은 ingest된 것만 안다. 문서를 넣는 행위가 봇의 지식 범위를 결정한다  
**2부 연결:** 프롬프트 원칙 2 — 컨텍스트 범위 제어 + LLM Wiki 패턴

**개념:**
- KnowledgeStore가 `.wiki/knowledge/`의 .md 파일을 로드해 키워드 검색
- `/ingest URL` 명령이 HTML → 텍스트 변환 후 .md로 저장
- 파일을 직접 넣어도 동일하게 작동

**기본 액션:**
- 실행 중 `/ingest <URL>` 입력
- 또는 `.wiki/knowledge/` 폴더에 .md 파일 직접 추가

**실험:**
- 질문: "ingest하기 전/후로 검색 결과가 달라질까요?"
- 절차: ingest 전 → "방금 저장한 내용 알려줘" → 결과 없음 확인. ingest 후 → 같은 질문 → 내용 출력
- 발견: "봇의 지식은 ingest 시점의 스냅샷이다"

**확인 포인트:**
- `.wiki/knowledge/` 폴더에 파일 생성 확인
- ingest한 내용으로 질문 → 관련 내용 답변 확인

**챌린지:** 직접 작성한 .md 파일을 `.wiki/knowledge/`에 넣고 질문해보기

**변경 파일:** `.wiki/knowledge/` 샘플 파일 포함 (gitignore 예외)

---

### step-7 — 검색 범위 조정

**이전:** Step 1~6 ✅  
**다음 힌트:** 보너스 또는 Step 8

**핵심 메시지:** 컨텍스트가 많다고 좋은 게 아니다. 범위가 넓을수록 노이즈도 늘어난다  
**2부 연결:** 프롬프트 원칙 2 심화 — 검색 우선순위

**개념:**
- `config.yml`의 `github.repos` 목록이 GitHubWikiTool의 검색 대상
- 레포가 많을수록 관련 없는 결과가 섞여 답변 품질 저하
- 지식베이스 vs GitHub 검색 우선순위 설계

**기본 액션:**
- `config.yml`에서 `github.repos` 목록 변경

**실험:**
- 질문: "레포를 많이 추가하면 답변이 더 좋아질까요?"
- 절차: repos에 관련 없는 레포 추가 → 특정 질문 → 관련 없는 결과 섞이는지 확인
- 발견: "범위는 좁을수록 정확하다. Tool의 컨텍스트도 설계가 필요하다"

**확인 포인트:**
- 레포 1개 → 정확한 답변
- 레포 3개 이상 → 노이즈 증가 확인

**챌린지:** 완전히 다른 주제의 GitHub 레포 추가 후 질문 → 엉뚱한 결과 체험

**변경 파일:** `config.yml`

---

### step-bonus — PersonaTool 페르소나 설정

**이전:** Step 1~7 ✅  
**다음 힌트:** Step 8 — 대화 히스토리

**핵심 메시지:** @LLMDescription 하나가 봇의 성격을 바꾼다  
**2부 연결:** Tool이란? (Step 2 복습)

**개념:**
- PersonaTool은 검색이 아닌 "답변 스타일"을 지정하는 메타 Tool
- LLM이 PersonaTool을 선택하면 그 description의 어투로 답변
- @LLMDescription을 바꾸는 것만으로 완전히 다른 봇이 됨

**기본 액션:**
- `PersonaTool.kt` @LLMDescription 채우기 (MZ 인턴 예시 또는 직접 설계)

**실험:**
- 질문: "@LLMDescription에 어투를 지정하면 LLM이 따라갈까요?"
- 절차: "냉소적인 시니어" 페르소나 적용 → 같은 질문 → "MZ 인턴" 적용 → 비교
- 발견: "LLM은 description을 역할 지침으로 읽는다. Tool description이 프롬프트 역할을 한다"

**확인 포인트:**
- 페르소나 변경 전/후 같은 질문의 어투 차이 확인

**챌린지:** `prompts/persona-guide.md` 참고해 나만의 페르소나 설계

**변경 파일:** `PersonaTool.kt`

---

### step-8 — 대화 히스토리

**이전:** Step 1~7 + 보너스 ✅  
**다음 힌트:** Step 9 — 내 Tool 만들기

**핵심 메시지:** LLM은 매번 새 대화다. 히스토리를 프롬프트에 넣는 것만으로 기억하는 것처럼 동작한다  
**2부 연결:** (심화) 대화 기록 — 컨텍스트 관리

**개념:**
- LLM API는 stateless — 각 호출은 독립적
- 이전 대화를 프롬프트에 포함하면 문맥 유지 가능
- `ArrayDeque`로 최대 3턴 보관 → 오래된 것부터 제거 (sliding window)
- 관련 코드: `WorkshopAgent.kt`의 `buildSummaryPrompt()` — history 파라미터 추가 지점

**기본 액션:**
- `WorkshopAgent.kt`에 history 필드 추가
- `buildSummaryPrompt()`에 이전 대화 주입
- `answer()` 마지막에 history 업데이트

```kotlin
private val history = ArrayDeque<Pair<String, String>>()

// buildSummaryPrompt 안에:
if (history.isNotEmpty()) {
    appendLine("이전 대화:")
    history.forEach { (q, a) -> appendLine("Q: $q\nA: ${a.take(150)}") }
}

// answer() 마지막:
history.addLast(question to result)
if (history.size > 3) history.removeFirst()
```

**실험:**
- 질문: "히스토리 없이 '그거 더 자세히'라고 하면?"
- 절차: 구현 전 → "wiki-agent 기획서 v1이 뭐야?" → "그거 v2랑 뭐가 달라?" → 맥락 없는 답변 확인. 구현 후 → 같은 시퀀스 → 비교
- 발견: "상태를 기억하는 것만으로 봇 품질이 달라진다"

**확인 포인트:**
- 3턴 후속 질문이 앞 맥락을 유지하는지 확인
- 4번째 질문부터 첫 턴 맥락이 사라지는지 확인

**챌린지:** 히스토리 크기를 5로 변경 / 히스토리에 타임스탬프 추가

**변경 파일:** `WorkshopAgent.kt`

---

### step-9 — 내 Tool 만들기

**이전:** Step 1~8 ✅  
**다음 힌트:** — (완성)

**핵심 메시지:** Tool 하나 = 에이전트 능력 하나. 오늘 배운 구조 그대로 내 케이스에  
**2부 연결:** Tool이란? + LLM이 Tool 고르는 순간

**개념:**
- @Tool + @LLMDescription + 함수 구현 = 에이전트 능력 추가
- Main.kt에 인스턴스 등록하면 WorkshopAgent가 자동으로 routing prompt에 포함
- 구조는 KnowledgeTool, GitHubWikiTool과 동일

**기본 액션:**
- `MyTool.kt` @LLMDescription + mySearch() 구현
- `Main.kt`에 myTool 인스턴스 등록
- `WorkshopAgent.kt`에 myTool 파라미터 전달

**실험:**
- 질문: "@LLMDescription을 비워두면 내 Tool이 선택될까요?"
- 절차: 빈 상태로 등록 → 내 Tool 관련 질문 → 선택 안 됨 확인. 채운 뒤 → 같은 질문 → 선택됨
- 발견: "Description은 Tool의 존재를 LLM에게 알리는 선언이다. Step 2에서 배운 것과 동일"

**확인 포인트:**
- 로그에 `TOOL: mySearch` 출력
- 내 Tool에 적합한 질문 → 선택됨, 다른 질문 → 선택 안 됨

**챌린지:** mySearch에 두 번째 파라미터 추가 (`language: String = "ko"` 등) + @LLMDescription 작성

**변경 파일:** `MyTool.kt`, `Main.kt`, `WorkshopAgent.kt`

---

## 헤더/푸터 공통 요소 (모든 브랜치 동일)

### 헤더
```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

## 빠른 시작
git clone / ./gradlew run
```

### 푸터
- 브랜치 정답지 표 (main README와 동일)
- 참고 문서 표 (main README와 동일)
