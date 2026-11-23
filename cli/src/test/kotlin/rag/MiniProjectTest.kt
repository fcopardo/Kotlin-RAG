package rag

import org.junit.jupiter.api.io.TempDir
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan step 6 on the committed mini-project (`src/test/resources/code/mini-project`): its structure, the
 * `rag structure` golden (reviewed by hand, SPEC §18.6), and that searching a function's name finds it.
 */
class MiniProjectTest {
    private val project = File("src/test/resources/code/mini-project")

    @Test
    fun `rag structure matches the reviewed golden`() {
        val actual = StructureReport.text(StructureReport.of(BookReader().read(project), project.name))
        val golden = File("src/test/resources/code/mini-project.structure.txt").readText()
        if (actual != golden) File("build/mini-project.structure.actual.txt").apply { parentFile.mkdirs() }.writeText(actual)
        assertEquals(golden, actual, "differs from the golden; the new output is in build/mini-project.structure.actual.txt")
    }

    @Test
    fun `pages are the files, and the unit counts are known`() {
        val book = BookReader().read(project)
        assertEquals(
            listOf(
                "app/src/main/kotlin/shop/cart/Cart.kt", "app/src/main/kotlin/shop/cart/Discounts.kt",
                "app/src/main/kotlin/shop/pay/Payment.kt", "app/src/main/kotlin/shop/pay/Receipts.kt",
                "app/src/test/kotlin/shop/cart/CartTest.kt", "native/checksum.c", "tools/report.py", "web/src/cart.ts",
            ),
            book.book.pageLabels,
        )
        assertEquals(21, book.chapters.size)
        assertEquals(46, book.segments.sumOf { it.paragraphs.size })
        assertEquals(122, book.segments.sumOf { s -> s.paragraphs.sumOf { it.phrases.size } })
        assertEquals(
            listOf("build/: build folder", "debug.log: gitignore", ".gitignore: hidden", "package-lock.json: lockfile").sorted(),
            book.skippedFiles.sorted(),
        )
    }

    @Test
    fun `a search for a function's name returns that function, titled by its path and cited by its file`(@TempDir dir: File) =
        SqliteStore(File(dir, "mini.db").path).use { store ->
            Indexer(store).index(project, embedder = FakeEmbedder())
            val searcher = Searcher(store, FakeEmbedder())
            mapOf(
                "adler32" to Triple("native › checksum.c", "uint32_t adler32(", "native/checksum.c"),
                "render" to Triple("web › cart.ts › CartBadge", "render(items: CartItem[]): void", "web/src/cart.ts"),
                "load" to Triple("tools › report.py", "def load(path):", "tools/report.py"),
            ).forEach { (query, expected) ->
                val top = searcher.search(query, k = 3, mode = Mode.LEXICAL).first()
                assertEquals(expected.first, top.chapter.title, query)
                assertEquals(true, expected.second in top.paragraph.text, "$query: ${top.paragraph.text}")
                assertEquals(expected.third, top.paragraph.pageStartLabel, query)
                assertEquals("file", top.pageKind, query)
            }
        }
}
