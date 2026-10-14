package rag.search

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One evaluation question (SPEC §11): a query and where its answer is in the book. */
@Serializable
data class EvalQuestion(
    val id: String,
    val query: String,
    val expectedPages: List<Int>,
    val expectedChapter: String,
    /** `keyword` shares words with the answer; `paraphrase` shares no content words with it. */
    val kind: String,
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

    /** 1-based rank of the first hit whose paragraph pages include an expected page. */
    fun pageRank(hits: List<Hit>, question: EvalQuestion): Int? =
        hits.indexOfFirst { h -> question.expectedPages.any { it in h.paragraph.pageStart..h.paragraph.pageEnd } }
            .takeIf { it >= 0 }?.plus(1)

    fun chapterRank(hits: List<Hit>, question: EvalQuestion): Int? =
        hits.indexOfFirst { it.chapter.title == question.expectedChapter }.takeIf { it >= 0 }?.plus(1)

    fun run(searcher: Searcher, questions: List<EvalQuestion>, modes: List<Mode>, k: Int = 5, bookId: Long? = null): List<QuestionResult> =
        modes.flatMap { mode ->
            questions.map { q ->
                val hits = searcher.search(q.query, k, bookId, mode)
                QuestionResult(q, mode, pageRank(hits, q), chapterRank(hits, q), hits.map { it.paragraph.pageStart..it.paragraph.pageEnd })
            }
        }

    fun summarize(results: List<QuestionResult>, k: Int): List<ModeSummary> =
        results.groupBy { it.mode }.map { (mode, rs) ->
            ModeSummary(
                mode = mode,
                k = k,
                recall = rs.count { it.pageRank != null }.toDouble() / rs.size,
                mrr = rs.sumOf { r -> r.pageRank?.let { 1.0 / it } ?: 0.0 } / rs.size,
                chapterRecall = rs.count { it.chapterRank != null }.toDouble() / rs.size,
                recallByKind = rs.groupBy { it.question.kind }
                    .mapValues { (_, g) -> g.count { it.pageRank != null }.toDouble() / g.size }
                    .toSortedMap(),
            )
        }
}
