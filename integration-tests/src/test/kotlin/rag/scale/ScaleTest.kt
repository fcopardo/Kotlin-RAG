package rag.scale

import org.junit.jupiter.api.Assumptions.assumeTrue
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.search.Evaluation
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Search cost as books are added to one DB (SPEC §8.4). Opt-in: RAG_SCALE="1,100,1000" lists the
 * book counts to measure (each book ~1,650 paragraphs, like *Compose internals*; RAG_SCALE_PARAGRAPHS
 * overrides). Times use FakeEmbedder, so they cover the index and the DB, not a model.
 */
class ScaleTest {

    private val epubDir = File("src/test/resources/epub")

    @Test
    fun `search cost as books are added`() {
        val counts = System.getenv("RAG_SCALE")?.split(',')?.map { it.trim().toInt() }?.sorted()
        assumeTrue(!counts.isNullOrEmpty(), "set RAG_SCALE, e.g. 1,100,1000")
        val paragraphs = System.getenv("RAG_SCALE_PARAGRAPHS")?.toInt() ?: 1650
        val books = SyntheticBooks(listOf(epubDir.resolve("sun-tzu_the-art-of-war_lionel-giles.epub"), epubDir.resolve("pg132.epub")))
        val queries = Evaluation.load(File("src/test/resources/eval/art-of-war-se.jsonl")).map { it.query }
        val embedder = FakeEmbedder()
        val dir = System.getenv("RAG_SCALE_DIR")?.let(::File) ?: Files.createTempDirectory("rag-scale").toFile()
        val db = dir.resolve("scale.db").apply { delete() }

        SqliteStore(db.path).use { store ->
            val indexer = Indexer(store)
            var indexed = 0
            for (target in counts!!) {
                val start = System.nanoTime()
                while (indexed < target) {
                    val id = store.insertBook(books.build(indexed, paragraphs), "synthetic-$indexed", "synthetic")
                    // A fresh FakeEmbedder per book: it keeps every text it embeds (for batching tests).
                    indexer.embed(id, FakeEmbedder())
                    indexed++
                }
                val indexSeconds = (System.nanoTime() - start) / 1e9
                store.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")

                val searcher = Searcher(store, embedder)
                val report = Mode.entries.associateWith { mode ->
                    searcher.search("warm up", 5, mode = mode)
                    queries.map { q ->
                        val t = System.nanoTime()
                        searcher.search(q, 5, mode = mode)
                        (System.nanoTime() - t) / 1e6
                    }.sorted()
                }
                // The same vector search with every book's bits in RAM: what the cache bound trades away.
                val unbounded = Searcher(store, embedder, bitsCacheBytes = Long.MAX_VALUE)
                unbounded.search("warm up", 5, mode = Mode.VECTOR)
                val inRam = queries.map { q ->
                    val t = System.nanoTime()
                    unbounded.search(q, 5, mode = Mode.VECTOR)
                    (System.nanoTime() - t) / 1e6
                }.sorted()
                println("scale $target books: vector with all bits in RAM (${unbounded.cachedBitsBytes / 1_048_576} MB) median ${"%.1f".format(inRam[inRam.size / 2])} ms")
                val rt = Runtime.getRuntime()
                println("scale $target books (${target * paragraphs} paragraphs): +${"%.1f".format(indexSeconds)} s to index, " +
                    "DB ${db.length() / 1_048_576} MB, bits cache ${searcher.cachedBitsBytes / 1_048_576} MB, " +
                    "heap ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB; " +
                    report.entries.joinToString { (mode, t) -> "${mode.name.lowercase()} median ${"%.1f".format(t[t.size / 2])} ms max ${"%.1f".format(t.last())} ms" })
                assertTrue(report.values.all { it.isNotEmpty() })
            }
        }
        if (System.getenv("RAG_SCALE_DIR") == null) dir.deleteRecursively()
    }
}
