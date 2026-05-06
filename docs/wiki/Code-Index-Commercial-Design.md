# 상용 코드 검색 도구와의 비교

Cursor, Sourcegraph Cody, Continue.dev 등 상용 코드 검색 도구의 설계 원칙을 정리합니다.

## 핵심 원칙: LLM은 인덱싱 타임에 쓰지 않는다

모든 상용 도구가 공유하는 원칙입니다.

```
[틀린 접근]  파일 → LLM 요약 생성 → 요약을 임베딩 → DB 저장
[맞는 접근]  파일 → 코드 전용 임베딩 모델 → DB 저장
                                              ↓
             사용자 쿼리 → 임베딩 → 유사 청크 검색 → LLM으로 답변 생성
```

LLM은 검색 *후* 답변을 생성할 때만 사용합니다.
이유: LLM을 파일마다 호출하면 5,000파일 기준 수천 번의 API 호출 → 수 시간 소요.

## 상용 도구별 설계

### Cursor

- **파서**: Tree-sitter AST → 함수/클래스 경계에서 청킹
- **청킹 단위**: 함수 레벨 (~500자)
- **임베딩**: 자체 서버 (모델 미공개)
- **증분**: 파일 저장 시 즉시 해당 파일만 재인덱싱 (Merkle 해시 추적)
- **검색**: 벡터 유사도

### Sourcegraph Cody

- **파서**: 언어별 심볼 추출기
- **청킹 단위**: 파일 + 심볼 단위 혼합
- **검색**: BM25 키워드 + 벡터 **하이브리드**
- **규모**: 300,000개+ 레포, 90GB 모노레포 처리

### Continue.dev

- **파서**: Tree-sitter
- **임베딩**: voyage-code-3, nomic-embed-text 등 사용자 선택
- **로컬 옵션**: Ollama로 완전 로컬 실행 가능

## wiki-agent와의 차이

| 항목 | wiki-agent | 상용 (Cursor 기준) |
|------|-----------|------------------|
| 파서 | Regex | Tree-sitter AST |
| 청킹 단위 | 함수 레벨 (~500자) | **함수 레벨** (~500자) |
| LLM 인덱싱 | 없음 | **없음** |
| 임베딩 | Google text-embedding-004 | 코드 전용 모델 |
| 증분 인덱싱 | git diff 기반 | 파일 저장 즉시 |
| 검색 방식 | 벡터만 | **BM25 + 벡터 하이브리드** |
| 중첩 클래스/extension | 일부 누락 | 정확히 추출 |

## 참고 자료

- [Build Real-Time Codebase Indexing for AI Code Generation (CocoIndex)](https://cocoindex.io/blogs/index-code-base-for-rag/)
- [cAST: Enhancing Code RAG with Structural Chunking via AST (arXiv 2506.15655)](https://arxiv.org/html/2506.15655v1)
- [How Cursor Actually Indexes Your Codebase](https://towardsdatascience.com/how-cursor-actually-indexes-your-codebase/)
- [How Cody provides remote repository context (Sourcegraph)](https://sourcegraph.com/blog/how-cody-provides-remote-repository-context/)
- [Continue.dev Embedding Documentation](https://docs.continue.dev/customize/model-roles/embeddings)

---

> **관련 문서:** [Code-Index-Architecture.md](Code-Index-Architecture.md) · [Code-Index-Feasibility.md](Code-Index-Feasibility.md)
