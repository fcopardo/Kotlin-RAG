// Plain documents: text, Markdown, HTML and Word .docx (SPEC §18).
plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    // The HTML walker is the EPUB one: both turn XHTML blocks into paragraphs, code and tables.
    implementation(project(":epub"))
    implementation("org.jsoup:jsoup:1.23.2")
}
