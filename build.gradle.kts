plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/koog/public")
}

dependencies {
    implementation("ai.koog:koog-agents-jvm:0.8.0")
    implementation("ai.koog:agents-features-a2a-server-jvm:0.8.0")
    implementation("ai.koog:agents-features-a2a-client-jvm:0.8.0")
    implementation("ai.koog:a2a-transport-server-jsonrpc-http-jvm:0.8.0")
    implementation("ai.koog:a2a-transport-client-jsonrpc-http-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-anthropic-client-jvm:0.8.0")
    implementation("ai.koog:prompt-executor-google-client-jvm:0.8.0")
    implementation("com.slack.api:bolt:1.46.0")
    implementation("com.slack.api:bolt-socket-mode:1.46.0")
    implementation("org.glassfish.tyrus.bundles:tyrus-standalone-client:1.21")
    implementation("com.neovisionaries:nv-websocket-client:2.14")
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("io.ktor:ktor-server-cio:3.1.2")
    implementation("io.ktor:ktor-server-core:3.1.2")
    implementation("com.charleskorn.kaml:kaml:0.67.0")
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation(project(":callgraph-plugin"))
    implementation(project(":context"))
    implementation(project(":config"))
    implementation(project(":github"))
    implementation(project(":rag"))
    implementation(project(":confluence"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.veronikapj.wiki.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform {
        excludeTags("eval", "generate", "smoke")
    }
}

tasks.register<Test>("smokeTest") {
    useJUnitPlatform {
        includeTags("smoke")
    }
}

tasks.register<Test>("evalTest") {
    useJUnitPlatform {
        includeTags("eval")
    }
}

tasks.register<Test>("generateGoldenDataset") {
    useJUnitPlatform {
        includeTags("generate")
    }
}

configurations.all {
    resolutionStrategy.force(
        "com.fasterxml.jackson.core:jackson-databind:2.15.2",
        "com.fasterxml.jackson.core:jackson-core:2.15.2",
        "com.fasterxml.jackson.core:jackson-annotations:2.15.2",
    )
}
