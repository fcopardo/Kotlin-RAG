package rag.search

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import rag.embed.OllamaEmbedder
import rag.index.Indexer
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What other books cost an evaluation set (SPEC §8.4): each set runs against its own book, then
 * against a library holding it and the others, where only its own book can answer. Needs
 * RAG_OLLAMA=1, RAG_BOOK_PDF, RAG_KOTLIN_PDF and RAG_CTCI_PDF (several minutes to embed).
 */
@Tag("book")
@Tag("ollama")
class DistractorEvalTest {

    @Test
    fun `evaluation sets keep their recall in a library`() {
        val pdfs = listOf("RAG_BOOK_PDF", "RAG_KOTLIN_PDF", "RAG_CTCI_PDF").map { System.getenv(it)?.let(::File) }
        assumeTrue(System.getenv("RAG_OLLAMA") == "1" && pdfs.all { it?.isFile == true }, "needs RAG_OLLAMA=1 and the three book PDFs")
        val (compose, kotlin, ctci) = pdfs.map { it!! }
        val artOfWar = File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub")
        val embedder = OllamaEmbedder()
        val dir = Files.createTempDirectory("rag-distractors").toFile()
        try {
            SqliteStore(dir.resolve("library.db").path).use { store ->
                val indexer = Indexer(store)
                val ids = listOf(compose, kotlin, ctci, artOfWar).associateWith { indexer.index(it, embedder = embedder).bookId }
                val searcher = Searcher(store, embedder)
                val modes = listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)
                for ((set, book) in listOf("compose-internals" to compose, "art-of-war-se" to artOfWar)) {
                    val questions = Evaluation.load(File("src/test/resources/eval/$set.jsonl"))
                    val id = ids.getValue(book)
                    val alone = Evaluation.summarize(Evaluation.run(searcher, questions, modes, bookId = id), 5)
                    val library = Evaluation.summarize(Evaluation.run(searcher, questions, modes, bookId = null, answerBookId = id), 5)
                    for ((a, l) in alone.zip(library)) {
                        println("distractors $set ${a.mode.name.lowercase()}: alone %.2f, in a 4-book library %.2f".format(a.recall, l.recall))
                        // Recorded in SPEC §8.4; this only catches a collapse.
                        assertTrue(l.recall >= a.recall - 0.15 - 1e-9, "$set ${a.mode}: ${l.recall} vs ${a.recall} alone")
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
