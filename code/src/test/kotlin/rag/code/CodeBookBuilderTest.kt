package rag.code

import org.junit.jupiter.api.Timeout
import rag.BookFormat
import rag.PageKind
import rag.RagException
import rag.structure.ChapterStrategy
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Part 3c: a small project through the whole builder. */
@Timeout(10)
class CodeBookBuilderTest {
    private val shapes = """
        |package q
        |
        |/** A circle. */
        |class Circle(val r: Double) {
        |    fun area(): Double {
        |        val sq = r * r
        |
        |        return 3.14 * sq
        |    }
        |}
        |
        |fun describe(c: Circle) = "r=${'$'}{c.r}"
        |""".trimMargin()
    private val gen = "import sys\r\n\r\n\r\ndef main(args):\r\n    \"\"\"Entry point.\"\"\"\r\n\r\n    print(args)\r\n\r\n\r\nif __name__ == \"__main__\":\r\n    main(sys.argv)\r\n"
    private val readme = "# Readme\n\nHello.\n"
    private val broken = "fun f() {\n"

    private val files = listOf(
        SourceFile("tools/gen.py", gen.toByteArray()),
        SourceFile("lib/src/main/kotlin/q/Shapes.kt", shapes.toByteArray()),
        SourceFile("empty.kt", "  \n\t\n".toByteArray()),
        SourceFile("broken.kt", broken.toByteArray()),
        SourceFile("README.md", readme.toByteArray()),
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `a small project`() {
        val s = CodeBookBuilder.build("demo", files)
        val text = s.book.text
        val genText = gen.replace("\r\n", "\n").trimEnd()
        assertEquals(listOf("# Readme\n\nHello.", "fun f() {", shapes.trimEnd(), genText).joinToString("\n\n"), text)
        assertEquals(listOf("README.md", "broken.kt", "lib/src/main/kotlin/q/Shapes.kt", "tools/gen.py"), s.book.pageLabels)
        val starts = listOf(0, text.indexOf("fun f()"), text.indexOf("package q"), text.indexOf("import sys"))
        assertEquals(starts, s.book.pageStarts.toList())
        assertEquals(
            listOf(
                "README.md", "broken.kt", "lib › q › Shapes.kt", "lib › q › Shapes.kt › Circle", "lib › q › Shapes.kt", "tools › gen.py",
            ),
            s.chapters.map { it.title },
        )
        assertEquals(text.indexOf("/** A circle"), s.chapters[3].charStart)
        assertEquals(text.indexOf("\n\nfun describe"), s.chapters[4].charStart)
        assertEquals(
            listOf(
                text.indexOf("fun f()")..text.indexOf("fun f()") + 8,
                text.indexOf("fun area")..text.indexOf("    }\n}") + 4,
                text.indexOf("fun describe")..text.indexOf("c.r}\"") + 4,
                text.indexOf("def main")..text.indexOf("print(args)") + 10,
            ),
            s.book.blocks,
        )
        assertEquals(listOf(0..15, starts[1]..starts[1] + 8, starts[2]..starts[3] - 3, starts[3]..text.length - 1), s.book.codeRanges)
        assertEquals(
            listOf(
                "README.md ${sha(readme.toByteArray())} 0 15",
                "broken.kt ${sha(broken.toByteArray())} ${starts[1]} ${starts[1] + 8}",
                "lib/src/main/kotlin/q/Shapes.kt ${sha(shapes.toByteArray())} ${starts[2]} ${starts[3] - 3}",
                "tools/gen.py ${sha(gen.toByteArray())} ${starts[3]} ${text.length - 1}",
            ),
            s.projectFiles.map { "${it.path} ${it.sha256} ${it.charStart} ${it.charEnd}" },
        )
        assertEquals(listOf("broken.kt: line 1: unclosed {"), s.plan.warnings)
        assertEquals(ChapterStrategy.CODE, s.plan.strategy)
        assertEquals("demo", s.title)
        assertEquals(BookFormat.CODE, s.format)
        assertEquals(PageKind.FILE, s.pageKind)
        assertEquals("code/scan-v1", s.extractor)
        assertEquals("code", s.kind)
    }

    @Test
    fun `a function is one paragraph, blank lines and all`() {
        val s = CodeBookBuilder.build("demo", files)
        val text = s.book.text
        val paragraphs = s.segments.flatMap { it.paragraphs }
        for (b in s.book.blocks!!) {
            assertTrue(paragraphs.any { it.start == b.first && it.end == b.last && it.isCode }, "one paragraph for ${text.substring(b.first, b.last + 1)}")
        }
        for (seg in s.segments) for (p in seg.paragraphs) {
            assertTrue(p.start >= seg.chapter.charStart && p.end <= seg.chapter.charEnd, "paragraph inside its chapter")
        }
    }

    @Test
    fun `a single file is a project of one, and no text is an error`() {
        val s = CodeBookBuilder.build("Main.kt", listOf(SourceFile("Main.kt", "fun main() {\n    println(1)\n}\n".toByteArray())))
        assertEquals(listOf("Main.kt"), s.chapters.map { it.title })
        assertEquals(listOf(0..28), s.book.blocks)
        assertFailsWith<RagException.BookUnreadable> { CodeBookBuilder.build("none", listOf(SourceFile("a.kt", " \n".toByteArray()))) }
        assertFailsWith<RagException.BookUnreadable> { CodeBookBuilder.build("none", emptyList()) }
    }
}
