// The desktop SQLite driver: sqlite-jdbc covers every desktop platform (SPEC §15.2).
dependencies {
    api(project(":core"))
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
}
