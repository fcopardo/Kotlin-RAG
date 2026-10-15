// Tests that combine several modules: indexing and search end to end, both SQL drivers, the
// committed fixture books, and the opt-in real-book, Ollama and ONNX tests.
dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":pdf-pdfbox"))
    testImplementation(project(":epub"))
    testImplementation(project(":store-jdbc"))
    testImplementation(project(":store-bundled"))
    testImplementation(project(":embed-ollama"))
    testImplementation(project(":embed-onnx"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":pdf-pdfbox")))
    testImplementation(testFixtures(project(":epub")))
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
}

// The same suite on the androidx bundled SQLite driver used on Android (SPEC §15.2).
val testBundledSqlite by tasks.registering(Test::class) {
    description = "Runs the integration tests with the androidx bundled SQLite driver."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("rag.sql.driver", "bundled")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(testBundledSqlite)
}
