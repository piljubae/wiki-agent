# RAG 임베딩 모드

wiki-agent는 두 가지 임베딩 방식을 지원합니다.

## 모드 비교

| 항목 | `LLM_EXPAND` | `GOOGLE_EMBEDDING` |
|------|-------------|-------------------|
| 방식 | LLM으로 쿼리/문서 확장 → ChromaDB 내장 sentence-transformers | Google `text-embedding-004` API로 명시적 벡터 생성 |
| 추가 API 키 | 불필요 | `GOOGLE_API_KEY` 필요 |
| 비용 | LLM 호출 토큰 비용 | Google Embedding API 비용 |
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
// GoogleEmbeddingClient.kt
private val model = "text-embedding-004"
private val endpoint =
    "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent?key=$apiKey"
```

Google `text-embedding-004` 모델로 768차원 벡터를 생성합니다.

> **Reference:** [Google text-embedding-004](https://ai.google.dev/gemini-api/docs/models/gemini#text-embedding)

## config.yml 설정

```yaml
rag:
  enabled: true
  chromaUrl: http://localhost:8000
  embeddingMode: LLM_EXPAND     # 또는 GOOGLE_EMBEDDING
```

`GOOGLE_EMBEDDING` 사용 시 `.env`에 추가:
```
GOOGLE_API_KEY=AIza...
```

## 선택 가이드

- **빠르게 시작하려면:** `LLM_EXPAND` (추가 API 키 불필요)
- **더 나은 벡터 품질이 필요하면:** `GOOGLE_EMBEDDING`

---

> **Source:** [EmbeddingClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/rag/EmbeddingClient.kt) · [WikiConfig.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt)
