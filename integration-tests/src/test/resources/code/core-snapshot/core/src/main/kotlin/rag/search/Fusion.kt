package rag.search

/** Reciprocal rank fusion (SPEC §8.3 step 4). Pure. */
object Fusion {

    /**
     * Fuses ranked lists: each item scores `Σ weight / (k + rank)` over the lists it's in (rank
     * from 1). [weights] defaults to 1 per list. Ties keep first-seen order.
     */
    fun <T> rrf(lists: List<List<T>>, k: Int = 60, weights: List<Double> = List(lists.size) { 1.0 }): List<Pair<T, Double>> {
        require(weights.size == lists.size) { "one weight per list" }
        val scores = LinkedHashMap<T, Double>()
        lists.forEachIndexed { l, list ->
            list.forEachIndexed { i, item -> scores[item] = (scores[item] ?: 0.0) + weights[l] / (k + i + 1) }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}

/** Builds FTS5 queries from user text without letting FTS syntax through. Pure. */
object FtsQuery {
    private val term = Regex("[\\p{L}\\p{N}_]+")

    /**
     * Common English words. In an any-term query they match nearly every paragraph and drown the
     * words that matter ("how does the framework decide…" ranked on "how", "the", "does").
     */
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do", "does", "for", "from", "has",
        "have", "how", "i", "if", "in", "into", "is", "it", "its", "of", "on", "or", "so", "that", "the",
        "their", "then", "there", "these", "this", "to", "was", "we", "what", "when", "where", "which",
        "who", "why", "will", "with", "you", "your",
    )

    /** Distinct words of [text] without stop words (all of them if only stop words), or empty. */
    fun terms(text: String, max: Int = 32): List<String> {
        val all = term.findAll(text.lowercase()).map { it.value }.distinct().toList()
        return all.filterNot { it in stopWords }.ifEmpty { all }.take(max)
    }

    /**
     * Any-term query: each word quoted (so AND, OR, NOT, `*`, `:` are plain words) and joined with
     * OR. BM25 ranks paragraphs matching more and rarer words higher. Null when there are no words.
     */
    fun anyTerm(text: String): String? = terms(text).takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "\"$it\"" }
}
