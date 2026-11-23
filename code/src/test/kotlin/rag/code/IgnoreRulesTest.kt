package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Part 4a: `.gitignore` rules. Each case prints as `path (dir|file)`. */
@Timeout(10)
class IgnoreRulesTest {
    private fun rules(vararg files: Pair<String, String>) = IgnoreRules().apply { files.forEach { (b, t) -> add(b, t) } }

    private fun check(r: IgnoreRules, expected: Map<String, Boolean>) {
        val wrong = expected.filter { (k, v) ->
            val dir = k.endsWith("/")
            r.isIgnored(k.removeSuffix("/"), dir) != v
        }.map { (k, v) -> "$k should ${if (v) "" else "not "}be ignored" }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `names at any depth, folders only, negation`() {
        val r = rules("" to "build/\n*.log\n!keep.log\n# comment\n\n   \nsecret?.txt\n[Tt]emp*\n[!a]x\n")
        check(
            r,
            mapOf(
                "build/" to true, "build" to false, "a/b/build/" to true,
                "x.log" to true, "a/b/x.log" to true, "keep.log" to false, "a/keep.log" to false,
                "secret1.txt" to true, "a/secret9.txt" to true, "secret12.txt" to false, "secret/.txt" to false,
                "Temp.txt" to true, "temp" to true, "attemp" to false,
                "bx" to true, "ax" to false, "abx" to false,
                "# comment" to false, "README.md" to false,
            ),
        )
    }

    @Test
    fun `anchored rules and star never crossing a slash`() {
        val r = rules("" to "/out\nsrc/*.kt\ndocs/**/*.tmp\nlogs/**\n**/gen/\na/**/b\n")
        check(
            r,
            mapOf(
                "out" to true, "out/" to true, "a/out" to false,
                "src/A.kt" to true, "src/x/A.kt" to false, "lib/src/A.kt" to false,
                "docs/x.tmp" to true, "docs/a/b/x.tmp" to true, "x.tmp" to false, "z/docs/x.tmp" to false,
                "logs/a" to true, "logs/a/b" to true, "logs/" to false,
                "gen/" to true, "p/q/gen/" to true, "gen" to false,
                "a/b" to true, "a/x/b" to true, "a/x/y/b" to true, "a/bb" to false, "z/a/b" to false,
            ),
        )
    }

    @Test
    fun `escapes, trailing spaces and CRLF`() {
        val r = rules("" to "\\#hash\r\n\\!bang\r\nspace\\ \r\ntrail   \r\nq\\*\r\n")
        check(
            r,
            mapOf(
                "#hash" to true, "!bang" to true, "space " to true, "space" to false,
                "trail" to true, "trail   " to false, "q*" to true, "qx" to false,
            ),
        )
    }

    @Test
    fun `nested files apply inside their folder and override shallower ones`() {
        val r = rules("" to "*.dat\n/top\n", "sub" to "*.txt\n!b.txt\n/only\n!keep.dat\n", "sub/deep" to "b.txt\n")
        check(
            r,
            mapOf(
                "sub/a.txt" to true, "a.txt" to false, "sub/b.txt" to false, "sub/x/a.txt" to true,
                "sub/only" to true, "sub/x/only" to false, "only" to false,
                "keep.dat" to true, "sub/keep.dat" to false, "sub/x/keep.dat" to false, "other/keep.dat" to true,
                "sub/deep/b.txt" to true, "sub/deep/x/b.txt" to true, "subx/a.txt" to false,
                "top" to true, "sub/top" to false,
            ),
        )
    }

    @Test
    fun `the last matching rule wins`() {
        check(rules("" to "*.c\n!*.c\n"), mapOf("a.c" to false))
        check(rules("" to "!x\nx\n"), mapOf("x" to true))
        check(rules("" to "*\n!*.kt\n!*/\n"), mapOf("a.kt" to false, "a.txt" to true, "dir/" to false))
        check(rules(), mapOf("anything" to false, "a/b/" to false))
    }

    @Test
    fun `many rules stay fast`() {
        val text = (1..100).joinToString("\n") { "dir$it/**/file$it-*.tmp\n!keep$it?.log\n*.ext$it" }
        val r = rules("" to text, "a" to text, "a/b" to text)
        val started = System.nanoTime()
        var hits = 0
        repeat(5_000) { i -> val k = i % 100 + 1; if (r.isIgnored("a/b/dir$k/x/file$k-$i.tmp", false)) hits++ }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(5_000, hits, "each path matches its folder's rule (anchored to a/b)")
        assertTrue(ms < 3_000, "took $ms ms for 900 rules x 5000 paths")
    }

    @Test
    fun `review fixes - a folder is not inside itself, spaces before the slash, double star inside a name`() {
        val r = rules("" to "build/  \nsrc/foo**bar\n", "sub" to "*\n")
        check(
            r,
            mapOf(
                "sub/" to false, "sub/x" to true, "build/" to true, "build" to false,
                "src/fooXbar" to true, "src/foo*bar" to true, "src/foo/bar" to false,
            ),
        )
    }
}
