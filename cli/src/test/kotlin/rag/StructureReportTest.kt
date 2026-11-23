package rag

import kotlinx.serialization.json.Json
import rag.code.CodeBookBuilder
import rag.code.SourceFile
import rag.document.DocumentBuilder
import rag.document.Heading
import rag.structure.Segmenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan step 5, part 5a: the `rag structure` report. */
class StructureReportTest {
    private val a = "package p\n\nclass A {\n    fun one(): Int {\n        return 1\n    }\n\n    fun two() = 2\n}\n"

    private fun code() = CodeBookBuilder.build(
        "a", listOf(SourceFile("a/src/main/kotlin/p/A.kt", a.toByteArray())), skipped = listOf("build/: build folder", "x.lock: lockfile"),
    )

    @Test
    fun `a code project as text`() {
        assertEquals(
            """
            |project a (code)
            |files: 1 read, 2 skipped (1 build folder, 1 lockfile)
            |  a › p › A.kt  [a/src/main/kotlin/p/A.kt]
            |    L1  9 chars  3 tok  package p
            |  a › p › A.kt › A  [a/src/main/kotlin/p/A.kt]
            |    L3  9 chars  4 tok  class A {
            |    L4–L6  39 chars  9 tok  fun one(): Int {
            |    L8  13 chars  5 tok  fun two() = 2
            |    L9  1 chars  1 tok  }
            |totals: 2 files, 5 functions, 7 lines, 0 warnings
            |""".trimMargin(),
            StructureReport.text(StructureReport.of(code(), "fallback")),
        )
    }

    @Test
    fun `depth keeps large reports short`() {
        val s = StructureReport.of(code(), "x")
        assertEquals(
            "project a (code)\nfiles: 1 read, 2 skipped (1 build folder, 1 lockfile)\n  a › p › A.kt  [a/src/main/kotlin/p/A.kt]\n" +
                "  a › p › A.kt › A  [a/src/main/kotlin/p/A.kt]\ntotals: 2 files, 5 functions, 7 lines, 0 warnings\n",
            StructureReport.text(s, depth = 1),
        )
        assertEquals(
            "project a (code)\nfiles: 1 read, 2 skipped (1 build folder, 1 lockfile)\ntotals: 2 files, 5 functions, 7 lines, 0 warnings\n",
            StructureReport.text(s, depth = 0),
        )
    }

    @Test
    fun `reasons are counted, most frequent first`() {
        val b = CodeBookBuilder.build(
            "b", listOf(SourceFile("B.kt", "fun b() {}\n".toByteArray())),
            skipped = listOf("a.lock: lockfile", "b/: build folder", "c.lock: lockfile", "d.png: binary"),
        )
        assertEquals("files: 1 read, 4 skipped (2 lockfile, 1 binary, 1 build folder)", StructureReport.text(StructureReport.of(b, "b")).lines()[1])
    }

    @Test
    fun `a function split by the size cap is one unit with its pieces`() {
        val big = "def big():\n" + "    x = 1\n".repeat(350)
        val book = CodeBookBuilder.build("big", listOf(SourceFile("big.py", big.toByteArray())))
        val block = book.book.blocks!!.single()
        val parts = book.segments.flatMap { it.paragraphs }.filter { it.start >= block.first && it.end <= block.last }
        assertTrue(parts.size > 1, "the cap splits it")
        val unit = StructureReport.of(book, "big").sections.single().units.single()
        assertEquals(
            StructureUnit("L1–L351", block.last - block.first + 1, parts.sumOf { Segmenter().tokenEstimate(book.book.text, it.start, it.end) }, "def big():", parts.size),
            unit,
        )
        assertTrue(StructureReport.text(StructureReport.of(book, "big")).contains("  → ${parts.size} pieces\n"))
    }

    @Test
    fun `long first lines are cut`() {
        val line = "fun " + "x".repeat(70) + "() = 1"
        val book = CodeBookBuilder.build("c", listOf(SourceFile("C.kt", "$line\n".toByteArray())))
        assertEquals("fun " + "x".repeat(53) + "...", StructureReport.of(book, "c").sections.single().units.single().text)
    }

    @Test
    fun `a paper cites its locations and has no files line`() {
        val text = "One\n\nAlpha beta.\n\nTwo\n\nGamma delta."
        val book = DocumentBuilder().build(text, "Notes", BookFormat.TEXT, "t", headings = listOf(Heading("One", 1, 0), Heading("Two", 1, text.indexOf("Two"))))
        assertEquals(
            """
            |paper Notes (paper)
            |  One  [loc 1]
            |    loc 1  3 chars  1 tok  One
            |    loc 1  11 chars  3 tok  Alpha beta.
            |  Two  [loc 1]
            |    loc 1  3 chars  1 tok  Two
            |    loc 1  12 chars  3 tok  Gamma delta.
            |totals: 2 sections, 4 paragraphs, 4 sentences, 0 warnings
            |""".trimMargin(),
            StructureReport.text(StructureReport.of(book, "fallback")),
        )
    }

    @Test
    fun `warnings are listed, and the title falls back`() {
        val book = CodeBookBuilder.build("w", listOf(SourceFile("W.kt", "fun f() {\n".toByteArray())))
        val s = StructureReport.of(book, "w")
        assertEquals(listOf("W.kt: line 1: unclosed {"), s.warnings)
        assertTrue(StructureReport.text(s).endsWith("totals: 1 files, 1 functions, 1 lines, 1 warnings\nwarning: W.kt: line 1: unclosed {\n"))
        val untitled = DocumentBuilder().build("Just text.", null, BookFormat.TEXT, "t")
        assertEquals("fallback.txt", StructureReport.of(untitled, "fallback.txt").title)
    }

    @Test
    fun `json round-trips`() {
        val s = StructureReport.of(code(), "a")
        assertEquals(s, Json.decodeFromString<Structure>(StructureReport.json(s)))
        assertTrue(Regex("\n +\"root\": \"project\"").containsMatchIn(StructureReport.json(s)), "pretty-printed")
    }

    @Test
    fun `review fix - line numbers count from each file's own start`() {
        val book = CodeBookBuilder.build(
            "two", listOf(SourceFile("a.kt", "fun a() = 1\n".toByteArray()), SourceFile("b.kt", "// b\n\n\nfun b() {\n    x()\n}\n".toByteArray())),
        )
        val b = StructureReport.of(book, "two").sections.last()
        assertEquals("b.kt", b.where)
        assertEquals(listOf("L1", "L4–L6"), b.units.map { it.where })
    }
}
