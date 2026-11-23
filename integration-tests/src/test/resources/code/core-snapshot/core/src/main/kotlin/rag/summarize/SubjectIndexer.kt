package rag.summarize

import rag.embed.Embedder
import rag.index.Indexer
import rag.index.UnitKind
import rag.store.SqliteStore
import rag.structure.Chapters

/**
 * Asks a local model what a book is about (SPEC §8.5): from its chapter titles and, when the book
 * has one, its summary. The weighted subjects replace the book's previous "llm" subjects and are
 * embedded with each [embedders] model, so filters can match them by meaning.
 */
class SubjectIndexer(
    private val store: SqliteStore,
    private val summarizer: Summarizer,
    private val embedders: List<Pair<Embedder, Int>> = emptyList(),
) {
    fun describe(bookId: Long): List<BookSubject> {
        val book = store.book(bookId) ?: throw IllegalArgumentException("no book with id $bookId")
        summarizer.checkAvailable()
        val chapters = store.units(bookId, UnitKind.CHAPTER).mapNotNull { it.title }.filter { it != Chapters.FRONT_MATTER }
        val summary = store.summaries(bookId).firstOrNull { it.level == SummaryLevel.BOOK.db }?.text
        val output = summarizer.complete(Subjects.SYSTEM, Subjects.prompt(book.title ?: "Untitled", chapters, summary))
        val subjects = Subjects.parse(output)
        require(subjects.isNotEmpty()) { "${summarizer.model} gave no subjects in the expected format: ${output.take(200)}" }
        store.setSubjects(bookId, "llm", subjects)
        val indexer = Indexer(store)
        embedders.forEach { (embedder, dim) -> indexer.embedSubjects(bookId, embedder, dim) }
        return subjects
    }
}
