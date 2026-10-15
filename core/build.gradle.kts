// Pure Kotlin: pipeline, keys, store logic over SqlConnection, search, evaluation, summary tree.
// Runs on the JVM and Android as is (SPEC §15.1).
plugins {
    kotlin("plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    // Search results and evaluation questions are @Serializable: part of the API.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
