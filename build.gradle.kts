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
val hopliteVersion = "2.9.0"
val micrometerVersion = "1.14.5"

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    implementation("com.sksamuel.hoplite:hoplite-core:$hopliteVersion")
    implementation("com.sksamuel.hoplite:hoplite-yaml:$hopliteVersion")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("io.micrometer:micrometer-core:$micrometerVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.ambon.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Testcontainers scans PATH to detect docker-machine. On Windows, Microsoft Store Python
    // shim paths contain illegal characters (<, >) that crash java.nio.file.Paths.get().
    // Strip those entries from the test JVM's PATH so the scan never sees them.
    val badMarkers =
        listOf(
            "PythonSoftwareFoundation.Python",
            "WindowsApps",
        )
    val original = System.getenv("PATH") ?: ""
    val filtered =
        original
            .split(";")
            .filter { entry -> badMarkers.none { marker -> entry.contains(marker, ignoreCase = true) } }
            .joinToString(";")
    environment("PATH", filtered)
    // Docker Desktop on Windows (WSL2 backend) exposes the daemon on a non-default named pipe.
    // Set DOCKER_HOST so Testcontainers' EnvironmentAndSystemPropertyClientProviderStrategy
    // connects to the right pipe instead of the stub //./pipe/docker_engine.
    if (System.getProperty("os.name", "").contains("Windows", ignoreCase = true)) {
        environment("DOCKER_HOST", "npipe:////./pipe/docker_cli")
    }
}

// Map -Pconfig.X=Y project properties to config.override.X system properties.
// Provides a shell-safe, uniform way to override any Hoplite config value at runtime.
// Example: ./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
//          ./gradlew run -Pconfig.ambonMUD.server.tickMillis=500
fun JavaExec.applyConfigOverrides() {
    project.properties
        .filter { (k, _) -> k.startsWith("config.") }
        .forEach { (k, v) -> systemProperty("config.override.$k", v.toString()) }
}

tasks.named<JavaExec>("run") {
    applyConfigOverrides()
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Runs AmbonMUD and opens the browser demo client."
    mainClass.set(application.mainClass)
    classpath = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)["main"].runtimeClasspath
    standardInput = System.`in`
    systemProperty("config.override.ambonMUD.demo.autoLaunchBrowser", "true")
    applyConfigOverrides()
}

ktlint {
    verbose.set(true)
    android.set(false)
}
