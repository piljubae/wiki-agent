# 프로젝트 메모리 (ProjectMemory)

## 핵심 질문

> 팀이나 도메인 문맥을 봇에게 어떻게 알려주나요?

## 개요

`ProjectMemory`는 팀/도메인 정보를 `.wiki/memory.md`에 저장하고, 모든 LLM 프롬프트에 자동으로 삽입합니다.  
"클라이언트 = 모바일 앱" 같은 회사 내부 용어나 팀 이름을 알려두면 검색·답변 품질이 높아집니다.

## 저장 위치

```
.wiki/
└── memory.md   ← 줄바꿈 기반 bullet 목록
```

## 슬래시 커맨드

```
/askpj memory add 모바일 앱(iOS/Android) 개발팀
/askpj memory add 클라이언트 = 모바일 앱 (고객 아님)
/askpj memory add 주요 검색: 배포 프로세스, 온보딩, 기술 문서
/askpj memory show    ← 저장된 내용 확인
/askpj memory clear   ← 전체 삭제
```

## 저장 형식

```markdown
- 모바일 앱(iOS/Android) 개발팀
- 클라이언트 = 모바일 앱 (고객 아님)
- 주요 검색: 배포 프로세스, 온보딩, 기술 문서
```

## LLM 프롬프트 삽입 위치

```kotlin
memory?.let {
    appendLine("# 프로젝트 정보")
    appendLine(it)
}
```

시스템 프롬프트 또는 답변 생성 프롬프트의 맨 앞에 포함됩니다.

## 온보딩과의 관계

DM에서 온보딩 4단계를 완료하면 수집한 팀 정보가 자동으로 `ProjectMemory`에 저장됩니다.  
온보딩 없이 `/askpj memory add`로 직접 입력해도 동일하게 동작합니다.

→ [온보딩 플로우](Onboarding-Flow) 참고

## 스레드 안전성

여러 Slack 이벤트가 동시에 `memory.md`에 쓰기를 시도할 수 있어 `ReentrantLock`으로 보호합니다:

```kotlin
private val lock = ReentrantLock()

fun add(content: String) = lock.withLock {
    file.appendText("- $content\n")
}
```

---

> **Source:** [ProjectMemory.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/context/ProjectMemory.kt) · [SlackConfigHandler.kt](https://github.com/Veronikapj/wiki-agent/blob/main/src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt)
