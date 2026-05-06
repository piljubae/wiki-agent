# 청크 (Chunk)

RAG에서 문서를 잘라서 저장하는 단위. 검색 시 LLM에 전달되는 최소 정보 단위입니다.

---

## 왜 파일 전체를 저장하지 않나

```
BannerViewModel.kt  →  500줄

"배너 클릭 이벤트 처리" 검색
  파일 전체 반환: 관련 없는 500줄이 LLM 컨텍스트에 들어감  ❌
  onBannerClick() 함수만 반환: 딱 필요한 10줄만             ✅
```

LLM 컨텍스트 창은 유한합니다.
관련 없는 내용이 많을수록 답변 품질이 떨어지고 비용이 늘어납니다.

---

## 청킹 전략 비교

| 전략 | 단위 | 장점 | 단점 |
|------|------|------|------|
| 고정 길이 | 500자마다 자름 | 구현 단순 | 함수 중간에서 잘릴 수 있음 |
| 클래스 단위 | 클래스 하나 | 문맥 유지 | 여전히 너무 큼 |
| **함수 단위** | 함수 하나 | 딱 필요한 단위 | 파서 필요 |
| 슬라이딩 윈도우 | 겹치게 자름 | 경계 손실 방지 | 중복 저장 |

wiki-agent는 **함수 단위** — Cursor와 같은 방식입니다.

---

## 함수 단위 분리 기준

**시작점**: `fun` 키워드 감지

```kotlin
fun onBannerClick(bannerId: String): Unit {
suspend fun loadProducts(): List<Product> {
override fun onCreate(savedInstanceState: Bundle?) {
```

**끝점**: 중괄호 깊이가 0이 되는 지점

```kotlin
fun onBannerClick(bannerId: String): Unit {   // depth 1 시작
    viewModelScope.launch {                   // depth 2
        _events.send(...)
    }                                         // depth 1
}                                             // depth 0 → 청크 끝
```

**멀티라인 파라미터**: 괄호 깊이로 해결

```kotlin
fun createClickFusionSignal(    // parenDepth = 1
    clickType: FusionSignalType,
    product: ProductModel,
    position: Int
): FusionSignal? {              // parenDepth = 0 → 시그니처 완성
```

**중첩 클래스 / companion object**: ClassFrame 스택으로 정확한 className 추적

```kotlin
class BannerUtils {                  // classStack: [BannerUtils]
    companion object {               // classStack: [BannerUtils, (companion)]
        fun create(): BannerUtils    // className = BannerUtils ((companion) 제외)
    }
}
```

---

## ChromaDB에 저장되는 형태

```
id:       "thefarmersfront/kurly-android:features/banner/BannerViewModel.kt:BannerViewModel:onBannerClick:3a1f9c"
document: """
    package com.kurly.feature.banner
    file: features/banner/BannerViewModel.kt
    class: BannerViewModel

    fun onBannerClick(bannerId: String): Unit {
        viewModelScope.launch {
            _events.send(BannerEvent.Navigate(bannerId))
        }
    }
"""
metadata: { repo, file_path, class_name, function_name, sig_hash, branch }
```

함수 하나 = ChromaDB 문서 하나 = BM25 인덱스 문서 하나.

---

## 바디 500자 제한

바디가 길면 잘립니다. 검색 목적으로는 앞부분으로 충분합니다.

```kotlin
val body = when {
    afterSig.startsWith("{") -> extractBraceBlock(afterSig, maxChars = 500)
    afterSig.startsWith("=") -> "= " + ...take(498)   // 단일 표현식 함수
    else -> ""                                         // abstract / interface 선언
}
```

---

## 참고 자료

- [Chunking Strategies for LLM Applications — Pinecone](https://www.pinecone.io/learn/chunking-strategies/) — 전략별 비교 (추천)
- [How Cursor Actually Indexes Your Codebase](https://towardsdatascience.com/how-cursor-actually-indexes-your-codebase/) — 함수 단위 청킹 실제 사례

---

> **관련 문서:** [AST.md](AST.md) · [Embedding.md](Embedding.md) · [Code-Index-Architecture.md](Code-Index-Architecture.md)
