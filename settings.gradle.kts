plugins {
    // Lets the JDK 21 toolchain be downloaded (once, into Gradle's cache) when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rag"

// SPEC §15.3: a pure core, one module per platform-specific piece, the desktop CLI, and the tests
// that combine several modules.
include(
    ":core",
    ":pdf-pdfbox",
    ":epub",
    ":store-jdbc",
    ":store-bundled",
    ":embed-ollama",
    ":embed-onnx",
    ":cli",
    ":integration-tests",
)
