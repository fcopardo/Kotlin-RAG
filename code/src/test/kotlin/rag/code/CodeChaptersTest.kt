package rag.code

import org.junit.jupiter.api.Timeout
import rag.structure.ChapterStart
import kotlin.test.Test
import kotlin.test.assertEquals

/** Part 3b: chapter starts at files, classes, and back after nested classes. */
@Timeout(10)
class CodeChaptersTest {
    private fun cls(name: String, start: Int, end: Int) = Declaration(DeclarationKind.CLASS, name, start, end, 0, null)
    private fun fn(name: String, start: Int, end: Int) = Declaration(DeclarationKind.FUNCTION, name, start, end, 0, null)

    private fun show(starts: List<ChapterStart>) = starts.map { "${it.title} @${it.charOffset} p${it.pdfPage}" }

    @Test
    fun `files, nested classes and the code after them`() {
        val a = "package p\n\nclass Outer {\n    class Inner {\n        fun f() {}\n    }\n    fun g() {}\n}\n\nfun top() {}"
        val b = "class B {\n}"
        val text = "$a\n\n$b"
        val bStart = a.length + 2
        val oS = text.indexOf("class Outer"); val oE = text.indexOf("}\n\nfun top")
        val iS = text.indexOf("class Inner"); val iE = text.indexOf("}\n    fun g")
        val files = listOf(
            ScannedFile(
                "m/src/main/kotlin/p/A.kt", 0, a.length - 1, 1, listOf("m", "p", "A.kt"),
                listOf(
                    cls("Outer", oS, oE), cls("Inner", iS, iE), fn("f", text.indexOf("fun f"), text.indexOf("{}\n    }") + 1),
                    fn("g", text.indexOf("fun g"), text.indexOf("{}\n}") + 1), fn("top", text.indexOf("fun top"), a.length - 1),
                ),
            ),
            ScannedFile("B.kt", bStart, text.length - 1, 2, listOf("B.kt"), listOf(cls("B", bStart, text.length - 1))),
        )
        assertEquals(
            listOf(
                "m › p › A.kt @0 p1",
                "m › p › A.kt › Outer @$oS p1",
                "m › p › A.kt › Outer › Inner @$iS p1",
                "m › p › A.kt › Outer @${iE + 1} p1",
                "m › p › A.kt @${oE + 1} p1",
                "B.kt › B @$bStart p2",
            ),
            show(CodeChapters.starts(text, files)),
        )
    }

    @Test
    fun `back starts holding only whitespace or closers are dropped`() {
        val t = "class X {}\nclass Y {\n  class I {\n  }\n}"
        val xE = t.indexOf("}"); val yS = t.indexOf("class Y"); val iS = t.indexOf("class I"); val iE = t.indexOf("}\n}")
        val f = ScannedFile("k.kt", 0, t.length - 1, 1, listOf("k.kt"), listOf(cls("X", 0, xE), cls("Y", yS, t.length - 1), cls("I", iS, iE)))
        assertEquals(listOf("k.kt › X @0 p1", "k.kt › Y @$yS p1", "k.kt › Y › I @$iS p1"), show(CodeChapters.starts(t, listOf(f))))
    }

    @Test
    fun `a file without classes is one chapter, functions never start one`() {
        val t = "fun a() {}\n\nfun b() {}"
        val f = ScannedFile("src/a.kt", 0, t.length - 1, 1, listOf("a.kt"), listOf(fn("a", 0, 9), fn("b", 12, t.length - 1)))
        assertEquals(listOf("a.kt @0 p1"), show(CodeChapters.starts(t, listOf(f))))
        assertEquals(emptyList(), CodeChapters.starts("", emptyList()))
    }

    @Test
    fun `class names on the stack, not parent indexes, make the title`() {
        val t = "class A {\n  class B {\n    class C {\n      x\n    }\n    y\n  }\n  z\n}"
        val aE = t.length - 1; val bS = t.indexOf("class B"); val bE = t.indexOf("}\n  z"); val cS = t.indexOf("class C"); val cE = t.indexOf("}\n    y")
        val f = ScannedFile("A.kt", 0, aE, 1, listOf("A.kt"), listOf(cls("A", 0, aE), cls("B", bS, bE), cls("C", cS, cE)))
        assertEquals(
            listOf("A.kt › A @0 p1", "A.kt › A › B @$bS p1", "A.kt › A › B › C @$cS p1", "A.kt › A › B @${cE + 1} p1", "A.kt › A @${bE + 1} p1"),
            show(CodeChapters.starts(t, listOf(f))),
        )
    }
}
