# wiki-agent-workshop 설계

**목적:** Koog 에이전트 세션 실습용 스켈레톤 레포. 참가자가 프롬프트와 @LLMDescription만 수정해서 에이전트 동작이 바뀌는 과정을 7단계로 체험.

**레포:** `github.com/piljubae/wiki-agent-workshop`

---

## 아키텍처

### 데이터 소스
- **GitHub Wiki** (`piljubae/wiki-agent`) — 토큰 없이 공개 접근, 기본 데이터
- **로컬 지식베이스** (`.wiki/knowledge/`) — Step 6에서 참가자가 URL ingest

Confluence / Slack / RAG / eval 완전 제거.

### 참가자가 편집할 것
- `prompts/system-prompt.txt` — Step 4/5에서 채움 (Kotlin 코드 건드릴 필요 없음)
- `src/.../tool/KnowledgeTool.kt` — `@LLMDescription("")` Step 2에서 채움
- `src/.../tool/GitHubWikiTool.kt` — `@LLMDescription("")` Step 2에서 채움
- `src/.../tool/PersonaTool.kt` — `@LLMDescription("")` 보너스에서 채움

### 참가자가 편집하지 않을 것
- Koog 인프라 (WorkshopAgent.kt, Main.kt)
- Tool 구현체 내부 로직
- build.gradle.kts

---

## 디렉토리 구조

```
wiki-agent-workshop/
├── build.gradle.kts                  # koog-agents-jvm + prompt-executor-google-client-jvm
├── settings.gradle.kts
├── config.yml                        # provider: GEMINI_CODE, github.repos 설정
├── prompts/
│   ├── system-prompt.txt             # 비어있음 (Step 4/5에서 채움)
│   └── persona-guide.md             # 보너스 실습 가이드
├── .wiki/
│   └── knowledge/                    # 비어있음 (Step 6에서 ingest)
└── src/main/kotlin/io/github/piljubae/workshop/
    ├── Main.kt                       # CLI 러너 (완성)
    ├── config/
    │   ├── WorkshopConfig.kt         # ModelProvider, GithubConfig, KnowledgeConfig
    │   └── ConfigLoader.kt           # config.yml 로드
    ├── agent/
    │   ├── WorkshopAgent.kt          # Orchestrator — system-prompt.txt 읽어서 주입
    │   └── tool/
    │       ├── KnowledgeTool.kt      # @LLMDescription("") ← Step 2
    │       ├── GitHubWikiTool.kt     # @LLMDescription("") ← Step 2
    │       └── PersonaTool.kt        # @LLMDescription("") ← 보너스
    ├── knowledge/
    │   ├── KnowledgeStore.kt         # .wiki/knowledge/ 파일 읽기
    │   └── IngestAgent.kt            # URL → 마크다운 변환 → 저장
    └── github/
        └── GitHubWikiClient.kt       # GitHub Wiki API 호출
```

---

## 브랜치 전략

| 브랜치 | 상태 | 비고 |
|--------|------|------|
| `main` | 스켈레톤 | @LLMDescription 비어있음, system-prompt.txt 비어있음 |
| `step-2` | @LLMDescription 작성 완성 | KnowledgeTool + GitHubWikiTool |
| `step-4` | system-prompt.txt 역할 + 출력 형식 추가 | |
| `step-5` | Tool 호출 강제 문구 추가 | "검색 없이 직접 답하지 마세요" |
| `step-6` | .wiki/knowledge/ 샘플 파일 포함 | ingest된 예시 문서 1-2개 |
| `step-7` | config.yml spaces 좁히기 예시 주석 추가 | |
| `step-bonus` | PersonaTool @LLMDescription 완성본 | "MZ 인턴" 예시 |

Step 1 / Step 3은 코드 변경 없이 실행해서 결과를 확인하는 단계 → 별도 브랜치 없음.

---

## 핵심 구현 포인트

### system-prompt.txt 파일 분리
WorkshopAgent가 시작 시 `prompts/system-prompt.txt`를 읽어 system prompt로 주입.
파일이 비어있으면 system prompt 없이 실행 → Step 1 동작.

```kotlin
val systemPrompt = File("prompts/system-prompt.txt").readText().trim()
// prompt { if (systemPrompt.isNotBlank()) system(systemPrompt) }
```

### config.yml (기본값)
```yaml
model:
  provider: GEMINI_CODE   # Google 계정으로 무료 실행
github:
  enabled: true
  repos:
    - piljubae/wiki-agent  # 기본 데이터소스
knowledge:
  path: .wiki/knowledge
```

### PersonaTool stub
```kotlin
@Tool("persona")
@LLMDescription("")  // ← 보너스: 여기에 페르소나 설명 붙여넣기
fun persona(
    @LLMDescription("사용자에게 전달할 최종 답변 내용")
    content: String
): String = content
```

WorkshopAgent의 system prompt에 "최종 답변은 반드시 persona tool을 통해 출력하세요" 추가
→ @LLMDescription을 바꾸면 LLM이 persona tool 호출 방식을 바꿈.

---

## persona-guide.md 구성

### 페르소나 예시 목록

| 이름 | 특징 |
|------|------|
| MZ 인턴 | 이모지 남발, "ㅋㅋ", "ㄹㅇ", 짧게 끊어 답변 |
| 갓생러 | 모든 답변에 생산성 팁. "이것도 루틴화하면 좋습니다" |
| 번아웃 5년차 | 귀찮음 최대. 최소한만. "...그냥 문서 보세요" |
| 너무 정중한 GPT | 매 답변마다 "물론이죠!", 면책 조항, 과도한 공손함 |
| 유튜버 편집장 | "자 오늘은!", 결론 먼저, "이거 레전드임", 썸네일 문체 |
| 시그마 개발자 | 감정 없음. 핵심만. 코드로 대답 가능하면 코드로. |
| 스타트업 대표 | "임팩트", "피봇", "그로스". 모든 걸 비즈니스 지표로 |
| 중2병 현자 | 어둡고 철학적. "진정한 답은... 스스로 찾아야 합니다" |
| NPC 알바 | 정해진 멘트만. "그건 제 담당이 아닌데요" |
| K-직장선배 | "야", 반말, "그것도 모르냐", 핵심은 알려줌 |

### @LLMDescription 생성 프롬프트 템플릿
Claude/Gemini에 붙여넣기용 프롬프트 제공.
출력: `@LLMDescription("...")` 에 바로 붙여넣을 수 있는 1-2문장.

---

## 세션 연결 (Step 별 편집 대상)

| Step | 편집 파일 | 핵심 메시지 |
|------|-----------|------------|
| 1 | 없음 (그냥 실행) | Tool 없으면 아무것도 안 한다 |
| 2 | KnowledgeTool.kt, GitHubWikiTool.kt (@LLMDescription) | LLM은 설명을 읽고 Tool을 고른다 |
| 3 | 없음 (로그 관찰) | Description 품질 = Tool 선택 정확도 |
| 4 | prompts/system-prompt.txt (역할 + 출력 형식) | 역할과 출력 형식 분리 |
| 5 | prompts/system-prompt.txt (Tool 호출 강제) | Tool 호출 유도 |
| 6 | URL ingest 명령 실행 | 컨텍스트 범위 제어 |
| 7 | config.yml (repos 조정) | 컨텍스트 많다고 좋은 게 아니다 |
| 보너스 | PersonaTool.kt (@LLMDescription) | @LLMDescription 하나로 동작이 달라진다 |
