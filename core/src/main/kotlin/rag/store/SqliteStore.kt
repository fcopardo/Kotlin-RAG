package rag.store

import rag.StructuredBook
import rag.embed.QuantizedVector
import rag.embed.Quantizer
import rag.index.UnitKeys
import rag.index.UnitKind
import rag.summarize.BookSubject
import rag.summarize.Subjects
import java.time.Instant

data class BookRow(
    val id: Long,
    val fileSha256: String,
    val title: String?,
    val chapterStrategy: String,
    val pageOffset: Int?,
    val embedModel: String?,
    val embedDim: Int?,
    val embedStatus: String,
    val createdAt: String,
    /** "pdf" or "epub". */
    val format: String = "pdf",
    /** "pdf", "print" or "location" (SPEC §16.5). */
    val pageKind: String = "pdf",
    val summaryModel: String? = null,
    val summaryPrompt: String? = null,
    /** "none", "partial" or "complete" (SPEC §5.7.4). */
    val summaryStatus: String = "none",
    /**
     * Every embedding model the book has vectors for, first added first (SPEC §15.9). [embedModel],
     * [embedDim] and [embedStatus] are the first of them, the book's primary model.
     */
    val models: List<BookModel> = emptyList(),
) {
    fun model(name: String): BookModel? = models.firstOrNull { it.model == name }
}

/** One embedding model's vectors on a book: dimension and "partial" or "complete". */
data class BookModel(val model: String, val dim: Int, val status: String)

data class StoredUnit(
    val id: Long,
    val bookId: Long,
    val kind: UnitKind,
    val hash: String,
    val occurrence: Int,
    val chapter: Int?,
    val parentId: Long?,
    val ordinal: Int,
    val start: Int,
    val end: Int,
    val pageStart: Int,
    val pageEnd: Int,
    val title: String?,
    val isCode: Boolean,
) {
    /** The unit key (SPEC §6.1), rebuilt from the stored parts. */
    val key: String
        get() = UnitKeys.format(hash, occurrence, kind, if (kind == UnitKind.CHAPTER) bookId.toInt() else chapter)
}

/** A stored summary (SPEC §5.7): generated text with the span of book text it covers. */
data class StoredSummary(
    val id: Long,
    val bookId: Long,
    /** The chapter unit, or the book unit for level "book". */
    val unitId: Long,
    val level: String,
    val depth: Int,
    val ordinal: Int,
    val charStart: Int,
    val charEnd: Int,
    val pageStart: Int,
    val pageEnd: Int,
    val model: String,
    val prompt: String,
    val text: String,
)

/** A paragraph that still needs an embedding, with what's needed to build its input. */
data class ParagraphToEmbed(val unitId: Long, val hash: String, val start: Int, val end: Int, val isCode: Boolean, val chapterTitle: String)

/** A stored embedding: int8 + scale for rescoring, sign bits for the first pass. */
data class StoredVector(val unitId: Long, val scale: Float, val int8: ByteArray, val bits: LongArray)

/**
 * SQLite storage (SPEC §7). Text is stored once, in `book.text`; units hold offsets. The FTS5
 * tables are contentless: they index text without storing it.
 */
class SqliteStore(path: String, driver: SqlDriver = SqlDriver.default()) : AutoCloseable {

    /** The SQL connection: JDBC on desktop, androidx bundled on Android (SPEC §15.2). */
    val db: SqlConnection = driver.open(path)

    init {
        db.execute("PRAGMA foreign_keys = ON")
        db.execute("PRAGMA journal_mode = WAL")
        // Another connection writing (e.g. an app's indexing worker while its UI searches): wait for
        // it instead of failing with SQLITE_BUSY.
        db.execute("PRAGMA busy_timeout = 10000")
        SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { db.execute(it) }
        migrate()
        // SPEC §7: phases 1-5 are schema version 1; EPUB is 2; summaries are 3; several models per book are 4;
        // book subjects and vectors are 5 (new tables only, created above).
        db.execute("PRAGMA user_version = $SCHEMA_VERSION")
    }

    override fun close() = db.close()

    /**
     * Version 1 → 2 (EPUB, SPEC §16.5): book format and page kind, page labels, `pdf_page` → `page_no`.
     * Detected by columns, since databases from before `user_version` was set report version 0.
     */
    private fun migrate() {
        val pageColumns = columns("page")
        if ("pdf_page" in pageColumns) {
            update("ALTER TABLE page RENAME COLUMN pdf_page TO page_no")
        }
        if ("label" !in pageColumns) {
            update("ALTER TABLE page ADD COLUMN label TEXT NOT NULL DEFAULT ''")
            update("UPDATE page SET label = CAST(page_no AS TEXT)")
        }
        val bookColumns = columns("book")
        if ("format" !in bookColumns) update("ALTER TABLE book ADD COLUMN format TEXT NOT NULL DEFAULT 'pdf'")
        if ("page_kind" !in bookColumns) update("ALTER TABLE book ADD COLUMN page_kind TEXT NOT NULL DEFAULT 'pdf'")
        // Version 2 → 3 (summaries, SPEC §5.7): progress columns, and the full summary table.
        if ("summary_model" !in bookColumns) {
            update("ALTER TABLE book ADD COLUMN summary_model TEXT")
            update("ALTER TABLE book ADD COLUMN summary_prompt TEXT")
            update("ALTER TABLE book ADD COLUMN summary_status TEXT NOT NULL DEFAULT 'none'")
        }
        if ("char_start" !in columns("summary")) {
            // The version 1-2 placeholder table is always empty.
            update("DROP TABLE summary")
            update(SUMMARY_TABLE)
        }
        // Version 3 → 4 (SPEC §15.9): embeddings keyed by (unit, model); every model listed in book_model.
        val embeddingKey = query("PRAGMA table_info(embedding)") { it.string("name")!! to it.int("pk") }.filter { it.second > 0 }
        if (embeddingKey.size == 1) transaction {
            update("ALTER TABLE embedding RENAME TO embedding_v3")
            update(EMBEDDING_TABLE)
            update("INSERT INTO embedding SELECT unit_id, model, dim, scale, vec_i8, vec_bits FROM embedding_v3")
            update("DROP TABLE embedding_v3")
            update("INSERT OR IGNORE INTO book_model(book_id, model, dim, status) SELECT id, embed_model, embed_dim, embed_status FROM book WHERE embed_model IS NOT NULL")
        }
    }

    private fun columns(table: String): Set<String> = query("PRAGMA table_info($table)") { it.string("name")!! }.toSet()

    /** Display labels of a book's pages, in page order ("13", "xii", "loc 4"). */
    fun pageLabels(bookId: Long): List<String> =
        query("SELECT label FROM page WHERE book_id = ? ORDER BY page_no", bookId) { it.string(1)!! }

    private var transactionDepth = 0

    /** Runs [block] in a transaction; nested calls join the outer one. */
    fun <T> transaction(block: () -> T): T {
        if (transactionDepth > 0) {
            transactionDepth++
            try { return block() } finally { transactionDepth-- }
        }
        db.begin()
        transactionDepth = 1
        try {
            return block().also { db.commit() }
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            transactionDepth = 0
        }
    }

    // --- books ---

    fun findBookBySha(sha256: String): Long? =
        query("SELECT id FROM book WHERE file_sha256 = ?", sha256) { it.long(1) }.firstOrNull()

    fun book(id: Long): BookRow? = query("SELECT $BOOK_COLUMNS FROM book WHERE id = ?", id, mapper = ::bookRow).firstOrNull()
        ?.let { it.copy(models = models(it.id)) }

    fun books(): List<BookRow> {
        val models = query("SELECT * FROM book_model ORDER BY rowid") { it.long("book_id") to bookModel(it) }
            .groupBy({ it.first }, { it.second })
        return query("SELECT $BOOK_COLUMNS FROM book ORDER BY id", mapper = ::bookRow).map { it.copy(models = models[it.id].orEmpty()) }
    }

    private fun models(bookId: Long): List<BookModel> =
        query("SELECT * FROM book_model WHERE book_id = ? ORDER BY rowid", bookId, mapper = ::bookModel)

    private fun bookModel(r: SqlRow) = BookModel(r.string("model")!!, r.int("dim"), r.string("status")!!)

    fun bookText(id: Long): String =
        query("SELECT text FROM book WHERE id = ?", id) { it.string(1)!! }.firstOrNull()
            ?: throw IllegalArgumentException("no book with id $id")

    /** Inserts the book with its pages, units and FTS rows, replacing [replacing] if given. One transaction. */
    fun insertBook(structured: StructuredBook, fileSha256: String, extractor: String, replacing: Long? = null): Long = transaction {
        replacing?.let(::deleteBookRows)
        val bookId = insert(
            "INSERT INTO book(file_sha256, title, text, extractor, chapter_strategy, page_offset, format, page_kind, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            fileSha256, structured.title, structured.book.text, extractor,
            structured.plan.strategy.name.lowercase(), structured.plan.pageOffset,
            structured.format.name.lowercase(), structured.pageKind.name.lowercase(), Instant.now().toString(),
        )
        batch("INSERT INTO page(book_id, page_no, char_start, label) VALUES (?,?,?,?)",
            structured.book.pageStarts.mapIndexed { i, start -> listOf(bookId, i + 1, start, structured.book.pageLabel(i + 1)) })
        insertUnits(bookId, structured)
        setSubjects(bookId, "metadata", Subjects.fromMetadata(structured.subjects))
        bookId
    }

    fun deleteBook(id: Long) = transaction { deleteBookRows(id) }

    private fun deleteBookRows(id: Long) {
        // Contentless FTS rows aren't linked by foreign keys: delete them by rowid first.
        update("DELETE FROM para_fts WHERE rowid IN (SELECT id FROM unit WHERE book_id = ? AND kind = 'g')", id)
        update("DELETE FROM phrase_fts WHERE rowid IN (SELECT id FROM unit WHERE book_id = ? AND kind = 'p')", id)
        update("DELETE FROM summary_fts WHERE rowid IN (SELECT id FROM summary WHERE book_id = ?)", id)
        update("DELETE FROM book WHERE id = ?", id)
    }

    /**
     * Inserts the book's units with ids assigned here (one writer per database), so parents are
     * known before their children are written and everything can go in batches on any driver.
     */
    private fun insertUnits(bookId: Long, structured: StructuredBook) {
        val text = structured.book.text
        val records = UnitKeys.build(structured, bookId)
        val base = query("SELECT coalesce(max(id), 0) FROM unit") { it.long(1) }.first()
        val ids = LongArray(records.size) { base + it + 1 }
        batch(
            """INSERT INTO unit(id, book_id, kind, hash, occurrence, chapter, parent_id, ordinal, char_start, char_end,
               page_start, page_end, title, is_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            records.mapIndexed { i, r ->
                listOf(ids[i], bookId, r.kind.code.toString(), hashToLong(r.hash), r.occurrence, r.chapter, r.parentIndex?.let { ids[it] },
                    r.ordinal, r.start, r.end, r.pageStart, r.pageEnd, r.title, if (r.isCode) 1 else 0)
            },
        )
        fun fts(kind: UnitKind) = records.indices.filter { records[it].kind == kind }
            .map { i -> listOf(ids[i], text.substring(records[i].start, records[i].end + 1)) }
        batch("INSERT INTO para_fts(rowid, text) VALUES (?, ?)", fts(UnitKind.PARAGRAPH))
        batch("INSERT INTO phrase_fts(rowid, text) VALUES (?, ?)", fts(UnitKind.PHRASE))
    }

    // --- units ---

    fun units(bookId: Long, kind: UnitKind? = null): List<StoredUnit> =
        if (kind == null) query("SELECT * FROM unit WHERE book_id = ? ORDER BY id", bookId, mapper = ::storedUnit)
        else query("SELECT * FROM unit WHERE book_id = ? AND kind = ? ORDER BY id", bookId, kind.code.toString(), mapper = ::storedUnit)

    fun unit(id: Long): StoredUnit? = query("SELECT * FROM unit WHERE id = ?", id, mapper = ::storedUnit).firstOrNull()

    fun children(parentId: Long): List<StoredUnit> =
        query("SELECT * FROM unit WHERE parent_id = ? ORDER BY ordinal", parentId, mapper = ::storedUnit)

    /** Units of any book sharing [hash]: the master record (SPEC §6.3). */
    fun unitsByHash(hash: String): List<StoredUnit> =
        query("SELECT * FROM unit WHERE hash = ? ORDER BY id", hashToLong(hash), mapper = ::storedUnit)

    /**
     * Units with this key. Phrase, paragraph and book keys don't name a book, so the same key
     * can match one unit per book; pass [bookId] to narrow it down.
     */
    fun findByKey(key: String, bookId: Long? = null): List<StoredUnit> {
        val k = UnitKeys.parse(key) ?: return emptyList()
        val conditions = mutableListOf("hash = ?", "kind = ?", "occurrence = ?")
        val args = mutableListOf<Any?>(hashToLong(k.hash), k.kind.code.toString(), k.occurrence)
        when (k.kind) {
            UnitKind.CHAPTER -> { conditions += "book_id = ?"; args += k.scope }
            UnitKind.PARAGRAPH, UnitKind.PHRASE -> { conditions += "chapter = ?"; args += k.scope }
            UnitKind.BOOK -> Unit
        }
        if (bookId != null) { conditions += "book_id = ?"; args += bookId }
        return query("SELECT * FROM unit WHERE ${conditions.joinToString(" AND ")} ORDER BY id", *args.toTypedArray(), mapper = ::storedUnit)
    }

    /** Unit ids matching an FTS5 query, best BM25 first. [table] is `para_fts` or `phrase_fts`. */
    fun ftsMatch(table: String, matchQuery: String, limit: Int = 20): List<Long> {
        require(table in setOf("para_fts", "phrase_fts", "summary_fts")) { "unknown FTS table $table" }
        return query("SELECT rowid FROM $table WHERE $table MATCH ? ORDER BY bm25($table) LIMIT ?", matchQuery, limit) { it.long(1) }
    }

    /**
     * Unit ids matching an FTS5 query, best BM25 first, limited to one book, to [bookIds], and/or to
     * the children of one unit (e.g. a paragraph's phrases). The FTS tables are contentless, so this
     * joins on rowid. Several books are ranked together in one query: BM25 over one FTS table is
     * comparable across books.
     */
    fun ftsSearch(
        table: String,
        matchQuery: String,
        bookId: Long? = null,
        parentId: Long? = null,
        limit: Int = 20,
        bookIds: Collection<Long>? = null,
        /** Narrows the FTS scan itself (e.g. one paragraph's phrases, which have consecutive ids). */
        rowids: LongRange? = null,
    ): List<Long> {
        require(table in setOf("para_fts", "phrase_fts")) { "unknown FTS table $table" }
        val filters = buildList {
            if (bookId != null) add("u.book_id = ?")
            if (parentId != null) add("u.parent_id = ?")
            if (bookIds != null) add("u.book_id IN (${bookIds.joinToString(",") { "?" }})")
            if (rowids != null) add("$table.rowid BETWEEN ? AND ?")
        }
        val where = (listOf("$table MATCH ?") + filters).joinToString(" AND ")
        val args = (listOfNotNull<Any>(matchQuery, bookId, parentId) + bookIds.orEmpty() +
            listOfNotNull(rowids?.first, rowids?.last) + limit).toTypedArray()
        return query("SELECT $table.rowid FROM $table JOIN unit u ON u.id = $table.rowid WHERE $where ORDER BY bm25($table) LIMIT ?", *args) {
            it.long(1)
        }
    }

    fun count(table: String): Int = query("SELECT count(*) FROM $table") { it.int(1) }.first()

    // --- embeddings ---

    fun paragraphsMissingEmbedding(bookId: Long, model: String): List<ParagraphToEmbed> = query(
        """SELECT u.id, u.hash, u.char_start, u.char_end, u.is_code, c.title
           FROM unit u JOIN unit c ON c.id = u.parent_id
           LEFT JOIN embedding e ON e.unit_id = u.id AND e.model = ?
           WHERE u.book_id = ? AND u.kind = 'g' AND e.unit_id IS NULL ORDER BY u.id""",
        model, bookId,
    ) { ParagraphToEmbed(it.long(1), longToHash(it.long(2)), it.int(3), it.int(4), it.int(5) == 1, it.string(6) ?: "") }

    /** Copies an existing embedding of the same text, model and dimension (SPEC §6.3). */
    fun copyEmbeddingByHash(unitId: Long, hash: String, model: String, dim: Int): Boolean = update(
        """INSERT INTO embedding(unit_id, model, dim, scale, vec_i8, vec_bits)
           SELECT ?, e.model, e.dim, e.scale, e.vec_i8, e.vec_bits
           FROM embedding e JOIN unit u ON u.id = e.unit_id
           WHERE u.hash = ? AND e.model = ? AND e.dim = ? LIMIT 1""",
        unitId, hashToLong(hash), model, dim,
    ) == 1

    fun saveEmbeddings(model: String, vectors: List<Pair<Long, QuantizedVector>>) = transaction {
        batch("INSERT INTO embedding(unit_id, model, dim, scale, vec_i8, vec_bits) VALUES (?,?,?,?,?,?)",
            vectors.map { (unitId, v) -> listOf(unitId, model, v.dim, v.scale, v.int8, Quantizer.bitsToBlob(v.bits)) })
    }

    /** Drops one model's vectors from a book, or every model's when [model] is null. */
    fun clearEmbeddings(bookId: Long, model: String? = null) = transaction {
        if (model == null) {
            update("DELETE FROM embedding WHERE unit_id IN (SELECT id FROM unit WHERE book_id = ?)", bookId)
            update("DELETE FROM book_model WHERE book_id = ?", bookId)
            update("DELETE FROM book_vector WHERE book_id = ?", bookId)
            update("DELETE FROM subject_vector WHERE book_id = ?", bookId)
        } else {
            update("DELETE FROM embedding WHERE model = ? AND unit_id IN (SELECT id FROM unit WHERE book_id = ?)", model, bookId)
            update("DELETE FROM book_model WHERE book_id = ? AND model = ?", bookId, model)
            update("DELETE FROM book_vector WHERE book_id = ? AND model = ?", bookId, model)
            update("DELETE FROM subject_vector WHERE book_id = ? AND model = ?", bookId, model)
        }
        syncPrimary(bookId)
    }

    // --- subjects and book vectors (SPEC §8.5) ---

    /** A book's subjects, from every source, highest weight first. */
    fun subjects(bookId: Long): List<BookSubject> = query(
        "SELECT subject, weight, source FROM book_subject WHERE book_id = ? ORDER BY weight DESC, subject", bookId,
    ) { BookSubject(it.string(1)!!, it.float(2).toDouble(), it.string(3)!!) }

    /** Every book's subjects, by book id. */
    fun allSubjects(): Map<Long, List<BookSubject>> = query(
        "SELECT book_id, subject, weight, source FROM book_subject ORDER BY book_id, weight DESC, subject",
    ) { it.long(1) to BookSubject(it.string(2)!!, it.float(3).toDouble(), it.string(4)!!) }.groupBy({ it.first }, { it.second })

    /** Replaces a book's subjects from [source] ("metadata" or "llm"); vectors of dropped subjects go too. */
    fun setSubjects(bookId: Long, source: String, subjects: List<BookSubject>) = transaction {
        update("DELETE FROM book_subject WHERE book_id = ? AND source = ?", bookId, source)
        batch("INSERT OR REPLACE INTO book_subject(book_id, subject, weight, source) VALUES (?,?,?,?)",
            subjects.map { listOf(bookId, it.subject, it.weight, source) })
        update("DELETE FROM subject_vector WHERE book_id = ? AND subject NOT IN (SELECT subject FROM book_subject WHERE book_id = ?)", bookId, bookId)
    }

    /** Subjects of a book without a vector from this model and dimension. */
    fun subjectsMissingVector(bookId: Long, model: String, dim: Int): List<String> = query(
        """SELECT DISTINCT s.subject FROM book_subject s
           LEFT JOIN subject_vector v ON v.book_id = s.book_id AND v.subject = s.subject AND v.model = ? AND v.dim = ?
           WHERE s.book_id = ? AND v.book_id IS NULL ORDER BY s.subject""",
        model, dim, bookId,
    ) { it.string(1)!! }

    fun saveSubjectVectors(bookId: Long, model: String, vectors: List<Pair<String, QuantizedVector>>) = transaction {
        batch("INSERT OR REPLACE INTO subject_vector(book_id, subject, model, dim, scale, vec_i8) VALUES (?,?,?,?,?,?)",
            vectors.map { (subject, v) -> listOf(bookId, subject, model, v.dim, v.scale, v.int8) })
    }

    /** Every subject vector of this model: (book, subject, vector). Few and short, read in full. */
    fun subjectVectors(model: String): List<Triple<Long, String, StoredVector>> = query(
        "SELECT book_id, subject, scale, vec_i8 FROM subject_vector WHERE model = ?", model,
    ) { Triple(it.long(1), it.string(2)!!, StoredVector(0, it.float(3), it.bytes(4), LongArray(0))) }

    /**
     * The book's vector for [model]: the mean of its paragraph vectors, normalized. A cheap "what is
     * this book about" point for routing searches across books.
     */
    fun updateBookVector(bookId: Long, model: String) = transaction {
        var sum: DoubleArray? = null
        query(
            "SELECT e.scale, e.vec_i8 FROM embedding e JOIN unit u ON u.id = e.unit_id WHERE u.book_id = ? AND e.model = ?",
            bookId, model,
        ) { r ->
            val scale = r.float(1)
            val int8 = r.bytes(2)
            val acc = sum ?: DoubleArray(int8.size).also { sum = it }
            for (i in int8.indices) acc[i] += int8[i] * scale.toDouble()
        }
        val mean = sum ?: return@transaction
        val v = Quantizer.quantize(rag.embed.Vectors.l2Normalize(FloatArray(mean.size) { mean[it].toFloat() }))
        update("INSERT OR REPLACE INTO book_vector(book_id, model, dim, scale, vec_i8) VALUES (?,?,?,?,?)",
            bookId, model, v.dim, v.scale, v.int8)
    }

    /** Book vectors of this model, by book id. */
    fun bookVectors(model: String): Map<Long, StoredVector> = query(
        "SELECT book_id, scale, vec_i8 FROM book_vector WHERE model = ?", model,
    ) { it.long(1) to StoredVector(0, it.float(2), it.bytes(3), LongArray(0)) }.toMap()

    /** Records a model's progress on a book; the first model added is the book's primary one. */
    fun setEmbedState(bookId: Long, model: String, dim: Int, status: String) = transaction {
        update(
            """INSERT INTO book_model(book_id, model, dim, status) VALUES (?,?,?,?)
               ON CONFLICT(book_id, model) DO UPDATE SET dim = excluded.dim, status = excluded.status""",
            bookId, model, dim, status,
        )
        syncPrimary(bookId)
    }

    /** book.embed_* mirror the primary model, so readers that know one model per book still work. */
    private fun syncPrimary(bookId: Long) {
        val primary = models(bookId).firstOrNull()
        update("UPDATE book SET embed_model = ?, embed_dim = ?, embed_status = ? WHERE id = ?",
            primary?.model, primary?.dim, primary?.status ?: "none", bookId)
    }

    /** Binary vectors of a book's paragraphs from one model, for the in-memory first pass. */
    fun binaryVectors(bookId: Long, model: String): List<Pair<Long, LongArray>> = query(
        "SELECT e.unit_id, e.vec_bits FROM embedding e JOIN unit u ON u.id = e.unit_id WHERE u.book_id = ? AND e.model = ? ORDER BY e.unit_id",
        bookId, model,
    ) { it.long(1) to Quantizer.blobToBits(it.bytes(2)) }

    fun vectors(unitIds: Collection<Long>, model: String): List<StoredVector> {
        if (unitIds.isEmpty()) return emptyList()
        val marks = unitIds.joinToString(",") { "?" }
        return query("SELECT unit_id, scale, vec_i8, vec_bits FROM embedding WHERE model = ? AND unit_id IN ($marks)", model, *unitIds.toTypedArray()) {
            StoredVector(it.long(1), it.float(2), it.bytes(3), Quantizer.blobToBits(it.bytes(4)))
        }
    }

    // --- summaries (SPEC §5.7) ---

    fun setSummaryState(bookId: Long, model: String?, prompt: String?, status: String) {
        update("UPDATE book SET summary_model = ?, summary_prompt = ?, summary_status = ? WHERE id = ?", model, prompt, status, bookId)
    }

    /** An existing summary at this position of the tree, made with this model and prompt version. */
    fun findSummary(unitId: Long, level: String, depth: Int, ordinal: Int, model: String, prompt: String): StoredSummary? = query(
        "SELECT * FROM summary WHERE unit_id = ? AND level = ? AND depth = ? AND ordinal = ? AND model = ? AND prompt = ?",
        unitId, level, depth, ordinal, model, prompt, mapper = ::storedSummary,
    ).firstOrNull()

    /** Stores a summary and indexes its text in `summary_fts`; one transaction. */
    fun insertSummary(
        bookId: Long, unitId: Long, level: String, depth: Int, ordinal: Int,
        charStart: Int, charEnd: Int, pageStart: Int, pageEnd: Int, model: String, prompt: String, text: String,
    ): Long = transaction {
        val id = insert(
            """INSERT INTO summary(book_id, unit_id, level, depth, ordinal, char_start, char_end, page_start, page_end, model, prompt, text)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            bookId, unitId, level, depth, ordinal, charStart, charEnd, pageStart, pageEnd, model, prompt, text,
        )
        update("INSERT INTO summary_fts(rowid, text) VALUES (?, ?)", id, text)
        id
    }

    fun summaries(bookId: Long): List<StoredSummary> =
        query("SELECT * FROM summary WHERE book_id = ? ORDER BY char_start, depth, id", bookId, mapper = ::storedSummary)

    fun summary(id: Long): StoredSummary? = query("SELECT * FROM summary WHERE id = ?", id, mapper = ::storedSummary).firstOrNull()

    fun deleteSummaries(bookId: Long) = transaction {
        update("DELETE FROM summary_fts WHERE rowid IN (SELECT id FROM summary WHERE book_id = ?)", bookId)
        update("DELETE FROM summary WHERE book_id = ?", bookId)
    }

    /** Summaries without a vector from this model and dimension. */
    fun summariesMissingEmbedding(bookId: Long, model: String, dim: Int): List<StoredSummary> = query(
        "SELECT * FROM summary WHERE book_id = ? AND (embed_model IS NULL OR embed_model <> ? OR dim <> ?) ORDER BY id",
        bookId, model, dim, mapper = ::storedSummary,
    )

    fun saveSummaryEmbeddings(model: String, vectors: List<Pair<Long, QuantizedVector>>) = transaction {
        batch("UPDATE summary SET embed_model = ?, dim = ?, scale = ?, vec_i8 = ?, vec_bits = ? WHERE id = ?",
            vectors.map { (id, v) -> listOf(model, v.dim, v.scale, v.int8, Quantizer.bitsToBlob(v.bits), id) })
    }

    /** Summaries carry one model's vectors (the book's primary model at the time they were embedded). */
    fun summaryBinaryVectors(bookId: Long, model: String): List<Pair<Long, LongArray>> = query(
        "SELECT id, vec_bits FROM summary WHERE book_id = ? AND embed_model = ? AND vec_bits IS NOT NULL ORDER BY id", bookId, model,
    ) { it.long(1) to Quantizer.blobToBits(it.bytes(2)) }

    fun summaryVectors(ids: Collection<Long>): List<StoredVector> {
        if (ids.isEmpty()) return emptyList()
        val marks = ids.joinToString(",") { "?" }
        return query("SELECT id, scale, vec_i8, vec_bits FROM summary WHERE id IN ($marks) AND vec_i8 IS NOT NULL", *ids.toTypedArray()) {
            StoredVector(it.long(1), it.float(2), it.bytes(3), Quantizer.blobToBits(it.bytes(4)))
        }
    }

    /** Summary ids matching an FTS5 query in one book, best BM25 first. */
    fun summaryFtsSearch(matchQuery: String, bookId: Long, limit: Int = 20): List<Long> =
        summaryFtsSearch(matchQuery, listOf(bookId), limit)

    /** Summary ids in any of [bookIds], ranked together, best BM25 first. */
    fun summaryFtsSearch(matchQuery: String, bookIds: Collection<Long>, limit: Int = 20): List<Long> {
        if (bookIds.isEmpty()) return emptyList()
        return query(
            """SELECT summary_fts.rowid FROM summary_fts JOIN summary s ON s.id = summary_fts.rowid
               WHERE summary_fts MATCH ? AND s.book_id IN (${bookIds.joinToString(",") { "?" }})
               ORDER BY bm25(summary_fts) LIMIT ?""",
            matchQuery, *bookIds.toTypedArray(), limit,
        ) { it.long(1) }
    }

    /** Paragraph ids inside `[from, to]` matching an FTS5 query, best BM25 first. */
    fun paragraphsInSpan(matchQuery: String, bookId: Long, from: Int, to: Int, limit: Int = 1): List<Long> = query(
        """SELECT para_fts.rowid FROM para_fts JOIN unit u ON u.id = para_fts.rowid
           WHERE para_fts MATCH ? AND u.book_id = ? AND u.char_start >= ? AND u.char_end <= ? ORDER BY bm25(para_fts) LIMIT ?""",
        matchQuery, bookId, from, to, limit,
    ) { it.long(1) }

    /** The first paragraph that starts inside `[from, to]`. */
    fun firstParagraphInSpan(bookId: Long, from: Int, to: Int): StoredUnit? = query(
        "SELECT * FROM unit WHERE book_id = ? AND kind = 'g' AND char_start >= ? AND char_start <= ? ORDER BY char_start LIMIT 1",
        bookId, from, to, mapper = ::storedUnit,
    ).firstOrNull()

    private fun storedSummary(r: SqlRow) = StoredSummary(
        r.long("id"), r.long("book_id"), r.long("unit_id"), r.string("level")!!, r.int("depth"),
        r.int("ordinal"), r.int("char_start"), r.int("char_end"), r.int("page_start"), r.int("page_end"),
        r.string("model")!!, r.string("prompt")!!, r.string("text")!!,
    )

    // --- JDBC helpers ---

    private fun <T> query(sql: String, vararg args: Any?, mapper: (SqlRow) -> T): List<T> = db.query(sql, args.toList(), mapper)

    private fun update(sql: String, vararg args: Any?): Int = db.execute(sql, args.toList())

    private fun insert(sql: String, vararg args: Any?): Long = db.insert(sql, args.toList())

    private fun batch(sql: String, rows: List<List<Any?>>) = db.batch(sql, rows)

    private fun bookRow(r: SqlRow) = BookRow(
        r.long("id"), r.string("file_sha256")!!, r.string("title"), r.string("chapter_strategy")!!,
        r.intOrNull("page_offset"), r.string("embed_model"), r.intOrNull("embed_dim"), r.string("embed_status")!!, r.string("created_at")!!,
        r.string("format")!!, r.string("page_kind")!!,
        r.string("summary_model"), r.string("summary_prompt"), r.string("summary_status")!!,
    )

    private fun storedUnit(r: SqlRow) = StoredUnit(
        r.long("id"), r.long("book_id"), UnitKind.of(r.string("kind")!!.single()), longToHash(r.long("hash")),
        r.int("occurrence"), r.intOrNull("chapter"), r.longOrNull("parent_id"), r.int("ordinal"), r.int("char_start"),
        r.int("char_end"), r.int("page_start"), r.int("page_end"), r.string("title"), r.int("is_code") == 1,
    )

    companion object {
        const val SCHEMA_VERSION = 5

        /**
         * Every book column except `text`: reading a book row must not pull the whole book off disk
         * (100 books' rows took 116 ms with it, 11 ms without; SPEC §8.4).
         */
        private const val BOOK_COLUMNS = "id, file_sha256, title, extractor, chapter_strategy, page_offset, embed_model, " +
            "embed_dim, embed_status, created_at, format, page_kind, summary_model, summary_prompt, summary_status"

        /** SPEC §15.9: one vector per paragraph and model. */
        private val EMBEDDING_TABLE = """
            CREATE TABLE IF NOT EXISTS embedding (
              unit_id INTEGER NOT NULL REFERENCES unit(id) ON DELETE CASCADE,
              model TEXT NOT NULL,
              dim INTEGER NOT NULL,
              scale REAL NOT NULL,
              vec_i8 BLOB NOT NULL,
              vec_bits BLOB NOT NULL,
              PRIMARY KEY (unit_id, model)
            )""".trimIndent()

        /** SPEC §7, Phase 6: generated summaries stored with their text and the span they cover. */
        private val SUMMARY_TABLE = """
            CREATE TABLE IF NOT EXISTS summary (
              id INTEGER PRIMARY KEY,
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              unit_id INTEGER NOT NULL REFERENCES unit(id) ON DELETE CASCADE,
              level TEXT NOT NULL CHECK (level IN ('section','group','chapter','book')),
              depth INTEGER NOT NULL DEFAULT 0,
              ordinal INTEGER NOT NULL,
              char_start INTEGER NOT NULL,
              char_end INTEGER NOT NULL,
              page_start INTEGER NOT NULL,
              page_end INTEGER NOT NULL,
              model TEXT NOT NULL,
              prompt TEXT NOT NULL,
              text TEXT NOT NULL,
              embed_model TEXT, dim INTEGER, scale REAL, vec_i8 BLOB, vec_bits BLOB,
              UNIQUE (unit_id, level, depth, ordinal, model, prompt)
            )""".trimIndent()

        /** Unit hashes are 16 hex chars (64 bits); stored as INTEGER, half the size of the text. */
        fun hashToLong(hash: String): Long = java.lang.Long.parseUnsignedLong(hash, 16)

        fun longToHash(value: Long): String = java.lang.Long.toHexString(value).padStart(16, '0')

        /** SPEC §7. Statements are separated by ';' and must not contain one themselves. */
        private val SCHEMA = """
            CREATE TABLE IF NOT EXISTS book (
              id INTEGER PRIMARY KEY,
              file_sha256 TEXT NOT NULL UNIQUE,
              title TEXT,
              text TEXT NOT NULL,
              extractor TEXT NOT NULL,
              chapter_strategy TEXT NOT NULL,
              page_offset INTEGER,
              embed_model TEXT,
              embed_dim INTEGER,
              embed_status TEXT NOT NULL DEFAULT 'none' CHECK (embed_status IN ('none','partial','complete')),
              created_at TEXT NOT NULL,
              format TEXT NOT NULL DEFAULT 'pdf',
              page_kind TEXT NOT NULL DEFAULT 'pdf',
              summary_model TEXT,
              summary_prompt TEXT,
              summary_status TEXT NOT NULL DEFAULT 'none' 
            );
            CREATE TABLE IF NOT EXISTS page (
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              page_no INTEGER NOT NULL,
              char_start INTEGER NOT NULL,
              label TEXT NOT NULL DEFAULT '',
              PRIMARY KEY (book_id, page_no)
            );
            CREATE TABLE IF NOT EXISTS unit (
              id INTEGER PRIMARY KEY,
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              kind TEXT NOT NULL CHECK (kind IN ('p','g','c','b')),
              hash INTEGER NOT NULL,
              occurrence INTEGER NOT NULL,
              chapter INTEGER,
              parent_id INTEGER REFERENCES unit(id) ON DELETE CASCADE,
              ordinal INTEGER NOT NULL,
              char_start INTEGER NOT NULL,
              char_end INTEGER NOT NULL,
              page_start INTEGER NOT NULL,
              page_end INTEGER NOT NULL,
              title TEXT,
              is_code INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS unit_hash ON unit(hash);
            CREATE INDEX IF NOT EXISTS unit_parent ON unit(parent_id, ordinal);
            CREATE INDEX IF NOT EXISTS unit_book_kind ON unit(book_id, kind);
            $EMBEDDING_TABLE;
            CREATE TABLE IF NOT EXISTS book_model (
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              model TEXT NOT NULL,
              dim INTEGER NOT NULL,
              status TEXT NOT NULL CHECK (status IN ('partial','complete')),
              PRIMARY KEY (book_id, model)
            );
            CREATE TABLE IF NOT EXISTS book_subject (
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              subject TEXT NOT NULL,
              weight REAL NOT NULL,
              source TEXT NOT NULL CHECK (source IN ('metadata','llm')),
              PRIMARY KEY (book_id, subject, source)
            );
            CREATE TABLE IF NOT EXISTS subject_vector (
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              subject TEXT NOT NULL,
              model TEXT NOT NULL,
              dim INTEGER NOT NULL,
              scale REAL NOT NULL,
              vec_i8 BLOB NOT NULL,
              PRIMARY KEY (book_id, subject, model)
            );
            CREATE TABLE IF NOT EXISTS book_vector (
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              model TEXT NOT NULL,
              dim INTEGER NOT NULL,
              scale REAL NOT NULL,
              vec_i8 BLOB NOT NULL,
              PRIMARY KEY (book_id, model)
            );
            $SUMMARY_TABLE;
            CREATE VIRTUAL TABLE IF NOT EXISTS para_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
            CREATE VIRTUAL TABLE IF NOT EXISTS phrase_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
            CREATE VIRTUAL TABLE IF NOT EXISTS summary_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61')
        """.trimIndent()
    }
}
