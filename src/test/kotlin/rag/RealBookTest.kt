package rag

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import rag.extract.Normalizer
import rag.extract.PdfExtractor
import rag.structure.ChapterDetector
import rag.structure.ChapterStrategy
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks chapter detection on "Jetpack Compose internals" (Leanpub, 2022-11-20, 205 pages).
 * The book isn't part of the repo: set RAG_BOOK_PDF to a local copy to run these.
 */
@Tag("book")
class RealBookTest {

    private val pdf = System.getenv("RAG_BOOK_PDF")?.let(::File)

    private val titles = listOf(
        "Prelude",
        "1. Composable functions",
        "2. The Compose compiler",
        "3. The Compose runtime",
        "4. Compose UI",
        "5. State snapshot system",
        "6. Effects and effect handlers",
        "7. Advanced Compose Runtime use cases",
    )
    private val startPages = listOf(9, 11, 25, 58, 88, 147, 173, 185)

    @BeforeTest
    fun requireBook() {
        assumeTrue(pdf?.isFile == true, "set RAG_BOOK_PDF to the Compose internals PDF to run")
    }

    @Test
    fun `outline gives the chapters and the TOC pages`() {
        val result = BookReader().read(pdf!!)

        assertEquals(ChapterStrategy.OUTLINE, result.plan.strategy)
        assertEquals((5..8).toSet(), result.plan.tocPages)
        assertEquals(listOf("Front matter") + titles, result.chapters.map { it.title })
        // Page 1 is an image-only cover, so front matter starts on page 2.
        assertEquals(listOf(2) + startPages, result.chapters.map { it.pageStart })
        assertEquals(205, result.chapters.last().pageEnd)
    }

    @Test
    fun `TOC pages give the same chapters with offset 8`() {
        val extractor = PdfExtractor()
        extractor.open(pdf!!).use { document ->
            val plan = ChapterDetector().fromToc(document, extractor.readPages(document), (5..8).toSet())!!

            assertEquals(8, plan.pageOffset)
            assertEquals(titles, plan.starts.map { it.title })
            assertEquals(startPages, plan.starts.map { it.pdfPage })
            assertEquals(emptyList(), plan.warnings)
        }
    }

    @Test
    fun `every paragraph and phrase points at its exact, trimmed text`() {
        val result = BookReader().read(pdf!!)
        val text = result.book.text

        result.segments.forEach { s ->
            s.paragraphs.forEach { p ->
                assertTrue(p.start >= s.chapter.charStart && p.end <= s.chapter.charEnd, "paragraph outside its chapter")
                val pText = text.substring(p.start, p.end + 1)
                assertEquals(pText.trim(), pText)
                assertTrue(pText.split(Regex("\\s+")).size * 1.3 <= 400, "paragraph over the cap")
                assertTrue(p.phrases.isNotEmpty())
                p.phrases.zipWithNext().forEach { (a, b) -> assertTrue(a.end < b.start, "phrases overlap") }
                p.phrases.forEach { ph ->
                    assertTrue(ph.start >= p.start && ph.end <= p.end, "phrase outside its paragraph")
                    val phText = text.substring(ph.start, ph.end + 1)
                    assertEquals(phText.trim(), phText)
                }
            }
        }
    }

    @Test
    fun `code listings lose their line numbers`() {
        val result = BookReader().read(pdf!!)
        val code = result.segments.flatMap { s -> s.paragraphs.filter { it.isCode } }

        assertTrue(code.size > 100, "expected many code listings, found ${code.size}")
        // The first listing in chapter "1. Composable functions" (page 13) starts with the annotation.
        val chapter = result.segments.single { it.chapter.title == "1. Composable functions" }
        val first = chapter.paragraphs.first { it.isCode }
        assertEquals("@Composable", result.text(first.phrases.first().start, first.phrases.first().end))
        // No leftover runs of consecutively numbered lines.
        val lines = result.book.text.lines()
        val leftovers = lines.zipWithNext().count { (a, b) ->
            val n = Regex("^(\\d+) ").find(a)?.groupValues?.get(1)?.toInt()
            n != null && b.startsWith("${n + 1} ")
        }
        assertEquals(0, leftovers, "numbered code lines left in the text")
    }

    @Test
    fun `the indexed book fits the 3 MB budget`(@org.junit.jupiter.api.io.TempDir dir: File) {
        val db = File(dir, "book.db")
        rag.store.SqliteStore(db.path).use { store ->
            rag.index.Indexer(store).index(pdf!!, embedder = rag.embed.FakeEmbedder(dim = 768), embedDim = rag.index.Indexer.DEFAULT_DIM)
        }
        val bytes = dir.listFiles()!!.filter { it.name.startsWith("book.db") }.sumOf { it.length() }

        assertTrue(bytes <= 3 * 1024 * 1024, "DB is ${bytes / 1024} KB")
    }

    @Test
    fun `running chapter titles and TOC leaders are gone`() {
        val result = BookReader().read(pdf!!)

        assertFalse(". . . . . . . ." in result.book.text, "TOC pages should be left out")
        // 35 URL footnotes and their markers are gone (NFKC would have turned "Birch¹" into "Birch1").
        assertFalse(Regex("(?m)^\\d+https?://").containsMatchIn(result.book.text), "footnote lines left")
        listOf("Birch1", "cs.android.com2", "Shikov5").forEach { assertFalse(it in result.book.text, "marker left: $it") }
        result.chapters.drop(1).forEach { c ->
            val target = Normalizer.hashNormalize(c.title)
            val chapterText = result.text(c)
            // Starts at its heading (which may wrap over two lines, like chapter 7's).
            assertTrue(Normalizer.hashNormalize(chapterText).startsWith(target), "'${c.title}' should start at its heading")
            // No running header ("<title> <page>") is left on later pages.
            val runningHeaders = chapterText.lines().count { Normalizer.hashNormalize(it).matches(Regex("${Regex.escape(target)} \\d+")) }
            assertEquals(0, runningHeaders, "'${c.title}' running headers left")
        }
    }
}
