# wiki-agent

슬랙에서 `@wiki 질문` 하면 로컬 지식베이스 · Confluence · GitHub Wiki에서 관련 문서를 찾아 요약 + 링크를 스레드로 답변하는 Slack 봇입니다.

## 아키텍처

```
Slack (mention / slash command / DM)
    │
    ▼
SlackBotGateway (Bolt Socket Mode)
    │   DM URL 감지 → IngestAgent (자동 저장)
    ▼
OrchestratorAgent (Koog AIAgent)
    ├── KnowledgeTool   ──► KnowledgeStore (.wiki/knowledge/)       ← 1순위
    ├── ConfluenceTool  ──► ConfluenceSearchAgent                   ← 2순위
    │                          ├─ 1단계: title 검색 (설정 스페이스)  ──► Confluence REST API
    │                          ├─ 2단계(parallel): text 검색        ──► Confluence REST API
    │                          │                   스페이스 확장     ──► Confluence REST API
    │                          │                   RAG fallback      ──► ChromaDB
    │                          └─ 3단계: SearchStage 가중치 랭킹 + 중복 제거
    ├── GitHubWikiTool  ──► GitHubWikiSearchAgent ──► GitHub Wiki   ← 3순위 (github.enabled=true 시)
    └── VectorSearchTool ──► VectorSearchAgent ──► ChromaDB         ← 4순위 (rag.enabled=true 시)

IngestAgent ◄── /wiki ingest <URL>  (URL → LLM 컴파일 → KnowledgeStore + ChromaDB)
LintAgent   ◄── /wiki lint          (지식베이스 모순·고아 감지)
VectorIndexAgent ◄── /wiki reindex  (Confluence 전체 페이지 → ChromaDB 인덱싱)
```

OrchestratorAgent가 LLM을 통해 질문 의도를 파악하고 적절한 Tool을 선택합니다.  
검색 우선순위: **로컬 지식베이스 → Confluence → GitHub Wiki → RAG**

## 검색 플로우 (ConfluenceSearchAgent)

```
질문 → cleanQuery() → 제목 검색 (설정 스페이스)
                          │
               ≥ 3건?  ──► 조기 반환 (API 1회)
                          │
               < 3건?  ──► 병렬 실행 ──► text 검색
                                     ──► 전체 스페이스 제목 확장
                                     ──► RAG (5초 타임아웃)
                          │
                          ▼
                  SearchStage 가중치 랭킹 + pageId 중복 제거
```

`cleanQuery()`는 "알려줘", "어디 있어?" 같은 대화형 접미사와 CQL을 깨뜨리는 특수문자 `[]|~{}()_`를 자동으로 제거합니다.

### SearchStage 가중치

| Stage | 의미 | score |
|-------|------|-------|
| `TITLE_MATCH` | 설정 스페이스 제목 직접 매칭 | 1.0 |
| `SPACE_EXPANSION` | 전체 스페이스 제목 확장 | 0.8 |
| `TEXT_MATCH` | 본문 텍스트 검색 | 0.6 |
| `RAG` | ChromaDB 벡터 유사도 | 0.5 |

## 모듈 구조

Gradle 멀티모듈로 점진 분리 중 ([모듈화 로드맵](docs/plans/2026-06-09-modularization-roadmap.md)). 의존성은 안쪽(공통 기반)에서 바깥(앱)으로 단방향으로만 흐른다.

| 모듈 | 책임 | README |
|------|------|--------|
| `(root)` | Slack 게이트웨이 · 에이전트 조립 (Composition Root) | — |
| `:context` | 대화 이력 · 프로젝트 메모리 영속화 | [README](context/README.md) |
| `:config` | 설정 로딩 · 시크릿 해석 (shared kernel) | [README](config/README.md) |
| `:callgraph-plugin` | 컴파일 시 호출 그래프를 SQLite로 추출하는 Kotlin 컴파일러 플러그인 | [README](callgraph-plugin/README.md) |

> 나머지 패키지(`agent`, `slack`, `knowledge`, `onboarding`, `confluence`, `github`, `rag`, `llm`)는 아직 루트 모듈에 있으며 로드맵 순서대로 분리 예정.

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

### 0. 실행 환경 요건

| 항목 | 최소 버전 | 확인 방법 | 설치 |
|------|----------|----------|------|
| JDK | 17 | `java -version` | `brew install --cask temurin` 또는 [Temurin](https://adoptium.net) |
| Git | — | `git --version` | `brew install git` |

> `provider: GEMINI_CODE` 사용 시 Node.js 18 이상 필요: `brew install node`

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

## 코드 검색 + PR 이력 (선택)

Kurly Android 소스코드와 PR 기반 작업 이력을 검색할 수 있습니다.

### 필수 조건
- `rag.enabled: true` (ChromaDB 필요)
- `GITHUB_TOKEN` — `repo` scope 필요 (private repo 접근)

### 활성화

`config.yml`:
```yaml
github:
  codeRepos:
    - thefarmersfront/kurly-android
  codeSearch:
    branch: develop
    pollIntervalMinutes: 60    # 0이면 비활성
    webhookPort: 8080          # 0이면 비활성

rag:
  enabled: true
  chromaUrl: http://localhost:8000
```

### 초기 인덱싱
```
/wiki reindex-code    # 전체 코드베이스 인덱싱 (최초 1회)
```

### 사용법
```
@wiki BannerViewModel 어디있어?
@wiki panelCode 어디서 쓰여?
@wiki KMA-7275 어떤 작업이었어?
@wiki 배너 클릭 이벤트 누가 만들었어?
```

DM에 PR URL을 붙여넣으면 자동 인덱싱됩니다:
```
https://github.com/thefarmersfront/kurly-android/pull/7400
```

### Webhook 설정 (선택)
PR merge 시 자동 인덱싱하려면 GitHub Repo Settings → Webhooks에서:
- Payload URL: `http://your-server:8080/webhook/github`
- Content type: `application/json`
- Events: `Pull requests`

## RAG (선택)

ChromaDB 기반 의미 검색을 추가할 수 있습니다. RAG는 ConfluenceSearchAgent의 2단계 병렬 fallback으로 자동 실행됩니다 — CQL 결과가 부족할 때 title/text 검색과 동시에 5초 타임아웃으로 실행됩니다.

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

봇이 OrchestratorAgent를 통해 질문 의도를 파악하고, 로컬 지식베이스 · Confluence · GitHub Wiki · ChromaDB(RAG) 순으로 검색해 관련 문서 목록과 링크를 스레드로 답변합니다.

슬래시 커맨드:

```
/wiki ingest <URL>              # URL 내용을 지식베이스에 저장
/wiki lint                      # 지식베이스 품질 검사 (모순·고아 감지)
/wiki config space DEV,PM,HR   # 검색 스페이스 설정
/wiki config space show         # 현재 설정 확인
/wiki reindex                   # RAG 재인덱싱 (rag.enabled=true 시)
/wiki reindex status            # 마지막 인덱싱 정보
/wiki memory add <내용>         # 프로젝트 컨텍스트 추가 (도메인 용어, 팀 정보 등)
/wiki memory show               # 저장된 프로젝트 메모리 확인
/wiki memory clear              # 프로젝트 메모리 초기화
```

### 지식베이스

URL을 ingest하면 LLM이 내용을 분석해 `concepts/`, `entities/`, `sources/` 구조로 `.wiki/knowledge/`에 저장합니다. 이후 `@wiki 질문` 시 Confluence보다 먼저 검색됩니다.

DM에 URL을 붙여넣으면 `/wiki ingest` 없이 자동으로 저장됩니다.

```
/wiki ingest https://팀내문서.example.com/배포가이드
```

### 프로젝트 메모리

봇이 도메인 용어와 팀 컨텍스트를 기억하도록 학습시킬 수 있습니다:

```
/wiki memory add 우리 팀은 Spring Boot 3.x + Kotlin을 사용합니다
/wiki memory add 배포 프로세스 담당: 인프라팀 (김철수)
```

저장된 메모리는 LLM 동의어 생성과 답변 생성 시 컨텍스트로 주입됩니다.

## 테스트

```bash
# 유닛 테스트
./gradlew test

# 검색 품질 평가 (실제 Confluence 연결 필요, CONFLUENCE_TOKEN 환경변수 필요)
./gradlew evalTest
```

`evalTest`는 147개 자동 생성 골든 케이스로 Recall@K, MRR을 측정하고 `docs/eval/` 에 리포트를 저장합니다.

## 프로젝트 구조

```
src/main/kotlin/io/github/veronikapj/wiki/
├── Main.kt
├── agent/
│   ├── ConfluenceSearchAgent.kt    # 3단계 검색 플로우 + cleanQuery + SearchStage 랭킹
│   ├── GitHubWikiSearchAgent.kt    # GitHub Search API + 결과 포맷
│   ├── OrchestratorAgent.kt        # Koog AIAgent (Tool 라우팅 + 대화 이력 + 프로젝트 메모리)
│   ├── SearchResult.kt             # SearchResult + SearchStage(score) enum
│   ├── SearchProgressListener.kt   # 검색 진행 콜백 인터페이스
│   └── tool/
│       ├── ConfluenceTool.kt       # @Tool 래퍼 → ConfluenceSearchAgent
│       ├── GitHubWikiTool.kt       # @Tool 래퍼 → GitHubWikiSearchAgent
│       ├── KnowledgeTool.kt        # @Tool 래퍼 → KnowledgeStore (1순위 검색)
│       ├── SourceTracker.kt        # 검색 소스 출처 추적
│       └── VectorSearchTool.kt     # @Tool 래퍼 → VectorSearchAgent
├── knowledge/
│   ├── KnowledgeStore.kt           # .wiki/knowledge/ 파일 I/O (thread-safe)
│   ├── IngestAgent.kt              # URL fetch → LLM 컴파일 → KnowledgeStore + ChromaDB
│   ├── LintAgent.kt                # 지식베이스 모순·고아·오래됨 감지
│   └── KnowledgeTool.kt            # @Tool 래퍼 → KnowledgeStore 키워드 검색
├── config/
│   ├── WikiConfig.kt               # 설정 데이터 클래스 (RagConfig, GithubConfig 포함)
│   ├── ConfigLoader.kt             # YAML 파서
│   └── SecretLoader.kt             # env → .env → config 시크릿 로딩
├── confluence/
│   └── ConfluenceClient.kt         # Confluence REST API (searchByTitle / searchByText / CQL)
├── context/
│   ├── ConversationStore.kt        # JSONL 기반 세션별 대화 이력 영속화
│   └── ProjectMemory.kt            # .wiki/memory.md 기반 프로젝트 메모리
├── github/
│   └── GitHubWikiClient.kt         # GitHub Search API + raw wiki 콘텐츠 조회
├── llm/
│   ├── ClaudeCodeLLMClient.kt      # Claude Code CLI 기반 LLM
│   └── LLMExecutorBuilder.kt       # LLM 프로바이더 팩토리
├── rag/
│   ├── ChromaClient.kt             # ChromaDB HTTP REST 클라이언트
│   ├── EmbeddingClient.kt          # LlmExpandClient + GoogleEmbeddingClient
│   ├── VectorIndexAgent.kt         # Confluence → ChromaDB 인덱싱
│   └── VectorSearchAgent.kt        # ChromaDB 검색 (searchStructured 포함)
└── slack/
    ├── SlackBotGateway.kt          # Bolt Socket Mode 게이트웨이
    └── SlackConfigHandler.kt       # 슬래시 커맨드 핸들러

src/test/kotlin/io/github/veronikapj/wiki/
└── eval/
    ├── EvalMetrics.kt              # Recall@K, MRR, honest-zero, word-overlap 매칭
    ├── EvalReporter.kt             # 카테고리/SearchStage별 리포트 생성
    ├── GoldenCase.kt               # 골든 케이스 데이터 클래스 + Category enum
    ├── GoldenDatasetGenerator.kt   # Confluence 페이지 기반 자동 골든 케이스 생성
    └── SearchQualityEvalTest.kt    # evalTest 실행 + docs/eval/ 리포트 저장
```
