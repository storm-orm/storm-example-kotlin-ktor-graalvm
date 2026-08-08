plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("com.google.devtools.ksp") version "2.3.10"
    // The Storm plugin imports the BOM, adds storm-kotlin and storm-core, wires the
    // metamodel processor to KSP, and selects the compiler-plugin variant matching Kotlin.
    id("st.orm") version "1.13.1"
    id("org.graalvm.buildtools.native") version "0.11.1"
    application
}

group = "st.orm.demo"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.4.3"

dependencies {
    // Ktor server (storm-ktor is built against Ktor 3.4.3).
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    // CIO instead of Netty: Ktor's recommended engine for GraalVM native images.
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-thymeleaf:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    // Ktor's built-in dependency injection: the Storm plugin registers the ORMTemplate
    // and every auto-registered repository; the services are provided in Dependencies.kt.
    implementation("io.ktor:ktor-server-di:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:1.15.4")

    // Storm ORM: the Ktor integration and serialization modules. The Gradle plugin
    // already provides storm-kotlin, storm-core, and the metamodel processor.
    implementation("st.orm:storm-ktor")
    implementation("st.orm:storm-jackson3")
    implementation("st.orm:storm-kotlinx-serialization")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    runtimeOnly("st.orm:storm-postgresql")

    // Connection pooling and Flyway migrations (run explicitly at startup).
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.flywaydb:flyway-core:11.4.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:11.4.0")
    runtimeOnly("org.postgresql:postgresql:42.7.5")

    // Jackson 3 for parsing external APIs (no longer provided by a framework BOM).
    implementation(platform("tools.jackson:jackson-bom:3.0.0"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // SLF4J backend for Ktor/Storm logging.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("st.orm:storm-test")
    testRuntimeOnly("st.orm:storm-h2")
    testRuntimeOnly("com.h2database:h2:2.3.232")
    testImplementation("com.microsoft.playwright:playwright:1.61.0")
}

application {
    // Ktor's EngineMain reads application.conf (ktor.application.modules) to
    // locate and start Application.module().
    mainClass = "io.ktor.server.cio.EngineMain"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

// Playwright interface tests run against the live application: start the
// app (./gradlew run), then run ./gradlew e2eTest.
tasks.register<Test>("e2eTest") {
    description = "Runs Playwright interface tests against the running application."
    group = "verification"
    useJUnitPlatform {
        includeTags("e2e")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("app.baseUrl", System.getProperty("app.baseUrl") ?: "http://localhost:8080")
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("installPlaywrightBrowsers") {
    description = "Downloads the Chromium browser used by the Playwright tests."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    args("install", "chromium")
}

graalvmNative {
    // Pull reachability metadata for third-party libraries from the shared
    // GraalVM metadata repository.
    metadataRepository {
        enabled = true
    }
    binaries {
        named("main") {
            imageName = "storm-imdb-ktor-graalvm"
        }
    }
}
