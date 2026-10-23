package rag

/**
 * What a kind of document calls its four levels, root first (SPEC §18.2). Storage is the same four
 * levels for every kind, so a contract's clause and a book's chapter are both `c` units; only the
 * names and the reader differ.
 */
data class LevelNames(
    /** The whole document: book, paper, contract, project. */
    val root: String,
    /** The named part a passage sits in: chapter, clause, file. Deeper levels live in its title. */
    val section: String,
    /** What search returns and embeds: paragraph, function. */
    val passage: String,
    /** The smallest cited piece: phrase, sentence, line. */
    val fragment: String,
)

/** A kind of document: its name as stored in `book.kind`, and what its levels are called. */
data class DocumentKind(val name: String, val levels: LevelNames)

object DocumentKinds {
    val BOOK = DocumentKind("book", LevelNames("book", "chapter", "paragraph", "phrase"))
    val PAPER = DocumentKind("paper", LevelNames("paper", "section", "paragraph", "sentence"))
    val CONTRACT = DocumentKind("contract", LevelNames("contract", "clause", "paragraph", "sentence"))
    val CODE = DocumentKind("code", LevelNames("project", "file", "function", "line"))

    val ALL = listOf(BOOK, PAPER, CONTRACT, CODE)

    /** The kind called [name]; an unknown one (a newer writer, or a typo) is named but read as a book. */
    fun of(name: String): DocumentKind = ALL.firstOrNull { it.name == name } ?: DocumentKind(name, BOOK.levels)
}
