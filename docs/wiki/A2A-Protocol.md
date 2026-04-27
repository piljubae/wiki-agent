# A2A Protocol

## 개요

**A2A (Agent-to-Agent)** 는 에이전트끼리 HTTP JSON-RPC로 통신하는 프로토콜입니다.  
Koog 0.8.0에 내장되어 있으며, 현재는 **ACP (Agent Client Protocol)** 로도 불립니다.

## wiki-agent에서의 역할

wiki-agent는 A2A의 기본 구조를 따르지만, 현재 구현은 **단일 프로세스 내 직접 호출** 방식입니다.

```
OrchestratorAgent
    └── ConfluenceTool → ConfluenceSearchAgent  (같은 프로세스 내 호출)
```

A2A를 활성화하면 Specialist들을 독립 서비스로 분리할 수 있습니다.

## A2A 확장 방향

```
OrchestratorAgent (서버 A)
    └── HTTP JSON-RPC → ConfluenceSearchAgent (서버 B, 별도 프로세스)
                     → GitHubWikiSearchAgent (서버 C, 별도 프로세스)
```

## build.gradle.kts — A2A 의존성

```kotlin
implementation("ai.koog:agents-features-a2a-server-jvm:0.8.0")
implementation("ai.koog:agents-features-a2a-client-jvm:0.8.0")
implementation("ai.koog:a2a-transport-server-jsonrpc-http-jvm:0.8.0")
implementation("ai.koog:a2a-transport-client-jsonrpc-http-jvm:0.8.0")
```

## Orchestrator + Specialist 패턴과의 관계

| 패턴 요소 | A2A 없을 때 | A2A 사용 시 |
|---------|------------|------------|
| Orchestrator | 동일 프로세스에서 Tool 직접 호출 | HTTP로 Specialist 에이전트 호출 |
| Specialist | Tool 클래스 | 독립 HTTP 서비스 |
| 확장성 | 단일 서버 | 마이크로서비스 |

---

> **Reference:** [Koog A2A Protocol](https://docs.koog.ai) · [build.gradle.kts](https://github.com/Veronikapj/wiki-agent/blob/main/build.gradle.kts)
