plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/koog/public")
}

dependencies {
    // OnboardingTool 생성자가 이 타입들을 노출하므로 api로 전파
    // (executor: MultiLLMPromptExecutor, model: LLModel → koog;
    //  ConfluenceTool/CodeSearchTool/SourceTracker → search;
    //  ConfluenceClient → confluence; GitHubCodeClient → github)
    api(project(":search"))
    api(project(":confluence"))
    api(project(":github"))
    api("ai.koog:koog-agents-jvm:0.8.0")

    // AnthropicModels/Params는 내부 프롬프트 설정에만 사용 → implementation
    implementation("ai.koog:prompt-executor-anthropic-client-jvm:0.8.0")
    implementation("com.charleskorn.kaml:kaml:0.67.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

configurations.all {
    resolutionStrategy.force(
        "com.fasterxml.jackson.core:jackson-databind:2.15.2",
        "com.fasterxml.jackson.core:jackson-core:2.15.2",
        "com.fasterxml.jackson.core:jackson-annotations:2.15.2",
    )
}
