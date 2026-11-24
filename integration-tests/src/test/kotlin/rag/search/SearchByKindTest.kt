package rag.search

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import rag.embed.Embedder
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** App update step 1: one query, the best hits of each kind of document side by side. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchByKindTest {
    private lateinit var dir: File
    private lateinit var store: SqliteStore
    private val query = "all warfare is based on deception"

    /** Counts query embeddings: the point of searchByKind is to embed the query once. */
    private class Counting(private val inner: FakeEmbedder = FakeEmbedder()) : Embedder by inner {
        var queries = 0
        override fun embedQuery(text: String): FloatArray = inner.embedQuery(text).also { queries++ }
    }

    @BeforeAll
    fun index() {
        dir = Files.createTempDirectory("rag-by-kind").toFile()
        store = SqliteStore(File(dir, "all.db").path)
        val indexer = Indexer(store)
        val embedder = FakeEmbedder()
        indexer.index(File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub"), embedder = embedder)
        val paper = File(dir, "notes.md").apply {
            writeText("# Deception in strategy\n\nAll warfare is based on deception, the old texts say.\n\n## Today\n\nModern strategy still values deception and surprise.\n")
        }
        indexer.index(paper, embedder = embedder)
        val project = File(dir, "game").apply { mkdirs() }
        File(project, "Strategy.kt").writeText(
            "package game\n\n/** All warfare is based on deception: feint before you attack. */\nfun deception(turn: Int): Boolean {\n    return turn % 2 == 0\n}\n\n" +
                "/** A move only the code mentions: zxqfeint. */\nfun zxqfeint() = 1\n",
        )
        indexer.index(project, embedder = embedder)
    }

    @AfterAll
    fun cleanUp() {
        if (::store.isInitialized) store.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun `each kind gets its own section, in a fixed order`() {
        val byKind = Searcher(store, FakeEmbedder()).searchByKind(query)
        assertEquals(listOf("book", "paper", "code"), byKind.keys.toList())
        byKind.forEach { (kind, hits) ->
            assertTrue(hits.size in 1..3, "$kind: ${hits.size} hits")
            assertTrue(hits.all { it.kind == kind }, "$kind sections hold only $kind hits")
        }
    }

    @Test
    fun `a section is exactly the search for that kind, with the query embedded once`() {
        val counting = Counting()
        val byKind = Searcher(store, counting).searchByKind(query)
        assertEquals(1, counting.queries, "one query embedding for all kinds")
        val plain = Searcher(store, FakeEmbedder())
        byKind.forEach { (kind, hits) -> assertEquals(plain.search(query, 3, kind = kind), hits, kind) }
    }

    @Test
    fun `kinds narrows the sections, which keep the fixed order`() {
        val byKind = Searcher(store, FakeEmbedder()).searchByKind(query, kinds = listOf("code", "book"))
        assertEquals(listOf("book", "code"), byKind.keys.toList())
    }

    @Test
    fun `a kind with no hits is left out, and keyword search needs no embedder`() {
        val byKind = Searcher(store, null).searchByKind("zxqfeint", mode = Mode.LEXICAL)
        assertEquals(listOf("code"), byKind.keys.toList())
        assertTrue(byKind.getValue("code").first().paragraph.text.contains("fun zxqfeint"))
    }

    @Test
    fun `every hit says its kind`() {
        val s = Searcher(store, FakeEmbedder())
        assertTrue(s.search(query, 5, kind = "book").all { it.kind == "book" })
        assertTrue(s.search(query, 10).map { it.kind }.toSet().all { it in setOf("book", "paper", "code") })
    }
}
