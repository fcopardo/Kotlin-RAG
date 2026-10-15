plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.android.library") version "9.4.1" apply false
}

// Android modules configure themselves (AGP has Kotlin built in); everything else is plain JVM.
val androidModules = setOf("android", "pdf-pdfbox-android")
/**
 * Apps read the library with their own Kotlin compiler, which reads metadata up to one version
 * newer than itself. AGP 9's built-in Kotlin is 2.2, so the library targets 2.2 (language, API and
 * stdlib) while building with Kotlin 2.4 (docs/ANDROID.md).
 */
fun org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension.consumerKotlinCompatibility() {
    coreLibrariesVersion = "2.2.21"
    (this as org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension).compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

// Modules apps never depend on: not published.
val unpublished = setOf("cli", "integration-tests")

allprojects {
    group = "rag"
    version = "0.1.0"
}

configure(subprojects.filter { it.name !in androidModules }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        // Build and test with JDK 21 (an installed one, or downloaded by the Foojay resolver). The
        // output runs on any Java 21+. A native JDK also matters for native libraries (SQLite drivers).
        jvmToolchain(21)
        consumerKotlinCompatibility()
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
        listOf("RAG_OLLAMA", "RAG_OLLAMA_URL", "RAG_BOOK_PDF", "RAG_KOTLIN_PDF", "RAG_CTCI_PDF", "RAG_SUMMARY_MODEL", "RAG_ONNX_MODEL_DIR", "RAG_ONNX_NOMIC_DIR", "RAG_SCALE", "RAG_SCALE_PARAGRAPHS", "RAG_SCALE_DIR").forEach { name ->
            inputs.property(name, providers.environmentVariable(name).orElse(""))
        }
    }
}

// Libraries publish as rag:<module>:<version> (`./gradlew publishToMavenLocal`), so apps depend on
// them like any Maven artifact (docs/ANDROID.md). Test fixtures stay out of the published variants.
configure(subprojects.filter { it.name !in androidModules && it.name !in unpublished }) {
    apply(plugin = "maven-publish")
    extensions.configure<JavaPluginExtension> { withSourcesJar() }
    afterEvaluate {
        val java = components["java"] as AdhocComponentWithVariants
        listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
            configurations.findByName(name)?.let { java.withVariantsFromConfiguration(it) { skip() } }
        }
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") { from(java) }
        }
    }
}
