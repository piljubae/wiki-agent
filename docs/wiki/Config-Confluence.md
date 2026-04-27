# config.yml — confluence 섹션

Confluence 연결은 **선택 사항**입니다. 설정하지 않으면 GitHub Wiki 또는 RAG만으로 동작합니다.

## 설정

`.wikiq/config.yml`:

```yaml
confluence:
  baseUrl: https://yourcompany.atlassian.net
  spaces:
    - DEV
    - PM
```

## 각 항목

| 항목 | 설명 | 기본값 |
|------|------|--------|
| `baseUrl` | Confluence Cloud URL | `""` (빈 값 = 비활성) |
| `spaces` | 검색 대상 스페이스 키 목록 | `[]` |
| `token` | Base64 인코딩된 API 토큰 (`.env` 사용 권장) | `""` |

## Confluence API 토큰 발급

1. [id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens) 접속
2. **Create API token** → 이름 입력 → **Create**
3. 토큰 복사 후 Base64 인코딩:

```bash
echo -n "your@email.com:your-api-token" | base64
```

4. 결과를 `.env`에 저장:

```
CONFLUENCE_TOKEN=<base64 인코딩 결과>
```

## Confluence 없이 실행하기

`baseUrl`을 비워두면 `ConfluenceSearchAgent`는 "Confluence가 설정되지 않았습니다"를 반환합니다.  
GitHub Wiki 또는 RAG가 활성화되어 있으면 해당 소스로만 동작합니다.

```yaml
confluence:
  baseUrl: ""     # Confluence 비활성
  spaces: []
```

## CQL 검색 방식

내부적으로 Confluence REST API를 사용합니다:

```
GET {baseUrl}/wiki/rest/api/content/search
    ?cql=text~"{query}" AND space IN ("{spaces}")
    &limit=5
```

---

> **Reference:** [Atlassian API Token 발급](https://id.atlassian.com/manage-profile/security/api-tokens) · [Confluence REST API CQL](https://developer.atlassian.com/cloud/confluence/advanced-searching-using-cql/)  
> **Source:** [WikiConfig.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt)
