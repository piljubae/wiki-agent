plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
    repositories { mavenLocal() }
}
