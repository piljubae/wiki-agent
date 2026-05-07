# 검색 품질 개선 설계 — 컨텍스트 키워드 보존 + Post-Retrieval Re-ranking

## 배경

위키봇에 "클라이언트 위클리" 질문 시 "물류 위클리" 문서가 반환되는 문제가 있다.

원인 분석:
1. **라우터 QUERY 드롭**: OrchestratorAgent의 QUERY 작성 프롬프트에 "팀명 등 수식어보다
   문서 이름 자체를 우선"이라는 지시가 있어 LLM이 "클라이언트"를 제거하고 "위클리"만 전달
2. **CQL OR 시맨틱**: `title ~ "위클리"`는 모든 팀 위클리를 반환 (의도적 설계)

CQL 동작 방식 (변경 불가):
- 스페이스 = OR (`title ~ "클라이언트 위클리"` → 클라이언트 OR 위클리 → false positive 다수)
- `searchByKeywords()` AND 폴백은 text 검색 결과가 0일 때만 실행 (건드리지 않음)

---

## 목표

- "클라이언트 위클리" → 클라이언트팀 위클리 문서가 상위에 오도록 개선
- 기존 검색 recall은 그대로 유지 (AND/OR CQL 변경 없음)
- 변경 최소화: 2개 파일만 수정

---

## 설계

### A: 라우터 QUERY 원칙 수정 (OrchestratorAgent.kt)

**현재 프롬프트 (문제)**:
```
- QUERY: 핵심 검색어 (팀명 등 수식어보다 문서 이름 자체를 우선)
```

**수정 후**:
```
- QUERY: 핵심 검색어 (범위를 좁히는 수식어는 포함할 것)
  - 좋은 예: "클라이언트 위클리", "iOS 배포 가이드", "프론트엔드 테스트 전략"
  - 나쁜 예: "위클리" (팀명 제거 → 엉뚱한 팀 문서 반환)
```

효과: 라우터가 "클라이언트 위클리"를 그대로 QUERY로 전달 → Confluence 검색 정확도 향상

### C: Post-Retrieval Re-ranking (ConfluenceSearchAgent.kt)

`searchStructured()`에 `originalQuestion: String` 파라미터 추가.
`combineAndRank()` 결과를 원래 질문 키워드와 제목 매칭 점수로 재정렬.

```kotlin
// 재정렬 로직
val questionKeywords = extractSignificantKeywords(originalQuestion)
return combined.sortedByDescending { page ->
    val titleLower = page.title.lowercase()
    questionKeywords.count { kw -> titleLower.contains(kw.lowercase()) }
}
```

호출 체인: `OrchestratorAgent` → `ConfluenceTool.search()` → `ConfluenceSearchAgent.searchStructured(originalQuestion)`

`ConfluenceTool`의 `search()` 메서드도 `originalQuestion` 파라미터를 추가해 전달.

---

## 파일 변경 범위

| 파일 | 변경 내용 |
|------|----------|
| `agent/OrchestratorAgent.kt` | QUERY 프롬프트 원칙 수정 + bad/good 예시 추가 |
| `agent/ConfluenceSearchAgent.kt` | `searchStructured(originalQuestion)` 파라미터 + re-ranking |
| `knowledge/ConfluenceTool.kt` | `search(originalQuestion)` 파라미터 추가 |

OrchestratorAgent에서 `ConfluenceTool.search(question, ...)` 호출 시 원래 질문을 함께 전달.

---

## 예상 효과

| 시나리오 | 현재 | 개선 후 |
|---------|------|---------|
| "클라이언트 위클리" | 물류팀 위클리 상위 | 클라이언트팀 위클리 상위 |
| "iOS 배포 가이드" | 혼합 반환 | iOS 관련 문서 상위 |
| 일반 질문 ("배포 프로세스") | 변화 없음 (수식어 없음) | 변화 없음 |

---

## 하위 호환성

- `routerConfig` 없을 때 fallback 동작 그대로
- re-ranking은 `questionKeywords`가 비어있으면 기존 순서 유지 (no-op)
- 기존 `extractSignificantKeywords()` 재사용 — 새 로직 없음
