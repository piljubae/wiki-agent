plugins {
    kotlin("jvm")
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/koog/public")
}

dependencies {
    implementation(project(":config"))

    implementation("ai.koog:koog-agents-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-anthropic-client-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-google-client-jvm:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
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
