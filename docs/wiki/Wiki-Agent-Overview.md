# wiki-agent 아키텍처 개요

## 전체 흐름

```
Slack (mention / slash command)
    │
    ▼
SlackBotGateway        ← Slack Bolt SDK 1.46, Socket Mode
    │  AppMentionEvent 수신 → 멘션 텍스트에서 봇 ID 제거
    ▼
OrchestratorAgent      ← Koog AIAgent (LLM이 Tool 선택)
    ├── confluenceSearch   ← ConfluenceSearchAgent → Confluence REST API (CQL)
    ├── githubWikiSearch   ← GitHubWikiSearchAgent → GitHub Search API  [github.enabled=true]
    └── vectorSearch       ← VectorSearchAgent → ChromaDB               [rag.enabled=true]
    │
    ▼
Slack 스레드로 답변 전송
```

## 컴포넌트별 역할

### SlackBotGateway
- `AppMentionEvent` 수신 → `@봇 ID` 부분 제거 → OrchestratorAgent에 질문 전달
- `/wiki` 슬래시 커맨드 → SlackConfigHandler에 라우팅
- **구현:** `com.slack.api.bolt.socket_mode.SocketModeApp`

### OrchestratorAgent
- Koog `AIAgent`로 구현
- 시스템 프롬프트: "검색 없이 직접 답변하지 마세요"
- LLM이 등록된 Tool 중 적합한 것을 선택
- fallback: `AnthropicModels.Haiku_4_5` → `AnthropicModels.Sonnet_4`

### ConfluenceSearchAgent
- Confluence REST API CQL로 검색
- 검색 범위: `config.yml`의 `confluence.spaces`
- **항상 활성** (기본 문서 소스)

### GitHubWikiSearchAgent
- GitHub Search API (`/search/code?q=...+repo:{owner}/{repo}.wiki`)
- raw 콘텐츠: `https://raw.githubusercontent.com/wiki/{owner}/{repo}/{page}.md`
- **조건부 활성:** `github.enabled: true`

### VectorSearchAgent
- ChromaDB HTTP REST API로 의미 검색
- 임베딩: `LLM_EXPAND` 또는 `GOOGLE_EMBEDDING`
- **조건부 활성:** `rag.enabled: true`

## 프로젝트 구조

```
src/main/kotlin/io/github/veronikapj/wiki/
├── Main.kt
├── agent/
│   ├── OrchestratorAgent.kt
│   ├── ConfluenceSearchAgent.kt
│   ├── GitHubWikiSearchAgent.kt
│   └── tool/
│       ├── ConfluenceTool.kt
│       ├── GitHubWikiTool.kt
│       └── VectorSearchTool.kt
├── config/
│   ├── WikiConfig.kt
│   ├── ConfigLoader.kt
│   └── SecretLoader.kt
├── confluence/
│   └── ConfluenceClient.kt
├── github/
│   └── GitHubWikiClient.kt
├── rag/
│   ├── ChromaClient.kt
│   ├── EmbeddingClient.kt
│   ├── VectorIndexAgent.kt
│   └── VectorSearchAgent.kt
└── slack/
    ├── SlackBotGateway.kt
    └── SlackConfigHandler.kt
```

---

> **Source:** [github.com/Veronikapj/wiki-agent](https://github.com/Veronikapj/wiki-agent)
