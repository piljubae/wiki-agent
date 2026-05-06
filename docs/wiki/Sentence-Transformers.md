# Sentence Transformers

문장/문서를 벡터로 변환하는 오픈소스 라이브러리 + 모델 모음.
ChromaDB가 기본으로 내장하는 임베딩 방식입니다.

---

## 핵심 모델: `all-MiniLM-L6-v2`

ChromaDB를 기본 설정으로 실행하면 자동으로 사용하는 모델입니다.

```
"배너 클릭 이벤트"  →  384차원 벡터  (로컬 실행, 무료)
```

| 항목 | 내용 |
|------|------|
| 개발사 | Microsoft |
| 차원 수 | 384차원 |
| 모델 크기 | ~80MB |
| 실행 위치 | ChromaDB 컨테이너 내부 (로컬) |
| 비용 | 무료, API 키 불필요 |
| 언어 | 영어 중심, 한국어 품질 보통 |

---

## LLM과 임베딩 모델의 차이

```
GPT-4, Gemini (LLM)
  → "다음 단어 예측"을 위해 학습
  → 텍스트 생성에 최적화
  → 벡터 거리 측정에 부적합

text-embedding-004, all-MiniLM-L6-v2 (임베딩 전용 모델)
  → "비슷한 문장이 비슷한 벡터가 되도록" 학습
  → 벡터 출력에 최적화
  → 생성 기능 없음
```

임베딩 모델은 LLM보다 훨씬 작고(80MB~수백MB) 빠르며 비용이 낮습니다.

---

## wiki-agent 설정

```yaml
# config.yml
rag:
  embeddingMode: LLM_EXPAND      # ChromaDB 기본 임베딩 (all-MiniLM-L6-v2) 사용
  # embeddingMode: GOOGLE_EMBEDDING  # Google text-embedding-004 사용 시
```

`LLM_EXPAND` 모드: ChromaDB가 내부적으로 all-MiniLM-L6-v2를 호출합니다.
별도 API 키나 외부 호출 없이 동작합니다.

---

## 참고 자료

- [Sentence Transformers 공식 문서](https://sbert.net/) — 모델 목록, 사용법
- [all-MiniLM-L6-v2 — Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) — 모델 상세 정보
- [SBERT 원본 논문](https://arxiv.org/abs/1908.10084) — Sentence-BERT: Sentence Embeddings using Siamese BERT-Networks

---

> **관련 문서:** [Embedding.md](Embedding.md) · [Code-Embedding-Models.md](Code-Embedding-Models.md) · [RAG-Embedding-Modes.md](RAG-Embedding-Modes.md)
