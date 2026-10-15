// Embeddings and summaries through a local Ollama server (java.net.http: JVM only).
plugins {
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":core"))
}
