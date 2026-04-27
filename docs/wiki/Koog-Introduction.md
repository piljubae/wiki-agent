# Koog 프레임워크 소개

## 개요

**Koog**는 JetBrains가 개발한 Kotlin-native AI 에이전트 프레임워크입니다.

- **버전:** 0.8.0 (2026년 4월 기준)
- **Maven:** `ai.koog:koog-agents-jvm:0.8.0`
- **Repository:** [JetBrains/koog](https://github.com/JetBrains/koog)
- **문서:** [docs.koog.ai](https://docs.koog.ai)

## 지원 플랫폼

JVM, JS, WasmJS, Android, iOS (Kotlin Multiplatform)

## 지원 LLM Provider

| Provider | 패키지 |
|----------|--------|
| Anthropic (Claude) | `prompt-executor-anthropic-client-jvm` |
| Google (Gemini) | `prompt-executor-google-client-jvm` |
| OpenAI | `prompt-executor-openai-client-jvm` |
| DeepSeek | 별도 client |
| Ollama | 별도 client |

## 다른 프레임워크와 비교

| 항목 | LangChain | LlamaIndex | **Koog** |
|------|-----------|-----------|---------|
| 주 언어 | Python | Python | **Kotlin** |
| JVM 생태계 | ❌ | ❌ | ✅ |
| 타입 안전성 | 낮음 | 낮음 | **높음** |
| 에이전트 간 통신 | 직접 구현 | 직접 구현 | **A2A 내장** |
| Android/iOS 지원 | ❌ | ❌ | ✅ |
| Spring Boot 통합 | ✅ | ✅ | ✅ |

## build.gradle.kts 설정

```kotlin
repositories {
    maven("https://packages.jetbrains.team/maven/p/koog/public")
}

dependencies {
    implementation("ai.koog:koog-agents-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-anthropic-client-jvm:0.8.0")
    // Google 사용 시
    implementation("ai.koog:prompt-executor-google-client-jvm:0.8.0")
}
```

---

> **Reference:** [github.com/JetBrains/koog](https://github.com/JetBrains/koog) · [docs.koog.ai](https://docs.koog.ai)
