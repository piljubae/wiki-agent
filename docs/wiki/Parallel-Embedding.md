# 임베딩 병렬화

순차 실행으로 3시간 걸리던 코드 인덱싱을 ~10분으로 줄인 방법입니다.

## 왜 순차 실행이 느린가

Gemini API 1회 호출 ≈ 700ms. 청크 15,417개를 하나씩 기다리면:

```
15,417 × 700ms = 10,791초 ≈ 3시간
```

CPU는 놀고, 네트워크 응답만 기다리는 전형적인 **I/O bound** 문제입니다.

## Semaphore란

열쇠 꾸러미 비유가 직관적입니다. 열쇠가 15개 있고, API를 호출하려면 열쇠를 하나 빌려야 해요. 반납하면 대기 중인 코루틴이 가져갑니다.

```
열쇠 15개

coroutine-1  ─[🔑빌림]─ API 호출 중... ─[🔑반납]─
coroutine-2  ─[🔑빌림]─ API 호출 중...           ─[🔑반납]─
coroutine-3  ─[🔑빌림]─ API 호출 중...
...
coroutine-16 ─[열쇠 없음, 대기]──────────────── 열쇠 생기면 진행
```

`withPermit { }` 블록 진입 시 열쇠를 가져가고, 블록이 끝나면 자동으로 반납합니다.

## 구현

```kotlin
private val embeddingSemaphore = Semaphore(15)

val embeddings = coroutineScope {
    entries.map { entry ->
        async {
            embeddingSemaphore.withPermit {
                runCatching { embeddingFn(entry.doc) }.getOrElse { emptyList() }
            }
        }
    }.awaitAll()
}
```

- `async { }` — 각 API 호출을 코루틴으로 분리
- `Semaphore(15)` — 동시 요청 수를 15개로 제한
- `awaitAll()` — 전부 끝날 때까지 대기, 순서 보존

## 왜 15개인가

Gemini 무료 티어 한도를 역산한 값입니다.

```
제한: 1,500 RPM = 25 RPS

API 1회 ≈ 700ms → 동시 N개면 초당 N ÷ 0.7 req/sec

N=15 → 15 ÷ 0.7 ≈ 21 req/sec = 1,260 RPM  ✅ 여유 있음
N=20 → 20 ÷ 0.7 ≈ 28 req/sec = 1,680 RPM  ❌ 한도 초과 → 429 에러
```

실제로 20으로 설정했다가 rate limit 에러가 발생해 15로 낮췄습니다.
유료 플랜으로 전환하면 50~100까지 올릴 수 있습니다.

## 3-Phase 구조

임베딩을 병렬화하려면 수집·임베딩·저장을 분리해야 합니다. 기존 코드는 이 세 가지가 하나의 루프 안에 섞여 있었습니다.

```
Phase 1: 청크 수집    순차, 빠름 (파일 읽기 + 파싱)
    ↓
Phase 2: 임베딩      병렬, I/O bound (Gemini API × 15 동시)
    ↓
Phase 3: ChromaDB upsert   순차, 빠름 (배치 1회)
```

## 결과

| | 이전 | 이후 |
|--|------|------|
| 동시 요청 수 | 1 (순차) | 15 (병렬) |
| 15,417 청크 처리 | ~3시간 | ~10분 |
| Gemini RPM 사용 | ~84 | ~1,260 |

---

> **Source:** [CodeIndexAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt)  
> **관련:** [ChromaDB-v2-Migration](ChromaDB-v2-Migration) · [Incremental-Indexing](Incremental-Indexing) · [Embedding-Cache](Embedding-Cache)
