// The `rag` command for desktop.
plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":pdf-pdfbox"))
    implementation(project(":epub"))
    implementation(project(":store-jdbc"))
    implementation(project(":store-bundled")) // -Drag.sql.driver=bundled
    implementation(project(":embed-ollama"))
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
}

application {
    mainClass.set("rag.MainKt")
    applicationName = "rag"
    // sqlite-jdbc and androidx load native libraries; JDK 24+ warns unless native access is enabled.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
