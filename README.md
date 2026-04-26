# wiki-agent

슬랙에서 `@wiki 질문` 하면 Confluence · GitHub Wiki · ChromaDB(RAG) 를 검색해 요약 + 링크를 스레드로 답변하는 Slack 봇입니다.

## 아키텍처

```
Slack (mention / DM / slash command)
    │
    ▼
SlackBotGateway (Bolt Socket Mode)
    │   ├── SearchProgressListener  → "Confluence 검색 중..." 실시간 업데이트
    │   ├── 온보딩 플로우 (DM 첫 메시지)
    │   └── 리액션 피드백 (👍/👎)
    │
    ▼
OrchestratorAgent (Koog AIAgent)
    │   ├── ConversationStore  → 스레드별 대화 이력 (JSONL + 슬라이딩 윈도우 압축)
    │   └── ProjectMemory      → 팀/도메인 문맥 (.wiki/memory.md)
    │
    ├── ConfluenceTool  ──► ConfluenceSearchAgent  ──► Confluence REST API (CQL 2단계)
    ├── GitHubWikiTool  ──► GitHubWikiSearchAgent  ──► GitHub Search API
    └── VectorSearchTool ──► VectorSearchAgent     ──► ChromaDB (RAG fallback 포함)

VectorIndexAgent ◄── /wiki reindex  (Confluence 전체 페이지 → ChromaDB 인덱싱)
```

## 기술 스택

| 항목 | 버전 |
|------|------|
| Kotlin | 2.3 |
| Koog | 0.8.0 |
| Slack Bolt SDK | 1.46 |
| Ktor Client | 3.1.2 |
| ChromaDB | Docker (optional) |
| Logback | 1.5.13 |

## 사전 준비

### 1. Slack App 설정

1. [api.slack.com/apps](https://api.slack.com/apps) → **Create New App** → From scratch
2. **Socket Mode** 탭 → Enable → App-Level Token 생성 (`connections:write` scope) → `SLACK_APP_TOKEN` 메모
3. **OAuth & Permissions** → Bot Token Scopes 추가:
   - `app_mentions:read`, `chat:write`, `commands`, `channels:read`, `reactions:read`, `im:history`, `im:read`
4. **Slash Commands** → `/wiki` 추가 (Socket Mode라 Request URL 불필요)
5. **Event Subscriptions** → Enable → Subscribe to bot events: `app_mention`, `message.im`, `reaction_added`
6. **Install App** → 워크스페이스 설치 → `SLACK_BOT_TOKEN` 메모

### 2. Confluence API 토큰

[Atlassian 계정 보안 설정](https://id.atlassian.com/manage-profile/security/api-tokens)에서 토큰 생성 후 Base64 인코딩:

```bash
echo -n "your@email.com:your-api-token" | base64
```

### 3. GitHub 토큰 (선택)

GitHub Wiki 검색을 활성화하려면 [GitHub Personal Access Token](https://github.com/settings/tokens)을 생성하세요 (권한: `repo`). public 레포는 토큰 없이도 동작합니다.

## 설정

### 시크릿 관리

시크릿은 환경변수 → `.env` 파일 → `config.yml` 순서로 로드됩니다.  
`.env.example`을 복사해서 `.env`를 만드세요 (`.gitignore`에 등록됨):

```bash
cp .env.example .env
# .env 파일을 편집해서 실제 토큰을 입력
```

```
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
CONFLUENCE_TOKEN=<base64 email:api-token>
ANTHROPIC_API_KEY=sk-ant-...   # provider=ANTHROPIC 시
GOOGLE_API_KEY=AIza...         # provider=GOOGLE 또는 rag.embeddingMode=GOOGLE_EMBEDDING 시
GITHUB_TOKEN=ghp_...           # github.enabled=true 시 (public repo는 없어도 됨)
```

### config.yml

`.wikiq/config.yml`은 로컬 전용 파일입니다 (`.gitignore` 등록됨).  
`.wikiq/config.yml.example`을 복사해서 편집하세요:

```bash
cp .wikiq/config.yml.example .wikiq/config.yml
```

```yaml
model:
  provider: CLAUDE_CODE  # CLAUDE_CODE | ANTHROPIC | GOOGLE

confluence:
  baseUrl: https://yourcompany.atlassian.net
  spaces:
    - DEV
    - PM

slack: {}

github:
  enabled: false
  repos:
    - owner/repo1

rag:
  enabled: false
  chromaUrl: http://localhost:8000
  embeddingMode: LLM_EXPAND   # LLM_EXPAND | GOOGLE_EMBEDDING
```

### LLM 프로바이더

| 모드 | `provider` 설정 | 필요한 시크릿 |
|------|----------------|--------------|
| 로컬 (Claude Code CLI) | `CLAUDE_CODE` | 없음 |
| Claude API | `ANTHROPIC` + `name` | `ANTHROPIC_API_KEY` |
| Gemini API | `GOOGLE` + `name` | `GOOGLE_API_KEY` |

OrchestratorAgent는 기본 모델 호출 실패 시 `Haiku_4_5 → Sonnet_4` 순으로 자동 폴백합니다.

## 사용법

### 기본 검색

슬랙에서:

```
@wiki 배포 프로세스 알려줘
@wiki 신규 입사자 온보딩 절차는?
```

봇이 질문 의도를 파악해 Confluence · GitHub Wiki · ChromaDB 중 적절한 소스를 선택하고, 검색 중에는 실시간으로 진행 상태를 업데이트합니다.

DM으로도 사용 가능합니다 — 스레드 없이 채널 전체가 하나의 세션이 됩니다.

### 슬래시 커맨드

```
/wiki config space DEV,PM,HR   # 검색 스페이스 설정
/wiki config space show         # 현재 설정 확인
/wiki reindex                   # RAG 재인덱싱 (rag.enabled=true 시)
/wiki reindex status            # 마지막 인덱싱 시각 + 문서 수 확인
/wiki memory add <내용>         # 프로젝트 정보 저장
/wiki memory show               # 저장된 프로젝트 정보 확인
/wiki memory clear              # 프로젝트 정보 초기화
/wiki help                      # 도움말
```

## 주요 기능

### 대화 이력 (ConversationStore)

각 Slack 스레드를 독립 세션으로 관리합니다.

- **저장 위치**: `.wiki/sessions/{sessionId}.jsonl` — JSONL 형식으로 누적
- **컨텍스트 윈도우**: 최근 5턴을 LLM 프롬프트에 포함
- **슬라이딩 윈도우 압축**: 대화가 10턴을 초과하면 오래된 턴을 LLM으로 요약 후 최근 4턴만 보존
  - 요약본은 `{sessionId}.summary.md`에 저장되어 다음 호출부터 프롬프트 앞에 삽입됨

```
스레드 ts (threadTs) → sessionId
DM 채널 → "dm-{channelId}" 로 고정 세션
```

### 프로젝트 메모리 (ProjectMemory)

팀/도메인 문맥을 `.wiki/memory.md`에 저장해 모든 답변 품질을 높입니다.

```
/wiki memory add 모바일 앱(iOS/Android) 개발팀
/wiki memory add 클라이언트 = 모바일 앱 (고객 아님)
/wiki memory add 주요 검색: 배포 프로세스, 온보딩, 기술 문서
/wiki memory show
```

저장된 메모리는 OrchestratorAgent의 시스템 프롬프트에 자동으로 포함됩니다.

### 온보딩 플로우

DM에서 봇에게 처음 메시지를 보내면 4단계 설정 마법사가 실행됩니다:

1. 팀/조직 이름과 역할
2. 도메인 용어 (일반적 의미와 다른 것)
3. 주요 검색 대상 유형
4. Confluence 스페이스 선택 (팀 정보 기반 자동 추천)

수집된 정보는 ProjectMemory에 저장되어 이후 모든 검색에 반영됩니다. 온보딩이 완료되면 다음 메시지부터 바로 검색을 수행합니다.

### 실시간 검색 진행 표시

검색이 시작되면 답변 전에 진행 메시지를 먼저 전송하고, 답변이 완료되면 진행 메시지를 삭제합니다:

```
:mag: Confluence 검색 중...   ← 검색 시작 시 전송
                               ← 검색 완료 후 삭제 + 답변 전송
답변 내용...

📋 Confluence 3건 · GitHub Wiki 1건  ← 검색 소스 푸터
```

### 리액션 피드백

봇 답변에 👍 또는 👎 리액션을 남기면 로그에 기록됩니다. 향후 답변 품질 개선에 활용할 수 있습니다.

## 검색 전략

### CQL 2단계 검색

Confluence 검색은 두 단계로 진행됩니다:

1. **title 검색**: 원본 쿼리 + 동의어를 `title ~` 조건으로 검색 → 정확도 높은 결과 우선
2. **text 보완 검색**: title 결과가 부족하면 `text ~` 조건으로 본문 검색

동의어는 OR-clause로 병합해 단일 API 호출로 처리합니다 (N+1 → 1).

```
(title ~ "온보딩" OR title ~ "신규 입사자" OR title ~ "입사 가이드") AND type = page AND space IN ("DEV")
```

한국어 불용어(`그`, `이`, `을`, `를` 등)는 CQL 절에서 자동 제외되며, 특수문자는 `escapeCql()`로 이스케이프합니다.

### RAG fallback

CQL 검색 결과가 없을 때 자동으로 ChromaDB 벡터 검색으로 전환합니다 (5초 타임아웃). `rag.enabled=true` 상태여야 동작합니다.

## GitHub Wiki (선택)

```yaml
github:
  enabled: true
  repos:
    - owner/repo1
```

OrchestratorAgent가 기술 문서 / 코드 관련 질문에서 `githubWikiSearch` Tool을 선택합니다.

## RAG (선택)

### ChromaDB 실행

```bash
docker run -p 8000:8000 chromadb/chroma
```

### 임베딩 모드

| 모드 | 방식 | 필요한 시크릿 |
|------|------|--------------|
| `LLM_EXPAND` | LLM으로 쿼리/문서 확장 + ChromaDB 내장 sentence-transformers | 없음 |
| `GOOGLE_EMBEDDING` | Google `text-embedding-004` API 명시적 벡터 | `GOOGLE_API_KEY` |

### 인덱싱

```
/wiki reindex           # Confluence 전체 페이지 재인덱싱 (비동기, 즉시 응답)
/wiki reindex status    # 마지막 인덱싱 시각 + 문서 수 확인
```

## 실행

```bash
# Gradle로 실행
./gradlew run

# 또는 fat JAR 빌드 후 실행
./gradlew shadowJar
java -jar build/libs/wiki-agent-1.0.0-all.jar
```

## 테스트

```bash
# 유닛 테스트
./gradlew test

# 골든 데이터셋 평가 (실제 Confluence 연결 필요)
./gradlew evalTest
```

`evalTest`는 `src/test/resources/golden-dataset.json`에 정의된 질문-기대답변 쌍으로 검색 품질과 답변 품질을 측정합니다.

## 프로젝트 구조

```
src/main/kotlin/io/github/veronikapj/wiki/
├── Main.kt
├── agent/
│   ├── ConfluenceSearchAgent.kt    # CQL 2단계 검색 + 결과 포맷
│   ├── GitHubWikiSearchAgent.kt    # GitHub Search API + 결과 포맷
│   ├── OrchestratorAgent.kt        # Koog AIAgent (Tool 라우팅 + 대화 이력 + 메모리)
│   ├── SearchProgressListener.kt   # 검색 진행 상태 콜백 인터페이스
│   ├── SearchResult.kt             # 통합 검색 결과 모델 (CQL/RAG 공통)
│   └── tool/
│       ├── ConfluenceTool.kt       # @Tool 래퍼 → ConfluenceSearchAgent
│       ├── GitHubWikiTool.kt       # @Tool 래퍼 → GitHubWikiSearchAgent
│       ├── SourceTracker.kt        # 검색 소스별 건수 집계 + 푸터 포맷
│       └── VectorSearchTool.kt     # @Tool 래퍼 → VectorSearchAgent
├── config/
│   ├── WikiConfig.kt               # 설정 데이터 클래스 (RagConfig, GithubConfig 포함)
│   ├── ConfigLoader.kt             # YAML 파서
│   └── SecretLoader.kt             # env → .env → config 시크릿 로딩
├── confluence/
│   └── ConfluenceClient.kt         # Confluence REST API (CQL, escapeCql, listSpaces)
├── context/
│   ├── ConversationStore.kt        # JSONL 대화 이력 + 슬라이딩 윈도우 압축
│   └── ProjectMemory.kt            # .wiki/memory.md 기반 프로젝트 메모리
├── github/
│   └── GitHubWikiClient.kt         # GitHub Search API + raw wiki 콘텐츠 조회
├── llm/
│   ├── ClaudeCodeLLMClient.kt      # Claude Code CLI 기반 LLM
│   └── LLMExecutorBuilder.kt       # LLM 프로바이더 팩토리
├── rag/
│   ├── ChromaClient.kt             # ChromaDB HTTP REST 클라이언트
│   ├── EmbeddingClient.kt          # LlmExpandClient + GoogleEmbeddingClient
│   ├── VectorIndexAgent.kt         # Confluence → ChromaDB 인덱싱 (excerpt 기반)
│   └── VectorSearchAgent.kt        # ChromaDB 검색
└── slack/
    ├── SlackBotGateway.kt          # Bolt Socket Mode, 온보딩, 리액션 피드백
    └── SlackConfigHandler.kt       # 슬래시 커맨드 핸들러 (asyncExecutor 주입 가능)
```
