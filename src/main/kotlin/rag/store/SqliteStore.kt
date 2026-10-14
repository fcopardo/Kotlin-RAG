package rag.store

import rag.StructuredBook
import rag.embed.QuantizedVector
import rag.embed.Quantizer
import rag.index.UnitKeys
import rag.index.UnitKind
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
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
)

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

/** A paragraph that still needs an embedding, with what's needed to build its input. */
data class ParagraphToEmbed(val unitId: Long, val hash: String, val start: Int, val end: Int, val isCode: Boolean, val chapterTitle: String)

/** A stored embedding: int8 + scale for rescoring, sign bits for the first pass. */
data class StoredVector(val unitId: Long, val scale: Float, val int8: ByteArray, val bits: LongArray)

/**
 * SQLite storage (SPEC §7). Text is stored once, in `book.text`; units hold offsets. The FTS5
 * tables are contentless: they index text without storing it.
 */
class SqliteStore(path: String) : AutoCloseable {

    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        connection.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys = ON")
            s.execute("PRAGMA journal_mode = WAL")
            SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(s::execute)
            // SPEC §7: phases 1-5 are schema version 1; Phase 6 migrates to 2.
            if (s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } == 0) s.execute("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    override fun close() = connection.close()

    fun <T> transaction(block: () -> T): T {
        val wasAuto = connection.autoCommit
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = wasAuto
        }
    }

    // --- books ---

    fun findBookBySha(sha256: String): Long? =
        query("SELECT id FROM book WHERE file_sha256 = ?", sha256) { it.getLong(1) }.firstOrNull()

    fun book(id: Long): BookRow? = query("SELECT * FROM book WHERE id = ?", id, mapper = ::bookRow).firstOrNull()

    fun books(): List<BookRow> = query("SELECT * FROM book ORDER BY id", mapper = ::bookRow)

    fun bookText(id: Long): String =
        query("SELECT text FROM book WHERE id = ?", id) { it.getString(1) }.firstOrNull()
            ?: throw IllegalArgumentException("no book with id $id")

    /** Inserts the book with its pages, units and FTS rows, replacing [replacing] if given. One transaction. */
    fun insertBook(structured: StructuredBook, fileSha256: String, extractor: String, replacing: Long? = null): Long = transaction {
        replacing?.let(::deleteBookRows)
        val bookId = insert(
            "INSERT INTO book(file_sha256, title, text, extractor, chapter_strategy, page_offset, created_at) VALUES (?,?,?,?,?,?,?)",
            fileSha256, structured.title, structured.book.text, extractor,
            structured.plan.strategy.name.lowercase(), structured.plan.pageOffset, Instant.now().toString(),
        )
        batch("INSERT INTO page(book_id, pdf_page, char_start) VALUES (?,?,?)",
            structured.book.pageStarts.mapIndexed { i, start -> listOf(bookId, i + 1, start) })
        insertUnits(bookId, structured)
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

    private fun insertUnits(bookId: Long, structured: StructuredBook) {
        val text = structured.book.text
        val records = UnitKeys.build(structured, bookId)
        val ids = LongArray(records.size)
        connection.prepareStatement(
            """INSERT INTO unit(book_id, kind, hash, occurrence, chapter, parent_id, ordinal, char_start, char_end,
               page_start, page_end, title, is_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            Statement.RETURN_GENERATED_KEYS,
        ).use { unitInsert ->
            connection.prepareStatement("INSERT INTO para_fts(rowid, text) VALUES (?, ?)").use { paraFts ->
                connection.prepareStatement("INSERT INTO phrase_fts(rowid, text) VALUES (?, ?)").use { phraseFts ->
                    records.forEachIndexed { i, r ->
                        bind(unitInsert, bookId, r.kind.code.toString(), hashToLong(r.hash), r.occurrence, r.chapter, r.parentIndex?.let { ids[it] },
                            r.ordinal, r.start, r.end, r.pageStart, r.pageEnd, r.title, if (r.isCode) 1 else 0)
                        unitInsert.executeUpdate()
                        ids[i] = unitInsert.generatedKeys.use { it.next(); it.getLong(1) }
                        val fts = when (r.kind) {
                            UnitKind.PARAGRAPH -> paraFts
                            UnitKind.PHRASE -> phraseFts
                            else -> null
                        }
                        if (fts != null) {
                            bind(fts, ids[i], text.substring(r.start, r.end + 1))
                            fts.executeUpdate()
                        }
                    }
                }
            }
        }
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
        return query("SELECT rowid FROM $table WHERE $table MATCH ? ORDER BY bm25($table) LIMIT ?", matchQuery, limit) { it.getLong(1) }
    }

    /**
     * Unit ids matching an FTS5 query, best BM25 first, limited to one book and/or to the children
     * of one unit (e.g. a paragraph's phrases). The FTS tables are contentless, so this joins on rowid.
     */
    fun ftsSearch(table: String, matchQuery: String, bookId: Long? = null, parentId: Long? = null, limit: Int = 20): List<Long> {
        require(table in setOf("para_fts", "phrase_fts")) { "unknown FTS table $table" }
        val filters = buildList {
            if (bookId != null) add("u.book_id = ?")
            if (parentId != null) add("u.parent_id = ?")
        }
        val where = (listOf("$table MATCH ?") + filters).joinToString(" AND ")
        val args = listOfNotNull<Any>(matchQuery, bookId, parentId, limit).toTypedArray()
        return query("SELECT $table.rowid FROM $table JOIN unit u ON u.id = $table.rowid WHERE $where ORDER BY bm25($table) LIMIT ?", *args) {
            it.getLong(1)
        }
    }

    fun count(table: String): Int = query("SELECT count(*) FROM $table") { it.getInt(1) }.first()

    // --- embeddings ---

    fun paragraphsMissingEmbedding(bookId: Long): List<ParagraphToEmbed> = query(
        """SELECT u.id, u.hash, u.char_start, u.char_end, u.is_code, c.title
           FROM unit u JOIN unit c ON c.id = u.parent_id
           LEFT JOIN embedding e ON e.unit_id = u.id
           WHERE u.book_id = ? AND u.kind = 'g' AND e.unit_id IS NULL ORDER BY u.id""",
        bookId,
    ) { ParagraphToEmbed(it.getLong(1), longToHash(it.getLong(2)), it.getInt(3), it.getInt(4), it.getInt(5) == 1, it.getString(6) ?: "") }

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

    fun clearEmbeddings(bookId: Long) {
        update("DELETE FROM embedding WHERE unit_id IN (SELECT id FROM unit WHERE book_id = ?)", bookId)
    }

    fun setEmbedState(bookId: Long, model: String?, dim: Int?, status: String) {
        update("UPDATE book SET embed_model = ?, embed_dim = ?, embed_status = ? WHERE id = ?", model, dim, status, bookId)
    }

    /** Binary vectors of a book's paragraphs, for the in-memory first pass. */
    fun binaryVectors(bookId: Long): List<Pair<Long, LongArray>> = query(
        "SELECT e.unit_id, e.vec_bits FROM embedding e JOIN unit u ON u.id = e.unit_id WHERE u.book_id = ? ORDER BY e.unit_id",
        bookId,
    ) { it.getLong(1) to Quantizer.blobToBits(it.getBytes(2)) }

    fun vectors(unitIds: Collection<Long>): List<StoredVector> {
        if (unitIds.isEmpty()) return emptyList()
        val marks = unitIds.joinToString(",") { "?" }
        return query("SELECT unit_id, scale, vec_i8, vec_bits FROM embedding WHERE unit_id IN ($marks)", *unitIds.toTypedArray()) {
            StoredVector(it.getLong(1), it.getFloat(2), it.getBytes(3), Quantizer.blobToBits(it.getBytes(4)))
        }
    }

    // --- JDBC helpers ---

    private fun bind(statement: java.sql.PreparedStatement, vararg args: Any?) {
        args.forEachIndexed { i, a -> statement.setObject(i + 1, a) }
    }

    private fun <T> query(sql: String, vararg args: Any?, mapper: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { s ->
            bind(s, *args)
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(mapper(rs)) } }
        }

    private fun update(sql: String, vararg args: Any?): Int =
        connection.prepareStatement(sql).use { s -> bind(s, *args); s.executeUpdate() }

    private fun insert(sql: String, vararg args: Any?): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { s ->
            bind(s, *args)
            s.executeUpdate()
            s.generatedKeys.use { it.next(); it.getLong(1) }
        }

    private fun batch(sql: String, rows: List<List<Any?>>) {
        connection.prepareStatement(sql).use { s ->
            rows.forEach { row -> bind(s, *row.toTypedArray()); s.addBatch() }
            s.executeBatch()
        }
    }

    private fun bookRow(rs: ResultSet) = BookRow(
        rs.getLong("id"), rs.getString("file_sha256"), rs.getString("title"), rs.getString("chapter_strategy"),
        rs.getObject("page_offset")?.let { (it as Number).toInt() }, rs.getString("embed_model"),
        rs.getObject("embed_dim")?.let { (it as Number).toInt() }, rs.getString("embed_status"), rs.getString("created_at"),
    )

    private fun storedUnit(rs: ResultSet) = StoredUnit(
        rs.getLong("id"), rs.getLong("book_id"), UnitKind.of(rs.getString("kind").single()), longToHash(rs.getLong("hash")),
        rs.getInt("occurrence"), rs.getObject("chapter")?.let { (it as Number).toInt() },
        rs.getObject("parent_id")?.let { (it as Number).toLong() }, rs.getInt("ordinal"), rs.getInt("char_start"),
        rs.getInt("char_end"), rs.getInt("page_start"), rs.getInt("page_end"), rs.getString("title"), rs.getInt("is_code") == 1,
    )

    companion object {
        const val SCHEMA_VERSION = 1

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
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS page (
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              pdf_page INTEGER NOT NULL,
              char_start INTEGER NOT NULL,
              PRIMARY KEY (book_id, pdf_page)
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
            CREATE TABLE IF NOT EXISTS embedding (
              unit_id INTEGER PRIMARY KEY REFERENCES unit(id) ON DELETE CASCADE,
              model TEXT NOT NULL,
              dim INTEGER NOT NULL,
              scale REAL NOT NULL,
              vec_i8 BLOB NOT NULL,
              vec_bits BLOB NOT NULL
            );
            CREATE TABLE IF NOT EXISTS summary (
              id INTEGER PRIMARY KEY,
              book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
              unit_id INTEGER NOT NULL REFERENCES unit(id) ON DELETE CASCADE,
              level TEXT NOT NULL CHECK (level IN ('section','chapter','book')),
              ordinal INTEGER NOT NULL,
              model TEXT NOT NULL,
              text TEXT NOT NULL,
              embed_model TEXT, dim INTEGER, scale REAL, vec_i8 BLOB, vec_bits BLOB
            );
            CREATE VIRTUAL TABLE IF NOT EXISTS para_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
            CREATE VIRTUAL TABLE IF NOT EXISTS phrase_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
            CREATE VIRTUAL TABLE IF NOT EXISTS summary_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61')
        """.trimIndent()
    }
}
