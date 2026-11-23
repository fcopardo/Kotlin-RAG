package rag

import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Plan step 5, part 5b: `rag structure`. */
class StructureCommandTest {
    @TempDir
    lateinit var tmp: File

    private fun put(path: String, text: String) = File(tmp, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun project(): File {
        put("demo/src/main/kotlin/p/A.kt", "package p\n\nclass A {\n    fun one(): Int {\n        return 1\n    }\n\n    fun two() = 2\n}\n")
        put("demo/build/x.kt", "x")
        return File(tmp, "demo")
    }

    @Test
    fun `a project prints the same report as StructureReport, and writes nothing`() {
        val root = project()
        val before = tmp.walkTopDown().map { it.relativeTo(tmp).path }.toSet()
        val r = StructureCommand().test("--project ${root.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals(StructureReport.text(StructureReport.of(BookReader().read(root), root.name)), r.stdout)
        assertTrue(r.stdout.startsWith("project demo (code)\nfiles: 1 read, 1 skipped (1 build folder)\n"), r.stdout)
        assertEquals(before, tmp.walkTopDown().map { it.relativeTo(tmp).path }.toSet(), "no database or other file is written")
    }

    @Test
    fun `--depth and --json`() {
        val root = project()
        val short = StructureCommand().test("--project ${root.path} --depth 0")
        assertEquals("project demo (code)\nfiles: 1 read, 1 skipped (1 build folder)\ntotals: 2 files, 5 functions, 7 lines, 0 warnings\n", short.stdout)
        val json = StructureCommand().test("--project ${root.path} --json")
        assertEquals(0, json.statusCode, json.stderr)
        assertEquals(StructureReport.of(BookReader().read(root), "demo"), Json.decodeFromString<Structure>(json.stdout))
    }

    @Test
    fun `a single file and a document work through --file`() {
        val kt = put("Solo.kt", "fun main() {\n    println(1)\n}\n")
        val r = StructureCommand().test("--file ${kt.path}")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stdout.startsWith("project Solo.kt (code)\nfiles: 1 read, 0 skipped\n"), r.stdout)
        val md = put("notes.md", "# One\n\nAlpha beta.\n\n# Two\n\nGamma delta.\n")
        val d = StructureCommand().test("--file ${md.path}")
        assertEquals(0, d.statusCode, d.stderr)
        assertTrue(d.stdout.startsWith("paper "), d.stdout)
        assertTrue("files:" !in d.stdout)
    }

    @Test
    fun `exactly one input, a folder for --project, and PDF or EPUB options only with --file`() {
        val root = project()
        val kt = put("Solo.kt", "fun main() {}\n")
        assertNotEquals(0, StructureCommand().test("").statusCode)
        assertNotEquals(0, StructureCommand().test("--project ${root.path} --file ${kt.path}").statusCode)
        assertNotEquals(0, StructureCommand().test("--project ${kt.path}").statusCode)
        val toc = StructureCommand().test("--project ${root.path} --toc-pages 2-3")
        assertNotEquals(0, toc.statusCode)
        assertTrue("--toc-pages" in toc.stderr, toc.stderr)
        val loc = StructureCommand().test("--project ${root.path} --location-size 500")
        assertNotEquals(0, loc.statusCode)
        assertTrue("--location-size" in loc.stderr, loc.stderr)
        assertNotEquals(0, StructureCommand().test("--project ${root.path} --depth -1").statusCode, "depth must be 0 or more")
    }

}
