# OrchestratorAgent + RAG Phase 2 설계

## 목표

- SlackBotGateway와 ConfluenceSearchAgent 사이에 **OrchestratorAgent** 추가
- CQL 검색(Phase 1)에 **RAG**(Phase 2)를 확장 옵션으로 추가
- 시크릿을 config.yml 대신 환경변수 / .env 파일로 분리

---

## 아키텍처

```
SlackBotGateway
    ↓
OrchestratorAgent  (Koog AIAgent)
    ├── ConfluenceTool      →  ConfluenceSearchAgent  (CQL, 항상 활성)
    └── VectorSearchTool    →  VectorSearchAgent      (rag.enabled=true 시)

VectorIndexAgent  ← /wiki reindex 슬래시 커맨드로 트리거
```

OrchestratorAgent는 Koog AIAgent로 구현한다. ConfluenceTool과 VectorSearchTool을 Tool로 등록하고 LLM이 질문을 보고 어느 Tool을 쓸지 직접 결정한다. `rag.enabled=false` 이면 VectorSearchTool을 등록하지 않는다.

---

## 신규 파일 목록

| 파일 | 역할 |
|------|------|
| `agent/OrchestratorAgent.kt` | Koog AIAgent, Tool 등록 및 LLM 라우팅 |
| `agent/tool/ConfluenceTool.kt` | ConfluenceSearchAgent를 Koog Tool로 래핑 |
| `agent/tool/VectorSearchTool.kt` | VectorSearchAgent를 Koog Tool로 래핑 |
| `rag/VectorIndexAgent.kt` | Confluence → ChromaDB 인덱싱 |
| `rag/VectorSearchAgent.kt` | ChromaDB 검색 (LLM_EXPAND / GOOGLE_EMBEDDING) |
| `rag/ChromaClient.kt` | ChromaDB HTTP REST 클라이언트 (Ktor) |
| `rag/EmbeddingClient.kt` | 임베딩 모드별 분기 |
| `config/SecretLoader.kt` | env var → .env → config.yml 우선순위 시크릿 로딩 |

---

## config.yml 변경

비민감 설정만 남긴다. 토큰·API 키는 환경변수 또는 .env로 이동.

```yaml
model:
  provider: CLAUDE_CODE
confluence:
  baseUrl: https://yourcompany.atlassian.net
  spaces:
    - DEV
    - PM
slack: {}
rag:
  enabled: false
  chromaUrl: http://localhost:8000
  embeddingMode: LLM_EXPAND   # LLM_EXPAND | GOOGLE_EMBEDDING
```

---

## 시크릿 관리 (SecretLoader)

우선순위: **환경변수 → .env 파일 → config.yml**

| 시크릿 | 환경변수명 |
|--------|-----------|
| Slack Bot Token | `SLACK_BOT_TOKEN` |
| Slack App Token | `SLACK_APP_TOKEN` |
| Confluence Token | `CONFLUENCE_TOKEN` |
| Anthropic API Key | `ANTHROPIC_API_KEY` |
| Google API Key | `GOOGLE_API_KEY` |

`.env` 파일은 `.gitignore` 등록. `.env.example` 은 커밋하여 팀 공유.

---

## RAG 임베딩 모드

### LLM_EXPAND (기본, 추가 API 키 없음)

**인덱싱 (`VectorIndexAgent`)**
1. Confluence 페이지 전체 fetch
2. LLM 프롬프트: "이 문서의 주제, 핵심 키워드, 동의어, 관련 질문 유형을 추출해줘"
3. 원문 + enriched 메타데이터를 ChromaDB에 텍스트로 저장

**검색 (`VectorSearchAgent`)**
1. LLM 프롬프트: "이 질문의 동의어, 영어 표현, 관련 개념을 생성해줘"
2. 확장된 쿼리로 ChromaDB full-text 검색
3. 결과 포맷 후 반환

### GOOGLE_EMBEDDING (정밀, Google API 키 필요)

**인덱싱**
1. Confluence 페이지 fetch
2. Google `text-embedding-004` API로 float 벡터 생성
3. 벡터 + 원문을 ChromaDB에 저장

**검색**
1. 질문을 Google embedding API로 벡터 변환
2. ChromaDB cosine similarity 검색
3. 결과 포맷 후 반환

`config.yml`의 `rag.embeddingMode` 한 줄로 전환.

---

## 슬래시 커맨드 추가

| 커맨드 | 동작 |
|--------|------|
| `/wiki reindex` | VectorIndexAgent 트리거 → 전체 재인덱싱 |
| `/wiki reindex status` | 마지막 인덱싱 시각 + 저장된 문서 수 출력 |

---

## ChromaDB 실행

```bash
docker run -p 8000:8000 chromadb/chroma
```

---

## 테스트 전략

- `OrchestratorAgent`: mockk으로 Tool 호출 여부 검증
- `VectorIndexAgent` / `VectorSearchAgent`: ChromaClient를 mock으로 대체
- `ChromaClient`: HTTP 응답 파싱 단위 테스트
- `SecretLoader`: 환경변수/파일/config 우선순위 단위 테스트
- `EmbeddingClient`: 모드별 분기 단위 테스트
