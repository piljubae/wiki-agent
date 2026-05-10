# Dual-Provider Router 설계 — Gemini Flash 라우팅 + CLAUDE_CODE 답변

## 배경

현재 wiki-agent는 모든 LLM 콜(라우팅 + 답변)에 `CLAUDE_CODE` provider를 사용한다.
CLAUDE_CODE는 subprocess 방식이라 콜당 10-15s 소요 → 총 응답시간 25-35s.

사용자가 보유한 GOOGLE_API_KEY(Gemini)를 라우팅 전용으로 쓰면
라우팅 단계를 ~1s로 단축할 수 있다.

---

## 목표

- Phase 1 응답(검색 중...) 시작 시간: 10-15s → ~1s
- 최종 답변 품질: CLAUDE_CODE 유지
- 설정 기반 분리 → 나중에 provider 교체 용이

---

## 아키텍처

### Config 구조

```yaml
# .wikiq/config.yml
model:
  provider: CLAUDE_CODE      # 최종 답변용 (기존)

router:
  provider: GOOGLE           # 라우팅 전용 (NEW)
  # model 생략 시 기본값: Gemini2_5Flash
```

`router` 섹션 없으면 `routerExecutor = executor` fallback → 하위 호환 유지.

### OrchestratorAgent

```kotlin
class OrchestratorAgent(
    val executor: PromptExecutor,                         // 답변용
    val routerExecutor: PromptExecutor = executor,        // 라우팅용
    ...
)
```

`routerExecutor`는 도구 선택(router) LLM 콜에만 사용.
최종 답변 생성(answerWithManualLoop / Koog path)은 `executor` 그대로.

### Main.kt

```kotlin
val routerExecutor = configProvider.routerConfig?.let {
    LLMExecutorBuilder(config = it, env = env).build()
} ?: executor

val orchestrator = OrchestratorAgent(
    executor = executor,
    routerExecutor = routerExecutor,
    ...
)
```

---

## 파일 변경 범위

| 파일 | 변경 내용 |
|------|----------|
| `config/WikiConfig.kt` | `RouterConfig` data class 추가 |
| `config/ConfigProvider.kt` | `routerConfig` 파싱 추가 |
| `agent/OrchestratorAgent.kt` | `routerExecutor` 파라미터 추가, router 콜에 적용 |
| `Main.kt` | `routerExecutor` 빌드 + 주입 |
| `.wikiq/config.yml` | `router:` 섹션 추가 |

---

## 예상 효과

| 단계 | 현재 | 개선 후 |
|------|------|---------|
| 라우팅 (Phase 1) | ~10-15s | ~1s |
| 답변 생성 (Phase 2) | ~10-15s | ~10-15s (동일) |
| **총 응답** | **25-35s** | **~12-17s** |

---

## Phase 2 (선택, 나중에)

Gemini를 답변 생성에도 사용해 총 응답을 ~2-3s로 단축.
품질 검증 후 결정.
