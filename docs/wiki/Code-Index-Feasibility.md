# 코드 인덱싱 실현 가능성 분석

kurly-android 전체 코드베이스 인덱싱 전에 진행한 사전 분석 기록입니다.

## 실제 파일 수 (2025-05 기준)

GitHub API로 `thefarmersfront/kurly-android` develop 브랜치 직접 조회:

```python
# git tree API 결과
total tree entries : 10,075
.kt 파일 (non-test)  : 5,021개
  icon 파일          :    16개 (무시 가능)
  실제 인덱싱 대상   : 5,005개

truncated: False  # tree가 잘리지 않음 → 파일 목록 완전
```

> 로컬 체크아웃 기준 약 5,295개 (워크트리 포함). GitHub 기준이 정확한 수치.

## 초기 설계의 문제: LLM enrichment

`CodeIndexAgent`의 초기 구현은 클래스마다 LLM을 호출해 한국어 설명을 생성했습니다:

```kotlin
// 문제가 된 코드
val enriched = runCatching {
    llmFn(buildEnrichPrompt(path, cls))  // 클래스당 LLM 1회 호출
}.getOrDefault(baseDoc)
```

### 비용 계산

```
5,005 파일 × 평균 2~3개 클래스/파일 = 약 10,000~15,000 LLM 호출

호출당 0.5초 → 1.5~2시간
호출당 1.0초 → 3~4시간
+ API 비용 상당
```

**결론: 실용적이지 않음**

## GitHub API Rate Limit 문제

GitHub API 인증 토큰 기준 5,000 req/hour.

```
5,005 파일 × 1회 Contents API 호출 = 5,005 요청
→ rate limit 초과 (5,000/hour)
→ 2시간 이상 소요
```

## 해결책: 두 가지 변경

### 1. LLM enrichment 비활성화 (embeddingFn 설정 시)

```kotlin
// 수정 후: embeddingFn이 있으면 LLM 스킵
val doc = if (embeddingFn != null) baseDoc
          else runCatching { llmFn(buildEnrichPrompt(path, cls)) }.getOrDefault(baseDoc)
```

임베딩 모델이 코드 구조를 직접 이해하므로 LLM 요약 불필요.

### 2. 로컬 체크아웃으로 GitHub API 대체

```
GitHub API (5,000 req/hour 제한, 파일당 HTTP):
  5,000파일 → 30~60분

로컬 파일시스템 (제한 없음):
  5,000파일 → 1~2분
```

## 최종 소요 시간 (개선 후)

| 단계 | 소요 시간 |
|------|----------|
| 파일 목록 수집 (로컬 walk) | ~1초 |
| 5,005파일 내용 읽기 (로컬 fs) | ~10초 |
| 클래스 추출 (regex) | ~30초 |
| Google embedding (~10,000회) | ~5분 |
| ChromaDB upsert | ~1분 |
| **초기 전체 인덱싱 합계** | **약 7~10분** |
| **증분 인덱싱 (변경 파일 30개)** | **약 30초** |

## 임베딩 모델 선택

한국어 쿼리("배너 클릭 어디서 처리해?") ↔ Kotlin 코드 매칭에 필요한 모델:

| 모델 | 비용 | 한국어+코드 |
|------|------|------------|
| ChromaDB 기본 (all-MiniLM-L6-v2) | 무료 | 낮음 |
| Google text-embedding-004 | 기존 Gemini API 키 재활용 | 높음 |
| voyage-code-3 | 유료 API | 높음 |
| nomic-embed-code (Ollama) | 무료 | 중간 |

**선택: Google text-embedding-004** — 이미 `GoogleEmbeddingClient`가 구현되어 있고, Gemini API 키로 바로 사용 가능.

---

> **관련 문서:** [Code-Index-Architecture.md](Code-Index-Architecture.md) · [Code-Index-Commercial-Design.md](Code-Index-Commercial-Design.md)
