package rag.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rag.extract.Normalizer
import java.io.File

/** One evaluation question (SPEC §11): a query and where its answer is in the book. */
@Serializable
data class EvalQuestion(
    val id: String,
    val query: String,
    /** Page ordinals of the answer (PDF pages); may be empty when [expectedAnchor] is given. */
    val expectedPages: List<Int> = emptyList(),
    /** Optional: the answer's chapter title, for chapter recall. */
    val expectedChapter: String = "",
    /** `keyword` shares words with the answer; `paraphrase` shares no content words with it. */
    val kind: String = "keyword",
    /**
     * A short phrase (at most 8 words) from the answer passage. Format-independent (SPEC §16.7): a
     * hit counts when its paragraph contains it, ignoring case and spacing.
     */
    val expectedAnchor: String? = null,
)

/** Where the answer showed up for one question in one mode. Ranks are 1-based; null if not in the top k. */
data class QuestionResult(
    val question: EvalQuestion,
    val mode: Mode,
    val pageRank: Int?,
    val chapterRank: Int?,
    val topPages: List<IntRange>,
)

data class ModeSummary(
    val mode: Mode,
    val k: Int,
    /** Share of questions with a hit on an expected page in the top k. */
    val recall: Double,
    /** Mean reciprocal rank of the first hit on an expected page (0 when missing). */
    val mrr: Double,
    /** Share of questions with a hit in the expected chapter in the top k. */
    val chapterRecall: Double,
    /** Recall per question kind. */
    val recallByKind: Map<String, Double>,
)

/** Runs an evaluation set against a [Searcher] and scores it. Scoring is pure. */
object Evaluation {

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads a JSON Lines file; blank lines and lines starting with `//` are skipped. */
    fun load(file: File): List<EvalQuestion> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") }
        .map { json.decodeFromString<EvalQuestion>(it) }
        .onEach { q -> require(q.expectedPages.isNotEmpty() || !q.expectedAnchor.isNullOrBlank()) { "${q.id}: needs expectedPages or expectedAnchor" } }

    /** Whether a hit answers the question: its paragraph is on an expected page, or contains the anchor. */
    fun answers(hit: Hit, question: EvalQuestion): Boolean {
        val onPage = question.expectedPages.any { it in hit.paragraph.pageStart..hit.paragraph.pageEnd }
        val anchor = question.expectedAnchor?.let(Normalizer::hashNormalize)?.takeIf { it.isNotEmpty() }
        return onPage || (anchor != null && anchor in Normalizer.hashNormalize(hit.paragraph.text))
    }

    /** 1-based rank of the first hit that answers the question. */
    fun pageRank(hits: List<Hit>, question: EvalQuestion): Int? =
        hits.indexOfFirst { answers(it, question) }.takeIf { it >= 0 }?.plus(1)

    /** Null when the question names no chapter, or the chapter isn't in the hits. */
    fun chapterRank(hits: List<Hit>, question: EvalQuestion): Int? =
        if (question.expectedChapter.isBlank()) null
        else hits.indexOfFirst { it.chapter.title == question.expectedChapter }.takeIf { it >= 0 }?.plus(1)

    /**
     * Searches [bookId] (or every book) and scores the hits. With [answerBookId], only hits from that
     * book can answer: in a library, page 17 of another book doesn't answer a question about this one.
     */
    fun run(
        searcher: Searcher,
        questions: List<EvalQuestion>,
        modes: List<Mode>,
        k: Int = 5,
        bookId: Long? = null,
        answerBookId: Long? = bookId,
    ): List<QuestionResult> =
        modes.flatMap { mode ->
            questions.map { q ->
                val all = searcher.search(q.query, k, bookId, mode)
                // Hits from other books keep their rank but can't answer.
                val hits = if (answerBookId == null) all else all.map { if (it.bookId == answerBookId) it else null }
                QuestionResult(
                    q, mode,
                    hits.indexOfFirst { it != null && answers(it, q) }.takeIf { it >= 0 }?.plus(1),
                    if (q.expectedChapter.isBlank()) null
                    else hits.indexOfFirst { it != null && it.chapter.title == q.expectedChapter }.takeIf { it >= 0 }?.plus(1),
                    all.map { it.paragraph.pageStart..it.paragraph.pageEnd },
                )
            }
        }

    fun summarize(results: List<QuestionResult>, k: Int): List<ModeSummary> =
        results.groupBy { it.mode }.map { (mode, rs) ->
            ModeSummary(
                mode = mode,
                k = k,
                recall = rs.count { it.pageRank != null }.toDouble() / rs.size,
                mrr = rs.sumOf { r -> r.pageRank?.let { 1.0 / it } ?: 0.0 } / rs.size,
                chapterRecall = rs.filter { it.question.expectedChapter.isNotBlank() }
                    .let { c -> if (c.isEmpty()) 0.0 else c.count { it.chapterRank != null }.toDouble() / c.size },
                recallByKind = rs.groupBy { it.question.kind }
                    .mapValues { (_, g) -> g.count { it.pageRank != null }.toDouble() / g.size }
                    .toSortedMap(),
            )
        }
}
