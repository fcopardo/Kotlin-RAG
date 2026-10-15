// EPUB books through jsoup and java.util.zip (JVM and Android, SPEC §16).
plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    implementation("org.jsoup:jsoup:1.23.2")
}
