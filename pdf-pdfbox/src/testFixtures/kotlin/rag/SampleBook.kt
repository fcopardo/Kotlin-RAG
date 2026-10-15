package rag

import rag.extract.TestPdfs
import java.io.File

/** SPEC §11 generated book: cover, 2 TOC pages, preface, chapters at PDF pages 5, 8 and 11. */
object SampleBook {

    private val bodies = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel")

    /** PDF page (5..12) -> printed page (1..8), with a running book header, chapter header and footer. */
    /** On a chapter's first page the chapter line is its heading; on later pages it's a running title. */
    private fun chapterPage(pdfPage: Int, chapter: String, extra: List<String>): List<String> {
        val printed = pdfPage - 4
        val word = bodies[printed - 1]
        return listOf("The Bike Book", chapter, "", "Text about $word on this page.", "") +
            extra + listOf("Another $word paragraph here.", "", "$printed")
    }

    /**
     * [extra] lines are added mid-page on every chapter page, e.g. a repeated paragraph. Mid-page,
     * because a line repeated at a page edge is (rightly) removed as a header or footer.
     */
    fun pages(extra: List<String> = emptyList()): List<List<String>> = buildList {
        add(listOf("The Bike Book", "A. Writer"))                                              // 1 cover
        add(listOf("Contents", "", "Introduction . . . . . . . . 1", "   Why bikes . . . . . 2", "Riding Fast . . . . . . . . 4")) // 2 TOC
        add(listOf("Contents", "", "Repairs . . . . . . . . . . 7", "Index . . . . . . . . . . ix")) // 3 TOC
        add(listOf("Preface", "", "A short preface before the first chapter."))               // 4 front matter
        listOf(5 to "Introduction", 6 to "Introduction", 7 to "Introduction",
            8 to "Riding Fast", 9 to "Riding Fast", 10 to "Riding Fast",
            11 to "Repairs", 12 to "Repairs",
        ).forEach { (pdfPage, chapter) -> add(chapterPage(pdfPage, chapter, extra)) }
    }

    val pages: List<List<String>> = pages()

    val outline = listOf("Contents" to 2, "Introduction" to 5, "Riding Fast" to 8, "Repairs" to 11)

    /** Writes the book (with outline) to [file]. */
    fun write(file: File, extra: List<String> = emptyList()): File {
        TestPdfs.build(pages(extra), outline).use { it.save(file) }
        return file
    }
}
