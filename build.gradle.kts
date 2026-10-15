plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    group = "rag"
    version = "0.1.0"

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        // Build and test with JDK 21 (an installed one, or downloaded by the Foojay resolver). The
        // output runs on any Java 21+. A native JDK also matters for native libraries (SQLite drivers).
        jvmToolchain(21)
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"(platform("org.junit:junit-bom:5.14.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Env vars aren't task inputs by default: without this, toggling RAG_OLLAMA reuses a stale result.
        listOf("RAG_OLLAMA", "RAG_OLLAMA_URL", "RAG_BOOK_PDF", "RAG_KOTLIN_PDF", "RAG_CTCI_PDF", "RAG_SUMMARY_MODEL", "RAG_ONNX_MODEL_DIR").forEach { name ->
            inputs.property(name, providers.environmentVariable(name).orElse(""))
        }
    }
}
