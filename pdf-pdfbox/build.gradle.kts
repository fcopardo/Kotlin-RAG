// PDF books through PDFBox (desktop). Android would use PdfBox-Android (SPEC §15.2).
plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testFixturesApi("org.apache.pdfbox:pdfbox:3.0.8")
}
