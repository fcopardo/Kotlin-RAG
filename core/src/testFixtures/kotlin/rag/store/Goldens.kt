package rag.store

import rag.index.UnitKind
import rag.search.Mode
import rag.search.Searcher
import java.security.MessageDigest

/**
 * The parts of an index that must match across platforms (SPEC §15.8), as plain lines: the desktop
 * writes them as goldens, the Android tests rebuild them on the device and compare.
 */
object Goldens {
    /** Fixture books, in indexing order (book ids, and so chapter keys, depend on it). */
    val BOOKS = listOf("sample.pdf", "art-of-war-se.epub", "pg132.epub")

    val QUERIES = listOf(
        "riding fast",
        "repairs",
        "attack by stratagem",
        "the art of war is of vital importance to the state",
        "spies",
        "terrain",
    )

    /** One line per unit, in id order: key, kind, offsets, pages and title. */
    fun unitLines(store: SqliteStore, bookId: Long): List<String> =
        store.units(bookId).map { "${it.key} ${it.kind.code} ${it.start} ${it.end} ${it.pageStart} ${it.pageEnd} ${it.title.orEmpty()}" }

    fun chapters(store: SqliteStore, bookId: Long): String =
        store.units(bookId, UnitKind.CHAPTER).joinToString("|") { it.title.orEmpty() }

    /** Paragraph keys of the top lexical hits: the part of search that needs no model. */
    fun lexicalKeys(searcher: Searcher, query: String, k: Int = 5): String =
        searcher.search(query, k, mode = Mode.LEXICAL).joinToString("|") { "${it.bookId}:${it.paragraph.unitKey}" }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
