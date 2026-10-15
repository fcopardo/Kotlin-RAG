plugins {
    // Lets the JDK 21 toolchain be downloaded (once, into Gradle's cache) when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "rag"
