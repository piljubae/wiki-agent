plugins {
    kotlin("jvm")
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform {
        // 실제 Confluence에 접속하는 통합테스트(ConfluenceClientIntegrationTest)는 제외
        excludeTags("eval")
    }
}
