# Per-User Persona Selection Design

**Date:** 2026-05-11  
**Status:** Approved

## Problem

현재 페르소나는 `.wikiq/config.yml`에 고정되어 있어 서버 재시작 없이 변경 불가. 모든 사용자에게 동일한 페르소나가 적용됨.

## Goal

Slack 사용자별로 독립적인 페르소나를 선택·저장할 수 있게 한다. Home Tab의 드롭다운 UI로 선택하고, 파일에 영속된다.

## Design

### 1. 데이터 계층: `UserPersonaStore`

새 파일 `slack/UserPersonaStore.kt`:

- `Map<String, PersonaType>` 인메모리 저장
- `~/.wikiq/user-personas.json`으로 영속 (서버 시작 시 로드, 변경 시 즉시 저장)
- `get(userId: String): PersonaType?`
- `set(userId: String, persona: PersonaType)`

### 2. OrchestratorAgent 변경

생성자 파라미터 변경:
- Before: `persona: PersonaType = PersonaType.DEFAULT`
- After: `userPersonaStore: UserPersonaStore, defaultPersona: PersonaType = PersonaType.DEFAULT`

메시지 처리 시 userId 기반 룩업:
```kotlin
val effectivePersona = userPersonaStore.get(userId)?.description
    ?: defaultPersona.description
```

### 3. Home Tab UI

기존 액션 버튼 섹션 아래에 페르소나 섹션 추가:

```
🎭 페르소나 설정
나에게 적용되는 응답 스타일을 선택하세요.

[현재: 기본 (DEFAULT)  ▼]
```

- Slack `static_select` (action ID: `home_persona_select`)
- Home Tab 열 때마다 현재 userId의 설정값이 기본 선택으로 표시
- 11가지 PersonaType 모두 옵션으로 제공

### 4. Block Action 처리

`home_persona_select` 액션 핸들러:
1. userId, 선택된 persona 값 추출
2. `UserPersonaStore.set(userId, persona)`
3. Home Tab 리프레시 (선택값 즉시 반영)
4. DM: "✅ 페르소나가 [이름]으로 변경되었습니다"

## Flow

```
사용자 메시지 수신
    ↓
SlackBotGateway (userId 추출)
    ↓
OrchestratorAgent.handle(userId, message)
    ↓
userPersonaStore.get(userId) ?: defaultPersona
    ↓
LLM 프롬프트에 페르소나 description 주입
```

## Files Changed

| 파일 | 변경 |
|------|------|
| `slack/UserPersonaStore.kt` | 신규 |
| `slack/SlackBotGateway.kt` | Home Tab UI + block action 핸들러 |
| `agent/OrchestratorAgent.kt` | persona 파라미터 교체 |
| `Main.kt` | UserPersonaStore 초기화 및 주입 |
