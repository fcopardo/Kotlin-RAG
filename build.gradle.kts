import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "rag"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    // Build and test with JDK 21 (an installed one, or downloaded by the Foojay resolver). The
    // output runs on any Java 21+. A native JDK also matters for native libraries (SQLite drivers).
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("rag.MainKt")
    applicationName = "rag"
    // sqlite-jdbc loads a native library; JDK 24+ warns unless native access is enabled.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Env vars aren't task inputs by default: without this, toggling RAG_OLLAMA reuses a stale result.
    listOf("RAG_OLLAMA", "RAG_OLLAMA_URL", "RAG_BOOK_PDF", "RAG_KOTLIN_PDF", "RAG_CTCI_PDF", "RAG_SUMMARY_MODEL").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}

// The same suite on the androidx bundled SQLite driver used on Android (SPEC §15.2).
val testBundledSqlite by tasks.registering(Test::class) {
    description = "Runs all tests with the androidx bundled SQLite driver."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("rag.sql.driver", "bundled")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(testBundledSqlite)
}
