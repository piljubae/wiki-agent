# :onboarding

> 한 줄 요약: 신규 입사자가 슬랙에서 **단계별 온보딩 커리큘럼**을 진행하도록 안내하는 모듈 — 진도 관리 + 단계별 위키/코드 자료 안내 + 질문 응답.

## 이게 왜 필요한가

처음 합류한 사람이 "뭐부터 봐야 하나"를 묻지 않아도 되도록, 미리 정의한 커리큘럼(`curriculum.yaml`)을 따라 한 단계씩 안내한다. 사용자가 봇에게 말하면 현재 단계의 위키 문서·코드를 찾아 설명하고, 다음 단계로 넘기거나 진도를 기록한다.

## 핵심 개념

- **커리큘럼(Curriculum)** — `curriculum.yaml`에 정의된 단계(step) 목록. 각 단계는 제목·설명과 참고 자료(`ContentSource`: Confluence 페이지/코드 등)를 가진다.
- **세션(OnboardingSession)** — 사용자별 진행 상태(현재 단계, 완료/건너뛴 단계, 메모). 마크다운 파일로 영속화된다.
- **레벨(UserLevel)** — 사용자 수준에 따라 보여줄 단계를 필터링.

## 무엇을 제공하나 (공개 API)

### `OnboardingTool` — 온보딩 대화 진입점

사용자 메시지를 받아 의도(시작/다음/건너뛰기/진도/질문 등)를 분류하고, 현재 단계의 위키 섹션·코드를 찾아 안내 메시지를 만든다. 위키는 Confluence 페이지를 H2 섹션으로 파싱해 단계별로 보여준다.

생성자에 검색 도구·클라이언트·LLM 실행기를 주입받는다(아래 의존 참고).

### `OnboardingSession` / `OnboardingSessionStore`

세션 데이터 모델과 마크다운 파일 영속화(`toMd`/`parseMd`).

### `OnboardingCurriculum` / `CurriculumLoader`

`@Serializable` 커리큘럼 모델과 `curriculum.yaml`을 kaml로 로딩하는 로더.

## 의존성

공개(`api`) — `OnboardingTool` 생성자가 타입을 노출하므로 소비자에게 전파:
- `:search` (`ConfluenceTool`, `CodeSearchTool`, `SourceTracker`)
- `:confluence` (`ConfluenceClient`), `:github` (`GitHubCodeClient`)
- `ai.koog:koog-agents` (`MultiLLMPromptExecutor`, `LLModel`)

내부(`implementation`):
- `ai.koog:prompt-executor-anthropic-client` (모델/파라미터 설정에만 사용)
- `com.charleskorn.kaml` (curriculum.yaml 파싱), `kotlinx-serialization-json`, `kotlinx-coroutines-core`, `slf4j-api`

> `@Serializable` 커리큘럼 모델을 위해 `kotlin("plugin.serialization")` 플러그인을 적용한다. Koog 전용 Maven 저장소 + 루트와 동일한 jackson 버전 강제도 포함.

## 테스트

```bash
./gradlew :onboarding:test
```

`OnboardingSessionStoreTest` — 세션 저장/파싱(toMd/parseMd) round-trip, 진도 전이.
`OnboardingToolTest` — 의도 분류, 단계 안내 생성 (검색/LLM은 mockk로 대체).

---

이 모듈은 wiki-agent 모듈화의 일부로 분리됐다. 전체 그림은 [모듈화 로드맵](../docs/plans/2026-06-09-modularization-roadmap.md) 참고.
