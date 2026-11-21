package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Part 3a: file titles without source-set noise. */
@Timeout(10)
class SourceTitlesTest {
    @Test
    fun `titles`() {
        mapOf(
            "core/src/main/kotlin/rag/search/Searcher.kt" to listOf("core", "rag/search", "Searcher.kt"),
            "core/src/test/kotlin/rag/SearcherTest.kt" to listOf("core (test)", "rag", "SearcherTest.kt"),
            "app/src/main/java/com/x/MainActivity.kt" to listOf("app", "com/x", "MainActivity.kt"),
            "app/src/androidTest/java/com/x/UiTest.kt" to listOf("app (androidTest)", "com/x", "UiTest.kt"),
            "shared/src/commonMain/kotlin/a/B.kt" to listOf("shared (commonMain)", "a", "B.kt"),
            "src/main/kotlin/Main.kt" to listOf("Main.kt"),
            "src/test/kotlin/MainTest.kt" to listOf("test", "MainTest.kt"),
            "src/lib.rs" to listOf("lib.rs"),
            "crates/foo/src/lib.rs" to listOf("crates/foo", "lib.rs"),
            "src/pkg/mod.py" to listOf("pkg", "mod.py"),
            "src/pkg/sub/mod.py" to listOf("pkg/sub", "mod.py"),
            "src/js/app.js" to listOf("app.js"),
            "web/src/components/src/x.ts" to listOf("web", "components/src", "x.ts"),
            "cmd/server/main.go" to listOf("cmd/server", "main.go"),
            "Sources/App/View.swift" to listOf("Sources/App", "View.swift"),
            "main.go" to listOf("main.go"),
            "a//b/c.kt" to listOf("a/b", "c.kt"),
        ).forEach { (path, expected) -> assertEquals(expected, SourceTitles.of(path), path) }
    }
}
