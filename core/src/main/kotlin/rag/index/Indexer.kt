package rag.index

import rag.BookReader
import rag.RagException
import rag.ReadOptions
import rag.embed.Embedder
import rag.embed.Quantizer
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/** [skippedFiles]: what a code project's reader left out (`"<path>: <reason>"`); empty for documents and skipped runs. */
data class IndexResult(val bookId: Long, val skipped: Boolean, val embedded: EmbedResult?, val skippedFiles: List<String> = emptyList())

data class EmbedResult(val embedded: Int, val copied: Int, val status: String)

/** What happened to one file of a folder run ([Indexer.indexFolder], SPEC §18.5 step 5). */
enum class FolderOutcome { INDEXED, ALREADY_INDEXED, NOT_A_DOCUMENT, FAILED }

/** One file of a folder run: its path relative to the folder (`/`-separated), what happened, its book, and why. */
data class FolderFile(val path: String, val outcome: FolderOutcome, val bookId: Long? = null, val detail: String? = null)

/**
 * Indexes PDFs into a [SqliteStore] and embeds their paragraphs (SPEC §4, §5.6).
 *
 * Structure is committed in one transaction; embeddings follow in one transaction per batch,
 * so an interrupted run leaves `embed_status = 'partial'` and [embed] resumes it.
 */
class Indexer(
    private val store: SqliteStore,
    private val reader: BookReader = BookReader(),
    private val batchSize: Int = 32,
) {
    /**
     * Indexes [file] (a book, document, source file or project folder) unless it is already there, by its
     * fingerprint ([BookReader.fingerprint]: the file's SHA-256, or a project's), and [reindex] is false.
     * With an [embedder], it's checked before anything is committed, then paragraphs are embedded.
     */
    fun index(
        file: File,
        tocPages: Set<Int>? = null,
        reindex: Boolean = false,
        embedder: Embedder? = null,
        embedDim: Int = DEFAULT_DIM,
        locationSize: Int = ReadOptions.DEFAULT_LOCATION_SIZE,
        onBatch: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): IndexResult {
        val sha = reader.fingerprint(file)
        val existing = store.findBookBySha(sha)
        if (existing != null && !reindex) return IndexResult(existing, skipped = true, embedded = null)

        embedder?.checkAvailable()
        val structured = reader.read(file, options = ReadOptions(tocPages = tocPages, locationSize = locationSize))
        val bookId = store.insertBook(structured, sha, structured.extractor, replacing = existing)
        val embedded = embedder?.let { embed(bookId, it, embedDim, onBatch = onBatch) }
        return IndexResult(bookId, skipped = false, embedded = embedded, skippedFiles = structured.skippedFiles)
    }

    /**
     * Indexes every document in [dir] as its own document (SPEC §18.5 step 5, `rag index --dir`), with the same
     * options as [index].
     *
     * **What it must do.** Go through a folder of notes, papers, contracts and books and index each file that
     * a reader can read, one document per file; files already indexed (same bytes, anywhere) are left alone
     * unless [reindex]; say for every file what happened. One bad file never stops the others, but an
     * unreachable embedding backend stops the run before anything is stored.
     *
     * **How.**
     * 1. When there is an [embedder], check it is available first (it throws when it isn't).
     * 2. Collect the files under [dir] at any depth, never entering a folder whose name starts with `.` and
     *    leaving out files whose name starts with `.` and symbolic links (none of these are reported). Order
     *    them by their path relative to [dir], `/`-separated.
     * 3. For each file: if `reader.sourceFor` refuses it ([RagException.BookUnreadable]), it is
     *    [FolderOutcome.NOT_A_DOCUMENT] with the exception's message as detail. Otherwise call [index] for it:
     *    a skipped result is [FolderOutcome.ALREADY_INDEXED], else [FolderOutcome.INDEXED], both with the book
     *    id. If [index] throws a [RagException] other than [RagException.EmbeddingBackendUnavailable] or
     *    [RagException.ModelMismatch], the file is [FolderOutcome.FAILED] with the message as detail; those two
     *    stop the run (rethrow them).
     * 4. Call [onFile] with each result as soon as it is known, and return them all in order.
     */
    fun indexFolder(
        dir: File,
        reindex: Boolean = false,
        embedder: Embedder? = null,
        embedDim: Int = DEFAULT_DIM,
        locationSize: Int = ReadOptions.DEFAULT_LOCATION_SIZE,
        onFile: (FolderFile) -> Unit = {},
    ): List<FolderFile> {
        // 1. Check embedder availability before anything is stored
        embedder?.checkAvailable()

        // 2. Collect files: any depth, skip hidden dirs/files and symlinks, sort by relative path
        val files = collectFiles(dir)

        // 3. Process each file
        val results = mutableListOf<FolderFile>()
        for (file in files) {
            val relPath = file.relativeTo(dir).path.replace('\\', '/')
            try {
                // Check if a reader can handle this file
                reader.sourceFor(file)
            } catch (e: RagException.BookUnreadable) {
                val result = FolderFile(relPath, FolderOutcome.NOT_A_DOCUMENT, detail = e.message)
                onFile(result)
                results += result
                continue
            }

            try {
                val indexResult = index(file, reindex = reindex, embedder = embedder, embedDim = embedDim, locationSize = locationSize)
                val outcome = if (indexResult.skipped) FolderOutcome.ALREADY_INDEXED else FolderOutcome.INDEXED
                val result = FolderFile(relPath, outcome, bookId = indexResult.bookId)
                onFile(result)
                results += result
            } catch (e: RagException.EmbeddingBackendUnavailable) {
                throw e
            } catch (e: RagException.ModelMismatch) {
                throw e
            } catch (e: RagException) {
                val result = FolderFile(relPath, FolderOutcome.FAILED, detail = e.message)
                onFile(result)
                results += result
            }
        }
        return results
    }

    private fun collectFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        collectFilesRecursive(dir, dir, result)
        result.sortBy { it.relativeTo(dir).path }
        return result
    }

    /** Files under [current] (not the folder itself): links are never followed, hidden entries never taken. */
    private fun collectFilesRecursive(current: File, base: File, result: MutableList<File>) {
        current.listFiles()?.forEach { file ->
            when {
                Files.isSymbolicLink(file.toPath()) || file.name.startsWith(".") -> Unit
                file.isDirectory -> collectFilesRecursive(file, base, result)
                file.isFile -> result.add(file)
            }
        }
    }

    /**
     * Embeds the book's paragraphs that have no vector from this model yet. Identical paragraphs are
     * embedded once. Changing this model's dimension drops its vectors first. Other models' vectors
     * are dropped too, unless [add] keeps them so the book can be searched with either (SPEC §15.9).
     *
     * [onBatch] runs after each committed batch with the texts embedded so far and in total. It may
     * throw to stop (e.g. a cancelled coroutine): the book stays `partial` and a later call resumes.
     */
    fun embed(
        bookId: Long,
        embedder: Embedder,
        embedDim: Int = DEFAULT_DIM,
        add: Boolean = false,
        onBatch: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): EmbedResult {
        val book = store.book(bookId) ?: throw IllegalArgumentException("no book with id $bookId")
        require(embedDim <= embedder.dim) { "--embed-dim $embedDim is larger than the model's ${embedder.dim} dimensions" }
        Quantizer.truncate(FloatArray(embedDim), embedDim) // validates the dimension early

        book.models.filter { (it.model == embedder.model && it.dim != embedDim) || (!add && it.model != embedder.model) }
            .forEach { store.clearEmbeddings(bookId, it.model) }
        val missing = store.paragraphsMissingEmbedding(bookId, embedder.model)
        store.setEmbedState(bookId, embedder.model, embedDim, if (missing.isEmpty()) "complete" else "partial")
        if (missing.isEmpty()) {
            finish(bookId, embedder, embedDim)
            return EmbedResult(0, 0, "complete")
        }

        // Reuse vectors of identical text already in the DB, then embed one paragraph per new hash.
        var copied = 0
        val toEmbed = missing.filterNot { p ->
            store.copyEmbeddingByHash(p.unitId, p.hash, embedder.model, embedDim).also { if (it) copied++ }
        }
        val byHash = toEmbed.groupBy { it.hash }
        val text = store.bookText(bookId)
        var embedded = 0
        byHash.values.chunked(batchSize).forEach { batch ->
            val inputs = batch.map { group ->
                val p = group.first()
                val body = text.substring(p.start, p.end + 1).let { if (p.isCode) it else it.replace('\n', ' ') }
                "${p.chapterTitle}\n$body"
            }
            val vectors = embedder.embedDocuments(inputs).map { Quantizer.quantize(Quantizer.truncate(it, embedDim)) }
            store.saveEmbeddings(embedder.model, batch.zip(vectors).flatMap { (group, v) -> group.map { it.unitId to v } })
            embedded += batch.size
            copied += batch.sumOf { it.size - 1 }
            onBatch(embedded, byHash.size)
        }
        store.setEmbedState(bookId, embedder.model, embedDim, "complete")
        finish(bookId, embedder, embedDim)
        return EmbedResult(embedded, copied, "complete")
    }

    /** Once a model is complete: the book's mean vector and its subjects' vectors (SPEC §8.5). */
    private fun finish(bookId: Long, embedder: Embedder, embedDim: Int) {
        store.updateBookVector(bookId, embedder.model)
        embedSubjects(bookId, embedder, embedDim)
    }

    /** Embeds the book's subjects that have no vector from this model yet; they're few and short. */
    fun embedSubjects(bookId: Long, embedder: Embedder, embedDim: Int = DEFAULT_DIM) {
        val missing = store.subjectsMissingVector(bookId, embedder.model, embedDim)
        if (missing.isEmpty()) return
        val vectors = embedder.embedDocuments(missing).map { Quantizer.quantize(Quantizer.truncate(it, embedDim)) }
        store.saveSubjectVectors(bookId, embedder.model, missing.zip(vectors))
    }

    companion object {
        const val DEFAULT_DIM = 512

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
