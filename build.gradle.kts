import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration

plugins {
    kotlin("jvm") version "2.4.10"
    application
    jacoco
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.google.protobuf") version "0.10.0"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.1"
val hopliteVersion = "2.9.0"
val micrometerVersion = "1.17.0"
val grpcVersion = "1.83.0"
val grpcKotlinVersion = "1.5.0"
val protobufVersion = "4.35.1"
val exposedVersion = "1.3.1"

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:1.5.37")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.1")
    implementation("com.sksamuel.hoplite:hoplite-core:$hopliteVersion")
    implementation("com.sksamuel.hoplite:hoplite-yaml:$hopliteVersion")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("io.micrometer:micrometer-core:$micrometerVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")

    // Exposed (Kotlin SQL framework)
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    // Connection pool
    implementation("com.zaxxer:HikariCP:7.1.0")
    // JDBC driver
    implementation("org.postgresql:postgresql:42.7.13")
    // Schema migration
    implementation("org.flywaydb:flyway-core:13.1.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.1.0")

    // gRPC / Protobuf
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")

    // Test: H2 in Postgres mode
    testImplementation("com.h2database:h2:2.4.240")

    testImplementation("io.grpc:grpc-testing:$grpcVersion")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets-jvm:$ktorVersion")
}

kotlin {
    jvmToolchain(21)
}

val integrationTestTag = "integration"

application {
    mainClass.set("dev.ambon.MainKt")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "dev.ambon.MainKt"
    }
    // Flyway 10+ registers its location handlers and database types via
    // `META-INF/services/org.flywaydb.core.extensibility.Plugin`. Both
    // flyway-core (29 entries including ClasspathLocationHandlerImpl) and
    // flyway-database-postgresql (3 Postgres-specific entries) ship their
    // own copy. The shadow plugin's default `duplicatesStrategy = EXCLUDE`
    // drops one of them BEFORE the ServiceFileTransformer gets to merge,
    // so the fat jar ended up with only 3 entries and Flyway threw
    // `Unknown prefix for location (should be one of ): classpath:db/callback`
    // with an empty prefix list at startup. Setting duplicates INCLUDE
    // lets the transformer see both copies and concatenate them.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    transform(ServiceFileTransformer::class.java) {
        path = "META-INF/services"
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags(integrationTestTag)
    }
    // Prevent the entire test suite from hanging indefinitely (e.g. due to coroutine
    // leaks or gRPC streams that never close). Individual test timeouts are configured
    // in junit-platform.properties; this is a backstop for the Gradle process itself.
    timeout = Duration.ofMinutes(5)
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration-tagged tests."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags(integrationTestTag)
    }
    shouldRunAfter(tasks.test)
    timeout = Duration.ofMinutes(5)
}

tasks.check {
    dependsOn(integrationTest)
}

// Map -Pconfig.X=Y project properties to config.override.X system properties.
// Provides a shell-safe, uniform way to override any Hoplite config value at runtime.
// Example: ./gradlew run -Pconfig.ambonMUD.logging.level=DEBUG
//          ./gradlew run -Pconfig.ambonMUD.server.tickMillis=500
fun JavaExec.applyConfigOverrides() {
    project.properties
        .filter { (k, _) -> k.startsWith("config.") }
        .forEach { (k, v) -> systemProperty("config.override.${k.removePrefix("config.")}", v.toString()) }
}

tasks.named<JavaExec>("run") {
    applyConfigOverrides()
    // Work around JDK 21+ Windows bug where Netty's NIO selector fails with
    // "Unable to establish loopback connection" because PipeImpl defaults to
    // Unix domain sockets which can fail in some environments.
    jvmArgs("-Djava.net.preferIPv4Stack=true")
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Runs AmbonMUD and opens the browser demo client (worktree-stable ports)."
    mainClass.set(application.mainClass)
    classpath = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)["main"].runtimeClasspath
    standardInput = System.`in`
    systemProperty("config.override.ambonMUD.demo.autoLaunchBrowser", "true")
    applyConfigOverrides()
    jvmArgs("-Djava.net.preferIPv4Stack=true")

    // Parallel-worktree support: derive stable telnet/web ports from the
    // checkout directory name so several clones can run demo servers side by
    // side without fighting over :4000/:8080. The same worktree always gets
    // the same ports (restarts stay predictable; stale processes are easy to
    // attribute), probing upward if a port is genuinely taken. Explicit
    // -Pconfig.ambonMUD.server.telnetPort/webPort overrides win outright, and
    // AMBONMUD_PORT_OFFSET=<0-999> pins the offset. `run` keeps the fixed
    // defaults. The in-app browser auto-launch reads the final config, so the
    // demo client always opens at the right URL.
    val explicitTelnet = project.hasProperty("config.ambonMUD.server.telnetPort")
    val explicitWeb = project.hasProperty("config.ambonMUD.server.webPort")
    val worktreeName = rootDir.name
    val portOffsetEnv = providers.environmentVariable("AMBONMUD_PORT_OFFSET")
    doFirst {
        val offset = portOffsetEnv.orNull?.toIntOrNull()?.let { Math.floorMod(it, 1000) }
            ?: Math.floorMod(worktreeName.hashCode(), 1000)

        fun freePortFrom(start: Int): Int {
            var candidate = start
            repeat(50) {
                try {
                    ServerSocket(candidate).use { return candidate }
                } catch (_: IOException) {
                    candidate += 1
                }
            }
            throw GradleException("No free port found in $start..${start + 49}")
        }
        val exec = this as JavaExec
        val telnetPort = if (explicitTelnet) null else freePortFrom(14000 + offset)
        val webPort = if (explicitWeb) null else freePortFrom(18000 + offset)
        telnetPort?.let { exec.systemProperty("config.override.ambonMUD.server.telnetPort", it) }
        webPort?.let { exec.systemProperty("config.override.ambonMUD.server.webPort", it) }
        println(
            "AmbonMUD demo [$worktreeName] — telnet :${telnetPort ?: "(explicit override)"}, " +
                "web http://localhost:${webPort ?: "(explicit override)"}",
        )
    }
}

// ---------------------------------------------------------------------------
// Web client build
//
// Runs `bun run build` in web-v3/ to produce the static assets that get
// bundled into the fat JAR under src/main/resources/web-v3/.
// Skips gracefully with a warning if bun is not installed, so backend-only
// developers can still run and test the server without the web client.
// ---------------------------------------------------------------------------

val buildWeb by tasks.registering {
    group = "build"
    description = "Builds the web client (requires bun)."
    val webDir = layout.projectDirectory.dir("web-v3")
    val outputDir = layout.projectDirectory.dir("src/main/resources/web-v3")
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    // Only declare inputs when web-v3/ source exists (not in Docker builder stage
    // where only the pre-built output is copied from the frontend stage).
    if (webDir.file("package.json").asFile.exists()) {
        inputs.dir(webDir.dir("src"))
        inputs.file(webDir.file("package.json"))
        inputs.file(webDir.file("bun.lock"))
        inputs.file(webDir.file("vite.config.ts"))
        inputs.file(webDir.file("index.html"))
        inputs.file(webDir.file("tsconfig.json"))
    }
    outputs.dir(outputDir)
    doLast {
        if (!webDir.file("package.json").asFile.exists()) {
            logger.warn("⚠ web-v3/ source not found — skipping web client build (pre-built assets expected).")
            return@doLast
        }
        val bun = if (isWindows) "bun.exe" else "bun"
        val bunAvailable = try {
            ProcessBuilder(bun, "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
        if (!bunAvailable) {
            logger.warn("⚠ bun not found — skipping web client build. The web UI will not be available.")
            logger.warn("  Install bun (https://bun.sh) to enable the web client.")
            return@doLast
        }
        val result = ProcessBuilder(bun, "run", "build")
            .directory(webDir.asFile)
            .inheritIO()
            .start()
            .waitFor()
        if (result != 0) {
            throw GradleException("Web client build failed (exit code $result)")
        }
    }
}

tasks.processResources {
    dependsOn(buildWeb)
}

// Suppress ktlint violations in protobuf/grpc generated sources.
// The filter lambda works for ktlintCheck but not ktlintFormat (plugin quirk).
// Writing a child .editorconfig into build/generated/ is the reliable cross-task fix.
val suppressKtlintForGenerated by tasks.registering {
    val editorConfigFile = layout.buildDirectory.file("generated/.editorconfig")
    outputs.file(editorConfigFile)
    doLast {
        editorConfigFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                [*.kt]
                ktlint_standard_function-naming = disabled
                ktlint_standard_filename = disabled
                ktlint_standard_max-line-length = disabled
                """.trimIndent(),
            )
        }
    }
}

ktlint {
    verbose.set(true)
    android.set(false)
    filter {
        exclude { entry ->
            val normalized = entry.file.path.replace("\\", "/")
            normalized.contains("build/generated")
        }
    }
}

// Ensure the editorconfig shim exists before any ktlint task inspects generated sources.
tasks.matching { it.name.startsWith("runKtlint") }.configureEach {
    dependsOn(suppressKtlintForGenerated)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Multi-instance local test configurations
//
// These tasks wire up a two-engine / two-gateway topology on localhost:
//
//   runEngine1   ENGINE  gRPC :9091  zones: crossroads_path, thornhaven_city,
//                                          thornwood_forest, farmer_fields, cobblestone_road,
//                                          highland_trails, old_mines, marsh_of_fog,
//                                          goblin_warrens, dark_barrows
//   runEngine2   ENGINE  gRPC :9092  zones: sea_cliffs, sunken_temple, ruined_fortress,
//                                          shadowmere_fen, thornhaven_sewers, haunted_manor,
//                                          barrens_wastes, frost_caverns, celestial_peak,
//                                          dungeon_of_echoes
//   runGateway1  GATEWAY telnet :4000  web :8080  connects to engine-1 + engine-2
//   runGateway2  GATEWAY telnet :4001  web :8081  connects to engine-1 + engine-2
//
// Start order: engines first, then gateways. Each task runs in a separate terminal.
// The profile YAML files live in src/main/resources/application-{profile}.yaml and
// are loaded as a higher-priority overlay on top of application.yaml.
// You can still append -Pconfig.* overrides to any of these tasks.
// ---------------------------------------------------------------------------

tasks.register<JavaExec>("runEngine1") {
    group = "application"
    description = "Run Engine 1 (gRPC :9091) for multi-instance local testing."
    mainClass.set(application.mainClass)
    classpath = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)["main"].runtimeClasspath
    standardInput = System.`in`
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    systemProperty("ambon.profile", "engine1")
    applyConfigOverrides()
}

tasks.register<JavaExec>("runEngine2") {
    group = "application"
    description = "Run Engine 2 (gRPC :9092) for multi-instance local testing."
    mainClass.set(application.mainClass)
    classpath = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)["main"].runtimeClasspath
    standardInput = System.`in`
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    systemProperty("ambon.profile", "engine2")
    applyConfigOverrides()
}

tasks.register<JavaExec>("runGateway1") {
    group = "application"
    description = "Run Gateway 1 (telnet :4000, web :8080) for multi-instance local testing."
    mainClass.set(application.mainClass)
    classpath = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)["main"].runtimeClasspath
    standardInput = System.`in`
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    systemProperty("ambon.profile", "gw1")
    applyConfigOverrides()
}

tasks.register<JavaExec>("runGateway2") {
    group = "application"
    description = "Run Gateway 2 (telnet :4001, web :8081) for multi-instance local testing."
    mainClass.set(application.mainClass)
    classpath = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)["main"].runtimeClasspath
    standardInput = System.`in`
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    systemProperty("ambon.profile", "gw2")
    applyConfigOverrides()
}
