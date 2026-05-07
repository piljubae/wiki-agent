# Execution Verification Design

**목적:** wiki-agent-workshop의 Step 1~7을 실제 실행하여 빌드, Tool 라우팅, LLM 응답 품질을 검증한다.

**방식:** main 브랜치에서 참가자처럼 직접 파일을 편집하며 진행. Step 완료 후 `git checkout -- .`로 초기화.

---

## 고정 질문 세트

| ID | 질문 | 타입 | 사용 Step |
|----|------|------|-----------|
| Q1 | `Koog가 뭐야?` | 정의형 | 1, 2, 4, 5 |
| Q2 | `ToolRegistry에 Tool 등록하는 방법 알려줘` | 절차형 | 4 |
| Q3 | `wiki-agent 아키텍처 어떻게 생겼어?` | 탐색형 | 2, 3, 7 |
| Q4 | `오늘 점심 뭐 먹을까?` | 무관련 | 5 |
| Q5 | (ingest 후 자유 질문) | 로컬 지식 | 6 |

### 질문-Step 연관 근거

- **Q1을 여러 Step에서 반복하는 이유:** "같은 질문인데 동작이 달라졌는가" 비교 관찰. Step 1(직접 답변) → Step 2(Tool 경유) → Step 4(형식 변화) → Step 5(강제 Tool 호출)
- **Q3이 Step 3에서 쓰이는 이유:** 두 Tool 중 githubWikiSearch가 명백히 맞는 질문이므로 설명이 모호할 때 라우팅 오류가 드러남. Q1은 어떤 Tool을 써도 답이 나와 오류가 보이지 않음
- **Q2가 Step 4에서 처음 쓰이는 이유:** Step 4 이전에는 출력 형식 지시가 없어 번호 리스트 여부를 검증할 수 없음
- **Q4가 Step 5에서만 쓰이는 이유:** Tool 강제 문구 없이는 무관련 질문에 Tool 호출을 기대할 수 없음
- **Q5가 Step 6에서만 쓰이는 이유:** ingest 전/후 비교가 목적이므로 그 자리에서 결정

---

## 실행 방식

**환경:** main 브랜치, `./gradlew run` 직접 실행

**Step별 사이클:**
```
[편집] 파일 수정
   ↓
[실행] ./gradlew run
   ↓
[입력] 해당 Step 질문 입력
   ↓
[기록] 로그 + 응답 캡처 → 결과 문서에 붙여넣기
   ↓
[초기화] git checkout -- .
```

---

## Step별 편집 대상 + 확인 포인트

| Step | 편집 파일 | 확인 포인트 | 질문 |
|------|-----------|------------|------|
| 1 | 없음 | 로그: `No tool descriptions` | Q1 |
| 2 | KnowledgeTool.kt, GitHubWikiTool.kt | 로그: `TOOL: githubWikiSearch` | Q1, Q3 |
| 3 | 위 두 파일 (`@LLMDescription("검색")`) | 잘못된 Tool 선택 또는 라우팅 실패 | Q3 |
| 4 | prompts/system-prompt.txt | Q1: 한 줄 정의+부연 / Q2: 번호 리스트 | Q1, Q2 |
| 5 | prompts/system-prompt.txt (강제 문구 추가) | Q4에도 Tool 호출 로그 | Q1, Q4 |
| 6 | (실행 중 /ingest) | ingest 전 "없음" → 후 내용 출력 | Q5 |
| 7 | config.yml (repos 변경) | 레포 1개 vs 3개+ 응답 품질 차이 | Q3 |

---

## 결과 기록 형식

파일: `docs/verification/2026-05-03-execution-check.md`

```markdown
## Step N — [제목]

**편집 내용:** (무엇을 바꿨는지)

### Q1: Koog가 뭐야?
**로그:** (관련 로그 라인)
**응답:**
> (실제 응답)
**판정:** ✅ / ❌
**메모:** (이상한 점)
```

---

## 성공 기준

- **Pass:** 각 Step의 확인 포인트 로그가 찍히고, 응답이 의도한 구조/내용으로 나옴
- **Fail:** 로그 미출력, 빌드 오류, 응답이 의도와 다른 형식이거나 엉뚱한 내용
- **결과 문서:** 각 Step Pass/Fail + Fail 원인 메모 → 이후 A(드라이런), C(가이드) 작업에 재사용
