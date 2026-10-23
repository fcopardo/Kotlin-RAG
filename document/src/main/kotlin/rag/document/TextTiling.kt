package rag.document

import kotlin.math.sqrt

/**
 * Where a document changes topic, for documents with no headings to divide them (SPEC §18.4).
 *
 * TextTiling (Hearst, 1997), on paragraphs instead of fixed token windows: compare the vocabulary
 * of the [window] paragraphs before each gap with the [window] after, then cut at the gaps whose
 * dip is deeper than average. Purely lexical, so a document's structure does not depend on an
 * embedding model — see SPEC §18.4 for why that matters.
 */
object TextTiling {

    private val word = Regex("[\\p{L}\\p{N}]+")

    /** Words that appear in nearly every paragraph and so say nothing about the topic. */
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "that", "this", "these", "those",
        "is", "are", "was", "were", "be", "been", "being", "am", "do", "does", "did", "have", "has",
        "had", "of", "to", "in", "on", "at", "by", "for", "with", "from", "as", "it", "its", "not",
        "no", "we", "you", "they", "he", "she", "i", "there", "here", "which", "who", "what", "when",
        "where", "how", "can", "could", "will", "would", "should", "may", "might", "must", "so",
        "into", "over", "under", "between", "about", "after", "before", "through", "during", "all",
        "any", "each", "more", "most", "other", "some", "such", "only", "own", "same", "also", "very",
    )

    /**
     * Indexes of the paragraphs that start a new topic, always including 0. [texts] are the
     * document's paragraphs in order.
     *
     * @param window paragraphs compared on each side of a gap.
     * @param minParagraphs smallest topic, so a run of one-line paragraphs is not cut to shreds.
     */
    fun cut(texts: List<String>, window: Int = 2, minParagraphs: Int = 3): List<Int> {
        if (texts.size < minParagraphs * 2) return listOf(0)
        val bags = texts.map { bag(it) }
        // Similarity across each gap between paragraph i-1 and i.
        val gaps = (1 until texts.size).map { i ->
            similarity(merge(bags, i - window, i), merge(bags, i, i + window))
        }
        // Depth of a dip: how far it sits below the nearest peak on either side.
        val depths = gaps.indices.map { i ->
            var left = gaps[i]
            var j = i
            while (j > 0 && gaps[j - 1] >= gaps[j]) { left = gaps[--j] }
            var right = gaps[i]
            var k = i
            while (k < gaps.size - 1 && gaps[k + 1] >= gaps[k]) { right = gaps[++k] }
            (left - gaps[i]) + (right - gaps[i])
        }
        val mean = depths.average()
        val sd = sqrt(depths.sumOf { (it - mean) * (it - mean) } / depths.size)
        // Hearst's cutoff: deeper than mean - sd/2. A flat document yields no cuts at all.
        val threshold = mean + sd / 2
        val cuts = mutableListOf(0)
        depths.forEachIndexed { i, depth ->
            val at = i + 1
            if (depth > threshold && depth > 0.0 && at - cuts.last() >= minParagraphs && texts.size - at >= minParagraphs) {
                cuts += at
            }
        }
        return cuts
    }

    private fun bag(text: String): Map<String, Int> =
        word.findAll(text.lowercase()).map { it.value }.filter { it.length > 2 && it !in stopWords }
            .groupingBy { it }.eachCount()

    private fun merge(bags: List<Map<String, Int>>, from: Int, to: Int): Map<String, Int> {
        val merged = HashMap<String, Int>()
        for (i in maxOf(0, from) until minOf(bags.size, to)) {
            bags[i].forEach { (w, n) -> merged[w] = (merged[w] ?: 0) + n }
        }
        return merged
    }

    /** Cosine between two term-frequency bags; 0 when either is empty. */
    private fun similarity(a: Map<String, Int>, b: Map<String, Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dot = a.entries.sumOf { (w, n) -> (n * (b[w] ?: 0)).toDouble() }
        val na = sqrt(a.values.sumOf { (it * it).toDouble() })
        val nb = sqrt(b.values.sumOf { (it * it).toDouble() })
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (na * nb)
    }
}
