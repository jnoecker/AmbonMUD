plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = "com.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")

    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("org.mindrot:jbcrypt:0.4")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.ambon.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Runs QuickMUD and opens the browser demo client."
    mainClass.set(application.mainClass)
    classpath = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)["main"].runtimeClasspath
    standardInput = System.`in`
    systemProperty("ambonmud.deployment.demoAutoLaunchBrowser", "true")
}

ktlint {
    verbose.set(true)
    android.set(false)
}
