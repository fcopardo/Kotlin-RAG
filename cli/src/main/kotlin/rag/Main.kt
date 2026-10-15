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
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import rag.embed.EmbeddingModel
import rag.embed.Embedder
import rag.embed.OllamaEmbedder
import rag.embed.OnnxModels
import rag.index.EmbedResult
import rag.index.Indexer
import rag.index.UnitKind
import kotlinx.serialization.json.Json
import rag.search.Evaluation
import rag.summarize.OllamaSummarizer
import rag.summarize.SummaryIndexer
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.sql.SQLException
import kotlin.system.exitProcess

private fun CliktCommand.onnxDirOption() =
    option("--onnx-dir", help = "Embed with ONNX Runtime from this model directory instead of Ollama (e.g. models/nomic-embed-text-v1.5)")
        .file(mustExist = true, canBeFile = false)

/**
 * Ollama, or with [onnxDir] the ONNX build phones use (SPEC §15.9). Models only built with ONNX
 * ("…@onnx-int8") need [onnxDir].
 */
private fun embedderFor(model: String, ollamaUrl: String, onnxDir: java.io.File?): Embedder {
    if (onnxDir == null) {
        if ('@' in model) throw UsageError("'$model' vectors come from an ONNX build: pass --onnx-dir with its files")
        return OllamaEmbedder(EmbeddingModel.forName(model), ollamaUrl)
    }
    val onnx = OnnxModels.forName(model)
        ?: throw UsageError("no ONNX build known for '$model' (known: ${OnnxModels.ALL.joinToString { it.storedName }})")
    return try {
        onnx.open(onnxDir)
    } catch (e: UnsatisfiedLinkError) {
        // ONNX Runtime ships natives for macOS arm64, Linux x64/arm64 and Windows x64 only.
        throw UsageError("ONNX Runtime has no native library for this JVM (${System.getProperty("os.name")} ${System.getProperty("os.arch")}). " +
            "On Apple silicon, run with an arm64 JDK (JAVA_HOME), not an Intel one under Rosetta.")
    }
}

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

    val onnxDir by onnxDirOption()

    fun embedder() = embedderFor(embedModel, ollamaUrl, onnxDir)

    fun report(result: EmbedResult) =
        echo("Embeddings: ${result.embedded} embedded, ${result.copied} reused from identical text, status ${result.status}")
}

private class IndexCommand : EmbeddingCommand("index") {
    override fun help(context: Context) = "Parse a PDF or EPUB, store its units, and embed its paragraphs."
    val file by option("--file", "--pdf", help = "PDF or EPUB file (format detected from content)").file(mustExist = true, canBeDir = false).required()
    val db by option("--db", help = "SQLite database file").required()
    val tocPages by option("--toc-pages", help = "PDF only: pages with the table of contents, e.g. 5-8")
        .convert { BookReader.parsePageRanges(it) }
    val locationSize by option("--location-size", help = "EPUB without a page list: characters per location")
        .int().default(ReadOptions.DEFAULT_LOCATION_SIZE)
    val reindex by option("--reindex", help = "Rebuild the book if it's already indexed").flag()
    val noEmbed by option("--no-embed", help = "Skip embedding (run `rag embed` later)").flag()

    override fun run() = SqliteStore(db).use { store ->
        val result = Indexer(store).index(file, tocPages, reindex, if (noEmbed) null else embedder(), embedDim, locationSize)
        if (result.skipped) {
            val book = store.book(result.bookId)!!
            echo("Already indexed as book ${result.bookId} (embeddings: ${book.embedStatus}). Use --reindex to rebuild.")
            if (book.embedStatus != "complete") echo("Run `rag embed --db $db --book ${result.bookId}` to finish embedding.")
            return@use
        }
        val book = store.book(result.bookId)!!
        val units = store.units(result.bookId).groupingBy { it.kind }.eachCount()
        echo("Indexed book ${result.bookId}: ${book.title ?: file.name} (${book.format}, pages: ${book.pageKind})")
        echo("Chapters by ${book.chapterStrategy}: ${units[UnitKind.CHAPTER] ?: 0}, paragraphs: ${units[UnitKind.PARAGRAPH] ?: 0}, phrases: ${units[UnitKind.PHRASE] ?: 0}")
        result.embedded?.let(::report)
    }
}

private class EmbedCommand : EmbeddingCommand("embed") {
    override fun help(context: Context) = "Embed (or finish embedding) an indexed book's paragraphs."
    val db by option("--db").required()
    val book by option("--book", help = "Book id (see `rag books`)").long().required()
    val add by option("--add", help = "Keep the book's other models' vectors, so it can be searched with either (e.g. for phones, SPEC §15.9)").flag()

    override fun run() = SqliteStore(db).use { store ->
        val embedder = embedder().also { it.checkAvailable() }
        report(Indexer(store).embed(book, embedder, embedDim, add = add))
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
    val noSummaries by option("--no-summaries", help = "Search paragraphs only").flag()
    val ollamaUrl by option("--ollama-url").default(OllamaEmbedder.DEFAULT_URL)
    val embedModel by option("--embed-model", help = "Defaults to the model the books were embedded with")
    val onnxDir by onnxDirOption()
    val lexicalWeight by option("--lexical-weight", help = "Keyword weight in hybrid fusion (vector = 1)").double().default(Searcher.LEXICAL_WEIGHT)
    val rrfK by option("--rrf-k", help = "Rank fusion constant; smaller makes rank matter more").int().default(Searcher.RRF_K)
    val frontMatterWeight by option("--front-matter-weight", help = "Score factor for front-matter paragraphs (1 = off)").double().default(Searcher.FRONT_MATTER_WEIGHT)
    val summaryWeight by option("--summary-weight", help = "Weight of summaries in hybrid fusion").double().default(Searcher.SUMMARY_WEIGHT)
    val maxSummaryHits by option("--max-summary-hits", help = "At most this many summary hits per query").int().default(Searcher.MAX_SUMMARY_HITS)

    override fun run() = SqliteStore(db).use { store ->
        val scope = if (book != null) listOfNotNull(store.book(book!!)) else store.books()
        if (scope.isEmpty()) throw UsageError(if (book != null) "no book with id $book" else "no books indexed")
        val model = embedModel ?: scope.mapNotNull { it.embedModel }.distinct().let { models ->
            if (models.size > 1) throw UsageError("books use different models ($models): pass --book or --embed-model")
            models.firstOrNull()
        }
        val embedder = if (mode == "lexical" || model == null) null else embedderFor(model, ollamaUrl, onnxDir)
        val hits = Searcher(store, embedder, lexicalWeight = lexicalWeight, rrfK = rrfK, includeSummaries = !noSummaries, frontMatterWeight = frontMatterWeight, summaryWeight = summaryWeight, maxSummaryHits = maxSummaryHits)
            .search(query, k, book, Mode.valueOf(mode.uppercase()))
        if (json) {
            echo(Json { prettyPrint = true }.encodeToString(hits))
            return@use
        }
        if (hits.isEmpty()) echo("No results.")
        hits.forEachIndexed { i, h ->
            val type = h.summaryLevel?.let { "${h.matchType} $it" } ?: h.matchType.name
            echo("${i + 1}. [$type] ${h.chapter.title} · ${pages(h.pageKind, h.paragraph.pageStartLabel, h.paragraph.pageEndLabel)}  (score %.4f)".format(h.score))
            h.summary?.let { echo("   summary: ${it.replace('\n', ' ')}") }
            echo("   phrase: ${h.phrase.text.replace('\n', ' ')}")
            if (h.paragraph.text != h.phrase.text) echo("   paragraph: ${h.paragraph.text.replace('\n', ' ').take(300)}")
            echo("   key: ${h.phrase.unitKey}")
        }
    }

    private fun pages(kind: String, start: String, end: String) = citation(kind, start, end)
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
    val onnxDir by onnxDirOption()
    val lexicalWeight by option("--lexical-weight", help = "Keyword weight in hybrid fusion (vector = 1)").double().default(Searcher.LEXICAL_WEIGHT)
    val rrfK by option("--rrf-k", help = "Rank fusion constant; smaller makes rank matter more").int().default(Searcher.RRF_K)
    val frontMatterWeight by option("--front-matter-weight", help = "Score factor for front-matter paragraphs (1 = off)").double().default(Searcher.FRONT_MATTER_WEIGHT)
    val summaryWeight by option("--summary-weight", help = "Weight of summaries in hybrid fusion").double().default(Searcher.SUMMARY_WEIGHT)
    val maxSummaryHits by option("--max-summary-hits", help = "At most this many summary hits per query").int().default(Searcher.MAX_SUMMARY_HITS)

    override fun run() = SqliteStore(db).use { store ->
        val questions = Evaluation.load(set)
        val scope = if (book != null) listOfNotNull(store.book(book!!)) else store.books()
        if (scope.isEmpty()) throw UsageError("no books to evaluate")
        val model = embedModel ?: scope.mapNotNull { it.embedModel }.distinct().singleOrNull()
        val embedder = model?.let { embedderFor(it, ollamaUrl, onnxDir) }
        val modes = if (mode == "all") listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID) else listOf(Mode.valueOf(mode.uppercase()))
        val results = Evaluation.run(Searcher(store, embedder, lexicalWeight = lexicalWeight, rrfK = rrfK, frontMatterWeight = frontMatterWeight, summaryWeight = summaryWeight, maxSummaryHits = maxSummaryHits), questions, modes, k, book)
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

private class SummarizeCommand : CliktCommand(name = "summarize") {
    override fun help(context: Context) = "Summarize a book's sections, chapters and the whole book with a local model (Phase 6)."
    val db by option("--db").required()
    val book by option("--book", help = "Book id (see `rag books`)").long().required()
    val summaryModel by option("--summary-model", help = "Ollama model that writes the summaries").default(OllamaSummarizer.DEFAULT_MODEL)
    val ollamaUrl by option("--ollama-url").default(OllamaEmbedder.DEFAULT_URL)
    val redo by option("--redo", help = "Delete this book's summaries and start over").flag()

    override fun run() = SqliteStore(db).use { store ->
        val row = store.book(book) ?: throw UsageError("no book with id $book")
        // Summary vectors use the book's own embedding model, so they're comparable with its paragraphs.
        val embedder = row.embedModel?.let { OllamaEmbedder(EmbeddingModel.forName(it), ollamaUrl) }
        if (embedder == null) echo("Book $book has no embeddings: summaries will be searchable by keywords only.")
        var done = 0
        val indexer = SummaryIndexer(store, OllamaSummarizer(summaryModel, ollamaUrl), embedder, progress = { what ->
            done++
            echo("  [$done] $what")
        })
        val started = System.nanoTime()
        val result = indexer.summarize(book, redo)
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        echo("Summaries: ${result.generated} written, ${result.reused} reused, ${result.embedded} embedded, status ${result.status} (${seconds}s)")
    }
}

private class BooksCommand : CliktCommand(name = "books") {
    override fun help(context: Context) = "List indexed books."
    val db by option("--db").required()

    override fun run() = SqliteStore(db).use { store ->
        val books = store.books()
        if (books.isEmpty()) echo("No books indexed.")
        books.forEach { b ->
            val vectors = b.models.joinToString(", ") { "${it.model}@${it.dim}d ${it.status}" }.ifEmpty { "no embeddings" }
            val summaries = b.summaryModel?.let { "summaries ${it} ${b.summaryStatus}" } ?: "no summaries"
            echo("${b.id}  ${b.title ?: "(untitled)"}  [${b.format}, pages: ${b.pageKind}; chapters by ${b.chapterStrategy}; $vectors; $summaries]")
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
    val summaries by option("--summaries", help = "Print the book's summaries").flag()

    override fun run() = SqliteStore(db).use { store ->
        val row = store.book(book) ?: throw UsageError("no book with id $book")
        val chapters = store.units(book, UnitKind.CHAPTER)
        val labels = store.pageLabels(book)
        if (summaries) {
            val titles = chapters.associate { it.id to it.title.orEmpty() }
            val all = store.summaries(book)
            if (all.isEmpty()) echo("No summaries. Run `rag summarize --db $db --book $book`.")
            all.sortedWith(compareBy({ it.level == "book" }, { it.charStart }, { listOf("section", "group", "chapter").indexOf(it.level) }, { it.depth }))
                .forEach { s ->
                    val span = citation(row.pageKind, labels.getOrElse(s.pageStart - 1) { "${s.pageStart}" }, labels.getOrElse(s.pageEnd - 1) { "${s.pageEnd}" })
                    val where = if (s.level == "book") "book" else titles[s.unitId].orEmpty()
                    echo("[${s.level}${if (s.level == "group") " d${s.depth}" else ""} #${s.ordinal}] $where · $span\n${s.text}\n")
                }
            return@use
        }
        val number = what
        if (number == null) {
            chapters.forEach { c ->
                val span = citation(row.pageKind, labels.getOrElse(c.pageStart - 1) { "${c.pageStart}" }, labels.getOrElse(c.pageEnd - 1) { "${c.pageEnd}" })
                echo("%2d  %-16s  %s".format(c.ordinal, span, c.title))
            }
        } else {
            val chapter = chapters.firstOrNull { it.ordinal == number } ?: throw UsageError("no chapter $number in book $book")
            echo(store.bookText(book).substring(chapter.start, chapter.end + 1))
        }
    }
}

/** "p. 13", "p. 13-15", "loc 3-loc 5" → shortened to "loc 3-5". */
private fun citation(kind: String, start: String, end: String): String = when {
    kind == "location" -> if (start == end) start else "$start-${end.removePrefix("loc ")}"
    start == end -> "p. $start"
    else -> "p. $start-$end"
}

fun main(args: Array<String>) {
    val command = Rag().subcommands(IndexCommand(), EmbedCommand(), SearchCommand(), EvalCommand(), SummarizeCommand(), BooksCommand(), DumpCommand())
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
    } catch (e: androidx.sqlite.SQLiteException) {
        System.err.println("Database error: ${e.message}")
        4
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        2
    }
    exitProcess(exitCode)
}
