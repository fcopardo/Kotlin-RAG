package rag

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Plan step 4, part 4c: `rag index --project <dir>`. */
class IndexProjectCommandTest {
    @TempDir
    lateinit var tmp: File

    private fun put(path: String, text: String) = File(tmp, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun project(): File {
        put("demo/src/main/kotlin/a/A.kt", "package a\n\nclass A {\n    fun f() = 1\n}\n")
        put("demo/tools/gen.py", "def gen():\n    return 1\n")
        put("demo/build/out.kt", "x")
        put("demo/.gitignore", "*.tmp\n")
        put("demo/notes.tmp", "scratch")
        return File(tmp, "demo")
    }

    private fun db() = File(tmp, "code.db").path

    @Test
    fun `indexes a project and reports files read and skipped`() {
        val root = project()
        val r = IndexCommand().test("--project ${root.path} --db ${db()} --no-embed")
        assertEquals(0, r.statusCode, r.stderr)
        val lines = r.stdout.trim().lines()
        assertEquals("Indexed project 1: demo (code, code, pages: file)", lines[0])
        assertTrue(lines[1].startsWith("3 files by code, "), lines[1])   // a › A.kt, a › A.kt › A, tools › gen.py
        assertEquals("Files: 2 read, 3 skipped (.gitignore: hidden, build/: build folder, notes.tmp: gitignore)", lines[2])
    }

    @Test
    fun `an unchanged project is not indexed again`() {
        val root = project()
        IndexCommand().test("--project ${root.path} --db ${db()} --no-embed")
        val again = IndexCommand().test("--project ${root.path} --db ${db()} --no-embed")
        assertEquals(0, again.statusCode, again.stderr)
        assertTrue(again.stdout.startsWith("Already indexed as project 1 "), again.stdout)
    }

    @Test
    fun `at most five skipped files are listed`() {
        val root = project()
        (1..6).forEach { put("demo/x$it.lock", "x") }
        val r = IndexCommand().test("--project ${root.path} --db ${db()} --no-embed")
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals(
            "Files: 2 read, 9 skipped (.gitignore: hidden, build/: build folder, notes.tmp: gitignore, x1.lock: lockfile, x2.lock: lockfile, …)",
            r.stdout.trim().lines()[2],
        )
    }

    @Test
    fun `exactly one of --file and --project`() {
        val root = project()
        val f = put("doc.txt", "Some text.\n")
        assertNotEquals(0, IndexCommand().test("--project ${root.path} --file ${f.path} --db ${db()} --no-embed").statusCode)
        assertNotEquals(0, IndexCommand().test("--db ${db()} --no-embed").statusCode)
    }

    @Test
    fun `PDF and EPUB options are refused with --project`() {
        val root = project()
        val toc = IndexCommand().test("--project ${root.path} --db ${db()} --no-embed --toc-pages 2-3")
        assertNotEquals(0, toc.statusCode)
        assertTrue("--toc-pages" in toc.stderr, toc.stderr)
        val loc = IndexCommand().test("--project ${root.path} --db ${db()} --no-embed --location-size 500")
        assertNotEquals(0, loc.statusCode)
        assertTrue("--location-size" in loc.stderr, loc.stderr)
    }

    @Test
    fun `--project needs a folder, and --file still indexes documents`() {
        val f = put("doc.txt", "Some text.\n")
        assertNotEquals(0, IndexCommand().test("--project ${f.path} --db ${db()} --no-embed").statusCode)
        val r = IndexCommand().test("--file ${f.path} --db ${db()} --no-embed")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stdout.startsWith("Indexed "), r.stdout)
        assertTrue("Files:" !in r.stdout, "no files line for a document")
    }

    @Test
    fun `review fix - a source file via --file is named by its own level`() {
        val f = put("Solo.kt", "class Solo {\n    fun hi() = 1\n}\n")
        IndexCommand().test("--file ${f.path} --db ${db()} --no-embed")
        val again = IndexCommand().test("--file ${f.path} --db ${db()} --no-embed")
        assertTrue(again.stdout.startsWith("Already indexed as project "), again.stdout)
    }
}
