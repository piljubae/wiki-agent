# 검색 플로우 고도화 설계

## 배경

- 현재 검색의 핵심 문제: recall 부족
- 품질 판단을 위한 평가 체계가 미흡 (골든 데이터셋 6건)
- 검색 플로우 자체도 개선 여지 있음 (항상 2회 API, 순차 실행)

## 목표

1. 데이터 기반 검증 체계 구축
2. 검색 recall 개선
3. 정성적 품질 기준: 답변이 맞다고 느껴지면 OK
4. Zero-hit 정직성: 관련 문서가 없으면 "없다"고 답변

## 브랜치 전략

- 베이스: `feat/search-quality-improvement` (PR #6)
- 새 브랜치에서 작업, 별도 PR

---

## 1. 골든 데이터셋 자동 생성

### 입력
Confluence 설정 스페이스(ProductApp, project 등)의 페이지들

### 생성 파이프라인
1. `ConfluenceClient`로 설정 스페이스 페이지 목록 조회 (제목 + excerpt)
2. 페이지당 3가지 유형 질문 생성:
   - **TITLE_BASED** — 제목을 자연어 질문으로 변환 ("배포 프로세스 가이드" → "배포 프로세스 가이드 알려줘")
   - **LLM_GENERATED** — LLM이 excerpt 읽고 사람이 물어볼 법한 질문 생성
   - **PARAPHRASE** — LLM이 동의어/유사 표현으로 바꾼 질문 ("배포 절차" → "릴리즈 방법")
3. 결과를 `golden-dataset.json`에 추가 (기존 6건 유지)

### 실행 방식
Gradle task (`./gradlew generateGoldenDataset`) 또는 테스트 태그 (`@Tag("generate")`)

### 데이터 형태
```json
{
  "id": "AUTO-001",
  "question": "릴리즈 절차가 어떻게 돼?",
  "category": "PARAPHRASE",
  "expectedDocTitles": ["배포 프로세스 가이드"],
  "sourcePageId": "12345"
}
```

### 예상 규모
설정 스페이스 페이지 수 x 3 유형. 너무 많으면 스페이스당 상위 N개 페이지로 제한. 목표: 50~100건.

---

## 2. Eval 체계

### 검색 품질 메트릭

| 메트릭 | 설명 |
|--------|------|
| Recall@5 | 상위 5건에 정답 문서 포함 비율 |
| Recall@1 | 1위 결과가 정답인 비율 |
| MRR (Mean Reciprocal Rank) | 정답 문서의 순위 역수 평균 (1위=1.0, 2위=0.5, 없으면=0) |
| Zero-hit rate | 결과 0건 비율 |
| Honest-zero | ZERO_EXPECTED에서 실제 0건 반환 비율 |

### 효율 메트릭

| 메트릭 | 설명 |
|--------|------|
| Avg latency | 검색 평균 응답 시간 (ms) |
| API calls/query | 쿼리당 Confluence API 호출 수 |
| Search stage hit | 어떤 단계에서 정답을 찾았는지 (title/text/expansion/RAG) |

### 리포트 구조

```
=== Search Quality Eval Report (2026-04-24) ===

[Summary]
Total cases: 65 | Recall@5: 64.6% | Recall@1: 38.5% | MRR: 0.52
Zero-hit: 12.3% | Honest-zero: 80.0%
Avg latency: 1,230ms | Avg API calls: 2.1/query

[By Category]
Category         | Count | R@5   | R@1   | MRR  | Avg ms
TITLE_BASED      |    20 | 90.0% | 70.0% | 0.78 |    890
LLM_GENERATED    |    20 | 60.0% | 30.0% | 0.45 |  1,350
PARAPHRASE       |    20 | 40.0% | 15.0% | 0.28 |  1,420
ZERO_EXPECTED    |     5 | —     | —     | —    |    650

[By Search Stage (hit source)]
Stage            | Count | %
title_match      |    25 | 59.5%
text_match       |     8 | 19.0%
space_expansion  |     5 | 11.9%
rag_fallback     |     4 |  9.5%

[Failed Cases]
ID       | Question              | Expected            | Got (top 3)
AUTO-042 | 릴리즈 절차가 어떻게… | 배포 프로세스 가이드 | (none)
AUTO-017 | QA 체크리스트 있어?   | QA 가이드            | 개발 가이드, ...
```

### 실행 & 저장
- `./gradlew evalTest` — 콘솔 출력 + `docs/eval/YYYY-MM-DD-eval-report.txt` 저장
- before/after 비교 시 이전 리포트와 diff 가능

---

## 3. 검색 플로우 개선

### 현재 플로우
```
Query → LLM(QUERY+SYNONYMS) → CQL 제목 → CQL 본문 → [스페이스 확장] → [RAG fallback]
```
항상 제목+본문 2회 호출. 스페이스 확장과 RAG는 순차적.

### 개선 플로우
```
Query → LLM(QUERY+SYNONYMS)
  → CQL 제목 검색
     ├─ 충분 (≥3건 title match) → 바로 답변 생성 (1회 API로 끝)
     └─ 부족 (<3건)
          → 병렬 실행:
          │   ├─ CQL 본문 검색 (설정 스페이스)
          │   ├─ CQL 제목 검색 (전체 스페이스 확장)
          │   └─ RAG (ChromaDB)
          → 결과 합산 + 중복 제거 + 랭킹
             → 답변 생성
```

### 핵심 변경

1. **Early return** — 제목 매칭 충분하면 추가 검색 안 함 (latency 절감)
2. **부족 시 병렬** — 본문/스페이스 확장/RAG를 순차가 아니라 병렬로 (latency + recall)
3. **결과 랭킹** — 소스별 가중치로 정렬:
   - title match (설정 스페이스): 1.0
   - title match (확장 스페이스): 0.8
   - text match: 0.6
   - RAG: 0.5

### 설정값
- "충분" 기준 (기본 ≥3건): eval 결과 보면서 조정
- Honest-zero: 모든 단계에서 0건이면 "관련 문서를 찾지 못했습니다" 반환

---

## 4. 작업 순서

```
Phase 1: 골든 데이터셋 자동 생성         ─┐
Phase 2: Baseline eval (현재 플로우)      ─┤ 병렬 가능
Phase 3: 검색 플로우 개선                 ─┘
Phase 4: After eval (개선 후 비교)        ← 1+2+3 완료 후
```
