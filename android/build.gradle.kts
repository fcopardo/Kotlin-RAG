// The Android library (SPEC §15.2): the pure core with Android's pieces (bundled SQLite,
// PdfBox-Android, EPUB, ONNX Runtime) behind a coroutine facade, RagEngine.
plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "rag.android"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        // Each module registers its BookSource/SqlDriver; keep all registrations.
        resources.merges += "META-INF/services/**"
    }
}

kotlin {
    jvmToolchain(21)
    // Readable by apps on Kotlin 2.2+ (AGP 9's built-in Kotlin), like the JVM modules (root build).
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    api(project(":core"))
    api(project(":epub"))
    api(project(":store-bundled"))
    api(project(":embed-onnx"))
    api(project(":pdf-pdfbox-android"))
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // api: RagModels.sessionOptions and OnnxModel.open take ONNX Runtime types.
    api("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(kotlin("test-junit"))

    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach {
            it.sources.assets?.addStaticSourceDirectory(rootProject.file("integration-tests/build/androidGoldens").path)
            // Evaluation sets for the opt-in benchmark (RagEngineBenchmark), under eval/.
            it.sources.assets?.addStaticSourceDirectory(rootProject.file("integration-tests/src/test/resources").path)
        }
    }
}

// The goldens are built on the desktop (SPEC §15.8) before the test APK packages them.
tasks.matching { it.name.startsWith("merge") && it.name.contains("AndroidTest") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(":integration-tests:androidGoldens")
}

// Published as rag:${project.name} with the library's other modules (docs/ANDROID.md).
afterEvaluate {
    publishing {
        publications.create<MavenPublication>("release") { from(components["release"]) }
    }
}
