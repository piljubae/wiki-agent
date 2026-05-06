# PR 이력 인덱싱

GitHub PR의 변경 이력을 ChromaDB에 저장하는 RAG 파이프라인입니다.

## 왜 필요한가

코드 인덱싱은 **현재 파일의 상태**를 검색합니다. 하지만 이런 질문에는 대답하지 못합니다:

- "이 필드가 왜 추가됐어?" — 의사결정 맥락은 코드에 없음
- "panelCode 관련 변경 이력은?" — 키워드 검색은 되지만 시맨틱 검색 불가
- "배너 광고 관련 PR이 뭐가 있었어?" — 코드 함수 검색으로는 찾기 어려움

PR diff에는 **무엇이 왜 바뀌었는지** 맥락이 담겨 있습니다.

## 구조: RAG 그 자체

PR 이력 인덱싱은 RAG(Retrieval-Augmented Generation) 패턴을 그대로 따릅니다.

```
[인덱싱 타임]
GitHub API (PR list)
    ↓
PR당 diff 가져오기 (unified diff 형태)
    ↓
파일별 청크로 분할
    ↓
메타데이터 부착 (pr_number, title, author, merged_at, file_path)
    ↓
ChromaDB upsert

[질문 타임]
질문 텍스트
    ↓
임베딩 → ChromaDB 벡터 검색
    ↓
관련 PR diff 청크 반환
    ↓
LLM이 맥락과 함께 답변
```

## ChromaDB에 저장되는 형태

```json
{
  "id": "pr-7275-features/home/AdBannerViewHolder.kt",
  "document": "+    val panelCode: String?,\n+    val isAd: Boolean,\n-    // DSP 프로퍼티 없던 시절",
  "metadata": {
    "type": "pr_diff",
    "pr_number": 7275,
    "pr_title": "KMA-7275 배너에 DSP panel_code 전달",
    "file": "features/home/AdBannerViewHolder.kt",
    "merged_at": "2026-04-28"
  }
}
```

## 다른 인덱싱과의 비교

wiki-agent의 모든 검색은 같은 RAG 구조를 사용합니다. 저장하는 소스만 다릅니다.

| 에이전트 | ChromaDB에 저장하는 것 | 검색으로 답하는 질문 |
|---------|----------------------|-------------------|
| `CodeIndexAgent` | 함수 코드 청크 | "이 함수가 어디 있어?" |
| `PrIndexAgent` | PR diff 청크 | "이게 왜 바뀌었어?" |
| Confluence 인덱싱 | 문서 페이지 청크 | "이 기능 스펙은?" |

## config.yml 설정

```yaml
github:
  codeRepos:
    - thefarmersfront/kurly-android
  prSearch:
    enabled: true
    maxPrs: 500          # 최근 N개 PR 인덱싱
```

---

> **Source:** [PrIndexAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/PrIndexAgent.kt)
> **관련:** [Code-Index-Architecture](Code-Index-Architecture) · [RAG-Indexing](RAG-Indexing) · [ChromaDB-v2-Migration](ChromaDB-v2-Migration)
