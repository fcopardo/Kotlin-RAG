package rag.summarize

import rag.embed.Embedder
import rag.embed.Quantizer
import rag.index.UnitKind
import rag.store.SqliteStore
import rag.store.StoredSummary
import rag.store.StoredUnit

data class SummaryResult(val generated: Int, val reused: Int, val embedded: Int, val status: String)

/**
 * Builds a book's summary tree (SPEC §5.7): sections → groups → chapter, per chapter, then the book.
 *
 * Every summary is committed as it's made. A rerun keeps summaries already made with the same model
 * and prompt version and only makes the missing ones; `redo` or another model starts over.
 */
class SummaryIndexer(
    private val store: SqliteStore,
    private val summarizer: Summarizer,
    /** Embeds summaries with the book's model; null leaves them unembedded (keyword search only). */
    private val embedder: Embedder? = null,
    private val batchSize: Int = 32,
    private val progress: (String) -> Unit = {},
) {
    private var generated = 0
    private var reused = 0

    fun summarize(bookId: Long, redo: Boolean = false): SummaryResult {
        val book = store.book(bookId) ?: throw IllegalArgumentException("no book with id $bookId")
        if (redo || (book.summaryModel != null && (book.summaryModel != summarizer.model || book.summaryPrompt != Prompts.VERSION))) {
            store.deleteSummaries(bookId)
        }
        summarizer.checkAvailable()
        embedder?.checkAvailable()
        store.setSummaryState(bookId, summarizer.model, Prompts.VERSION, "partial")
        generated = 0
        reused = 0

        val text = store.bookText(bookId)
        val title = book.title ?: "Untitled"
        val bookUnit = store.units(bookId, UnitKind.BOOK).single()
        val chapters = store.units(bookId, UnitKind.CHAPTER).sortedBy { it.ordinal }
        // Front matter (chapter 0) is left out unless it is the only chapter.
        val selected = chapters.filter { it.ordinal > 0 }.ifEmpty { chapters }

        val chapterSummaries = selected.mapNotNull { chapter ->
            val paragraphs = store.children(chapter.id).map {
                TreeParagraph(text.substring(it.start, it.end + 1), it.isCode, it.start, it.end, it.pageStart, it.pageEnd)
            }
            if (paragraphs.isEmpty()) null else chapter to chapterSummary(bookId, title, chapter, paragraphs)
        }
        if (chapterSummaries.isNotEmpty()) {
            val first = chapterSummaries.first().first
            val last = chapterSummaries.last().first
            get(bookId, bookUnit, SummaryLevel.BOOK, 0, 0, TreeSpan("", first.start, last.end, first.pageStart, last.pageEnd),
                SummaryTask(SummaryLevel.BOOK, title, null, chapterSummaries.map { (c, s) -> "${c.title}: ${s.text}" }, SummaryTree.BOOK_WORDS))
        }

        val embedded = embed(bookId, title, chapters)
        store.setSummaryState(bookId, summarizer.model, Prompts.VERSION, "complete")
        return SummaryResult(generated, reused, embedded, "complete")
    }

    private fun chapterSummary(bookId: Long, title: String, chapter: StoredUnit, paragraphs: List<TreeParagraph>): StoredSummary {
        val chapterSpan = TreeSpan("", chapter.start, chapter.end, chapter.pageStart, chapter.pageEnd)
        val sections = SummaryTree.sections(paragraphs)
        // A one-section chapter gets its chapter summary straight from the text.
        if (sections.size == 1) {
            return get(bookId, chapter, SummaryLevel.CHAPTER, 0, 0, chapterSpan,
                SummaryTask(SummaryLevel.SECTION, title, chapter.title, listOf(sections.single().text), SummaryTree.CHAPTER_WORDS))
        }
        var current = sections.mapIndexed { i, section ->
            get(bookId, chapter, SummaryLevel.SECTION, 0, i, section,
                SummaryTask(SummaryLevel.SECTION, title, chapter.title, listOf(section.text), SummaryTree.SECTION_WORDS))
        }
        var depth = 1
        while (SummaryTree.needsReduction(current.map { it.text })) {
            val previous = current
            current = SummaryTree.groups(previous.size).mapIndexed { g, members ->
                val span = TreeSpan("", previous[members.first()].charStart, previous[members.last()].charEnd,
                    previous[members.first()].pageStart, previous[members.last()].pageEnd)
                get(bookId, chapter, SummaryLevel.GROUP, depth, g, span,
                    SummaryTask(SummaryLevel.GROUP, title, chapter.title, members.map { previous[it].text }, SummaryTree.GROUP_WORDS))
            }
            depth++
        }
        return get(bookId, chapter, SummaryLevel.CHAPTER, 0, 0, chapterSpan,
            SummaryTask(SummaryLevel.CHAPTER, title, chapter.title, current.map { it.text }, SummaryTree.CHAPTER_WORDS))
    }

    /** The summary at this position: reused if it exists for this model and prompt, else made and stored. */
    private fun get(bookId: Long, unit: StoredUnit, level: SummaryLevel, depth: Int, ordinal: Int, span: TreeSpan, task: SummaryTask): StoredSummary {
        store.findSummary(unit.id, level.db, depth, ordinal, summarizer.model, Prompts.VERSION)?.let { reused++; return it }
        val text = SummaryText.clean(summarizer.summarize(task), task.maxWords)
        if (text.isEmpty()) throw rag.RagException.EmbeddingBackendUnavailable("The summary model returned no usable text for ${unit.title ?: "the book"}")
        val id = store.insertSummary(bookId, unit.id, level.db, depth, ordinal, span.start, span.end, span.pageStart, span.pageEnd,
            summarizer.model, Prompts.VERSION, text)
        generated++
        progress("${level.db} ${unit.title ?: "book"} #$ordinal")
        return store.summary(id)!!
    }

    /** Embeds summaries that lack a vector from the book's embedding model (SPEC §5.7.4). */
    private fun embed(bookId: Long, title: String, chapters: List<StoredUnit>): Int {
        val e = embedder ?: return 0
        val book = store.book(bookId)!!
        val dim = book.embedDim ?: return 0
        if (book.embedModel != e.model) return 0
        val titles = chapters.associate { it.id to it.title.orEmpty() }
        var count = 0
        store.summariesMissingEmbedding(bookId, e.model, dim).chunked(batchSize).forEach { batch ->
            val inputs = batch.map { s -> (titles[s.unitId] ?: title) + "\n" + s.text }
            val vectors = e.embedDocuments(inputs).map { Quantizer.quantize(Quantizer.truncate(it, dim)) }
            store.saveSummaryEmbeddings(e.model, batch.map { it.id }.zip(vectors))
            count += batch.size
        }
        return count
    }
}
