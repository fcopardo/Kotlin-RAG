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

// Inputs and expected outputs for the Android instrumented tests (SPEC §15.8), built on the desktop.
val androidGoldens by tasks.registering(JavaExec::class) {
    description = "Writes the fixture books, a desktop-built DB and the goldens the Android tests compare against."
    val epubs = layout.projectDirectory.dir("src/test/resources/epub")
    val out = layout.buildDirectory.dir("androidGoldens")
    inputs.files(sourceSets.test.get().runtimeClasspath)
    inputs.dir(epubs)
    outputs.dir(out)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("rag.android.AndroidGoldensKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.path, epubs.asFile.path) })
}
