package rag.search

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvaluationTest {

    private fun hit(pages: IntRange, chapter: String) = Hit(
        score = 0.0,
        matchType = MatchType.LEXICAL,
        phrase = UnitRef("k", "t", pages.first, pages.last, "${pages.first}", "${pages.last}"),
        paragraph = UnitRef("k", "t", pages.first, pages.last, "${pages.first}", "${pages.last}"),
        chapter = ChapterRef(1, chapter, 1, 100, "k", "1", "100"),
        bookId = 1,
        bookTitle = null,
        pageKind = "pdf",
    )

    private val question = EvalQuestion("q1", "query", listOf(17, 18), "One", "keyword")

    @Test
    fun `page rank is the first hit overlapping an expected page`() {
        val hits = listOf(hit(3..3, "Two"), hit(16..17, "One"), hit(18..18, "One"))

        assertEquals(2, Evaluation.pageRank(hits, question))
        assertEquals(2, Evaluation.chapterRank(hits, question))
    }

    @Test
    fun `no relevant hit means no rank`() {
        assertNull(Evaluation.pageRank(listOf(hit(5..6, "Two")), question))
        assertNull(Evaluation.chapterRank(listOf(hit(5..6, "Two")), question))
    }

    @Test
    fun `summary computes recall, MRR and recall by kind`() {
        val paraphrase = question.copy(id = "q2", kind = "paraphrase")
        val results = listOf(
            QuestionResult(question, Mode.HYBRID, pageRank = 1, chapterRank = 1, topPages = emptyList()),
            QuestionResult(paraphrase, Mode.HYBRID, pageRank = 4, chapterRank = null, topPages = emptyList()),
            QuestionResult(paraphrase.copy(id = "q3"), Mode.HYBRID, pageRank = null, chapterRank = 2, topPages = emptyList()),
        )

        val s = Evaluation.summarize(results, k = 5).single()

        assertEquals(2.0 / 3, s.recall, 1e-9)
        assertEquals((1.0 + 0.25) / 3, s.mrr, 1e-9)
        assertEquals(2.0 / 3, s.chapterRecall, 1e-9)
        assertEquals(mapOf("keyword" to 1.0, "paraphrase" to 0.5), s.recallByKind)
    }

    @Test
    fun `loads JSON lines, skipping blanks and comments`(@TempDir dir: File) {
        val file = File(dir, "set.jsonl").apply {
            writeText(
                """
                // a comment
                {"id":"q1","query":"positional memoization","expectedPages":[17],"expectedChapter":"One","kind":"keyword"}

                {"id":"q2","query":"x","expectedPages":[1,2],"expectedChapter":"Two","kind":"paraphrase","note":"ignored"}
                """.trimIndent()
            )
        }

        assertEquals(listOf("q1", "q2"), Evaluation.load(file).map { it.id })
    }

    @Test
    fun `an anchor phrase answers regardless of pages`() {
        val anchored = EvalQuestion("q9", "query", expectedAnchor = "the BIKE  went", kind = "paraphrase")
        val hit = hit(40..40, "Two").copy(paragraph = UnitRef("k", "Then the bike went really fast.", 40, 40, "loc 40", "loc 40"))

        assertEquals(1, Evaluation.pageRank(listOf(hit), anchored))
        assertNull(Evaluation.chapterRank(listOf(hit), anchored))
    }

    @Test
    fun `a question needs pages or an anchor`(@TempDir dir: File) {
        val file = File(dir, "bad.jsonl").apply { writeText("""{"id":"q1","query":"x"}""") }

        kotlin.test.assertFailsWith<IllegalArgumentException> { Evaluation.load(file) }
    }
}
