# 임베딩 (Embedding)

텍스트(또는 이미지, 음악)를 숫자 배열(벡터)로 변환하는 기술.
의미가 비슷한 텍스트는 비슷한 숫자 배열이 되어, 수학적으로 "거리"를 측정할 수 있게 됩니다.

```
"배너 클릭 이벤트 처리"  →  [0.23, -0.81, 0.44, ..., 0.12]  // 768개 숫자
"BannerViewModel"       →  [0.21, -0.79, 0.41, ..., 0.15]  // 비슷한 위치
"장바구니 담기"           →  [-0.54, 0.33, -0.22, ..., -0.41] // 먼 위치
```

---

## 숫자 배열이 만들어지는 원리

핵심 원칙 하나에서 출발합니다.

> **"같은 문맥에 자주 등장하는 단어는 의미가 비슷하다"**

수억 개의 문장을 분석하면:

```
"배너를 클릭하면 이벤트가 발생한다"
"버튼을 클릭하면 이벤트가 발생한다"
"배너를 탭하면 콜백이 호출된다"
```

"배너", "버튼", "클릭", "탭", "이벤트", "콜백"은 항상 비슷한 맥락에서 등장합니다.
모델은 이 패턴을 학습해서 이 단어들에게 **비슷한 위치의 숫자 배열**을 부여합니다.

학습 방식을 한 줄로 표현하면:
> "A 단어 주변에 B가 자주 나오면, A와 B의 벡터를 가깝게 당기도록 숫자를 조정" — 이걸 수억 번 반복.

---

## 768개 숫자의 의미

각 숫자가 딱 하나의 의미를 담지 않습니다.
768개 숫자 **전체가 한 덩어리**로 "의미 공간에서의 위치"를 나타냅니다.

2D로 단순화하면:

```kotlin
// x축 = "UI 관련 ↔ 데이터 관련"
// y축 = "사용자 액션 ↔ 상태"

"클릭"      → [0.90, 0.90]  // UI + 액션
"탭"        → [0.88, 0.88]  // 거의 같은 위치
"ViewModel" → [0.10, 0.30]  // 데이터 + 상태
"장바구니"   → [0.50, 0.10]  // 중간 + 상태
```

실제로는 이런 "의미 축"이 768개 있는 것입니다.
각 축이 무슨 의미인지는 사람이 정의하지 않고, 학습 과정에서 **자동으로 만들어집니다**.

---

## 주요 임베딩 모델

| 모델 | 특징 | 비용 |
|------|------|------|
| `all-MiniLM-L6-v2` | ChromaDB 기본 내장, 영어 중심 | 무료 (로컬 실행) |
| `text-embedding-004` | Google API, 한국어 포함 다국어 | API 비용 발생 |
| `voyage-code-3` | 코드 전용, 함수명·변수명 의미 더 잘 파악 | API 비용 발생 |

코드 검색에는 일반 텍스트 모델보다 코드 전용 모델이 더 정확합니다.
`onClick`이 클릭 이벤트 핸들러라는 것을 코드 문맥으로 학습했기 때문입니다.

---

## 상용 서비스 적용 사례

**검색**
- Google 검색 — "배너 클릭 처리 방법" 입력해도 "onClick event handling" 영어 문서가 나오는 이유
- GitHub Copilot, Cursor — 코드베이스 전체를 임베딩해서 관련 컨텍스트 파악
- Notion AI — 내 문서 안에서 관련 내용 찾기

**추천**
- 넷플릭스/유튜브 — "이 영상과 비슷한 영상" (영상 내용 → 벡터 → 가까운 벡터 추천)
- Spotify — 음악 추천 (오디오 파형도 벡터로 변환 가능)
- 쿠팡 — "이 상품과 비슷한 상품"

**분류/탐지**
- Gmail 스팸 필터 — 스팸 문장 벡터와 가까운지 판단
- 은행 이상 거래 탐지 — 정상 패턴 벡터와 거리가 먼 거래 탐지
- 구글 포토 "강아지 사진 찾기" — 이미지도 동일한 원리

> 텍스트든 이미지든 음악이든, 숫자 배열로 변환하면 같은 방식으로 검색/추천/분류가 가능합니다.

---

## wiki-agent에서의 사용

```kotlin
// embeddingFn이 null이면 ChromaDB 기본 임베딩(all-MiniLM-L6-v2) 사용
// 설정 시 Google text-embedding-004 호출
private val embeddingFn: (suspend (String) -> List<Float>)? = null
```

- **인덱싱 시**: 함수 청크 문서 → 임베딩 → ChromaDB 저장
- **검색 시**: 사용자 쿼리 → 임베딩 → ChromaDB에서 코사인 유사도로 가까운 청크 반환
- **LLM은 인덱싱 타임에 호출하지 않음** — 검색 후 답변 생성 시에만 사용

---

## 참고 자료

- [The Illustrated Word2Vec — Jay Alammar](https://jalammar.github.io/illustrated-word2vec/) — 임베딩 원리 시각적 설명 (영문, 추천)
- [Sentence Transformers 공식 문서](https://sbert.net/) — all-MiniLM-L6-v2 등 오픈소스 임베딩 모델
- [Google text-embedding-004](https://ai.google.dev/gemini-api/docs/models/gemini#text-embedding) — wiki-agent에서 사용하는 Google 임베딩 모델
- [voyage-code-3 (코드 전용 모델)](https://docs.voyageai.com/docs/embeddings) — Cursor가 채택한 코드 전용 임베딩

---

> **관련 문서:** [Cosine-Similarity.md](Cosine-Similarity.md) · [RAG-Embedding-Modes.md](RAG-Embedding-Modes.md) · [ChromaDB-Setup.md](ChromaDB-Setup.md)
