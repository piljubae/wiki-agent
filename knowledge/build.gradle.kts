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
    // public API(생성자/@Tool)에 노출되는 모듈은 api로 전파
    // (CodeIndexAgent/PrIndexAgent: GitHubCodeClient·ChromaClient·BM25Index,
    //  KnowledgeTool: SourceTracker·@Tool 애너테이션)
    api(project(":search"))
    api(project(":github"))
    api(project(":rag"))
    api("ai.koog:koog-agents-jvm:0.8.0")

    // CallGraphIndexAgent는 dbPath(String)만 받고 CallGraphDb는 내부 전용 → implementation
    implementation(project(":callgraph-plugin"))
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform {
        // 루트 test 정책과 동일하게 제외. (현재 knowledge 테스트엔 해당 태그가 없어
        // 실질 제외 대상은 없으며, @Tag("integration") 테스트는 외부 서비스 없이
        // 로컬 로직만 검증하므로 루트와 동일하게 실행된다.)
        excludeTags("eval", "generate", "smoke")
    }
}

configurations.all {
    resolutionStrategy.force(
        "com.fasterxml.jackson.core:jackson-databind:2.15.2",
        "com.fasterxml.jackson.core:jackson-core:2.15.2",
        "com.fasterxml.jackson.core:jackson-annotations:2.15.2",
    )
}
