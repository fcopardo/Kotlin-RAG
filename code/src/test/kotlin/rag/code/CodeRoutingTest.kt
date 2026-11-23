package rag.code

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import rag.BookFormat
import rag.BookFormats
import rag.BookReader
import rag.BookSource
import rag.RagException
import rag.ReadOptions
import rag.StructuredBook
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Routing: folders and source files go to the code reader before content sniffing; nothing else changes reader. */
@Timeout(10)
class CodeRoutingTest {
    @TempDir
    lateinit var tmp: File

    private class Fake(override val format: BookFormat) : BookSource {
        override val extractor = "fake"
        override fun read(file: File, options: ReadOptions): StructuredBook = error("not read in this test")
    }

    private val others = listOf(Fake(BookFormat.TEXT), Fake(BookFormat.HTML), Fake(BookFormat.PDF), Fake(BookFormat.EPUB), Fake(BookFormat.DOCX))
    private fun put(name: String, text: String) = File(tmp, name).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun `the core list of source extensions is the Language list`() {
        assertEquals(Language.entries.flatMap { it.extensions }.toSet(), BookFormats.CODE_EXTENSIONS)
    }

    @Test
    fun `routing table`() {
        val reader = BookReader(listOf(CodeBookSource()) + others)
        val cases = mapOf(
            put("A.kt", "class A") to BookFormat.CODE,
            put("B.KT", "class B") to BookFormat.CODE,
            put("c.py", "x = 1") to BookFormat.CODE,
            put("d.tsx", "export {}") to BookFormat.CODE,
            put("e.h", "int f(void);") to BookFormat.CODE,
            put("notes.md", "# Notes\n\ntext") to BookFormat.TEXT,
            put("readme.txt", "plain") to BookFormat.TEXT,
            put("Makefile", "all:\n\techo") to BookFormat.TEXT,
            put("data.json", "{}") to BookFormat.TEXT,
            put("page.html", "<!doctype html><html><body>x</body></html>") to BookFormat.HTML,
            File(tmp, "proj").apply { mkdirs(); File(this, "x.kt").writeText("fun x() {}") } to BookFormat.CODE,
        )
        assertEquals(
            cases.map { (f, fmt) -> "${f.name} $fmt" },
            cases.map { (f, _) -> "${f.name} ${reader.sourceFor(f).format}" },
        )
    }

    @Test
    fun `without the code reader, source files read as text and folders are refused`() {
        val reader = BookReader(others)
        assertEquals(BookFormat.TEXT, reader.sourceFor(put("A.kt", "class A")).format)
        assertFailsWith<RagException.BookUnreadable> { reader.sourceFor(File(tmp, "proj").apply { mkdirs() }) }
    }

    @Test
    fun `a folder reads as a code project with what was skipped`() {
        put("p/src/main/kotlin/a/A.kt", "package a\n\nclass A {\n    fun f() = 1\n}\n")
        put("p/build/gen.kt", "x")
        put("p/package-lock.json", "{}")
        val reader = BookReader(listOf(CodeBookSource()) + others)
        val book = reader.read(File(tmp, "p"))
        assertEquals("p", book.title)
        assertEquals(BookFormat.CODE, book.format)
        assertEquals(listOf("src/main/kotlin/a/A.kt"), book.projectFiles.map { it.path })
        assertEquals(listOf("build/: build folder", "package-lock.json: lockfile"), book.skippedFiles)
        assertEquals(listOf("a › A.kt", "a › A.kt › A"), book.chapters.map { it.title })
        assertEquals(ProjectReader().read(File(tmp, "p")).fingerprint, reader.fingerprint(File(tmp, "p")))
    }
}
