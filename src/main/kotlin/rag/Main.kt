package rag

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import rag.embed.EmbeddingModel
import rag.embed.OllamaEmbedder
import rag.index.EmbedResult
import rag.index.Indexer
import rag.index.UnitKind
import kotlinx.serialization.json.Json
import rag.search.Evaluation
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.sql.SQLException
import kotlin.system.exitProcess

private class Rag : CliktCommand(name = "rag") {
    override fun help(context: Context) = "Digest PDF books into a low-memory local RAG index."
    override fun run() = Unit
}

/** Options shared by commands that talk to Ollama. */
private abstract class EmbeddingCommand(name: String) : CliktCommand(name = name) {
    val ollamaUrl by option("--ollama-url", help = "Ollama base URL").default(OllamaEmbedder.DEFAULT_URL)
    val embedModel by option("--embed-model", help = "Ollama embedding model").default(EmbeddingModel.DEFAULT.name)
    val embedDim by option("--embed-dim", help = "Dimensions kept after Matryoshka truncation (multiple of 64)")
        .int().default(Indexer.DEFAULT_DIM)

    fun embedder() = OllamaEmbedder(EmbeddingModel.forName(embedModel), ollamaUrl)

    fun report(result: EmbedResult) =
        echo("Embeddings: ${result.embedded} embedded, ${result.copied} reused from identical text, status ${result.status}")
}

private class IndexCommand : EmbeddingCommand("index") {
    override fun help(context: Context) = "Parse a PDF, store its units, and embed its paragraphs."
    val pdf by option("--pdf", help = "PDF file").file(mustExist = true, canBeDir = false).required()
    val db by option("--db", help = "SQLite database file").required()
    val tocPages by option("--toc-pages", help = "PDF pages with the table of contents, e.g. 5-8")
        .convert { BookReader.parsePageRanges(it) }
    val reindex by option("--reindex", help = "Rebuild the book if it's already indexed").flag()
    val noEmbed by option("--no-embed", help = "Skip embedding (run `rag embed` later)").flag()

    override fun run() = SqliteStore(db).use { store ->
        val result = Indexer(store).index(pdf, tocPages, reindex, if (noEmbed) null else embedder(), embedDim)
        if (result.skipped) {
            val book = store.book(result.bookId)!!
            echo("Already indexed as book ${result.bookId} (embeddings: ${book.embedStatus}). Use --reindex to rebuild.")
            if (book.embedStatus != "complete") echo("Run `rag embed --db $db --book ${result.bookId}` to finish embedding.")
            return@use
        }
        val book = store.book(result.bookId)!!
        val units = store.units(result.bookId).groupingBy { it.kind }.eachCount()
        echo("Indexed book ${result.bookId}: ${book.title ?: pdf.name}")
        echo("Chapters by ${book.chapterStrategy}: ${units[UnitKind.CHAPTER] ?: 0}, paragraphs: ${units[UnitKind.PARAGRAPH] ?: 0}, phrases: ${units[UnitKind.PHRASE] ?: 0}")
        result.embedded?.let(::report)
    }
}

private class EmbedCommand : EmbeddingCommand("embed") {
    override fun help(context: Context) = "Embed (or finish embedding) an indexed book's paragraphs."
    val db by option("--db").required()
    val book by option("--book", help = "Book id (see `rag books`)").long().required()

    override fun run() = SqliteStore(db).use { store ->
        val embedder = embedder().also { it.checkAvailable() }
        report(Indexer(store).embed(book, embedder, embedDim))
    }
}

private class SearchCommand : CliktCommand(name = "search") {
    override fun help(context: Context) = "Search indexed books: exact, keyword and meaning-based."
    val db by option("--db").required()
    val query by option("--query", "-q", help = "What to look for").required()
    val k by option("--k", help = "Number of results").int().default(5)
    val book by option("--book", help = "Only this book id").long()
    val mode by option("--mode", help = "hybrid | lexical | vector").choice("hybrid", "lexical", "vector").default("hybrid")
    val json by option("--json", help = "Print results as JSON").flag()
    val ollamaUrl by option("--ollama-url").default(OllamaEmbedder.DEFAULT_URL)
    val embedModel by option("--embed-model", help = "Defaults to the model the books were embedded with")

    override fun run() = SqliteStore(db).use { store ->
        val scope = if (book != null) listOfNotNull(store.book(book!!)) else store.books()
        if (scope.isEmpty()) throw UsageError(if (book != null) "no book with id $book" else "no books indexed")
        val model = embedModel ?: scope.mapNotNull { it.embedModel }.distinct().let { models ->
            if (models.size > 1) throw UsageError("books use different models ($models): pass --book or --embed-model")
            models.firstOrNull()
        }
        val embedder = if (mode == "lexical" || model == null) null else OllamaEmbedder(EmbeddingModel.forName(model), ollamaUrl)
        val hits = Searcher(store, embedder).search(query, k, book, Mode.valueOf(mode.uppercase()))
        if (json) {
            echo(Json { prettyPrint = true }.encodeToString(hits))
            return@use
        }
        if (hits.isEmpty()) echo("No results.")
        hits.forEachIndexed { i, h ->
            echo("${i + 1}. [${h.matchType}] ${h.chapter.title} · p. ${pages(h.paragraph.pageStart, h.paragraph.pageEnd)}  (score %.4f)".format(h.score))
            echo("   phrase: ${h.phrase.text.replace('\n', ' ')}")
            if (h.paragraph.text != h.phrase.text) echo("   paragraph: ${h.paragraph.text.replace('\n', ' ').take(300)}")
            echo("   key: ${h.phrase.unitKey}")
        }
    }

    private fun pages(start: Int, end: Int) = if (start == end) "$start" else "$start-$end"
}

private class EvalCommand : CliktCommand(name = "eval") {
    override fun help(context: Context) = "Measure search quality with an evaluation set (JSON Lines)."
    val db by option("--db").required()
    val set by option("--set", help = "Evaluation set, one JSON question per line").file(mustExist = true, canBeDir = false).required()
    val book by option("--book", help = "Only this book id").long()
    val k by option("--k", help = "Results considered per question").int().default(5)
    val mode by option("--mode", help = "all | hybrid | lexical | vector").choice("all", "hybrid", "lexical", "vector").default("all")
    val details by option("--details", help = "Show the rank of every question").flag()
    val ollamaUrl by option("--ollama-url").default(OllamaEmbedder.DEFAULT_URL)
    val embedModel by option("--embed-model", help = "Defaults to the model the books were embedded with")

    override fun run() = SqliteStore(db).use { store ->
        val questions = Evaluation.load(set)
        val scope = if (book != null) listOfNotNull(store.book(book!!)) else store.books()
        if (scope.isEmpty()) throw UsageError("no books to evaluate")
        val model = embedModel ?: scope.mapNotNull { it.embedModel }.distinct().singleOrNull()
        val embedder = model?.let { OllamaEmbedder(EmbeddingModel.forName(it), ollamaUrl) }
        val modes = if (mode == "all") listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID) else listOf(Mode.valueOf(mode.uppercase()))
        val results = Evaluation.run(Searcher(store, embedder), questions, modes, k, book)
        val kinds = questions.groupingBy { it.kind }.eachCount().toSortedMap()

        echo("${questions.size} questions (${kinds.entries.joinToString { "${it.value} ${it.key}" }}), top $k")
        echo("%-8s %9s %6s %10s  %s".format("mode", "recall@$k", "MRR", "chapter@$k", "recall by kind"))
        Evaluation.summarize(results, k).forEach { s ->
            val byKind = s.recallByKind.entries.joinToString("  ") { "%s %.2f".format(it.key, it.value) }
            echo("%-8s %9.2f %6.2f %10.2f  %s".format(s.mode.name.lowercase(), s.recall, s.mrr, s.chapterRecall, byKind))
        }
        if (details) {
            echo("")
            echo("%-5s %-10s %s  query".format("id", "kind", modes.joinToString(" ") { "%-7s".format(it.name.lowercase()) }))
            questions.forEach { q ->
                val ranks = modes.joinToString(" ") { m ->
                    val r = results.first { it.question == q && it.mode == m }
                    "%-7s".format(r.pageRank?.let { "#$it" } ?: "-")
                }
                echo("%-5s %-10s %s  %s  (p. %s)".format(q.id, q.kind, ranks, q.query, q.expectedPages.joinToString(",")))
            }
        }
    }
}

private class BooksCommand : CliktCommand(name = "books") {
    override fun help(context: Context) = "List indexed books."
    val db by option("--db").required()

    override fun run() = SqliteStore(db).use { store ->
        val books = store.books()
        if (books.isEmpty()) echo("No books indexed.")
        books.forEach { b ->
            val vectors = b.embedModel?.let { "${it}@${b.embedDim}d ${b.embedStatus}" } ?: "no embeddings"
            echo("${b.id}  ${b.title ?: "(untitled)"}  [chapters by ${b.chapterStrategy}; $vectors]")
        }
    }
}

private class DumpCommand : CliktCommand(name = "dump") {
    override fun help(context: Context) = "Show a book's chapters, or one chapter's text."
    val db by option("--db").required()
    val book by option("--book").long().required()
    val what by mutuallyExclusiveOptions(
        option("--chapters", help = "List chapters (default)").flag().convert { null as Int? },
        option("--chapter", help = "Print chapter N's text").int(),
    ).single()

    override fun run() = SqliteStore(db).use { store ->
        store.book(book) ?: throw UsageError("no book with id $book")
        val chapters = store.units(book, UnitKind.CHAPTER)
        val number = what
        if (number == null) {
            chapters.forEach { c ->
                echo("%2d  pages %3d-%3d  %s".format(c.ordinal, c.pageStart, c.pageEnd, c.title))
            }
        } else {
            val chapter = chapters.firstOrNull { it.ordinal == number } ?: throw UsageError("no chapter $number in book $book")
            echo(store.bookText(book).substring(chapter.start, chapter.end + 1))
        }
    }
}

fun main(args: Array<String>) {
    val command = Rag().subcommands(IndexCommand(), EmbedCommand(), SearchCommand(), EvalCommand(), BooksCommand(), DumpCommand())
    val exitCode = try {
        command.parse(args)
        0
    } catch (e: CliktError) {
        command.echoFormattedHelp(e)
        // Clikt uses 1 for usage errors; the spec reserves 2 for bad arguments.
        if (e is UsageError) 2 else e.statusCode
    } catch (e: RagException) {
        System.err.println("Error: ${e.message}")
        e.exitCode
    } catch (e: SQLException) {
        System.err.println("Database error: ${e.message}")
        4
    }
    exitProcess(exitCode)
}
