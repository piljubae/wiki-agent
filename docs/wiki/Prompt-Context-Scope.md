# 프롬프트 설계 원칙 2 — 컨텍스트 범위 제어

## 원칙

LLM에게 줄 정보의 범위를 제한해야 합니다.  
**컨텍스트가 많을수록 좋은 게 아닙니다.** 노이즈가 많으면 정확도가 떨어집니다.

## 범위 제어 방법 — Confluence spaces

```yaml
# config.yml
confluence:
  baseUrl: https://yourcompany.atlassian.net
  spaces:
    - DEV   # 개발팀 스페이스만
    - PM    # PM 스페이스만
```

`HR`, `FINANCE` 스페이스는 검색 대상에서 제외됩니다.

**효과:**
- CQL 쿼리: `text~"키워드" AND space IN ("DEV","PM")`
- 검색 결과 수 감소 → 관련성 높은 문서만 LLM에 전달

## 범위 제어 방법 — 결과 개수 제한

```kotlin
// ConfluenceSearchAgent — 최대 5개 결과
val results = confluenceClient.search(query, spaces, limit = 5)
```

검색 결과 수를 제한하면:
- LLM에 전달되는 컨텍스트 토큰 감소
- 비용 절감
- 더 집중된 답변

## 범위 제어 방법 — GitHub Wiki repos 제한

```yaml
github:
  enabled: true
  repos:
    - Veronikapj/wiki-agent   # 이 레포 Wiki만
```

모든 GitHub 레포가 아니라 명시한 레포의 Wiki만 검색합니다.

## 나쁜 예 — 범위 없는 검색

```
"회사 전체 위키에서 배포 관련 모든 내용 가져와"
```

**문제:**
- 수백 개 결과 → LLM 컨텍스트 초과
- 관련 없는 팀의 배포 문서 포함
- 응답 속도 느림, 비용 증가

## 좋은 예 — 범위 제한 검색

```
DEV 스페이스에서 "배포 프로세스" 관련 상위 5개 문서
```

---

> **Source:** [ConfluenceSearchAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/agent/ConfluenceSearchAgent.kt) · [WikiConfig.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt)
