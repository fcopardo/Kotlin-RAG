package rag.language

import rag.DocumentKinds
import rag.StructuredBook

/** The language stored with each document (SPEC §19.4): detected at index time, or given by the caller. */
object DocumentLanguages {
    /** ISO 639-2 "no linguistic content": code projects, which match every language filter. */
    const val NONE = "zxx"

    private val code = Regex("[a-z]{2,3}")

    /** [language] lower-cased, if it looks like an ISO 639 code ("en", "es", "und", "zxx"); else an error. */
    fun normalize(language: String): String = language.trim().lowercase().also {
        require(code.matches(it)) { "'$language' is not an ISO 639 language code such as en or es" }
    }

    /** [override] when given; [NONE] for a code project; else detected from the document's text. */
    fun of(structured: StructuredBook, override: String? = null): String = when {
        override != null -> normalize(override)
        structured.kind == DocumentKinds.CODE.name -> NONE
        else -> LanguageDetector.detectDocument(structured.book.text)
    }
}
