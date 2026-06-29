plugins {
    kotlin("jvm")
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
