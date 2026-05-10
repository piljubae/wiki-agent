# RAG 임베딩 모드

wiki-agent는 두 가지 임베딩 방식을 지원하며, **Confluence/GitHub 문서용**과 **코드 인덱싱용**을 독립적으로 설정합니다.

## 모드 비교

| 항목 | `LLM_EXPAND` | `GOOGLE_EMBEDDING` |
|------|-------------|-------------------|
| 방식 | LLM으로 쿼리/문서 확장 → ChromaDB 내장 sentence-transformers | Google `gemini-embedding-001` API로 명시적 벡터 생성 |
| 추가 API 키 | 불필요 | `GOOGLE_API_KEY` 필요 |
| 비용 | LLM 호출 토큰 비용 | Google Embedding API 비용 |
| 벡터 차원 | sentence-transformers 기본값 | **3,072차원** |
| 정확도 | 의미 확장으로 recall 향상 | 전용 임베딩 모델로 precision 향상 |

## LLM_EXPAND 동작 방식

```kotlin
// LlmExpandClient.kt

// 문서 인덱싱 시: 원문 + LLM이 확장한 키워드·동의어
suspend fun enrichDocument(text: String): String {
    val prompt = """
        아래 문서의 핵심 키워드, 동의어, 영어 표현, 관련 질문 유형을 추출해서
        원문 아래에 덧붙여 반환하세요.
        문서: ${text.take(2000)}
    """.trimIndent()
    return llmFn(prompt)
}

// 검색 시: 쿼리 + LLM이 확장한 동의어·관련 개념
suspend fun expandQuery(query: String): String {
    val prompt = """
        아래 검색어의 동의어, 영어 표현, 관련 개념을 공백으로 구분해서 한 줄로 반환.
        검색어: $query
    """.trimIndent()
    return "$query ${llmFn(prompt)}"
}
```

## GOOGLE_EMBEDDING 동작 방식

```kotlin
// EmbeddingClient.kt
private val model = "gemini-embedding-001"
private val endpoint =
    "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent?key=$apiKey"
```

Google `gemini-embedding-001` 모델로 **3,072차원** 벡터를 생성합니다.

> **참고:** 이전 버전에서 사용하던 `text-embedding-004`(768차원)는 deprecated.  
> ChromaDB 컬렉션을 새로 만들 때 차원이 자동 결정되므로, 모델 변경 시 컬렉션을 재생성해야 합니다.

## config.yml 설정

```yaml
rag:
  enabled: true
  chromaUrl: http://localhost:8001
  embeddingMode: LLM_EXPAND       # Confluence/GitHub 문서용

github:
  codeSearch:
    embeddingMode: GOOGLE_EMBEDDING  # 코드 인덱싱 전용
```

`GOOGLE_EMBEDDING` 사용 시 `.env`에 추가:
```
GOOGLE_API_KEY=AIza...           # 필수 — 공용 fallback
GOOGLE_INDEX_API_KEY=AIza...     # 선택 — 인덱싱 전용 (대량 배치)
GOOGLE_SEARCH_API_KEY=AIza...    # 선택 — 검색 전용 (실시간 소량)
```

`GOOGLE_INDEX_API_KEY` / `GOOGLE_SEARCH_API_KEY`가 없으면 `GOOGLE_API_KEY`로 fallback.  
인덱싱(~17,000 청크)과 검색의 쿼터 소비 패턴이 달라 분리하면 영향 범위를 격리할 수 있습니다.

> **주의:** 전체 인덱싱에는 **Paid Tier 필수** — Free Tier는 100 req/day 제한으로 5,000+ 파일 처리 불가.  
> 자세한 내용: [Google-Embedding-API.md](Google-Embedding-API.md)

### 왜 두 개를 다르게 설정하나

| 대상 | 권장 모드 | 이유 |
|------|----------|------|
| Confluence/GitHub 문서 | `LLM_EXPAND` | 자연어 쿼리 — 의미 확장으로 recall 향상 |
| 코드 인덱싱 | `GOOGLE_EMBEDDING` | 코드 심볼 검색 — 정확한 벡터 매칭이 유리 |

## 선택 가이드

- **빠르게 시작하려면:** `LLM_EXPAND` (추가 API 키 불필요)
- **코드 검색 품질이 중요하면:** `GOOGLE_EMBEDDING`

---

> **Source:** [EmbeddingClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/EmbeddingClient.kt) · [WikiConfig.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt)  
> **관련:** [ChromaDB-Setup](ChromaDB-Setup) · [ChromaDB-v2-Migration](ChromaDB-v2-Migration) · [Google-Embedding-API.md](Google-Embedding-API.md) · [Secret-Loader.md](Secret-Loader.md)
