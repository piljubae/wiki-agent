# GitHub Wiki 연결

## 개요

GitHub 레포지토리의 Wiki 페이지를 문서 소스로 추가합니다.  
Confluence와 동등한 레벨에서 OrchestratorAgent가 Tool로 선택합니다.

## config.yml 설정

```yaml
github:
  enabled: true
  repos:
    - Veronikapj/wiki-agent      # {owner}/{repo} 형식
    - myorg/backend
    - myorg/frontend
```

## GITHUB_TOKEN

`.env`:
```
GITHUB_TOKEN=ghp_...
```

| 상황 | 토큰 필요 여부 |
|------|--------------|
| public 레포 Wiki | 불필요 (익명 요청 가능, rate limit 낮음) |
| private 레포 Wiki | 필요 (`repo` 권한) |
| rate limit 증가 | 필요 (인증 시 9 req/min, 미인증 시 10 req/min 전체) |

GitHub Personal Access Token 발급: [github.com/settings/tokens](https://github.com/settings/tokens)  
필요 권한: `repo` (private) 또는 권한 없음 (public)

## 동작 흐름

```
1. 사용자: "@배필주2 API 설계 가이드"
2. OrchestratorAgent: githubWikiSearch Tool 선택
3. GitHubWikiClient: GitHub Search API 호출
   GET https://api.github.com/search/code
       ?q=API+설계+가이드+repo:myorg/backend.wiki
4. 검색 결과에서 페이지 목록 추출
5. 각 페이지의 raw 콘텐츠 조회
   GET https://raw.githubusercontent.com/wiki/myorg/backend/{page}.md
6. 결과 포맷 후 반환
```

## 사용 가능한 레포 조건

- GitHub에서 Wiki 기능이 활성화된 레포
- Wiki에 최소 1개 이상의 페이지가 있어야 검색 가능
- GitHub Search API는 기본 브랜치만 인덱싱

---

> **Source:** [GitHubWikiClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/github/GitHubWikiClient.kt)  
> **Reference:** [GitHub Search API](https://docs.github.com/en/rest/search/search#search-code) · [GitHub Settings → Tokens](https://github.com/settings/tokens)
