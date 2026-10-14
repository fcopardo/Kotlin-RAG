import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "rag"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
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

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Env vars aren't task inputs by default: without this, toggling RAG_OLLAMA reuses a stale result.
    inputs.property("ragOllama", providers.environmentVariable("RAG_OLLAMA").orElse(""))
    inputs.property("ragOllamaUrl", providers.environmentVariable("RAG_OLLAMA_URL").orElse(""))
    inputs.property("ragBookPdf", providers.environmentVariable("RAG_BOOK_PDF").orElse(""))
    inputs.property("ragKotlinPdf", providers.environmentVariable("RAG_KOTLIN_PDF").orElse(""))
    inputs.property("ragCtciPdf", providers.environmentVariable("RAG_CTCI_PDF").orElse(""))
}
