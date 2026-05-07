# Branch README Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 8개 step 브랜치(step-2, step-4, step-5, step-6, step-7, step-bonus, step-8, step-9)의 README.md를 각 단계 전용으로 교체한다.

**Architecture:** 각 브랜치를 checkout → README.md 전체 교체(이전 단계 ✅ + 현재 단계 상세 + 다음 힌트) → 커밋 순서로 진행. main 브랜치는 변경 없음. 공통 헤더/푸터는 아래 `## 공통 템플릿` 섹션을 기준으로 모든 브랜치에 동일하게 사용.

**Tech Stack:** git (branch checkout), markdown

---

## 공통 템플릿

모든 브랜치 README.md의 시작과 끝은 아래를 그대로 사용한다.

### 공통 헤더 (모든 브랜치 동일)

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---
```

### 공통 푸터 (모든 브랜치 동일)

```markdown
---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

---

## Task 1: step-2 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-2`)

**Step 1: checkout**
```bash
cd /Users/pilju.bae/projects/wiki-agent-workshop
git checkout step-2
```

**Step 2: README.md 전체 교체**

파일 전체를 아래 내용으로 교체한다. 공통 헤더로 시작하고 공통 푸터로 끝낸다.

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1** — `./gradlew run` 실행 후 LLM이 Tool 없이 직접 답변하는 것 확인

---

## ━━ Step 2 — @LLMDescription 작성

> **핵심 메시지:** LLM은 설명을 읽고 Tool을 고른다

**2부 연결:** Tool이란?

---

### 개념

`@LLMDescription`은 LLM에게 보내는 Tool 소개 문구입니다.

`WorkshopAgent`는 실행 시 Java reflection으로 이 값을 읽어 아래와 같은 routing prompt를 조립합니다:

```
사용 가능한 도구:
- knowledgeSearch: 로컬 지식베이스에서 문서를 검색합니다.   ← @LLMDescription 값
- githubWikiSearch: GitHub Wiki에서 문서를 검색합니다.     ← @LLMDescription 값

질문: Koog @Tool 어노테이션이 뭐야?
TOOL: <선택한 도구>
QUERY: <검색어>
```

`@LLMDescription`이 비어있으면 이 목록에 아예 포함되지 않습니다.  
LLM은 Tool의 **존재 자체를 모릅니다.**

관련 코드: `WorkshopAgent.kt`의 `toolDescription()` 함수와 routing prompt 빌더 부분을 확인하세요.

---

### 기본 액션

**`src/main/kotlin/io/github/piljubae/workshop/agent/tool/KnowledgeTool.kt`**

```kotlin
// Before
@LLMDescription("")  // ← Step 2에서 채우세요

// After (예시)
@LLMDescription("로컬 지식베이스에서 문서를 검색합니다. URL을 ingest해서 저장한 문서를 찾을 때 사용하세요.")
```

**`src/main/kotlin/io/github/piljubae/workshop/agent/tool/GitHubWikiTool.kt`**

```kotlin
// Before
@LLMDescription("")  // ← Step 2에서 채우세요

// After (예시)
@LLMDescription("GitHub Wiki에서 Koog 프레임워크 관련 문서를 검색합니다. @Tool 어노테이션, AIAgent 설정 등 기술 문서를 찾을 때 사용하세요.")
```

`./gradlew run` 재실행 후 질문하세요.

---

### 실험

> 💭 **질문:** Description이 짧으면 LLM이 어떻게 반응할까요?

**절차:**
1. 방금 채운 설명으로 `"Koog @Tool 어노테이션이 뭐야?"` 입력 → 로그의 `TOOL:` 값 확인
2. 설명을 `@LLMDescription("검색")` 으로 변경 → `./gradlew run` 재실행
3. 같은 질문 입력 → 로그 변화 관찰
4. 좋은 설명으로 복원

**무엇을 발견했나요?**

Tool 선택이 사라졌다면 — LLM이 "검색"이라는 단어만으로는 이 Tool이 언제 필요한지 판단하지 못한 겁니다.

→ `@LLMDescription`은 사람에게 쓰는 주석이 아니라 **LLM이 읽는 판단 근거**입니다.

---

### ✅ 확인 포인트

- [ ] 로그에 `TOOL: knowledgeSearch` 출력됨
- [ ] 로그에 `TOOL: githubWikiSearch` 출력됨
- [ ] 지식베이스 관련 질문 vs GitHub 기술 문서 질문에서 서로 다른 Tool 선택됨

---

### (선택) 챌린지

`PersonaTool.kt`의 `@LLMDescription`도 채우고 `WorkshopAgent`에 등록해보세요.  
→ 보너스 단계를 미리 체험할 수 있습니다. `prompts/persona-guide.md` 참고.

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `KnowledgeTool.kt` | `@LLMDescription` 채움 |
| `GitHubWikiTool.kt` | `@LLMDescription` 채움 |

---

## ▶ 다음: Step 3 — 설명 품질이 라우팅에 미치는 영향

설명을 일부러 나쁘게 바꾸면 LLM이 잘못된 Tool을 선택하거나 선택을 포기합니다. 위 실험에서 이미 체험했다면, `step-4` 브랜치로 이동하세요.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 핵심 섹션 존재 확인**
```bash
grep -c "━━ Step 2" README.md   # 1 이상
grep -c "가설\|질문\|발견" README.md  # 1 이상 (실험 섹션)
grep -c "확인 포인트" README.md  # 1 이상
```
Expected: 각 명령이 1 이상 출력

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-2 detailed README with concept, experiment, challenge"
```

---

## Task 2: step-4 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-4`)

**Step 1: checkout**
```bash
git checkout step-4
```

**Step 2: README.md 전체 교체**

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1** — LLM이 Tool 없이 직접 답변하는 것 확인
- ✅ **Step 2** — `@LLMDescription` 작성 → `TOOL:` 로그 출력 확인
- ✅ **Step 3** — 나쁜 설명으로 라우팅 실패 체험 → 복원

---

## ━━ Step 4 — system-prompt.txt로 역할과 출력 형식 설정

> **핵심 메시지:** 역할과 출력 형식이 답변의 일관성을 만든다

**2부 연결:** 프롬프트 원칙 1 — 역할과 출력 형식 분리

---

### 개념

Tool이 올바른 문서를 찾아도, **답변을 어떻게 구성할지**는 시스템 프롬프트가 결정합니다.

시스템 프롬프트가 없으면 LLM은 매번 다른 구조로 답변합니다. 같은 질문을 두 번 해도 형식이 다릅니다.

`WorkshopAgent`는 `prompts/system-prompt.txt`를 읽어 `buildSummaryPrompt()`에 주입합니다:

```kotlin
// WorkshopAgent.kt
private fun buildSummaryPrompt(question: String, searchResult: String): String {
    return buildString {
        if (systemPrompt.isNotBlank()) appendLine(systemPrompt)  // ← 여기에 주입
        appendLine("질문: $question")
        appendLine("검색 결과: $searchResult")
        appendLine("위 검색 결과를 바탕으로 답변하세요.")
    }
}
```

시스템 프롬프트에는 **역할**과 **출력 형식**을 분리해서 작성합니다:
- 역할: "당신은 X를 전문으로 답변하는 봇입니다"
- 출력 형식: 질문 유형별로 어떤 구조로 답할지

---

### 기본 액션

**`prompts/system-prompt.txt`** (현재 비어있음)에 아래 내용을 작성하세요:

```
당신은 Koog 프레임워크와 wiki-agent에 대해 답변하는 전문 봇입니다.
항상 검색 결과를 바탕으로 답변하세요.

답변 형식:
- 정의형 질문("X가 뭐야?"): 한 줄 정의 + 부연 2-3문장
- 절차형 질문("어떻게 해?"): 번호 리스트 (1. 2. 3.)
- 기타: 핵심 먼저, 세부사항 아래
```

`./gradlew run` 재실행 후 같은 질문을 두 번 해보세요.

---

### 실험

> 💭 **질문:** 같은 질문, 같은 Tool — 시스템 프롬프트만 다르면 달라질까요?

**절차:**
1. `system-prompt.txt`를 비운 상태로 `"@LLMDescription이 뭐야?"` 입력 → 답변 구조 메모
2. 위 내용으로 채운 뒤 재실행 → 같은 질문 → 비교
3. 정의형/절차형 질문을 각각 해보고 형식이 다르게 나오는지 확인

**무엇을 발견했나요?**

시스템 프롬프트 없이는 답변 구조가 매번 달라집니다. 역할과 형식을 지정하는 순간 봇은 일관된 방식으로 답변하기 시작합니다.

→ 시스템 프롬프트는 봇의 **페르소나와 응답 규칙**을 정의합니다.

---

### ✅ 확인 포인트

- [ ] 정의형 질문("Koog가 뭐야?") → 한 줄 정의 + 부연 구조로 답변
- [ ] 절차형 질문("Koog 설치 방법은?") → 번호 리스트로 답변
- [ ] 같은 질문을 두 번 해도 동일한 형식 유지

---

### (선택) 챌린지

시스템 프롬프트에 언어 제약을 추가해보세요:

```
모든 답변은 반드시 한국어로 작성하세요.
```

영어 질문을 해도 한국어로 답하는지 확인합니다.

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `KnowledgeTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `GitHubWikiTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `prompts/system-prompt.txt` | 역할 + 출력 형식 작성 (Step 4) |

---

## ▶ 다음: Step 5 — Tool 호출 강제

시스템 프롬프트에 한 줄 추가해서 LLM이 반드시 검색을 거치도록 강제합니다.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 검증**
```bash
grep -c "━━ Step 4" README.md
grep -c "buildSummaryPrompt" README.md
```
Expected: 각 1 이상

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-4 detailed README with concept, experiment, challenge"
```

---

## Task 3: step-5 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-5`)

**Step 1: checkout**
```bash
git checkout step-5
```

**Step 2: README.md 전체 교체**

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1** — LLM이 Tool 없이 직접 답변하는 것 확인
- ✅ **Step 2** — `@LLMDescription` 작성 → `TOOL:` 로그 출력 확인
- ✅ **Step 3** — 나쁜 설명으로 라우팅 실패 체험 → 복원
- ✅ **Step 4** — `system-prompt.txt`에 역할 + 출력 형식 추가

---

## ━━ Step 5 — Tool 호출 강제

> **핵심 메시지:** 강제하지 않으면 LLM은 저장된 문서 대신 학습 데이터로 답한다

**2부 연결:** 프롬프트 원칙 3 — Tool 호출 유도

---

### 개념

LLM은 Tool 없이도 답변할 수 있습니다. "배포 프로세스가 뭐야?"라고 물으면 인터넷에서 배운 일반적인 배포 절차를 그냥 말합니다.

우리 봇의 목적은 **실제 저장된 문서**를 찾아주는 겁니다. 학습 데이터 기반 답변이 나오면 안 됩니다.

시스템 프롬프트에 명시적 금지 구문을 추가하면 LLM이 반드시 검색을 거치게 됩니다:

```
검색 없이 직접 답변하지 마세요.
```

단, 이 구문의 강도에 따라 봇 동작이 달라집니다:

| 강도 | 구문 | 동작 |
|------|------|------|
| 강함 | `검색 없이 직접 답변하지 마세요.` | 인사에도 Tool 호출 시도 |
| 중간 | `가능하면 Tool을 사용해 검색 후 답변하세요.` | 검색 가능한 경우만 Tool 사용 |
| 약함 | `필요한 경우 Tool을 사용할 수 있습니다.` | LLM 재량에 맡김 |

---

### 기본 액션

**`prompts/system-prompt.txt`** (Step 4 내용에 한 줄 추가):

```
당신은 Koog 프레임워크와 wiki-agent에 대해 답변하는 전문 봇입니다.
항상 검색 결과를 바탕으로 답변하세요.
검색 없이 직접 답변하지 마세요. 반드시 knowledgeSearch 또는 githubWikiSearch를 사용하세요.

답변 형식:
- 정의형 질문("X가 뭐야?"): 한 줄 정의 + 부연 2-3문장
- 절차형 질문("어떻게 해?"): 번호 리스트 (1. 2. 3.)
- 기타: 핵심 먼저, 세부사항 아래
```

---

### 실험

> 💭 **질문:** 검색을 강제하지 않으면 봇이 항상 Tool을 쓸까요?

**절차:**
1. 강제 구문을 **제거**한 상태에서 `"배포 프로세스 알려줘"` 입력
   → 로그에 `TOOL:` 없이 바로 답변이 나오는지 확인
2. 강제 구문을 **추가**한 뒤 재실행 → 같은 질문
   → 로그에 `TOOL:` 출력 확인
3. 강제 추가 상태에서 `"안녕"` 인사 입력
   → 인사에도 Tool 호출을 시도하는지 확인

**무엇을 발견했나요?**

강제 구문 없이는 LLM이 "이 정도는 내가 아는데?"라고 판단해서 검색을 건너뜁니다. 봇에 저장된 문서가 있어도 그 문서는 참조되지 않습니다.

→ 내부 문서 봇은 **검색 강제가 기본값**이어야 합니다.

---

### ✅ 확인 포인트

- [ ] 강제 구문 있을 때: 로그에 항상 `TOOL:` 출력
- [ ] 강제 구문 없을 때: 일반 지식 질문에서 `TOOL:` 없이 직접 답변
- [ ] "안녕" 같은 인사에도 Tool 호출 시도 (강제 상태)

---

### (선택) 챌린지

강도를 `"가능하면 검색"` (중간)으로 바꾸고 비교해보세요:

```
가능하면 knowledgeSearch 또는 githubWikiSearch를 사용해 검색 후 답변하세요.
```

같은 질문에서 Tool 선택 빈도가 달라지나요?

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `KnowledgeTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `GitHubWikiTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `prompts/system-prompt.txt` | 역할 + 출력 형식 + Tool 강제 구문 (Step 4+5) |

---

## ▶ 다음: Step 6 — 내 문서 주입

`/ingest <URL>` 또는 `.wiki/knowledge/`에 파일을 직접 넣어 봇이 검색할 수 있는 문서를 추가합니다.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 검증**
```bash
grep -c "━━ Step 5" README.md
grep -c "학습 데이터" README.md
```

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-5 detailed README with concept, experiment, challenge"
```

---

## Task 4: step-6 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-6`)

**Step 1: checkout**
```bash
git checkout step-6
```

**Step 2: README.md 전체 교체**

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1** — LLM이 Tool 없이 직접 답변하는 것 확인
- ✅ **Step 2** — `@LLMDescription` 작성 → `TOOL:` 로그 출력 확인
- ✅ **Step 3** — 나쁜 설명으로 라우팅 실패 체험 → 복원
- ✅ **Step 4** — `system-prompt.txt`에 역할 + 출력 형식 추가
- ✅ **Step 5** — Tool 호출 강제 구문 추가

---

## ━━ Step 6 — 지식베이스에 문서 추가

> **핵심 메시지:** 봇은 ingest된 것만 안다. 문서를 넣는 행위가 봇의 지식 범위를 결정한다

**2부 연결:** 프롬프트 원칙 2 — 컨텍스트 범위 제어

---

### 개념

`KnowledgeTool`은 `.wiki/knowledge/` 폴더 안의 `.md` 파일을 검색합니다.

봇이 "모른다"고 하는 두 가지 이유:
1. GitHub Wiki에도, 지식베이스에도 없는 내용
2. 지식베이스에 아직 추가하지 않은 내용

2번은 `/ingest` 명령으로 해결됩니다:

```
[사용자] /ingest https://github.com/JetBrains/koog/blob/main/README.md
    ↓
IngestAgent: URL 접근 → HTML 파싱 → 텍스트 추출
    ↓
.wiki/knowledge/sources/koog-readme.md 저장
    ↓
다음 질문부터 knowledgeSearch 검색 대상에 포함
```

`.wiki/knowledge/` 폴더에 `.md` 파일을 직접 넣어도 동일하게 작동합니다.

> 이 브랜치에는 `.wiki/knowledge/wiki-agent/` 에 샘플 문서 7개가 미리 들어있습니다.

---

### 기본 액션

실행 중 CLI에서 URL을 ingest하세요:

```
/ingest https://github.com/JetBrains/koog/blob/main/README.md
```

또는 직접 파일 추가:

```bash
# .wiki/knowledge/ 폴더에 .md 파일 직접 생성
echo "# 온보딩 체크리스트\n\n1. 슬랙 채널 등록\n2. Confluence 접근 권한 요청" \
  > .wiki/knowledge/onboarding.md
```

추가 후 `"방금 저장한 내용 알려줘"` 로 검색이 되는지 확인합니다.

---

### 실험

> 💭 **질문:** ingest하기 전/후로 검색 결과가 달라질까요?

**절차:**
1. ingest **전**: `"Koog AIAgent 설정 방법이 뭐야?"` 입력 → 결과 확인
2. `/ingest https://github.com/JetBrains/koog/blob/main/README.md` 실행
3. ingest **후**: 같은 질문 입력 → 결과 비교

**무엇을 발견했나요?**

ingest 전에는 GitHub Wiki 검색 결과만 나옵니다. ingest 후에는 방금 저장한 문서 내용이 답변에 반영됩니다.

→ 봇의 지식은 ingest 시점의 스냅샷입니다. **문서를 넣는 행위가 곧 봇을 학습시키는 행위**입니다.

---

### ✅ 확인 포인트

- [ ] `/ingest` 실행 후 `.wiki/knowledge/` 폴더에 파일 생성 확인
- [ ] ingest한 URL의 내용으로 질문 → 관련 내용 답변 확인
- [ ] ingest하지 않은 내용으로 질문 → "찾을 수 없다" 또는 GitHub 검색으로 fallback

---

### (선택) 챌린지

직접 작성한 `.md` 파일을 `.wiki/knowledge/`에 넣고 질문해보세요:

```bash
cat > .wiki/knowledge/my-team-info.md << 'EOF'
# 팀 정보

우리 팀의 배포는 매주 화요일 오후 2시에 진행됩니다.
배포 담당자: 홍길동 (Slack: @hong)
EOF
```

`"배포는 언제 해?"` → 방금 넣은 내용이 나오는지 확인합니다.

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `KnowledgeTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `GitHubWikiTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `prompts/system-prompt.txt` | 역할 + 출력 형식 + Tool 강제 구문 (Step 4+5) |
| `.wiki/knowledge/wiki-agent/` | 샘플 문서 7개 포함 (Step 6) |

---

## ▶ 다음: Step 7 — config.yml로 검색 범위 조정

GitHub 검색 대상 레포를 늘리거나 줄여서 정확도 변화를 체험합니다.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 검증**
```bash
grep -c "━━ Step 6" README.md
grep -c "ingest" README.md
```

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-6 detailed README with concept, experiment, challenge"
```

---

## Task 5: step-7 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-7`)

**Step 1: checkout**
```bash
git checkout step-7
```

**Step 2: README.md 전체 교체**

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1** — LLM이 Tool 없이 직접 답변하는 것 확인
- ✅ **Step 2** — `@LLMDescription` 작성 → `TOOL:` 로그 출력 확인
- ✅ **Step 3** — 나쁜 설명으로 라우팅 실패 체험 → 복원
- ✅ **Step 4** — `system-prompt.txt`에 역할 + 출력 형식 추가
- ✅ **Step 5** — Tool 호출 강제 구문 추가
- ✅ **Step 6** — URL ingest 또는 파일 추가로 지식베이스 확장

---

## ━━ Step 7 — config.yml로 검색 범위 조정

> **핵심 메시지:** 컨텍스트가 많다고 좋은 게 아니다. 범위가 넓을수록 노이즈도 늘어난다

**2부 연결:** 프롬프트 원칙 2 심화 — 검색 우선순위

---

### 개념

`GitHubWikiTool`은 `config.yml`의 `github.repos` 목록에 있는 레포의 `.md` 파일을 검색합니다.

```yaml
# config.yml
github:
  repos:
    - piljubae/wiki-agent   # 이 레포만 검색
```

레포를 추가하면 검색 범위가 넓어집니다. 하지만 **관련 없는 레포가 늘어날수록 노이즈도 늘어납니다.**

예시:
- `piljubae/wiki-agent` 1개만: "배포 가이드 알려줘" → 위키에서 정확한 문서 반환
- 5개 레포 추가 후: "배포 가이드 알려줘" → 다른 레포의 배포 문서가 섞여 혼란

검색 우선순위는 코드에서 이렇게 동작합니다:
1. `knowledgeSearch` — `.wiki/knowledge/` 로컬 파일 (가장 신뢰도 높음)
2. `githubWikiSearch` — 설정된 GitHub 레포

LLM이 상황에 따라 어떤 Tool을 쓸지 결정합니다.

---

### 기본 액션

**`config.yml`** 에서 `github.repos` 목록을 변경하세요:

```yaml
github:
  repos:
    - piljubae/wiki-agent      # 줄이면 정확도 올라감
    # - 다른레포/이름          # 추가하면 범위 넓어짐 → 노이즈 증가
```

변경 후 `./gradlew run` 재실행 (설정은 시작 시 로드됨).

---

### 실험

> 💭 **질문:** 레포를 많이 추가하면 답변이 더 좋아질까요?

**절차:**
1. `repos`에 관련 없는 레포 2-3개 추가:
   ```yaml
   repos:
     - piljubae/wiki-agent
     - JetBrains/koog
     - piljubae/wiki-agent-workshop
   ```
2. `"wiki-agent 배포 방법 알려줘"` 입력 → 답변의 출처와 정확도 확인
3. `repos`를 `piljubae/wiki-agent` 하나로 줄이고 재실행 → 같은 질문 비교

**무엇을 발견했나요?**

레포가 많아질수록 질문과 관련 없는 문서가 검색 결과에 섞입니다. 답변이 오히려 부정확해질 수 있습니다.

→ Tool의 컨텍스트도 설계가 필요합니다. **"좁고 깊게"가 "넓고 얕게"보다 낫습니다.**

---

### ✅ 확인 포인트

- [ ] 레포 1개: 질문과 직접 관련된 문서만 나옴
- [ ] 레포 3개 이상: 관련 없는 내용이 섞이는 현상 관찰

---

### (선택) 챌린지

완전히 다른 주제의 GitHub 레포를 추가하고 특정 질문을 해보세요. 엉뚱한 결과가 얼마나 섞이는지 확인합니다.

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `KnowledgeTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `GitHubWikiTool.kt` | `@LLMDescription` 채움 (Step 2) |
| `prompts/system-prompt.txt` | 역할 + 출력 형식 + Tool 강제 (Step 4+5) |
| `.wiki/knowledge/wiki-agent/` | 샘플 문서 포함 (Step 6) |
| `config.yml` | 범위 조정 주석 추가 (Step 7) |

---

## ▶ 다음: 보너스 또는 Step 8 (심화)

**보너스** — `PersonaTool.kt`의 `@LLMDescription`을 채워서 봇의 말투를 바꿔보세요.  
**Step 8** — `WorkshopAgent.kt`에 대화 히스토리를 추가해서 후속 질문을 처리해보세요.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 검증**
```bash
grep -c "━━ Step 7" README.md
grep -c "노이즈" README.md
```

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-7 detailed README with concept, experiment, challenge"
```

---

## Task 6: step-bonus 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-bonus`)

**Step 1: checkout**
```bash
git checkout step-bonus
```

**Step 2: README.md 전체 교체**

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1~7** — 기본 실습 완료 (에이전트 실행 → @LLMDescription → 시스템 프롬프트 → 문서 주입 → 범위 조정)

---

## ━━ 보너스 — PersonaTool로 페르소나 설정

> **핵심 메시지:** `@LLMDescription` 하나가 봇의 성격을 바꾼다

**2부 연결:** Tool이란? (Step 2 복습)

---

### 개념

`PersonaTool`은 검색을 하지 않습니다. 대신 **답변 스타일**을 지정합니다.

```kotlin
class PersonaTool {
    @Tool("applyPersona")
    @LLMDescription("")  // ← 이 설명이 봇의 어투를 결정합니다
    fun applyPersona(@LLMDescription("적용할 페르소나") persona: String): String {
        return persona  // 반환값이 summary prompt에 추가됨
    }
}
```

LLM이 `PersonaTool`을 선택하면, `@LLMDescription`에 적힌 어투 지침이 답변에 반영됩니다.

Step 2에서 배운 것과 동일한 원리입니다: **`@LLMDescription`은 LLM이 읽는 지침**입니다.

---

### 기본 액션

**`src/main/kotlin/io/github/piljubae/workshop/agent/tool/PersonaTool.kt`**

```kotlin
// Before
@LLMDescription("")  // ← 보너스에서 채우세요

// After (MZ 인턴 예시)
@LLMDescription("이 Tool을 통해 답변할 때는 MZ 인턴처럼 말합니다. 이모지를 2-3개 이상 쓰고, 'ㅋㅋ', 'ㄹㅇ', '레전드' 같은 유행어를 섞습니다.")
```

`./gradlew run` 재실행 후 질문해보세요.

---

### 실험

> 💭 **질문:** `@LLMDescription`에 어투를 지정하면 LLM이 따라갈까요?

**절차:**
1. "MZ 인턴" 페르소나 적용 → `"Koog가 뭐야?"` 입력 → 어투 확인
2. `@LLMDescription`을 "냉소적인 시니어" 스타일로 변경:
   ```kotlin
   @LLMDescription("이 Tool을 통해 답변할 때는 냉소적인 시니어 개발자처럼 말합니다. 짧고 직설적으로, 약간의 한숨 섞인 어투로 답합니다.")
   ```
3. 재실행 → 같은 질문 → 어투 비교

**무엇을 발견했나요?**

`@LLMDescription`의 텍스트를 바꾸는 것만으로 봇의 성격이 완전히 달라집니다. Tool의 description이 곧 프롬프트 역할을 합니다.

→ Step 2의 "판단 근거"와 같은 원리: LLM은 description을 읽고 행동을 결정합니다.

---

### ✅ 확인 포인트

- [ ] 페르소나 적용 전/후 같은 질문의 어투 차이 확인
- [ ] 서로 다른 두 페르소나를 적용하고 답변 스타일 비교

---

### (선택) 챌린지

`prompts/persona-guide.md`를 참고해서 나만의 페르소나를 설계해보세요.

예시 아이디어: 교수님 스타일, 해적, 냉정한 AI, 열정 넘치는 인턴...

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `PersonaTool.kt` | `@LLMDescription` MZ 인턴 페르소나로 채움 |

---

## ▶ 다음: Step 8 — 대화 히스토리 (심화)

`WorkshopAgent.kt`를 직접 수정해서 후속 질문에서 앞 맥락이 유지되도록 구현합니다.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 검증**
```bash
grep -c "━━ 보너스" README.md
grep -c "PersonaTool" README.md
```

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-bonus detailed README with concept, experiment, challenge"
```

---

## Task 7: step-8 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-8`)

**Step 1: checkout**
```bash
git checkout step-8
```

**Step 2: README.md 전체 교체**

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1~7** — 기본 실습 완료
- ✅ **보너스** — PersonaTool 페르소나 적용

---

## ━━ Step 8 — 대화 히스토리 (심화)

> **핵심 메시지:** LLM은 매번 새 대화다. 히스토리를 프롬프트에 넣는 것만으로 기억하는 것처럼 동작한다

**2부 연결:** (심화) 대화 기록 — 컨텍스트 관리

---

### 개념

LLM API는 **stateless**입니다. 각 호출은 이전 호출을 전혀 모릅니다.

```
질문 1: "wiki-agent 기획서 v1이 뭐야?"
→ LLM: "wiki-agent v1은 ..."

질문 2: "그거 v2랑 뭐가 달라?"
→ LLM: "그거"가 무엇인지 모름 → 엉뚱한 답변
```

해결책: 이전 대화를 프롬프트에 포함시키면 됩니다.

```kotlin
// buildSummaryPrompt 안에서:
if (history.isNotEmpty()) {
    appendLine("이전 대화:")
    history.forEach { (q, a) -> appendLine("Q: $q\nA: ${a.take(150)}") }
    appendLine()
}
```

`ArrayDeque`로 최대 3턴을 유지하고 오래된 것부터 제거합니다 (sliding window). 너무 많은 히스토리는 프롬프트 토큰을 낭비하고 오히려 혼란을 줍니다.

---

### 기본 액션

**`src/main/kotlin/io/github/piljubae/workshop/agent/WorkshopAgent.kt`**

아래 세 곳을 수정합니다:

**1. 히스토리 필드 추가 (클래스 최상단):**
```kotlin
private val history = ArrayDeque<Pair<String, String>>() // (질문, 답변)
```

**2. buildSummaryPrompt()에 히스토리 주입 (systemPrompt 이후):**
```kotlin
if (history.isNotEmpty()) {
    appendLine("이전 대화:")
    history.forEach { (q, a) -> appendLine("Q: $q\nA: ${a.take(150)}") }
    appendLine()
}
```

**3. answer() 마지막에 히스토리 업데이트:**
```kotlin
history.addLast(question to result)
if (history.size > 3) history.removeFirst()
```

---

### 실험

> 💭 **질문:** 히스토리 없이 "그거 더 자세히"라고 하면 어떻게 될까요?

**절차:**
1. 히스토리 **미구현** 상태 (`main` 브랜치)에서:
   - `"wiki-agent 기획서 v1이 뭐야?"` 입력
   - `"그거 v2랑 뭐가 달라?"` 입력 → 맥락 없는 답변 확인
2. 히스토리 **구현 후** (이 브랜치):
   - 같은 시퀀스 입력 → 맥락 유지 확인
3. 4번째 질문에서 첫 번째 질문 맥락이 사라지는지 확인 (3턴 한계)

**무엇을 발견했나요?**

히스토리를 프롬프트에 넣는 것만으로 LLM이 "기억"하는 것처럼 동작합니다. 실제로 기억하는 게 아니라, 매번 이전 대화를 다시 읽는 겁니다.

→ **상태 관리 = 프롬프트 관리**. LLM의 기억력은 프롬프트 길이만큼입니다.

---

### ✅ 확인 포인트

- [ ] 2번째 질문에서 1번째 맥락 유지됨
- [ ] 3번 연속 후속 질문 처리됨
- [ ] 4번째 질문에서 1번째 맥락이 사라짐 (정상 동작)

---

### (선택) 챌린지

히스토리 크기를 5로 변경해보세요:

```kotlin
if (history.size > 5) history.removeFirst()
```

더 긴 맥락 유지와 토큰 소비 증가를 체험해보세요.

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `WorkshopAgent.kt` | `history: ArrayDeque` 필드 + `buildSummaryPrompt()` 히스토리 주입 + `answer()` 업데이트 로직 추가 |

---

## ▶ 다음: Step 9 — 내 Tool 만들기

`MyTool.kt`를 직접 작성해서 나만의 에이전트 능력을 추가합니다.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 검증**
```bash
grep -c "━━ Step 8" README.md
grep -c "ArrayDeque\|sliding window\|stateless" README.md
```

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-8 detailed README with concept, experiment, challenge"
```

---

## Task 8: step-9 브랜치 README

**Files:**
- Modify: `README.md` (on branch `step-9`)

**Step 1: checkout**
```bash
git checkout step-9
```

**Step 2: README.md 전체 교체**

```markdown
# wiki-agent-workshop

> Koog 에이전트 실습 — `@LLMDescription` 하나로 봇 동작이 바뀌는 7단계 + 심화 2단계

---

## 빠른 시작

```bash
git clone https://github.com/piljubae/wiki-agent-workshop.git
cd wiki-agent-workshop
./gradlew run
```

> 다른 단계 정답을 보려면: `git checkout step-2` (step-2 ~ step-9, step-bonus)

---

## 완료한 단계

- ✅ **Step 1~7** — 기본 실습 완료
- ✅ **보너스** — PersonaTool 페르소나 적용
- ✅ **Step 8** — 대화 히스토리 구현

---

## ━━ Step 9 — 내 Tool 만들기 (심화)

> **핵심 메시지:** Tool 하나 = 에이전트 능력 하나. 오늘 배운 구조 그대로 내 케이스에

**2부 연결:** Tool이란? + LLM이 Tool 고르는 순간

---

### 개념

지금까지 `KnowledgeTool`, `GitHubWikiTool`, `PersonaTool`을 써왔습니다. 셋 다 구조는 동일합니다:

```kotlin
class XxxTool {
    @Tool("toolName")           // Tool 이름 (LLM이 TOOL: 다음에 출력하는 값)
    @LLMDescription("설명")    // LLM이 읽는 Tool 소개
    fun toolMethod(
        @LLMDescription("파라미터 설명")
        query: String
    ): String {
        // 실제 로직
        return "결과"
    }
}
```

새 Tool을 추가하는 절차:
1. `MyTool.kt` 구현 (`@Tool` + `@LLMDescription` + 함수 로직)
2. `Main.kt`에 인스턴스 생성
3. `WorkshopAgent` 생성 시 파라미터 전달

3단계면 에이전트가 새 능력을 갖게 됩니다.

---

### 기본 액션

**`src/main/kotlin/io/github/piljubae/workshop/agent/tool/MyTool.kt`** (이미 생성됨)

```kotlin
class MyTool {
    @Tool("mySearch")
    @LLMDescription("")  // ← 내 Tool이 하는 일을 설명하세요
    fun mySearch(
        @LLMDescription("검색할 내용이나 키워드")
        query: String,
    ): String {
        // TODO: 원하는 로직 구현
        return "MyTool 결과: $query (TODO: 실제 로직 구현)"
    }
}
```

`@LLMDescription`을 채우고 로직을 구현한 뒤 `Main.kt`에 등록하세요.

아이디어가 없다면 `prompts/my-tool-guide.md`를 참고하세요.

---

### 실험

> 💭 **질문:** `@LLMDescription`을 비워두면 내 Tool이 선택될까요?

**절차:**
1. `@LLMDescription("")` 빈 상태로 `Main.kt`에 등록 → 내 Tool 관련 질문 입력
   → 로그에 `TOOL: mySearch`가 나오는지 확인
2. `@LLMDescription`에 명확한 설명 추가 → 재실행 → 같은 질문
   → 선택 여부 비교

**무엇을 발견했나요?**

Step 2에서 배운 것과 똑같습니다. Description 없이는 Tool이 선택되지 않습니다.

→ **오늘 배운 모든 것이 여기서 연결됩니다.** `@LLMDescription` → 라우팅 → Tool 실행 → 답변. 이 구조 위에 Tool만 교체하면 전혀 다른 봇이 됩니다.

---

### ✅ 확인 포인트

- [ ] 로그에 `TOOL: mySearch` 출력됨
- [ ] 내 Tool에 맞는 질문 → 선택됨 / 다른 질문 → 선택 안 됨
- [ ] `@LLMDescription("")` 빈 상태 → 선택 안 됨 (Step 2 복습)

---

### (선택) 챌린지

`mySearch`에 두 번째 파라미터를 추가해보세요:

```kotlin
fun mySearch(
    @LLMDescription("검색할 내용")
    query: String,
    @LLMDescription("언어 코드 (ko, en 등), 기본값 ko")
    language: String = "ko",
): String {
    // language에 따라 다른 처리
}
```

LLM이 두 번째 파라미터도 상황에 맞게 채우는지 확인합니다.

---

### 이 브랜치에서 변경된 것

| 파일 | 변경 내용 |
|------|---------|
| `WorkshopAgent.kt` | `history` 필드 + `myTool?: MyTool` 파라미터 추가 (Step 8+9) |
| `MyTool.kt` | `@LLMDescription` + `mySearch()` 템플릿 작성 |
| `Main.kt` | `myTool` 인스턴스 생성 + `WorkshopAgent`에 전달 |

---

## 완성!

모든 단계를 마쳤습니다.

오늘 만든 구조:
- **Tool** — `@Tool` + `@LLMDescription` + 함수 구현
- **라우팅** — LLM이 description 읽고 Tool 선택
- **프롬프트** — 역할 + 형식 + 강제
- **히스토리** — 프롬프트에 이전 대화 포함

이 구조 위에 Tool만 교체하면 됩니다.  
내 팀의 반복 작업 하나를 Tool로 만들어보세요.

---

## 브랜치 정답지

| 브랜치 | 내용 |
|--------|------|
| `main` | 스켈레톤 — Step 1 시작 상태 |
| `step-2` | KnowledgeTool + GitHubWikiTool @LLMDescription 완성 |
| `step-4` | system-prompt.txt 역할 + 출력 형식 |
| `step-5` | Tool 호출 강제 추가 |
| `step-6` | 샘플 지식베이스 파일 포함 |
| `step-7` | config.yml 범위 조정 주석 |
| `step-bonus` | PersonaTool MZ 인턴 페르소나 |
| `step-8` | 대화 히스토리 (3턴) |
| `step-9` | MyTool 템플릿 + 가이드 |

---

## 참고 문서

| 문서 | 내용 | 관련 단계 |
|------|------|---------|
| [`docs/setup.md`](docs/setup.md) | Gemini CLI 설치 및 첫 실행 | Step 1 전 |
| [`docs/annotations.md`](docs/annotations.md) | @Tool / @LLMDescription 상세 가이드 | Step 2, 3 |
| [`docs/prompt-role-format.md`](docs/prompt-role-format.md) | 역할과 출력 형식 설계 원칙 | Step 4 |
| [`docs/prompt-tool-forcing.md`](docs/prompt-tool-forcing.md) | Tool 호출 강제 전략 | Step 5 |
| [`docs/config.md`](docs/config.md) | config.yml 전체 옵션 | Step 7 |
| [`docs/what-is-agent.md`](docs/what-is-agent.md) | 에이전트란 무엇인가 (개념) | 배경 |
| [`docs/koog.md`](docs/koog.md) | Koog 프레임워크 소개 | 배경 |
| [`docs/toolregistry.md`](docs/toolregistry.md) | ToolRegistry — Tool 등록 방법 | Step 9 |
| [`docs/agent-vs-api.md`](docs/agent-vs-api.md) | 에이전트 vs 단순 API 호출 비교 | 배경 |
```

**Step 3: 검증**
```bash
grep -c "━━ Step 9" README.md
grep -c "완성\!" README.md
```

**Step 4: 커밋**
```bash
git add README.md
git commit -m "docs: add step-9 detailed README with concept, experiment, challenge"
```

---

## Task 9: 모든 브랜치 push

**Step 1: main으로 복귀**
```bash
git checkout main
```

**Step 2: 모든 브랜치 push**
```bash
git push origin step-2 step-4 step-5 step-6 step-7 step-bonus step-8 step-9
```

Expected: 각 브랜치 push 성공 메시지

**Step 3: 결과 확인**
```bash
git branch -r | grep -E "step-[0-9]|step-bonus"
```
Expected: 8개 브랜치 모두 origin에 존재

---

## 검증 방법

각 브랜치 README가 올바른지 확인하려면:

```bash
for branch in step-2 step-4 step-5 step-6 step-7 step-bonus step-8 step-9; do
  echo "=== $branch ==="
  git show $branch:README.md | grep -E "━━ Step|━━ 보너스|완료한 단계|확인 포인트|실험" | head -5
  echo ""
done
```
