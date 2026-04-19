# Koog 에이전트 세션 자료 설계

## 개요

**제목:** "내가 원하는 에이전트, 직접 만들 수 있다 — Koog로 배우는 AI 에이전트 설계"

**목적:** AI 에이전트 개발이 막연했거나 Koog를 처음 접하는 개발자들에게 에이전트 설계 방법론을 전달한다. wiki-agent를 예시로 삼아 개념과 실습을 연결한다.

**시간:** 2시간

**형식:** 슬라이드 목차 + 각 섹션 핵심 내용

---

## 대상

| 레벨 | 설명 | 얻어가는 것 |
|------|------|------------|
| AI 입문 | ChatGPT만 써봤음 | 에이전트 구성 요소 이해, 설계 방법론 |
| API 경험자 | OpenAI API 써봤음 | 단순 API 호출과 에이전트의 차이, 프롬프트 설계 원칙 |
| 프레임워크 경험자 | LangChain 등 써봤음 | Koog 아키텍처 비교, Kotlin-native 에이전트 패턴 |

**핵심 takeaway:** 에이전트 설계 방법론 + Koog 이해 + 내 케이스에 적용할 수 있는 감각

---

## 회사 컨텍스트

- 개발자: Claude (premium, 공식 지급)
- 비개발자 직군: Gemini (전사)
- → Provider 교체 슬라이드가 실제 시나리오로 연결됨: "Claude로 만들고 Gemini로 배포"

---

## 슬라이드 목차

### 1부 — 에이전트, 만들어본 적 있나요? (15분)

- 슬라이드 1: 에이전트의 3가지 구성 요소 (LLM + Tool + Loop)
- 슬라이드 2: 데모 — wiki-agent 실제 동작 시연
- 슬라이드 3: "이게 Koog로 만들어졌다"

### 2부 — Koog가 뭔가 (30분)

- 슬라이드 4: JetBrains가 만든 Kotlin-native 에이전트 프레임워크
- 슬라이드 5: 다른 프레임워크와 비교 (LangChain, LlamaIndex)
- 슬라이드 6: 핵심 개념 — AIAgent / Tool / A2A Protocol
- 슬라이드 7: Provider 교체 한 줄 — "Claude로 만들고 Gemini로 배포"
- 슬라이드 8: Orchestrator + Specialist 패턴

### 3부 — 내 에이전트 어떻게 설계하나 (60분)

- 슬라이드 9: 에이전트 설계 3단계 (반복업무 → Tool → 프롬프트) — wiki-agent 사례로 설명
- 슬라이드 10: 프롬프트 설계 원칙 1 — 역할과 출력 형식 분리 (wiki-agent OrchestratorAgent 프롬프트)
- 슬라이드 11: 프롬프트 설계 원칙 2 — 컨텍스트 범위 제어 (ConfluenceSearchAgent 프롬프트)
- 슬라이드 12: 프롬프트 설계 원칙 3 — Tool 호출 유도 vs 직접 답변
- 슬라이드 13: before/after 프롬프트 실제 예시 (wiki-agent 기반)
- 슬라이드 14: 직군별 에이전트 아이디어 (PM/HR/개발자/마케터) — wiki-agent 확장 가능성
- 슬라이드 15: 워크숍 — wiki-agent config.yml 설정 → Slack에서 직접 질문 → 결과 확인 (20분)

### 4부 — 마무리 (15분)

- 슬라이드 16: 로컬 시작 방법 (Claude Code → config.yml → 실행)
- 슬라이드 17: Q&A
