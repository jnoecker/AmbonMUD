plugins {
    kotlin("jvm") version "2.3.10"
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.ambon.swarm.SwarmMainKt")
}

tasks.test {
    useJUnitPlatform()
}
