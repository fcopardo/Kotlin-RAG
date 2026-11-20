package rag.code

enum class DeclarationKind { CLASS, FUNCTION }

/**
 * A class-like or function declaration found in source code (SPEC §18.4).
 *
 * @property start offset of its first character: the first line of the doc comments and annotations
 *   directly above it, or its own first non-blank character.
 * @property end offset of its last character, inclusive: the closing `}` of its body, or the last
 *   non-blank character of a function without braces (`fun f() = …`).
 * @property depth number of enclosing declarations (0 = at the top of the file).
 * @property parent index in [Declarations.items] of the nearest enclosing declaration, or null.
 */
data class Declaration(
    val kind: DeclarationKind,
    val name: String,
    val start: Int,
    val end: Int,
    val depth: Int,
    val parent: Int?,
)

/** The declarations of one file, sorted by [Declaration.start], and anything the scan could not make sense of. */
class Declarations(val items: List<Declaration>, val warnings: List<String>)
