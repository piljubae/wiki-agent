# Slack-Confluence Q&A Bot 설계

## 개요

Confluence 위키를 지식 베이스로 삼아 슬랙에서 자연어로 질문하면 답변해주는 에이전트.
autodoc-agent의 Kotlin + Koog + A2A Protocol 스택을 재사용하고, 새 레포로 분리한다.

## 목표

- 직군 무관하게 누구나 슬랙에서 `@봇 질문` 형태로 Confluence 문서 검색
- 비개발자도 슬래시 커맨드로 검색 범위(스페이스) 설정 가능
- 로컬 / 팀 서버 / API 키 세 가지 실행 모드 지원

## 아키텍처

### Phase 1 (CQL + 스페이스 범위)

```
슬랙 멘션/@봇
    ↓
SlackBotGateway (Slack Bolt SDK, Kotlin)
    ↓
OrchestratorAgent (의도 파악 + 쿼리 정제)
    ↓ A2A
ConfluenceSearchAgent (ConfluenceTool, CQL 검색)
    ↓
슬랙 응답 (요약 + 원본 페이지 링크, 스레드)
```

### Phase 2 (RAG 추가)

```
슬랙 멘션/@봇
    ↓
SlackBotGateway
    ↓
OrchestratorAgent
    ↓ A2A
VectorSearchAgent (ChromaDB 로컬 벡터 검색)
    ↑
VectorIndexAgent (Confluence 페이지 임베딩, 백그라운드 sync)
    ↓
슬랙 응답
```

## 실행 모드

| 모드 | platform 설정 | API 키 | Slack Bot | 적합 대상 |
|------|--------------|--------|-----------|-----------|
| 로컬 | `CLAUDE_CODE` | 불필요 (Claude Code CLI만 설치) | 개인 Bot 토큰 | 개인, 소규모 팀 |
| 팀 서버 | `ANTHROPIC` or `GOOGLE` | 필요 | 팀 공용 Bot 토큰 | 팀 단위 공용 운영 |
| API 직접 | 원하는 provider | 필요 | 공용 Bot 토큰 | 비용/모델 직접 관리 |

## LLM Provider 설정

`config.yml` 한 줄 변경으로 provider 교체:

```yaml
# Claude (API)
platform: ANTHROPIC
model: claude-sonnet-4-6
api_key: sk-ant-...

# Gemini (API)
platform: GOOGLE
model: gemini-2.0-flash
api_key: AIza...

# 로컬 (API 키 없음)
platform: CLAUDE_CODE
```

## 비개발자 Config 인터페이스

슬랙 슬래시 커맨드로 설정 — 별도 UI 없이 슬랙 안에서 완결:

```
/wikiq config space DEV,PM,HR    # 검색할 Confluence 스페이스 설정
/wikiq config space show          # 현재 설정 확인
/wikiq reindex                    # RAG 재인덱싱 트리거 (Phase 2)
```

내부적으로 `config.yml`에 저장.

## 컴포넌트 목록

| 컴포넌트 | 역할 | Phase |
|----------|------|-------|
| `SlackBotGateway` | 멘션/슬래시 커맨드 수신 | 1 |
| `OrchestratorAgent` | 의도 파악 + 에이전트 라우팅 | 1 |
| `ConfluenceSearchAgent` | CQL 기반 Confluence 검색 | 1 |
| `SlackConfigHandler` | `/wikiq config` 커맨드 처리 | 1 |
| `VectorIndexAgent` | Confluence → ChromaDB 임베딩 | 2 |
| `VectorSearchAgent` | 의미 기반 벡터 검색 | 2 |

## 데이터 흐름 (Phase 1)

1. 슬랙 멘션 수신 → `SlackBotGateway`가 텍스트 + 채널 추출
2. `OrchestratorAgent`가 검색 키워드/의도 정제
3. `ConfluenceSearchAgent`가 설정된 스페이스 범위 내 CQL 검색
4. Top N 페이지 요약 생성
5. 슬랙 스레드로 응답 (요약 + 원본 링크)

## 기술 스택

- Kotlin + Koog 0.8.0
- A2A Protocol (autodoc-agent에서 재사용)
- Slack Bolt SDK (Java/Kotlin)
- Confluence REST API (CQL)
- ChromaDB (Phase 2, 로컬 벡터 DB)
