package rag.code

/**
 * The language families the scanner knows (SPEC §18.4), picked by file extension. [OTHER] files are
 * read as plain text: file and line levels only, no declarations.
 */
enum class Language(vararg extensions: String) {
    KOTLIN("kt", "kts"),
    JAVA("java"),
    JS_TS("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts"),
    C_CPP("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx"),
    CSHARP("cs"),
    GO("go"),
    RUST("rs"),
    SWIFT("swift"),
    PYTHON("py", "pyi"),
    OTHER;

    val extensions: Set<String> = extensions.toSet()

    companion object {
        /** The family of a file extension (without the dot), case-insensitive; [OTHER] if unknown. */
        fun of(extension: String): Language = entries.firstOrNull { extension.lowercase() in it.extensions } ?: OTHER
    }
}
