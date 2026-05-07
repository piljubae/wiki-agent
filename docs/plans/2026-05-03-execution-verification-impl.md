# Execution Verification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** wiki-agent-workshop Step 1~7을 main 브랜치에서 직접 편집하며 실행하고, 빌드/라우팅/응답 품질을 검증한 결과를 `docs/verification/2026-05-03-execution-check.md`에 기록한다.

**Architecture:** main 브랜치 → 파일 직접 편집 → `./gradlew run` 실행 → 질문 입력 → 로그+응답 기록 → `git checkout -- .` 초기화. 각 Step을 독립 Task로 처리.

**Tech Stack:** Kotlin + Koog 0.8.0, Gemini CLI (GEMINI_CODE provider), Gradle

**설계 문서:** `docs/plans/2026-05-03-execution-verification-design.md`

---

### Task 0: 환경 확인 및 결과 파일 초기화

**Files:**
- Create: `docs/verification/2026-05-03-execution-check.md`

**Step 1: JDK + Gemini CLI 확인**

```bash
java -version
gemini --version
```

Expected: JDK 17+, gemini CLI 응답 있음. 없으면 중단 후 `docs/setup.md` 참고.

**Step 2: 빌드 확인**

```bash
cd /Users/pilju.bae/projects/wiki-agent-workshop
git checkout main
git checkout -- .
./gradlew build
```

Expected: `BUILD SUCCESSFUL`

**Step 3: 결과 파일 생성**

아래 내용으로 `docs/verification/2026-05-03-execution-check.md` 생성:

```markdown
# Execution Verification — 2026-05-03

## 환경
- JDK: (버전 기록)
- Gemini CLI: (버전 기록)
- 브랜치: main

---
```

**Step 4: 커밋**

```bash
git add docs/verification/
git commit -m "chore: init verification result file"
```

---

### Task 1: Step 1 검증 — Tool 없이 직접 답변

**Files:**
- Modify: `docs/verification/2026-05-03-execution-check.md` (결과 추가)

**Step 1: 현재 상태 확인 (편집 없음)**

```bash
git checkout -- .
```

`src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt` 열어서
`@LLMDescription("")` 비어있는지 확인. GitHubWikiTool.kt도 동일.

**Step 2: 실행**

```bash
./gradlew run
```

**Step 3: Q1 입력**

```
질문 > Koog가 뭐야?
```

**Step 4: 관찰 및 기록**

확인 포인트:
- 로그에 `knowledgeDesc='' githubDesc=''` 출력 여부
- 로그에 `No tool descriptions — answering directly` 출력 여부
- 응답이 LLM 학습 데이터 기반으로 나오는지

결과 파일에 아래 형식으로 추가:

```markdown
## Step 1 — Tool 없이 직접 답변

**편집 내용:** 없음 (스켈레톤 상태)

### Q1: Koog가 뭐야?
**로그:**
```
(로그 붙여넣기)
```
**응답:**
> (실제 응답 붙여넣기)
**판정:** ✅ / ❌
**메모:** (이상한 점 있으면 기록)

---
```

**Step 5: 종료 + 초기화**

```
질문 > q
```

```bash
git checkout -- .
```

---

### Task 2: Step 2 검증 — @LLMDescription 채우기

**Files:**
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`
- Modify: `docs/verification/2026-05-03-execution-check.md`

**Step 1: @LLMDescription 채우기**

`KnowledgeTool.kt` 수정:
```kotlin
@LLMDescription("로컬 지식베이스에서 문서를 검색합니다. URL을 ingest해서 저장한 문서, 설치 방법, 환경 설정, 사용 방법 등을 찾을 때 사용하세요.")
```

`GitHubWikiTool.kt` 수정:
```kotlin
@LLMDescription("GitHub Wiki에서 문서를 검색합니다. Koog 프레임워크, wiki-agent 구조, 기술 개념을 찾을 때 사용하세요.")
```

**Step 2: 실행**

```bash
./gradlew run
```

**Step 3: Q1 입력 (Step 1과 비교)**

```
질문 > Koog가 뭐야?
```

확인 포인트:
- 로그에 `TOOL: githubWikiSearch` 또는 `TOOL: knowledgeSearch` 출력
- Step 1과 같은 질문인데 Tool 경유로 바뀌었는지

**Step 4: Q3 입력**

```
질문 > wiki-agent 아키텍처 어떻게 생겼어?
```

확인 포인트:
- 로그에 `TOOL: githubWikiSearch` 출력 (GitHub 질문이므로 knowledgeSearch가 아닌 이쪽)
- 검색 결과 기반 답변인지

**Step 5: 결과 파일에 기록**

```markdown
## Step 2 — @LLMDescription 채우기

**편집 내용:** KnowledgeTool.kt, GitHubWikiTool.kt @LLMDescription 작성

### Q1: Koog가 뭐야?
**로그:** (붙여넣기)
**응답:** > (붙여넣기)
**판정:** ✅ / ❌ | Step 1 대비 변화: (Tool 경유 여부)

### Q3: wiki-agent 아키텍처 어떻게 생겼어?
**로그:** (붙여넣기)
**응답:** > (붙여넣기)
**판정:** ✅ / ❌
**메모:**

---
```

**Step 6: 종료 + 초기화**

```bash
# 종료 후
git checkout -- .
```

---

### Task 3: Step 3 검증 — 나쁜 @LLMDescription

**Files:**
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`
- Modify: `docs/verification/2026-05-03-execution-check.md`

**Step 1: 설명 망가뜨리기**

`KnowledgeTool.kt`:
```kotlin
@LLMDescription("검색")
```

`GitHubWikiTool.kt`:
```kotlin
@LLMDescription("검색")
```

**Step 2: 실행 + Q3 입력**

```bash
./gradlew run
```

```
질문 > wiki-agent 아키텍처 어떻게 생겼어?
```

확인 포인트:
- 잘못된 Tool 선택 (knowledgeSearch 선택) 또는 라우팅 포기
- Step 2의 Q3 결과와 Tool 선택이 달라지는지

**Step 3: 결과 파일에 기록**

```markdown
## Step 3 — 나쁜 @LLMDescription

**편집 내용:** 두 Tool 모두 @LLMDescription("검색")으로 변경

### Q3: wiki-agent 아키텍처 어떻게 생겼어?
**로그:** (붙여넣기)
**응답:** > (붙여넣기)
**판정:** ✅ / ❌ (라우팅 오류 발생하면 ✅ — 의도된 현상)
**Step 2 대비 변화:** (Tool 선택이 달라졌는지)

---
```

**Step 4: 종료 + 초기화**

```bash
git checkout -- .
```

---

### Task 4: Step 4 검증 — system-prompt.txt 역할 + 출력 형식

**Files:**
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`
- Modify: `prompts/system-prompt.txt`
- Modify: `docs/verification/2026-05-03-execution-check.md`

**Step 1: @LLMDescription 채우기 (Step 2와 동일)**

KnowledgeTool.kt, GitHubWikiTool.kt를 Step 2와 같이 채운다.

**Step 2: system-prompt.txt 작성**

`prompts/system-prompt.txt`:
```
당신은 Koog 프레임워크와 wiki-agent에 대해 답변하는 전문 봇입니다.
항상 검색 결과를 바탕으로 답변하세요.

답변 형식:
- 정의형: 한 줄 정의 + 부연 2-3문장
- 절차형: 반드시 1., 2., 3. 숫자 형식으로 작성하세요 (* 불릿 사용 금지)
- 기타: 핵심 먼저, 세부사항 아래
```

**Step 3: 실행 + Q1 입력 (정의형 구조 확인)**

```bash
./gradlew run
```

```
질문 > Koog가 뭐야?
```

확인 포인트:
- 응답이 "한 줄 정의 + 부연 2-3문장" 구조인지
- Step 2 Q1 응답과 형식이 달라졌는지

**Step 4: Q2 입력 (절차형 번호 리스트 확인)**

```
질문 > ToolRegistry에 Tool 등록하는 방법 알려줘
```

확인 포인트:
- 응답이 `1. 2. 3.` 번호 리스트 형식인지

**Step 5: 결과 파일에 기록**

```markdown
## Step 4 — system-prompt.txt 역할 + 출력 형식

**편집 내용:** @LLMDescription 채움 + system-prompt.txt 작성

### Q1: Koog가 뭐야? (정의형)
**응답:** > (붙여넣기)
**판정:** ✅ / ❌ | 구조: 한 줄 정의 + 부연 여부
**Step 2 대비 형식 변화:** (기록)

### Q2: ToolRegistry에 Tool 등록하는 방법 알려줘 (절차형)
**응답:** > (붙여넣기)
**판정:** ✅ / ❌ | 번호 리스트 여부

---
```

**Step 6: 종료 + 초기화**

```bash
git checkout -- .
```

---

### Task 5: Step 5 검증 — Tool 호출 강제

**Files:**
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`
- Modify: `prompts/system-prompt.txt`
- Modify: `docs/verification/2026-05-03-execution-check.md`

**Step 1: Step 4 내용 + Tool 강제 문구 추가**

KnowledgeTool.kt, GitHubWikiTool.kt: Step 2와 동일하게 채운다.

`prompts/system-prompt.txt` (Step 4 내용에 한 줄 추가):
```
당신은 Koog 프레임워크와 wiki-agent에 대해 답변하는 전문 봇입니다.
항상 검색 결과를 바탕으로 답변하세요.

답변 형식:
- 정의형: 한 줄 정의 + 부연 2-3문장
- 절차형: 번호 리스트 (1. 2. 3.)
- 기타: 핵심 먼저, 세부사항 아래

검색 없이 직접 답변하지 마세요. 반드시 knowledgeSearch 또는 githubWikiSearch를 사용하세요.
```

**Step 2: 실행 + Q1 입력 (LLM이 알아도 Tool 쓰는지)**

```bash
./gradlew run
```

```
질문 > Koog가 뭐야?
```

확인 포인트:
- 로그에 `TOOL:` 출력 (학습 데이터로 직접 답변 안 함)

**Step 3: Q4 입력 (무관련 질문에도 Tool 호출)**

```
질문 > 오늘 점심 뭐 먹을까?
```

확인 포인트:
- 로그에 `TOOL:` 출력 (무관련 질문에도 Tool 호출 시도)
- 또는 Tool로 검색 후 "관련 문서 없음" 형태로 답변

**Step 4: 결과 파일에 기록**

```markdown
## Step 5 — Tool 호출 강제

**편집 내용:** system-prompt.txt에 강제 문구 추가

### Q1: Koog가 뭐야?
**로그:** (붙여넣기)
**판정:** ✅ / ❌ | Tool 경유 여부

### Q4: 오늘 점심 뭐 먹을까?
**로그:** (붙여넣기)
**응답:** > (붙여넣기)
**판정:** ✅ / ❌ | 무관련 질문에도 Tool 호출 여부

---
```

**Step 5: 종료 + 초기화**

```bash
git checkout -- .
```

---

### Task 6: Step 6 검증 — URL ingest

**Files:**
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`
- Modify: `prompts/system-prompt.txt`
- Modify: `docs/verification/2026-05-03-execution-check.md`

**Step 1: Step 5 상태로 세팅 + KnowledgeStore 컨텐츠 한계 해소**

KnowledgeTool.kt, GitHubWikiTool.kt: Step 2와 동일.
prompts/system-prompt.txt: Step 5와 동일.

`KnowledgeStore.kt:44` 수정 — 스니펫 길이 600 → 1500:
```kotlin
"[$path]\n${content.take(1500)}"
```
*이유: README 사전 준비 섹션(Slack App 설정 단계)이 chars ~950부터 시작. 600자 컷에서 잘려 LLM이 "정보 없음" 응답.*

**Step 2: 실행 + ingest 전 Q5 입력**

```bash
./gradlew run
```

먼저 ingest 없이 README 특화 내용 질문 (GitHub Wiki에 없는 구체적 설정 절차):
```
질문 > Slack App 설정 단계를 알려줘
```

확인 포인트: `TOOL: knowledgeSearch` 선택 + `지식베이스가 비어있습니다` 로그

**Step 3: ingest 실행**

```
질문 > /ingest https://raw.githubusercontent.com/Veronikapj/wiki-agent/main/README.md
```

확인 포인트: `저장됨: sources/...md` 출력

**Step 4: ingest 후 같은 질문**

```
질문 > Slack App 설정 단계를 알려줘
```

확인 포인트:
- `TOOL: knowledgeSearch` 선택 (ingest 전과 동일한 Tool)
- ingest 전: `지식베이스가 비어있습니다` → ingest 후: README 파일 검색 결과 반환
- LLM 응답에 `1. api.slack.com/apps → Create New App`, `2. Socket Mode → Enable` 등 번호 리스트 출력

**Step 5: 결과 파일에 기록**

```markdown
## Step 6 — URL ingest

**편집 내용:** 실행 중 /ingest 명령 사용 + KnowledgeStore content.take(1500)

### Q5: Slack App 설정 단계를 알려줘
**ingest 전 응답:** > (붙여넣기)
**ingest 후 응답:** > (붙여넣기)
**판정:** ✅ / ❌ | 전/후 내용 차이, 번호 리스트 여부

---
```

**Step 6: 종료 + 초기화**

```bash
git checkout -- .
# .wiki/knowledge/ 파일도 초기화
rm -rf .wiki/knowledge/sources/
```

---

### Task 7: Step 7 검증 — config.yml repos 조정

**Files:**
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`
- Modify: `src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`
- Modify: `prompts/system-prompt.txt`
- Modify: `config.yml`
- Modify: `docs/verification/2026-05-03-execution-check.md`

**Step 1: Step 5 상태 세팅 + repos 1개로 좁히기**

KnowledgeTool.kt, GitHubWikiTool.kt, system-prompt.txt: Step 5와 동일.

`config.yml` — repos 1개:
```yaml
github:
  enabled: true
  repos:
    - piljubae/wiki-agent
```

**Step 2: 실행 + Q3 입력 (좁은 범위)**

```bash
./gradlew run
```

```
질문 > wiki-agent 아키텍처 어떻게 생겼어?
```

응답 품질 기록.

**Step 3: 종료 후 repos 3개로 넓히기**

`config.yml` — 관련 없는 레포 추가:
```yaml
github:
  enabled: true
  repos:
    - piljubae/wiki-agent
    - piljubae/wiki-agent-workshop
    - torvalds/linux
```

**Step 4: 실행 + 같은 Q3 입력 (넓은 범위)**

```bash
./gradlew run
```

```
질문 > wiki-agent 아키텍처 어떻게 생겼어?
```

확인 포인트:
- repos 1개 vs 3개 응답 품질 차이
- 관련 없는 레포 결과가 섞이는지

**Step 5: 결과 파일에 기록**

```markdown
## Step 7 — config.yml repos 조정

### Q3: wiki-agent 아키텍처 어떻게 생겼어?
**repos 1개 응답:** > (붙여넣기)
**repos 3개 응답:** > (붙여넣기)
**판정:** ✅ / ❌ | 범위 확장 시 노이즈 증가 여부

---
```

**Step 6: 종료 + 초기화**

```bash
git checkout -- .
```

---

### Task 8: 결과 집계 + 커밋

**Files:**
- Modify: `docs/verification/2026-05-03-execution-check.md` (집계 섹션 추가)

**Step 1: 결과 파일 하단에 집계 추가**

```markdown
---

## 집계

| Step | 판정 | 주요 이슈 |
|------|------|----------|
| Step 1 | ✅ / ❌ | |
| Step 2 | ✅ / ❌ | |
| Step 3 | ✅ / ❌ | |
| Step 4 | ✅ / ❌ | |
| Step 5 | ✅ / ❌ | |
| Step 6 | ✅ / ❌ | |
| Step 7 | ✅ / ❌ | |

## 발견된 이슈 목록

- (있으면 기록, 없으면 "없음")

## 다음 단계 (드라이런 A용 인풋)

- (Step별 개선 필요 항목)
```

**Step 2: 커밋**

```bash
git add docs/verification/
git commit -m "docs: add execution verification results"
```
