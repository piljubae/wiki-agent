# 온보딩 위키 Android SSOT — 콘텐츠 오염 방지 설계

- 날짜: 2026-06-29
- 브랜치: `feat/onboarding-content-enhancement-2` (worktree)
- 대상 모듈: `onboarding`, `search`
- 기준 커밋: main 42f0edf (PR #40 온보딩 고도화 머지 포함)

## 1. 배경 & 문제

PR #40에서 온보딩에 "질문 기반 라이브 검색"이 추가됐다. 사용자가 온보딩 중 질문하면
`ContentGatherer.gatherForQuestion`이 현재 단계 위키 섹션 + 코드 검색 + **Confluence 검색**을
모아 LLM에 전달한다.

이 Confluence 라이브 검색이 의도와 달리 **Android 범위 밖 콘텐츠를 끌어와 답변을 오염**시킨다.
오염 경로는 둘이다.

1. **무관 스페이스 유입** — `ConfluenceSearchAgent`는 일반 Q&A와 온보딩이 공유한다.
   온보딩 질문 검색도 설정된 전체 스페이스(`ProductApp · project · ClientDivision · PSD`)를
   대상으로 하며, 두 곳에서 스페이스 제한을 푼다.
   - space 확장 검색: `searchByTitle(..., spaces = emptyList())` → 전체 스페이스
     (`ConfluenceSearchAgent.kt:77-80`)
   - global fallback: 설정 스페이스에 관련 결과가 없으면 space 제한 없이 재검색
     (`ConfluenceSearchAgent.kt:108-123`)
2. **스페이스 내 iOS 혼재** — 온보딩 위키 페이지가 속한 `ProductApp` 스페이스에는
   Android와 iOS 문서가 함께 있다. 스페이스 스코핑만으로는 iOS 문서가 그대로 섞인다.

### 확인된 사실 (실제 Confluence 조회, 2026-06-29)

- 온보딩 위키 페이지 `5912232879` = **"[Android] 프로젝트 온보딩 가이드"**, 스페이스 `ProductApp`.
  **자식 페이지 없음** (단일 종합 페이지). 온보딩 전 주제(셋업·구조·도메인·Compose·git·QA·PR·
  테스트·스킬·CI/CD)를 H2/H3 섹션으로 모두 담는다.
- `ProductApp`에 **Confluence 라벨이 없다** (`label = "ios"` 0건). 라벨 기반 필터 불가.
- iOS 문서 제목이 불규칙: `[iOS] ...`, `... (iOS)`, `컬리앱(iOS) 장애 보고서`,
  `v3.78.0 Release Note Android/iOS`(공용) 등. 단일 CQL 필터로 Android/iOS를 깨끗이
  분리하는 것은 불가능(양방향 누수).

## 2. 목표 & 비목표

### 목표
- **Android 온보딩 위키 페이지를 SSOT로 유지**한다.
- 온보딩 질문 경로의 Confluence 라이브 검색을 **`ProductApp` 스페이스로 한정**하고,
  space 확장·global fallback로 인한 무관 스페이스 유입을 차단한다.
- `ProductApp` 안의 iOS/공통 문서를 **걸러내지 않고 출처를 구분 표시**(라벨링)해,
  Android 권위 콘텐츠와 섞여 오염되는 것을 방지한다. LLM과 사용자가 "iOS 참조 / 공통"임을
  알 수 있게 한다.

### 비목표 (이번 범위 아님)
- **가이드 경로(`ContentGatherer.gather`)는 불변**. 이미 Android 위키 페이지 H2 섹션만 쓴다.
- **일반 Q&A 검색 동작 불변**. 온보딩 외 경로는 space 확장·global fallback을 그대로 유지한다.
- **코드 검색(`codeContent`) 불변**. 레포 자체가 kurly-android라 범위 내로 본다.
- iOS 문서를 검색 결과에서 제외(필터링)하지 않는다 — 라벨링만 한다.

## 3. 설계 개요 & 책임 분리

변경은 **질문 경로(`gatherForQuestion`)**에 집중된다.

| 모듈 | 책임 | iOS/온보딩 인지 여부 |
|------|------|---------------------|
| `search` (`ConfluenceSearchAgent`, `ConfluenceTool`) | "이 스페이스로만, fallback 없이" 검색하는 **범용 능력** + 구조화 결과 노출 | **모름** — 호출자가 데이터로 전달 |
| `onboarding` (`ContentGatherer`) | 결과를 제목+스니펫 토큰으로 Android/iOS/공통 분류 + 라벨 렌더링 | 플랫폼 인지 |
| `curriculum.yaml` / `OnboardingCurriculum` | SSOT 스페이스 선언 (`space: "ProductApp"`) | 선언만 |

"온보딩 여부"라는 개념이 `search` 모듈로 들어가지 않는다. 온보딩 호출자가
`strictSpaces = ["ProductApp"]`를 **파라미터로** 넘기면 스코프 한정 + fallback 비활성이 동작하고,
일반 Q&A는 파라미터를 안 넘겨(null) 현행 동작을 유지한다. `search` 모듈의 온보딩 결합도는 0.

## 4. 컴포넌트별 변경

### 4.1 `search` — `ConfluenceSearchAgent` (범용 능력 추가)

`searchStructured` / `search`에 옵션 파라미터 `strictSpaces: List<String>? = null` 추가.

```kotlin
suspend fun searchStructured(
    query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
    dateAfter: String? = null, dateBefore: String? = null,
    originalQuestion: String = "",
    strictSpaces: List<String>? = null,   // null = 기존 동작 그대로
): List<SearchResult>
```

`strictSpaces != null`일 때:
- 검색 대상 스페이스로 인스턴스 `spaces` 대신 `strictSpaces`를 사용한다.
- **space 확장 검색**(`emptyList()`로 전체 스페이스 검색, L77-80)을 **건너뛴다**.
- **global fallback**(space 제한 해제 재검색, L108-123)을 **건너뛴다**.
- title / text / keyword AND·OR fallback 파이프라인 자체는 유지한다 — 단지 스페이스 밖으로
  나가지 않는다.

온보딩은 `searchScopedStructured`(구조화)만 사용하므로 `search()` 문자열 버전에는
`strictSpaces`를 추가하지 않는다(YAGNI). `searchStructured`만 분기하면 충분하다.

### 4.2 `search` — `ConfluenceTool` (구조화 결과 노출)

ContentGatherer가 결과별 라벨링을 하려면 포맷된 문자열이 아니라 `List<SearchResult>`가 필요하다.

```kotlin
fun searchScopedStructured(query: String, spaces: List<String>): List<SearchResult> =
    runBlocking {
        tracker?.record("Confluence")
        searchAgent.searchStructured(query, strictSpaces = spaces)
    }
```

### 4.3 `onboarding` — `OnboardingCurriculum` (SSOT 스페이스 선언)

```kotlin
@Serializable
data class OnboardingCurriculum(
    val lastUpdated: String,
    val space: String? = null,   // 신규: 온보딩 SSOT 스페이스 키
    val phases: List<CurriculumStep>,
)
```

`curriculum.yaml` 최상위에 추가:

```yaml
lastUpdated: "2026-06-05"
space: "ProductApp"
phases:
  ...
```

`space`가 null이면 스코핑·라벨링을 생략하고 기존 경로를 유지한다(안전 폴백).

### 4.4 `onboarding` — `OnboardingTool`

`wikiPageId`와 동일한 패턴으로 `curriculum?.space`를 lazy로 읽어 `ContentGatherer`에
`onboardingSpace`로 주입한다.

```kotlin
private val onboardingSpace: String? by lazy { curriculum?.space }
// gatherer 생성 시 onboardingSpace = onboardingSpace 전달
```

`handleQuestion`의 `questionPrompt`에 플랫폼 구분 규칙 1개 추가 (4.7 참고).

### 4.5 `onboarding` — `ContentGatherer` (핵심)

생성자에 `onboardingSpace: String?` 추가.

`gatherForQuestion`의 Confluence 수집을 분기한다.
- `onboardingSpace != null` →
  `confluenceTool.searchScopedStructured(question, listOf(onboardingSpace))`를 호출하고,
  각 `SearchResult`를 플랫폼 분류하여 라벨이 붙은 `GatheredContent`로 변환한다.
- `onboardingSpace == null` → 기존 `confluenceContent(question)` 경로 유지.

`codeContent`는 불변. 현재 단계 위키 섹션 수집도 불변(Android 페이지 → 항상 Android).

### 4.6 플랫폼 분류 (제목 + 스니펫 토큰 스코어링)

라벨이 없고 제목만으로는 부족하므로(Android 문서에 "android"가 없는 경우가 많음),
**제목 + 스니펫**을 함께 스캔한다. **ANDROID가 기본값**이며 Android 토큰을 요구하지 않는다 —
iOS 신호가 없으면 Android로 본다.

```kotlin
enum class Platform { ANDROID, IOS, SHARED }

// 단어 경계 매칭 — "kiosk"가 "ios"로 오탐되는 것 방지
private val IOS_TOKENS = Regex(
    """\b(ios|swift|swiftui|uikit|xcode|cocoapods|testflight|kurly-ios)\b|아이폰""",
    RegexOption.IGNORE_CASE)
private val ANDROID_TOKENS = Regex(
    """\b(android|kotlin|compose|gradle|hilt|jetpack|kurly-android)\b|안드로이드""",
    RegexOption.IGNORE_CASE)

fun classify(title: String, snippet: String): Platform {
    val text = "$title\n$snippet"
    val ios = IOS_TOKENS.containsMatchIn(text)
    val android = ANDROID_TOKENS.containsMatchIn(text)
    return when {
        ios && android -> Platform.SHARED   // 양쪽 토큰 → 공통 (예: "Release Note Android/iOS")
        ios            -> Platform.IOS       // iOS 토큰만 → iOS 참조
        else           -> Platform.ANDROID   // 기본값 (Android 토큰 불필요)
    }
}
```

**알려진 한계 (의도적 수용)**: 제목·스니펫에 플랫폼 토큰이 전혀 없는 iOS 페이지는 ANDROID(기본)로
분류된다. 이런 페이지는 드물고, 보조 검색 결과일 뿐이며, LLM에 "📄 위키가 SSOT·권위 출처"임을
명시하므로 영향이 제한적이다. (정확도를 더 높이려면 결과별 ancestor 조회가 필요하나 결과당 API
호출이 늘어 이번 범위에서 제외.)

### 4.7 렌더링 & LLM 프롬프트

`GatheredContent`에 `platform: Platform = ANDROID` 필드 추가. `formatBlocks`가 마커를 부여한다.
각 `SearchResult`는 개별 `GatheredContent`(제목+스니펫+링크)로 변환되어 결과별 라벨이 붙는다.

```
=== 🔗 연관문서: <Android 제목> ===                   ← ANDROID, 마커 없음
=== 🔗 연관문서 [🍎 iOS 참조]: <제목> ===              ← IOS
=== 🔗 연관문서 [🔀 Android·iOS 공통]: <제목> ===       ← SHARED
```

`handleQuestion`의 `questionPrompt`에 규칙 추가:

```
- [🍎 iOS 참조]·[🔀 Android·iOS 공통] 표시 자료는 iOS 또는 공통 플랫폼 내용입니다.
  Android 온보딩 답변의 권위 있는 출처로 쓰지 말고, 필요 시 "(iOS 참조)"로만 인용하세요.
  SSOT는 📄 위키 자료입니다.
```

## 5. 데이터 흐름 (질문 경로, `onboardingSpace != null`)

```
사용자 질문
  → gatherForQuestion(question, currentStep)
      ├─ 현재 단계 위키 섹션 (Android 페이지 H2)            → GatheredContent(WIKI, ANDROID)
      ├─ codeContent(question)                              → GatheredContent(CODE, ANDROID) [불변]
      └─ searchScopedStructured(question, ["ProductApp"])   → List<SearchResult>
            → 각 결과 classify(title, snippet)
            → GatheredContent(CONFLUENCE, platform) 목록
  → formatBlocks(...)  // 플랫폼 마커 부여
  → questionPrompt (SSOT·참조 규칙 포함)
  → LLM
```

## 6. 테스트 전략 (전부 L1 Unit)

### `onboarding` — `ContentGathererTest` 추가

플랫폼 분류 `classify` (순수 함수, 표 기반):

| 입력(title / snippet) | 기대 |
|---|---|
| `[iOS] App Intent` / — | IOS |
| `2026.06.22 (iOS)` / — | IOS |
| `v3.78.0 Release Note Android/iOS` / — | SHARED |
| `프로젝트 온보딩 가이드` (토큰 없음) / — | ANDROID (기본값) |
| 제목 토큰 없음 / 스니펫에 `kurly-ios`·`아이폰` | IOS (스니펫 보완) |
| `kiosk 결제` / — (단어경계 오탐 방지) | ANDROID |
| `[iOS] ...` / 스니펫에 android 토큰 | SHARED |

스코프 한정 수집 (fake `ConfluenceTool`):
- `onboardingSpace != null` → `searchScopedStructured(q, ["ProductApp"])`가 호출되고,
  결과가 플랫폼 라벨이 붙은 `GatheredContent`로 변환되는지
- `onboardingSpace == null` → 기존 `confluenceContent` 폴백 경로가 유지되는지

렌더링 `formatBlocks`:
- IOS/SHARED 항목에 `[🍎 iOS 참조]`·`[🔀 Android·iOS 공통]` 마커, ANDROID엔 마커 없음

### `search` — `ConfluenceSearchAgentTest` 추가

- `strictSpaces` 지정 시 space 확장·global fallback 경로가 호출되지 않음
  (fake/stub client 호출 인자로 검증 — `searchByTitle(spaces=emptyList())` 미호출,
  global `searchByText(spaces=emptyList())` 미호출)
- `strictSpaces = null` → 기존 동작 회귀 없음

## 7. 변경 파일 요약

| 파일 | 변경 |
|------|------|
| `search/.../ConfluenceSearchAgent.kt` | `searchStructured`에 `strictSpaces` 파라미터 + 분기 |
| `search/.../tool/ConfluenceTool.kt` | `searchScopedStructured(query, spaces)` 추가 |
| `onboarding/.../OnboardingCurriculum.kt` | `OnboardingCurriculum.space: String?` 추가 |
| `onboarding/.../OnboardingTool.kt` | `onboardingSpace` lazy + gatherer 주입 + questionPrompt 규칙 |
| `onboarding/.../ContentGatherer.kt` | `onboardingSpace` 생성자, `Platform`/`classify`, `gatherForQuestion` 분기, `GatheredContent.platform`, `formatBlocks` 마커 |
| `.wiki/onboarding/curriculum.yaml` | 최상위 `space: "ProductApp"` |
| `onboarding/.../ContentGathererTest.kt` | 분류·스코프·렌더링 테스트 |
| `search/.../ConfluenceSearchAgentTest.kt` | strictSpaces 테스트 |

## 8. 리스크 & 대응

- **토큰 분류 누수**: 토큰 없는 iOS 페이지 → ANDROID 오분류. (4.6 한계 참고) SSOT 페이지가
  답변을 지배하고 LLM이 위키를 권위 출처로 보므로 영향 제한적.
- **공유 컴포넌트 회귀**: `ConfluenceSearchAgent`는 일반 Q&A와 공유. `strictSpaces` 기본 null로
  기존 경로를 100% 보존하고, 회귀 테스트로 검증.
- **`space` 미설정**: curriculum에 `space` 누락 시 null → 기존 동작 폴백(스코핑·라벨링 생략).
