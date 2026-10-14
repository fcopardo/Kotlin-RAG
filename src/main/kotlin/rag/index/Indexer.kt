package rag.index

import org.apache.pdfbox.util.Version
import rag.BookReader
import rag.ReadOptions
import rag.embed.Embedder
import rag.embed.Quantizer
import rag.store.SqliteStore
import java.io.File
import java.security.MessageDigest

data class IndexResult(val bookId: Long, val skipped: Boolean, val embedded: EmbedResult?)

data class EmbedResult(val embedded: Int, val copied: Int, val status: String)

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
     * Indexes [file] (PDF or EPUB) unless the same file is already there (by SHA-256) and [reindex] is false.
     * With an [embedder], it's checked before anything is committed, then paragraphs are embedded.
     */
    fun index(
        file: File,
        tocPages: Set<Int>? = null,
        reindex: Boolean = false,
        embedder: Embedder? = null,
        embedDim: Int = DEFAULT_DIM,
        locationSize: Int = ReadOptions.DEFAULT_LOCATION_SIZE,
    ): IndexResult {
        val sha = sha256(file)
        val existing = store.findBookBySha(sha)
        if (existing != null && !reindex) return IndexResult(existing, skipped = true, embedded = null)

        embedder?.checkAvailable()
        val structured = reader.read(file, options = ReadOptions(tocPages = tocPages, locationSize = locationSize))
        val bookId = store.insertBook(structured, sha, EXTRACTOR, replacing = existing)
        val embedded = embedder?.let { embed(bookId, it, embedDim) }
        return IndexResult(bookId, skipped = false, embedded = embedded)
    }

    /**
     * Embeds the book's paragraphs that have no vector yet. Switching model or dimension drops the
     * existing vectors first. Identical paragraphs are embedded once.
     */
    fun embed(bookId: Long, embedder: Embedder, embedDim: Int = DEFAULT_DIM): EmbedResult {
        val book = store.book(bookId) ?: throw IllegalArgumentException("no book with id $bookId")
        require(embedDim <= embedder.dim) { "--embed-dim $embedDim is larger than the model's ${embedder.dim} dimensions" }
        Quantizer.truncate(FloatArray(embedDim), embedDim) // validates the dimension early

        if (book.embedModel != null && (book.embedModel != embedder.model || book.embedDim != embedDim)) {
            store.transaction { store.clearEmbeddings(bookId) }
        }
        val missing = store.paragraphsMissingEmbedding(bookId)
        store.setEmbedState(bookId, embedder.model, embedDim, if (missing.isEmpty()) "complete" else "partial")
        if (missing.isEmpty()) return EmbedResult(0, 0, "complete")

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
        }
        store.setEmbedState(bookId, embedder.model, embedDim, "complete")
        return EmbedResult(embedded, copied, "complete")
    }

    companion object {
        const val DEFAULT_DIM = 512
        val EXTRACTOR = "pdfbox-${Version.getVersion()}/norm-v1"

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
