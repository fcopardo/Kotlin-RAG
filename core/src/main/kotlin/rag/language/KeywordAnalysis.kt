package rag.language

/**
 * How text is prepared for keyword search per language (SPEC §19.4, Phase 10 step 6).
 *
 * English, undetermined text and code keep the original keyword tables (`para_fts`, `phrase_fts`: SQLite's `porter`
 * stemmer), so their results don't change. Every other language goes to a second pair of tables (`para_fts_x`,
 * `phrase_fts_x`: `unicode61` with accents folded, no stemmer of SQLite's own), whose text is prepared here: each word
 * lower-cased and, for Spanish, followed by its stem from [SpanishStemmer]. A query in that language is stemmed the same
 * way, so "bibliotecas" finds "biblioteca" on a phone exactly as on the desktop; a query in another language still
 * matches the words as written.
 */
object KeywordAnalysis {
    private val word = Regex("[\\p{L}\\p{N}_]+")

    /** Whether [language]'s documents use the English keyword tables: English, undetermined and code. */
    fun usesEnglishTables(language: String): Boolean =
        language == "en" || language == LanguageDetector.UNDETERMINED || language == DocumentLanguages.NONE

    /** A stemmer for [language], or null when there is none (the words are matched as written). */
    private fun stemmer(language: String): ((String) -> String)? = when (language) {
        "es" -> SpanishStemmer::stem
        else -> null
    }

    private fun analyse(word: String, stem: ((String) -> String)?): String =
        if (stem != null && word.all { it.isLetter() }) stem(word) else word

    /**
     * [text] as stored in the non-English tables: its words, lower-cased, each followed by its stem when [language] has
     * a stemmer and the stem differs, separated by single spaces.
     */
    fun indexText(text: String, language: String): String {
        val stem = stemmer(language)
        return word.findAll(text.lowercase()).joinToString(" ") { m ->
            val stemmed = analyse(m.value, stem)
            if (stemmed == m.value) m.value else "${m.value} $stemmed"
        }
    }

    /** Common words the detector's lists leave out (they are shared with other languages there). */
    private val extraStopWords = mapOf("es" to setOf("la", "a", "o", "u", "e", "mi", "tu", "te", "me"))

    /** Common words of [language] that a query leaves out: its most frequent function words. */
    private fun stopWords(language: String): Set<String> =
        LanguageDetector.FUNCTION_WORDS[language]?.trim()?.split(Regex("\\s+"))?.toSet().orEmpty() + extraStopWords[language].orEmpty()

    /**
     * Distinct words of [query] for the non-English tables, in [language]: lower-cased, without [language]'s common
     * words (all of them if only common words), stemmed when [language] has a stemmer, at most [max].
     */
    fun terms(query: String, language: String, max: Int = 32): List<String> {
        val stop = stopWords(language)
        val all = word.findAll(query.lowercase()).map { it.value }.distinct().toList()
        val stem = stemmer(language)
        return all.filterNot { it in stop }.ifEmpty { all }.map { analyse(it, stem) }.distinct().take(max)
    }

    /** Any-term FTS5 query over the non-English tables (each term quoted, joined with OR); null when there are no words. */
    fun anyTerm(query: String, language: String): String? =
        terms(query, language).takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "\"$it\"" }
}
