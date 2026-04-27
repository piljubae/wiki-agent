# GitHub Wiki 검색 동작 방식

## GitHub Wiki의 구조

GitHub Wiki는 레포지토리와 별개인 git 레포지토리입니다.

- 주소: `https://github.com/{owner}/{repo}/wiki`
- git 주소: `https://github.com/{owner}/{repo}.wiki.git`
- 페이지 형식: Markdown (`.md`)

## 검색 URL 구성 (GitHubWikiClient)

```kotlin
internal fun buildSearchUrl(query: String, repos: List<String>): String {
    val repoQuery = repos.joinToString("+") { "repo:${it}.wiki" }
    val q = URLEncoder.encode("$query $repoQuery", "UTF-8")
    return "https://api.github.com/search/code?q=$q&per_page=10"
}
```

예시:
```
https://api.github.com/search/code
  ?q=API+설계+repo:myorg/backend.wiki
  &per_page=10
```

`repo:{owner}/{repo}.wiki` 형식이 핵심입니다. GitHub는 wiki 레포를 `.wiki` suffix로 접근합니다.

## raw 콘텐츠 URL 구성

```kotlin
internal fun buildRawUrl(repoFullName: String, path: String): String {
    val owner = repoFullName.substringBefore("/")
    val repo  = repoFullName.substringAfter("/")
    val page  = path.removeSuffix(".md")
    return "https://raw.githubusercontent.com/wiki/$owner/$repo/$page.md"
}
```

예시:
```
https://raw.githubusercontent.com/wiki/myorg/backend/API-Design-Guide.md
```

## API 요청 헤더

```kotlin
header("Accept", "application/vnd.github+json")
header("X-GitHub-Api-Version", "2022-11-28")
if (token.isNotBlank()) header("Authorization", "Bearer $token")
```

## Rate Limit

| 인증 방법 | Search Code API 제한 |
|---------|-------------------|
| 미인증 | 10 req/min (전체 검색 합산) |
| Token 인증 | 9 req/min (Search Code 전용) |

> **출처:** [GitHub REST API - Rate Limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)

## 제한 사항

- **기본 브랜치만 인덱싱:** GitHub Search API는 기본 브랜치만 검색
- **파일 크기 제한:** 384KB 초과 파일은 검색 불가
- **인덱싱 지연:** 새 페이지 추가 후 검색에 반영되기까지 시간 소요

---

> **Source:** [GitHubWikiClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/github/GitHubWikiClient.kt)  
> **Reference:** [GitHub Search Code API](https://docs.github.com/en/rest/search/search#search-code)
