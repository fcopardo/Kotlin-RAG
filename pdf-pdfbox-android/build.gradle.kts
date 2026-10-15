// PDF books on Android through PdfBox-Android (SPEC §15.2). There are no hand-written sources: they
// are generated from :pdf-pdfbox (PDFBox 3) with the package moved to com.tom_roush.pdfbox (2.0 API),
// so the two readers can't drift apart. Callers must run PDFBoxResourceLoader.init(context) first.
plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "rag.pdf"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}

/** Copies :pdf-pdfbox's sources and resources, rewritten for PdfBox-Android. */
abstract class PortPdfBox : DefaultTask() {
    @get:InputDirectory
    abstract val sources: DirectoryProperty

    @get:OutputDirectory
    abstract val kotlinOut: DirectoryProperty

    @get:OutputDirectory
    abstract val resourcesOut: DirectoryProperty

    @TaskAction
    fun port() {
        val src = sources.get().asFile
        val kotlinDir = kotlinOut.get().asFile.apply { deleteRecursively(); mkdirs() }
        val resourcesDir = resourcesOut.get().asFile.apply { deleteRecursively(); mkdirs() }
        src.resolve("kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val ported = file.readText()
                .replace("import org.apache.pdfbox.Loader\n", "")
                .replace("Loader.loadPDF(", "PDDocument.load(")
                .replace("org.apache.pdfbox.", "com.tom_roush.pdfbox.")
            check("org.apache.pdfbox" !in ported) { "Unported PDFBox reference in $file" }
            kotlinDir.resolve(file.relativeTo(src.resolve("kotlin")).path).apply { parentFile.mkdirs() }.writeText(ported)
        }
        src.resolve("resources").copyRecursively(resourcesDir, overwrite = true)
    }
}

val portPdfBox = tasks.register<PortPdfBox>("portPdfBox") {
    sources.set(rootProject.layout.projectDirectory.dir("pdf-pdfbox/src/main"))
    kotlinOut.set(layout.buildDirectory.dir("generated/ported/kotlin"))
    resourcesOut.set(layout.buildDirectory.dir("generated/ported/resources"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(portPdfBox, PortPdfBox::kotlinOut)
        variant.sources.resources?.addGeneratedSourceDirectory(portPdfBox, PortPdfBox::resourcesOut)
    }
}

// Published as rag:${project.name} with the library's other modules (docs/ANDROID.md).
afterEvaluate {
    publishing {
        publications.create<MavenPublication>("release") { from(components["release"]) }
    }
}
