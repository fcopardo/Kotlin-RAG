// The `rag` command for desktop.
plugins {
    application
    kotlin("plugin.serialization") // rag serve / rag mcp payloads
}

dependencies {
    implementation(project(":core"))
    implementation(project(":pdf-pdfbox"))
    implementation(project(":epub"))
    implementation(project(":store-jdbc"))
    implementation(project(":store-bundled")) // -Drag.sql.driver=bundled
    implementation(project(":embed-ollama"))
    implementation(project(":embed-onnx")) // --onnx-dir: the same model files as phones (SPEC §15.9)
    runtimeOnly("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":pdf-pdfbox")))
}

application {
    mainClass.set("rag.MainKt")
    applicationName = "rag"
    // sqlite-jdbc and androidx load native libraries; JDK 24+ warns unless native access is enabled.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
