package rag

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC §18.5 step 5, part 5b: `rag index --dir <folder>`, one document per file. */
class IndexDirCommandTest {
    @TempDir
    lateinit var tmp: File

    private fun put(path: String, text: String) = File(tmp, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun db() = File(tmp, "docs.db").path

    private fun notes(): File {
        put("notes/notes.md", "# Notes\n\nFirst point.\n\n## More\n\nSecond point.\n")
        put("notes/plain.txt", "Just some text about gardens.\n")
        File(tmp, "notes/img.png").writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 1, 2))
        return File(tmp, "notes")
    }

    @Test
    fun `one line per file, then a summary`() {
        val r = IndexCommand().test("--dir ${notes().path} --db ${db()} --no-embed")
        assertEquals(0, r.statusCode, r.stderr)
        val lines = r.stdout.trim().lines()
        assertEquals(4, lines.size, r.stdout)
        assertTrue(lines[0].startsWith("Skipped: img.png ("), lines[0])
        assertEquals("Indexed paper 1: notes.md", lines[1])
        assertEquals("Indexed paper 2: plain.txt", lines[2])
        assertEquals("Folder: 2 indexed, 0 already indexed, 1 skipped, 0 failed", lines[3])
    }

    @Test
    fun `a second run says what is already there`() {
        val dir = notes()
        IndexCommand().test("--dir ${dir.path} --db ${db()} --no-embed")
        val r = IndexCommand().test("--dir ${dir.path} --db ${db()} --no-embed")
        assertEquals(0, r.statusCode, r.stderr)
        val lines = r.stdout.trim().lines()
        assertEquals("Already indexed: notes.md (paper 1)", lines[1])
        assertEquals("Already indexed: plain.txt (paper 2)", lines[2])
        assertEquals("Folder: 0 indexed, 2 already indexed, 1 skipped, 0 failed", lines[3])
    }

    @Test
    fun `a file that fails is reported, the others are indexed, and the exit code is 3`() {
        val dir = notes()
        put("notes/blank.txt", "   \n\n")
        val r = IndexCommand().test("--dir ${dir.path} --db ${db()} --no-embed")
        assertEquals(3, r.statusCode, r.stdout + r.stderr)
        val lines = r.stdout.trim().lines()
        assertTrue(lines[0].startsWith("Failed: blank.txt (") && "Empty document" in lines[0], lines[0])
        assertEquals("Folder: 2 indexed, 0 already indexed, 1 skipped, 1 failed", lines.last())
    }

    @Test
    fun `exactly one of --file, --project and --dir, and no --toc-pages with --dir`() {
        val dir = notes()
        val f = put("one.txt", "One.\n")
        assertNotEquals(0, IndexCommand().test("--dir ${dir.path} --file ${f.path} --db ${db()} --no-embed").statusCode)
        assertNotEquals(0, IndexCommand().test("--dir ${dir.path} --project ${dir.path} --db ${db()} --no-embed").statusCode)
        assertNotEquals(0, IndexCommand().test("--dir ${f.path} --db ${db()} --no-embed").statusCode, "--dir needs a folder")
        val toc = IndexCommand().test("--dir ${dir.path} --db ${db()} --no-embed --toc-pages 2-3")
        assertNotEquals(0, toc.statusCode)
        assertTrue("--toc-pages" in toc.stderr, toc.stderr)
    }
}
