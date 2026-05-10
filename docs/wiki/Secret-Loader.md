# SecretLoader — 시크릿 로딩 우선순위

## 우선순위

시크릿은 다음 순서로 로드됩니다. 앞선 소스에 값이 있으면 뒤는 무시됩니다.

```
1. 환경변수 (System.getenv)
2. .env 파일 (프로젝트 루트)
3. config.yml의 값 (폴백)
```

## 코드 (SecretLoader.kt)

```kotlin
object SecretLoader {

    fun resolve(envKey: String, configValue: String): String =
        System.getenv(envKey)      // 1. 환경변수
            ?: dotEnvCache[envKey] // 2. .env 파일
            ?: configValue         // 3. config.yml 폴백

    fun resolveNullable(envKey: String, configValue: String?): String? =
        System.getenv(envKey)
            ?: dotEnvCache[envKey]
            ?: configValue
}
```

## .env 파일 형식

```
# 주석은 # 으로 시작
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
CONFLUENCE_TOKEN=<base64>
ANTHROPIC_API_KEY=sk-ant-...
GOOGLE_API_KEY=AIza...           # 공용 fallback + Gemini Flash 라우팅
GOOGLE_INDEX_API_KEY=AIza...     # 코드 인덱싱 전용 (선택, paid tier 필요)
GOOGLE_SEARCH_API_KEY=AIza...    # 코드 검색 전용 (선택, paid tier 필요)
GITHUB_TOKEN=ghp_...
```

**규칙:**
- `KEY=VALUE` 형식 (공백 없이)
- `#` 으로 시작하는 줄은 무시
- 빈 줄 무시
- 값이 없는 항목 무시

## .env.example

레포에 포함된 `.env.example`을 복사해서 시작합니다:

```bash
cp .env.example .env
```

`.env`는 `.gitignore`에 등록되어 있어 git에 커밋되지 않습니다.

## 권장 방식

| 환경 | 시크릿 관리 방법 |
|------|----------------|
| 로컬 개발 | `.env` 파일 |
| CI/CD | 환경변수 (GitHub Secrets 등) |
| 서버 배포 | 환경변수 또는 Secret Manager |

`config.yml`에 시크릿을 직접 쓰는 것은 git 커밋 실수 위험이 있어 **권장하지 않습니다.**

---

> **Source:** [SecretLoader.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/config/SecretLoader.kt) · [.env.example](https://github.com/Veronikapj/wiki-agent/blob/main/.env.example)  
> **관련:** [Google-Embedding-API.md](Google-Embedding-API.md) · [RAG-Embedding-Modes.md](RAG-Embedding-Modes.md)
