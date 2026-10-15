package rag.summarize

import org.junit.jupiter.api.io.TempDir
import rag.RagException
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.index.UnitKind
import rag.search.MatchType
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SummaryIndexerTest {

    @TempDir
    lateinit var dir: File
    private lateinit var store: SqliteStore
    private val embedder = FakeEmbedder()

    @BeforeTest
    fun setUp() {
        store = SqliteStore(File(dir, "rag.db").path)
    }

    @AfterTest
    fun tearDown() = store.close()

    private fun index(huge: Boolean = false): Long =
        Indexer(store).index(SummaryBooks.build(File(dir, "book$huge.epub"), huge), embedder = embedder).bookId

    private fun summarize(bookId: Long, summarizer: Summarizer = FakeSummarizer(), redo: Boolean = false) =
        SummaryIndexer(store, summarizer, embedder).summarize(bookId, redo)

    private fun chapter(bookId: Long, title: String) = store.units(bookId, UnitKind.CHAPTER).single { it.title == title }

    @Test
    fun `tree shape - one-section chapter, multi-section chapter, book, no front matter`() {
        val bookId = index()

        val result = summarize(bookId)

        val all = store.summaries(bookId)
        val snap = chapter(bookId, "Snapshots")
        val slots = chapter(bookId, "Slot Tables")
        assertEquals(listOf("chapter"), all.filter { it.unitId == snap.id }.map { it.level })
        val slotLevels = all.filter { it.unitId == slots.id }.groupingBy { it.level }.eachCount()
        assertTrue(slotLevels.getValue("section") > 1)
        assertEquals(1, slotLevels.getValue("chapter"))
        assertEquals(1, all.count { it.level == "book" })
        val front = store.units(bookId, UnitKind.CHAPTER).single { it.ordinal == 0 }
        assertTrue(all.none { it.unitId == front.id }, "front matter has no summaries")
        assertEquals("complete", store.book(bookId)!!.summaryStatus)
        assertEquals(all.size, result.generated)
        assertEquals(all.size, result.embedded)
    }

    @Test
    fun `spans - inside their chapter, chapter summaries cover the chapter, the book covers chapters 1 to n`() {
        val bookId = index()
        summarize(bookId)

        val chapters = store.units(bookId, UnitKind.CHAPTER).associateBy { it.id }
        val all = store.summaries(bookId)
        all.filter { it.level != "book" }.forEach { s ->
            val c = chapters.getValue(s.unitId)
            assertTrue(s.charStart >= c.start && s.charEnd <= c.end, "${s.level} ${s.ordinal}")
            if (s.level == "chapter") assertEquals(c.start to c.end, s.charStart to s.charEnd)
        }
        val numbered = chapters.values.filter { it.ordinal > 0 }
        val book = all.single { it.level == "book" }
        assertEquals(numbered.minOf { it.start } to numbered.maxOf { it.end }, book.charStart to book.charEnd)
    }

    @Test
    fun `long chapters are reduced through group summaries`() {
        val bookId = index(huge = true)
        val fake = FakeSummarizer(padTo = mapOf(SummaryLevel.SECTION to 150, SummaryLevel.GROUP to 150))

        summarize(bookId, fake)

        val huge = chapter(bookId, "Huge Chapter")
        val levels = store.summaries(bookId).filter { it.unitId == huge.id }
        val sections = levels.count { it.level == "section" }
        val groups = levels.filter { it.level == "group" }
        assertTrue(sections > 12, "sections: $sections")
        assertEquals((sections + 11) / 12, groups.count { it.depth == 1 })
        assertEquals(groups.size, fake.tasks.count { it.level == SummaryLevel.GROUP })
        val chapterTask = fake.tasks.single { it.level == SummaryLevel.CHAPTER && it.chapterTitle == "Huge Chapter" }
        assertEquals(groups.size, chapterTask.inputs.size, "the chapter is made from the group summaries")
    }

    @Test
    fun `an interrupted run resumes with only the missing summaries`() {
        val bookId = index()
        assertFailsWith<RagException.EmbeddingBackendUnavailable> { summarize(bookId, FakeSummarizer(failAfter = 3)) }
        assertEquals("partial", store.book(bookId)!!.summaryStatus)
        val made = store.summaries(bookId).size

        val resume = FakeSummarizer()
        val result = summarize(bookId, resume)

        assertEquals("complete", store.book(bookId)!!.summaryStatus)
        assertEquals(made, result.reused)
        assertEquals(store.summaries(bookId).size - made, resume.tasks.size)
    }

    @Test
    fun `redo or another model replaces everything, reindex removes it without orphans`() {
        val bookId = index()
        summarize(bookId)
        val count = store.summaries(bookId).size

        val redo = FakeSummarizer()
        summarize(bookId, redo, redo = true)
        assertEquals(count, redo.tasks.size)

        val other = FakeSummarizer(model = "other-model")
        summarize(bookId, other)
        assertEquals(count, other.tasks.size)
        assertTrue(store.summaries(bookId).all { it.model == "other-model" })
        assertEquals(count, store.count("summary_fts"))

        Indexer(store).index(File(dir, "bookfalse.epub"), reindex = true, embedder = embedder)
        assertEquals(0, store.count("summary"))
        assertEquals(0, store.count("summary_fts"))
    }

    @Test
    fun `an empty summary fails with exit code 5`() {
        val bookId = index()

        val e = assertFailsWith<RagException.EmbeddingBackendUnavailable> { summarize(bookId, FakeSummarizer(output = { "Here is a summary:" })) }

        assertEquals(5, e.exitCode)
    }

    @Test
    fun `search returns summary hits with their text, chapter and a paragraph in the span`() {
        val bookId = index()
        summarize(bookId)

        val hits = Searcher(store, embedder).search("slot table fact", k = 10)

        val summaryHits = hits.filter { it.matchType == MatchType.SUMMARY }
        assertTrue(summaryHits.isNotEmpty())
        assertTrue(summaryHits.size <= Searcher.MAX_SUMMARY_HITS)
        summaryHits.forEach { h ->
            assertTrue(!h.summary.isNullOrBlank())
            assertTrue(h.summaryLevel in setOf("section", "chapter", "book"))
            assertEquals("Slot Tables", h.chapter.title)
            assertTrue("slot table" in h.paragraph.text)
        }
        assertTrue(Searcher(store, embedder, includeSummaries = false).search("slot table fact", k = 10).none { it.matchType == MatchType.SUMMARY })
        // Summaries are added on top of the k passages, never instead of them.
        val passages = hits.filter { it.matchType != MatchType.SUMMARY }.map { it.paragraph.unitKey }
        assertEquals(Searcher(store, embedder, includeSummaries = false).search("slot table fact", k = 10).map { it.paragraph.unitKey }, passages)
    }

    @Test
    fun `exact hits still come first`() {
        val bookId = index()
        summarize(bookId)

        val first = Searcher(store, embedder).search("A snapshot is applied when its changes are ready.").first()

        assertEquals(MatchType.EXACT_PARAGRAPH, first.matchType)
    }
}
