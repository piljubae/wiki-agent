# wiki-agent

슬랙에서 `@wiki 질문` 하면 Confluence 위키를 검색해 요약 + 링크를 스레드로 답변하는 Slack 봇입니다.

## 아키텍처

```
Slack (mention / slash command)
    │
    ▼
SlackBotGateway (Bolt Socket Mode)
    │
    ▼
OrchestratorAgent (Koog AIAgent)
    ├── ConfluenceTool ──► ConfluenceSearchAgent ──► Confluence REST API (CQL)
    ├── GitHubWikiTool ──► GitHubWikiSearchAgent ──► GitHub Search API  (github.enabled=true 시)
    └── VectorSearchTool ──► VectorSearchAgent ──► ChromaDB            (rag.enabled=true 시)

VectorIndexAgent ◄── /wiki reindex  (Confluence 전체 페이지 → ChromaDB 인덱싱)
```

OrchestratorAgent가 LLM을 통해 질문 의도를 파악하고 적절한 Tool을 선택합니다.  
GitHub Wiki와 RAG는 각각 `config.yml` 한 줄로 활성화/비활성화합니다.

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
   - `app_mentions:read`, `chat:write`, `commands`, `channels:read`
4. **Slash Commands** → `/wiki` 추가 (Socket Mode라 Request URL 불필요)
5. **Event Subscriptions** → Enable → Subscribe to bot events: `app_mention`
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

`.wikiq/config.yml` 파일에서 비민감 설정을 관리합니다:

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
    - owner/repo2

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

## GitHub Wiki (선택)

GitHub 레포지토리 Wiki를 추가 문서 소스로 연결할 수 있습니다.

### 활성화

`config.yml`에서:

```yaml
github:
  enabled: true
  repos:
    - owner/repo1        # wiki가 활성화된 GitHub 레포
    - myorg/myproject
```

`GITHUB_TOKEN`은 환경변수 또는 `.env`에 설정합니다. public 레포는 토큰 없이도 동작합니다.

### 동작 방식

- OrchestratorAgent가 기술 문서 / 코드 관련 질문에서 `githubWikiSearch` Tool을 선택합니다
- GitHub Search API로 `.wiki` 레포에서 관련 페이지를 찾고 내용을 요약합니다

## RAG (선택)

ChromaDB 기반 의미 검색을 추가할 수 있습니다.

### ChromaDB 실행

```bash
docker run -p 8000:8000 chromadb/chroma
```

### RAG 활성화

`config.yml`에서:

```yaml
rag:
  enabled: true
  chromaUrl: http://localhost:8000
  embeddingMode: LLM_EXPAND   # 추가 API 키 불필요
```

### 임베딩 모드

| 모드 | 방식 | 필요한 시크릿 |
|------|------|--------------|
| `LLM_EXPAND` | LLM으로 쿼리/문서 확장 + ChromaDB 내장 sentence-transformers | 없음 |
| `GOOGLE_EMBEDDING` | Google `text-embedding-004` API 명시적 벡터 | `GOOGLE_API_KEY` |

### 인덱싱

```
/wiki reindex           # Confluence 전체 페이지 재인덱싱
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

## 사용법

슬랙에서:

```
@wiki 배포 프로세스 알려줘
```

봇이 OrchestratorAgent를 통해 질문 의도를 파악하고, Confluence · GitHub Wiki · ChromaDB(RAG) 중 적절한 소스를 선택해 관련 문서 목록과 링크를 스레드로 답변합니다.

슬래시 커맨드:

```
/wiki config space DEV,PM,HR   # 검색 스페이스 설정
/wiki config space show         # 현재 설정 확인
/wiki reindex                   # RAG 재인덱싱 (rag.enabled=true 시)
/wiki reindex status            # 마지막 인덱싱 정보
```

## 테스트

```bash
./gradlew test
```

## 프로젝트 구조

```
src/main/kotlin/io/github/veronikapj/wiki/
├── Main.kt
├── agent/
│   ├── ConfluenceSearchAgent.kt    # CQL 검색 + 결과 포맷
│   ├── GitHubWikiSearchAgent.kt    # GitHub Search API + 결과 포맷
│   ├── OrchestratorAgent.kt        # Koog AIAgent (Tool 라우팅)
│   └── tool/
│       ├── ConfluenceTool.kt       # @Tool 래퍼 → ConfluenceSearchAgent
│       ├── GitHubWikiTool.kt       # @Tool 래퍼 → GitHubWikiSearchAgent
│       └── VectorSearchTool.kt     # @Tool 래퍼 → VectorSearchAgent
├── config/
│   ├── WikiConfig.kt               # 설정 데이터 클래스 (RagConfig, GithubConfig 포함)
│   ├── ConfigLoader.kt             # YAML 파서
│   └── SecretLoader.kt             # env → .env → config 시크릿 로딩
├── confluence/
│   └── ConfluenceClient.kt         # Confluence REST API (CQL)
├── github/
│   └── GitHubWikiClient.kt         # GitHub Search API + raw wiki 콘텐츠 조회
├── llm/
│   ├── ClaudeCodeLLMClient.kt      # Claude Code CLI 기반 LLM
│   └── LLMExecutorBuilder.kt       # LLM 프로바이더 팩토리
├── rag/
│   ├── ChromaClient.kt             # ChromaDB HTTP REST 클라이언트
│   ├── EmbeddingClient.kt          # LlmExpandClient + GoogleEmbeddingClient
│   ├── VectorIndexAgent.kt         # Confluence → ChromaDB 인덱싱
│   └── VectorSearchAgent.kt        # ChromaDB 검색
└── slack/
    ├── SlackBotGateway.kt          # Bolt Socket Mode 게이트웨이
    └── SlackConfigHandler.kt       # 슬래시 커맨드 핸들러
```
