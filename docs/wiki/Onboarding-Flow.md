# 온보딩 플로우

## 핵심 질문

> 봇에게 처음 멘션하면 왜 설정 질문이 나오나요?

## 개요

봇에게 프로젝트 정보가 없을 때 (`.wiki/memory.md`가 없을 때) 채널 멘션(`@배필주2`)의 첫 메시지에서 4단계 온보딩이 시작됩니다.  
수집된 정보는 `ProjectMemory`에 저장되어 이후 모든 검색 품질을 높입니다.

> **참고:** 주요 Q&A 진입점은 Slack AI 패널(App Assistant)입니다. 온보딩은 채널 멘션(`@배필주2`) 경로에서만 트리거됩니다.

## 4단계 흐름

```
[1단계] 팀/조직 이름과 역할
        예: 모바일 앱(iOS/Android) 개발팀, 프로덕트앱개발 트라이브
    ↓
[2단계] 도메인 용어 (일반적 의미와 다른 것)
        예: 클라이언트 = 모바일 앱(고객 아님), PR = Pull Request
        없으면 "없음" 입력
    ↓
[3단계] 주요 검색 대상 유형
        예: 온보딩 가이드, 배포 프로세스, 기술 문서, 회의록
    ↓
[4단계] Confluence 스페이스 선택
        → 팀 정보 기반 자동 추천 제공
        → 스페이스 키를 쉼표로 입력: DEV, PM, HR
```

## 스페이스 자동 추천

4단계에서 Confluence API로 전체 스페이스 목록을 조회하고, 앞서 입력한 팀 정보와 키워드를 매칭해 추천합니다:

```kotlin
val recommended = collabSpaces.filter { space ->
    val lower = (space.key + " " + space.name).lowercase()
    keywords.any { lower.contains(it) }
}
```

## 온보딩 트리거 조건

```kotlin
private val needsOnboarding: Boolean get() {
    if (onboardingDone == true) return false
    val hasMem = projectMemory?.load() != null
    if (hasMem) onboardingDone = true
    return !hasMem
}
```

- `ProjectMemory`가 비어있는 경우에만 트리거
- 온보딩 완료 후 `onboardingDone = true`로 캐싱 (매 메시지마다 파일 I/O 방지)

## 온보딩 완료 후

```
설정 완료! 🎉 스페이스: DEV, PM
이제 질문하시면 Confluence에서 검색해 답변드립니다.
```

이후 채널 멘션은 바로 검색으로 처리됩니다. 저장된 정보는 `/askpj memory show`로 확인할 수 있습니다.

## 온보딩 건너뛰기

`/askpj memory add <내용>`으로 직접 정보를 저장하면 온보딩이 트리거되지 않습니다.

→ [프로젝트 메모리](Project-Memory) 참고

---

> **Source:** [SlackBotGateway.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt) · [ProjectMemory.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/context/ProjectMemory.kt)
