# 코드 인덱싱 아키텍처

Kurly Android 소스코드를 ChromaDB에 인덱싱하여 자연어로 검색하는 기능입니다.

## 전체 파이프라인

```
[로컬 git 체크아웃]          [ChromaDB]              [Slack]
  origin/develop   →  CodeIndexAgent  →  code_index  →  @wiki BannerViewModel 어디있어?
  .kt 파일 5,000개      클래스 추출           벡터 DB        → features/banner/BannerViewModel.kt
                        Google Embedding
```

## 두 가지 파일 소스 모드

### 1. GitHub API 모드 (기본)

`localRepoPath` 미설정 시 동작합니다.

```
GitHub git tree API → .kt 파일 경로 목록
  → GitHub Contents API (파일당 1회) → 파일 내용
```

**단점**: GitHub API rate limit 5,000 req/hour → 5,000파일 처리에 30~60분 소요

### 2. 로컬 체크아웃 모드 (권장)

`config.yml`에 `localRepoPath` 설정 시 동작합니다.

```
로컬 파일시스템 walk() → 파일 내용 (즉시)
```

**장점**:
- API rate limit 없음
- 5,000파일 처리에 1~2분
- git diff로 증분 인덱싱 가능 (변경 파일만 재처리)

## 인덱싱 파이프라인 상세

```
1. 파일 목록 수집
   - 로컬: File.walk() + .kt 필터 (build/, generated/, Test 제외)
   - GitHub API: /git/trees/{branch}?recursive=1

2. 파일 내용 읽기
   - 로컬: File.readText()
   - GitHub API: /contents/{path}?ref={branch}

3. 클래스 추출 (CodeIndexAgent.extractClasses)
   - Regex 기반 top-level 선언 추출
   - class / data class / sealed class / object / interface / enum class

4. 문서 생성 (CodeIndexAgent.buildIndexDocument)
   - 패키지명, 파일 경로, 클래스 종류, public 함수 목록

5. 임베딩 생성
   - embeddingFn 있음: Google text-embedding-004 호출
   - embeddingFn 없음: ChromaDB 기본 임베딩 (all-MiniLM-L6-v2)

6. ChromaDB upsert
   - collection: code_index
   - id: "{repo}:{filePath}:{className}"
   - metadata: repo, file_path, class_name, branch
```

## 설정

```yaml
# config.yml
github:
  codeRepos:
    - thefarmersfront/kurly-android
  codeSearch:
    branch: develop
    localRepoPath: /path/to/kurly-android-index  # 로컬 체크아웃 경로
    pollIntervalMinutes: 60

rag:
  enabled: true
  chromaUrl: http://localhost:8000
  embeddingMode: GOOGLE_EMBEDDING
  googleApiKey: "..."  # 또는 GOOGLE_API_KEY 환경변수
```

## 로컬 체크아웃 초기 설정

```bash
# 별도 경로에 shallow clone (디스크 절약)
git clone --depth=100 \
  https://github.com/thefarmersfront/kurly-android.git \
  /path/to/kurly-android-index

# 이후 자동 최신화는 LocalRepoSync.sync()가 처리
# (polling 주기마다 git fetch + reset --hard origin/develop)
```

## 관련 클래스

| 클래스 | 역할 |
|--------|------|
| `CodeIndexAgent` | 파일 목록 수집, 클래스 추출, ChromaDB 저장 |
| `LocalRepoSync` | git fetch/reset, git diff --name-only |
| `GitHubCodeClient` | GitHub API 모드에서 파일 목록/내용 fetch |
| `GoogleEmbeddingClient` | text-embedding-004 임베딩 생성 |
| `ChromaClient` | ChromaDB upsert/query |

---

> **Source:** [CodeIndexAgent.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/CodeIndexAgent.kt) · [LocalRepoSync.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/knowledge/LocalRepoSync.kt)
