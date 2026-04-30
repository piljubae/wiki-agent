# wiki-agent 프로젝트 기획서 v2.0

**작성일:** 2026-04-22  
**작성자:** 프로덕트앱개발팀  
**상태:** 진행 중  
**이전 버전:** [v1.0](wiki-agent-기획서-v1.md)

---

## v1.0 대비 변경 이유

v1.0 운영 2주 결과:
- Recall@5 **40.2%** — 목표(70%) 미달
- "찾을 수 없습니다" 응답 비율 59%
- 사용자 피드백: "동의어 검색이 안 된다", "약어를 못 알아듣는다"

원인 분석: CQL 단순 키워드 매칭의 한계 → 쿼리 전처리 + 동의어 확장 + RAG fallback 필요.

## v2.0 추가 범위

### 1. 쿼리 전처리 (cleanQuery)
- 대화형 접미사 제거 ("알려줘", "어디 있어?" 등)
- CQL 특수문자 정리
- **효과:** TITLE_BASED Recall@5 30.6% → **91.8%**

### 2. 동의어 확장 (Router LLM)
- QUERY + SYNONYMS 추출로 CQL OR 검색 확장
- 날짜 포맷 변환, 약어 확장, 영문 변환 포함
- **담당:** OrchestratorAgent 라우터 프롬프트

### 3. GitHub Wiki 검색
- `piljubae/wiki-agent.wiki` 기술 문서 연동
- 코드 관련 질문은 GitHub Wiki 우선
- **구현:** GitHubWikiSearchAgent

### 4. 로컬 지식베이스 (LLM Wiki 패턴)
- URL ingest → `.wiki/knowledge/` 마크다운 저장
- Confluence에 없는 팀 내부 문서 커버
- **신규:** KnowledgeStore + IngestAgent

### 5. 다중 스페이스 지원
- `config.yml`의 `confluence.spaces` 리스트로 설정
- 현재: ProductApp, project, ClientDivision

## 성능 목표 (v2.0)

| 지표 | v1.0 실적 | v2.0 목표 |
|------|----------|----------|
| Recall@5 전체 | 40.2% | **75% 이상** |
| TITLE_BASED Recall@5 | 30.6% | **90% 이상** |
| "찾을 수 없습니다" 비율 | 59% | **20% 이하** |
| 응답 시간 (p95) | 8초 | 5초 이하 |

## 배포 일정

| 날짜 | 내용 |
|------|------|
| 2026-04-24 | cleanQuery + 동의어 확장 배포 |
| 2026-04-26 | GitHub Wiki 검색 연동 |
| 2026-04-28 | 지식베이스 ingest 기능 릴리즈 |
| 2026-04-30 | v2.0 전체 배포 + 세션 발표 |
