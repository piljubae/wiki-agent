# Onboarding 콘텐츠 고도화 Design

> last_updated: 2026-06-28
> 선행 문서: [2026-06-01-onboarding-agent-design.md](2026-06-01-onboarding-agent-design.md) (v1 온보딩 에이전트 원설계)
> 접근법: **C 하이브리드** — 커리큘럼은 구조용으로 정적 유지하되, 콘텐츠 수집을 결정적 멀티소스로 확장하고 그 위에 적응형 레이어를 얹는다.

## 배경 — 왜 고도화하나

v1 온보딩(`:onboarding` 모듈, `OnboardingTool`)은 동작하지만 콘텐츠 측면에서 세 가지 한계가 있다.

1. **정적 커리큘럼 한계** — 위키 한 페이지의 고정 단계를 순차 안내할 뿐, 사용자가 실제로 무엇을 막혀 하는지에 적응하지 못한다.
2. **콘텐츠 소스 빈약** — `OnboardingTool` 생성자가 `confluenceTool`/`codeSearchTool`/`codeClient`를 주입받지만 **한 번도 호출하지 않는다**(dead dependencies). 콘텐츠 수집은 오직 `confluenceClient.fetchPageRawHtml(위키 1페이지)` 뿐.
3. **가이드 생성 품질** — 단일 위키 섹션을 LLM이 단순 요약. provenance·구조·레벨별 깊이가 없다.

`CurriculumStep.sources`는 이미 `CODE`/`CONFLUENCE`/`GITHUB_FILE`/`STATIC` 타입을 **모델로** 표현할 수 있는데, 실제 처리는 `CONFLUENCE_PAGE` 하나뿐이다. 즉 모델은 멀티소스를 표현할 수 있고, **배선만 안 된 상태**다.

## 스코프

### 포함
- ✅ **멀티소스 수집 배선** — 위키 섹션 + 코드검색 + confluence검색 + github-file
- ✅ **레벨 기반 깊이 조정** — `UserLevel`을 생성 프롬프트의 깊이 지시로 반영
- ✅ **질문 기반 라이브 검색** — 사용자 질문으로 코드/위키를 실시간 검색해 답변
- ✅ **생성 프롬프트 품질 개선** — provenance 헤더 + 일관 구조 출력

### 제외 (의도적)
- ❌ **STATIC 소스 부활** — 위키 SSOT 원칙 유지. static 파일을 살리면 위키와 drift 위험. 자세한 결정은 아래 "STATIC 처리" 참고.
- ❌ **deep-dive(사전 정의 심화 분기)** — 라이브 검색으로 v1 충분. 사용자가 자주 막히는 지점이 데이터로 쌓이면 그때 큐레이션 기반으로 추가.
- ❌ Koog 에이전트 루프 전환(B안) — manual loop·결정적·테스트 가능 철학 유지.

## 아키텍처

```
OnboardingTool.handle()
  ├─ START / LEVEL_RESPONSE / NEXT / SKIP / PROGRESS / JUMP   (현행 유지)
  ├─ generateGuide(step, session)
  │     └─ ContentGatherer.gather(step)  ← 신규: 멀티소스 수집
  │     └─ buildGuidePrompt(gathered, level)  ← 개선: provenance + 깊이
  └─ handleQuestion(message, session)
        └─ ContentGatherer.gatherForQuestion(message, step)  ← 신규: 라이브 검색
        └─ buildQuestionPrompt(gathered, conversationContext)
```

핵심 신규 단위는 `ContentGatherer` 하나. `OnboardingTool`에서 위키 파싱/검색 호출 책임을 떼어내 한 곳으로 모은다(현재 `loadWikiSections`/`parseHtmlToSections`/`getWikiContentForStep`가 `OnboardingTool`에 섞여 있음).

## 컴포넌트 1 — ContentGatherer (멀티소스 수집)

`onboarding` 모듈 내부 클래스. 검색 도구·위키 클라이언트를 주입받아 `CurriculumStep.sources`를 타입별로 디스패치한다.

```kotlin
internal class ContentGatherer(
    private val confluenceClient: ConfluenceClient?,
    private val confluenceTool: ConfluenceTool,
    private val codeSearchTool: CodeSearchTool,
    private val codeClient: GitHubCodeClient?,
    private val codeRepo: String?,
    private val codeBranch: String,
    private val wikiPageId: String?,
) {
    data class GatheredContent(
        val label: String,        // 사람이 읽는 출처 이름 (예: "프로젝트 구조")
        val provenance: Provenance, // WIKI / CODE / CONFLUENCE / GITHUB_FILE
        val text: String,
    )
    enum class Provenance(val emoji: String, val display: String) {
        WIKI("📄", "위키"), CODE("💻", "코드"),
        CONFLUENCE("🔗", "연관문서"), GITHUB_FILE("📁", "소스파일"),
    }

    fun gather(step: CurriculumStep): List<GatheredContent>
    fun gatherForQuestion(question: String, step: CurriculumStep?): List<GatheredContent>
}
```

### 타입별 디스패치

| ContentSource.type | 처리 | 기존 상태 |
|---|---|---|
| `CONFLUENCE_PAGE` | 위키 H2 섹션 추출 (`section` 키워드 매칭) | 유지 (코드 이전) |
| `CODE` | `codeSearchTool.codeSearch(source.query)` | **배선** |
| `CONFLUENCE` | `confluenceTool.confluenceSearch(source.query)` | **배선** |
| `GITHUB_FILE` | `codeClient.fetchFileContent(repo, source.path, branch)` (suspend, `String?`) | **배선** |
| `STATIC` | **무시**(로그 경고) — 위키 SSOT 유지 | 미지원 확정 |

### 결정적·바운드 규칙
- 소스당 텍스트 길이 상한(예: 위키 섹션은 현행, 검색 결과는 4000자 truncate).
- 단계당 소스 수 상한(예: 6개). 초과 시 앞쪽 우선.
- 개별 소스 수집 실패는 graceful — 해당 소스만 건너뛰고 로그, 나머지는 진행.
- 위키 섹션 캐시는 현행 유지(`wikiSectionsCache`를 ContentGatherer로 이동).
- LLM 자율 호출 없음 — `step.sources` / 질문에서 결정적으로 쿼리 도출.

### gatherForQuestion (라이브 검색)
질문 1건당 다음을 결정적으로 수행:
1. 현재 단계의 `CONFLUENCE_PAGE` 섹션(맥락) — 있으면 포함.
2. `codeSearchTool.codeSearch(question)` — 질문을 그대로 코드 검색.
3. `confluenceTool.confluenceSearch(question)` — 질문을 위키 검색.
4. 각 결과 truncate 후 `GatheredContent` 리스트로 반환.

→ 고정 콘텐츠가 아니라 **사용자가 물은 그 질문에 맞는** 콘텐츠를 모은다. 이것이 "정적 커리큘럼 한계" 해소의 핵심.

## 컴포넌트 2 — 적응형 레이어

### (a) 레벨 기반 깊이
`generateGuide`에 `session.level`을 전달 → 프롬프트에 깊이 지시 추가.

| 레벨 | 깊이 지시 |
|---|---|
| android A (입문) | 배경·용어 풀어서 상세히. 왜 필요한지부터. |
| android B (중급) | 핵심 위주, 익숙한 개념은 생략. |
| android C (숙련) | 요점·차이점만 간결히. kurly 고유 컨벤션 위주. |

compose/domain 레벨도 해당 주제 단계의 깊이에 보조 반영(예: compose A면 Compose 단계에서 더 상세).

### (b) 질문 기반 라이브 검색
`handleQuestion`이 현재는 *미리 정해진 위키 섹션*만 주입. 이를 `ContentGatherer.gatherForQuestion()` 결과로 교체. 답변 후 질문 요지를 `OnboardingSessionStore.addMemo()`로 기록(기존 API 재사용) — 향후 deep-dive 큐레이션의 데이터 근거가 된다.

## 컴포넌트 3 — 생성 품질 (프롬프트)

### generateGuide 프롬프트
- 멀티소스를 provenance 헤더와 함께 주입:
  ```
  === 📄 위키: 프로젝트 구조 ===
  ...
  === 💻 코드: ProductViewModel ===
  ...
  ```
- 출력 구조 일관화: **핵심 요약 → 상세 → 실습 액션 → 다음 안내**.
- anti-hallucination 규칙 유지·확장: "위 참고 자료에 있는 내용만. 파일 경로·클래스명은 자료에 명시된 것만." → 코드 소스에도 동일 적용.
- 깊이 지시(레벨) 삽입.
- Slack mrkdwn 규칙(`SLACK_FORMAT_RULE`), `다음`/질문 안내 등 기존 규칙 유지.

### handleQuestion 프롬프트
- `gatherForQuestion` 결과 + `conversationContext`(기존) 주입.
- "모르면 모른다고, 담당자/문서 안내" 규칙 유지.

## STATIC 처리 (결정 기록)

- 코드: `ContentGatherer`는 `STATIC`을 **처리하지 않는다**(로그 경고만). 위키 SSOT 유지.
- 기존 `.wiki/onboarding/steps/*.md` 17개 dead 파일: **이번 코드 작업과 분리**. 위키에 없는 큐레이션 콘텐츠가 있을 수 있으므로 "위키로 이관 후 삭제"가 정석. → 별도 후속 작업(콘텐츠 검수)으로 남긴다. 지금 삭제하지 않는다.
- `curriculum.yaml`에 STATIC 소스가 있으면 무시되므로, 실제 커리큘럼의 소스를 `confluence-page`/`code`/`confluence`로 갱신해야 한다(아래 운영 영향 참고).

## 테스트 전략

### 신규
- `ContentGathererTest`
  - 소스 타입별 디스패치: `code` → codeSearchTool 호출, `confluence` → confluenceTool 호출, `github-file` → codeClient 호출 (mockk verify).
  - `STATIC` 소스는 무시되고 결과에 포함되지 않는다.
  - 개별 소스 실패 시 나머지는 graceful 수집.
  - 길이/개수 상한 truncate 동작.
  - `gatherForQuestion`: 질문으로 codeSearch·confluenceSearch가 호출된다.

### 회귀·수정
- 기존 `OnboardingToolTest` 픽스처가 `type: static`을 쓰므로, 멀티소스 검증이 가능하도록 `type: confluence-page`/`code`로 교체하고 mockk로 도구 응답 주입. (현재도 static은 실효 동작하지 않으므로 동작 회귀는 없음.)
- 레벨별 깊이 지시가 프롬프트에 포함되는지 검증(프롬프트 문자열 assert 또는 캡처).
- `OnboardingSessionStoreTest`는 영향 없음(세션 모델 무변경).

### 빌드/실행
```bash
./gradlew :onboarding:test
```

## 변경 영향 범위

| 파일 | 변경 |
|---|---|
| `onboarding/.../ContentGatherer.kt` | **신규** |
| `onboarding/.../OnboardingTool.kt` | 수집 로직을 ContentGatherer로 위임, generateGuide/handleQuestion 프롬프트 개선, 레벨 전달 |
| `onboarding/.../OnboardingCurriculum.kt` | 변경 없음(기존 ContentSource 모델 재사용) |
| `onboarding/.../OnboardingSession.kt` | 변경 없음 |
| `.wiki/onboarding/curriculum.yaml` | 단계별 `sources`에 `code`/`confluence` 추가(운영 콘텐츠 작업) |
| `Main.kt` | 변경 없음(이미 도구 주입 중) |
| `onboarding/.../*Test.kt` | 픽스처 교체 + 신규 테스트 |

## 미해결/후속

- `curriculum.yaml`의 각 단계에 어떤 `code`/`confluence` 쿼리를 넣을지는 콘텐츠 큐레이션 작업(코드와 분리, 점진 보강 가능 — 소스가 없으면 위키만으로 동작).
- static 파일 위키 이관·삭제(별도 검수).
- deep-dive: 라이브 검색 메모 데이터가 쌓인 뒤 재검토.
