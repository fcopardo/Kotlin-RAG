package rag.summarize

/**
 * What a book is about (SPEC §8.5), for filtering and routing searches across many books.
 * [weight] is [Subjects.MAIN], [Subjects.TANGENTIAL] or [Subjects.BROAD]; [source] is "metadata"
 * (the file's own subjects) or "llm".
 */
data class BookSubject(val subject: String, val weight: Double, val source: String)

object Subjects {
    const val MAIN = 1.0
    const val TANGENTIAL = 0.5
    const val BROAD = 0.25

    /** Changing the prompt means bumping this; it's stored with the LLM's subjects. */
    const val PROMPT_VERSION = "s3"

    const val SYSTEM = "You are a librarian filing books in a catalog. Use only the information you are given. " +
        "Answer with exactly three lines, nothing else."

    /**
     * s1 asked for "subjects of this book" and got chapter topics ("State", "Effects") instead of
     * catalog subjects ("Jetpack Compose", "Kotlin", "Programming"). s2's `subject | weight` lines
     * were mangled by llama3.2:3b (weights first, numeric scores). One labelled line per weight works.
     */
    fun prompt(title: String, chapters: List<String>, summary: String?): String =
        "Book title: $title\n" +
            "Chapters:\n" + chapters.take(60).joinToString("\n") { "- $it" } + "\n" +
            (summary?.let { "Summary: $it\n" } ?: "") +
            "\nFile this whole book in a catalog. Answer with three lines:\n" +
            "Main: what the book as a whole is about, usually named in its title (1 to 3 subjects)\n" +
            "Tangential: languages, technologies or topics it relies on or covers along the way (0 to 3)\n" +
            "Broad: the general field it belongs to (1 or 2)\n" +
            "Separate subjects with commas. Use short, general names of 1 to 4 words, not chapter titles.\n\n" +
            // A worked example fixes the format; it's about gardening so it isn't copied.
            "Example, for a book titled \"Growing Vegetables at Home\":\n" +
            "Main: Vegetable gardening\n" +
            "Tangential: Composting, Soil science\n" +
            "Broad: Horticulture"

    private val line = Regex("^[\\s*•\\-]*\\**(main|tangential|broad)\\**\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)

    /** Parses the model's `Main:` / `Tangential:` / `Broad:` lines; anything else is ignored. */
    fun parse(output: String): List<BookSubject> = output.lines().flatMap { l ->
        val m = line.matchEntire(l.trim()) ?: return@flatMap emptyList()
        val weight = when (m.groupValues[1].lowercase()) { "main" -> MAIN; "tangential" -> TANGENTIAL; else -> BROAD }
        m.groupValues[2].split(',', ';').map { BookSubject(it.trim().trim('"', '\'', '.', '`', '*', ' '), weight, "llm") }
    }.filter { s -> s.subject.length in 2..60 && s.subject.any(Char::isLetter) && !s.subject.equals("none", ignoreCase = true) }
        // A subject the model names twice keeps its highest weight.
        .groupBy { it.subject.lowercase() }.map { (_, same) -> same.maxBy { it.weight } }
        .sortedByDescending { it.weight }.take(8)

    /**
     * Subjects declared by the file (EPUB `dc:subject`, PDF Keywords). Library of Congress headings
     * like "War -- Early works to 1800" keep only the topic before " -- ".
     */
    fun fromMetadata(raw: List<String>): List<BookSubject> = raw
        .flatMap { it.split(';') }
        .map { it.substringBefore(" -- ").trim().trimEnd('.') }
        .filter { it.isNotEmpty() && it.length <= 60 }
        .distinctBy { it.lowercase() }
        .map { BookSubject(it, MAIN, "metadata") }

    /**
     * Whether [filter] names [subject]: every word of the filter appears as a word of the subject,
     * ignoring case ("kotlin" matches "Kotlin coroutines", "compose" matches "Jetpack Compose").
     */
    fun nameMatches(filter: String, subject: String): Boolean {
        val words = subject.lowercase().split(Regex("[^\\p{L}\\p{N}+#]+")).toSet()
        val wanted = filter.lowercase().split(Regex("[^\\p{L}\\p{N}+#]+")).filter { it.isNotEmpty() }
        return wanted.isNotEmpty() && wanted.all { it in words }
    }
}
