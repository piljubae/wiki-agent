# CQL 검색 전략

## 핵심 질문

> Confluence 검색이 어떻게 동의어까지 처리하고 API 호출을 최소화하나요?

## 개요

`ConfluenceClient`는 CQL 쿼리 빌드와 API 호출을 담당합니다. `ConfluenceSearchAgent`가 `cleanQuery()`로 전처리한 쿼리를 전달하면, `ConfluenceClient`가 동의어 OR-clause를 포함한 CQL을 구성하여 실행합니다.

→ 쿼리 전처리(`cleanQuery`), 조기 반환, 병렬 fallback 전체 흐름은 [Search-Flow](Search-Flow) 참고

## 2단계 CQL 전략

`ConfluenceClient` 내부에서 title 검색과 text 검색 두 종류의 CQL을 생성합니다:

```
1단계: title 검색 (원본 + 동의어 OR-clause)
    ↓ 결과 부족 시 (ConfluenceSearchAgent 판단)
2단계: text 보완 검색
```

**1단계 — title 검색:**  
제목에서 찾으면 관련도가 높으므로 우선합니다.

```kotlin
fun buildTitleCqlSearchUrl(query: String, synonyms: List<String>): String {
    val titleClauses = mutableListOf("title ~ \"$safeQuery\"")
    synonyms.take(MAX_TEXT_CLAUSES - 1).forEach { s ->
        titleClauses.add("title ~ \"${escapeCql(s)}\"")
    }
    // (title ~ "온보딩" OR title ~ "신규 입사자" OR title ~ "입사 가이드") AND type = page
}
```

**2단계 — text 검색:**  
title에서 찾지 못하면 본문 전체를 검색합니다.

```kotlin
fun buildTextCqlSearchUrl(query: String, synonyms: List<String>): String {
    val textClauses = words.map { "text ~ \"${escapeCql(it)}\"" }
    // (text ~ "온보딩" OR text ~ "입사") AND type = page
}
```

## 동의어 OR-clause 병합

OrchestratorAgent가 LLM에게 동의어를 생성하도록 요청합니다:

```
TOOL: confluenceSearch
QUERY: 신입 온보딩
SYNONYMS: 신규 입사자, 입사 가이드, 온보딩 체크리스트
```

동의어를 각각 API로 호출하면 N+1 문제가 발생합니다.  
대신 OR-clause 하나로 묶어 **API 1회 호출**로 처리합니다:

```sql
(title ~ "신입 온보딩" OR title ~ "신규 입사자" OR title ~ "입사 가이드" OR title ~ "온보딩 체크리스트")
AND type = page AND space IN ("DEV")
```

## CQL Injection 방어 (escapeCql)

사용자 입력의 특수문자(`"`, `\`, `*` 등)가 CQL을 깨뜨릴 수 있습니다:

```kotlin
private fun escapeCql(value: String): String =
    value.replace("\\", "\\\\")
         .replace("\"", "\\\"")
         .replace("*", "\\*")
         .replace("?", "\\?")
```

## 한국어 불용어 처리

`그`, `이`, `을`, `를`, `이다`, `있다` 등 단독으로는 검색에 무의미한 단어를 CQL 절에서 제외합니다:

```kotlin
val words = query.split(Regex("\\s+"))
    .filter { it.length >= 1 && it !in STOPWORDS }
```

## MAX_TEXT_CLAUSES 상한

CQL이 지나치게 길어지면 Confluence API가 오류를 반환합니다.  
`MAX_TEXT_CLAUSES = 5`로 OR-clause 수를 제한합니다.

---

> **Source:** [ConfluenceClient.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/confluence/ConfluenceClient.kt)
