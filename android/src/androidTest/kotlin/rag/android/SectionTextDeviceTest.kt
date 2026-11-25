package rag.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rag.index.UnitKind
import rag.search.Hit
import rag.search.Mode
import rag.store.BundledSqlDriver
import rag.store.SqliteStore
import rag.store.StoredUnit
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RagEngine.sectionText] (Phase 9 app update, step 6) on the desktop-built code index (`code.db`, the frozen
 * `core` snapshot): the whole section with the paragraph marked, a window around the paragraph when the
 * section is too long, and null for keys that don't fit together. Unit offsets are inclusive (`end` is the last
 * character), as everywhere in the store.
 */
@RunWith(AndroidJUnit4::class)
class SectionTextDeviceTest {
    private lateinit var db: File
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val dir = File(context.cacheDir, "rag-section-test").apply { deleteRecursively(); mkdirs() }
        db = File(dir, "code.db")
        InstrumentationRegistry.getInstrumentation().context.assets.open("code.db").use { input ->
            db.outputStream().use { input.copyTo(it) }
        }
    }

    private class Fixture(val hit: Hit, val section: StoredUnit, val paragraph: StoredUnit, val bookText: String)

    /** The top hit for [query], with its section and paragraph units and the book text, read from the store. */
    private fun fixture(engine: RagEngine, query: String): Fixture {
        val hit = runBlocking { engine.search(query, k = 1, mode = Mode.LEXICAL) }.single()
        return SqliteStore(db.path, BundledSqlDriver()).use { store ->
            Fixture(
                hit,
                store.findByKey(hit.chapter.unitKey, hit.bookId).first(),
                store.findByKey(hit.paragraph.unitKey, hit.bookId).first(),
                store.bookText(hit.bookId),
            )
        }
    }

    @Test
    fun aShortSectionComesWholeWithTheParagraphMarked() = runBlocking {
        RagEngine(context, db).use { engine ->
            val f = fixture(engine, "normalize source indentation tabs")
            val text = assertNotNull(engine.sectionText(f.hit))
            // Unit offsets are inclusive: a unit is bookText[start..end].
            assertEquals(f.bookText.substring(f.section.start, f.section.end + 1), text.text)
            assertEquals(f.paragraph.start - f.section.start, text.highlightStart)
            assertEquals(f.paragraph.end + 1 - f.section.start, text.highlightEnd)
            assertEquals(f.hit.paragraph.text, text.text.substring(text.highlightStart, text.highlightEnd))
            assertEquals(f.section.title.orEmpty(), text.title)
            assertEquals(false, text.cutBefore)
            assertEquals(false, text.cutAfter)
        }
    }

    @Test
    fun aLongSectionIsCutToAWindowCenteredOnTheParagraph() = runBlocking {
        RagEngine(context, db).use { engine ->
            val f = fixture(engine, "normalize source indentation tabs")
            val paragraphLength = f.paragraph.end + 1 - f.paragraph.start
            val sectionLength = f.section.end + 1 - f.section.start
            val max = paragraphLength + 40
            assertTrue(sectionLength > max, "fixture: section of $sectionLength chars, paragraph of $paragraphLength")
            val text = assertNotNull(engine.sectionText(f.hit, maxChars = max))
            val start = (f.paragraph.start - 20).coerceAtMost(f.section.end + 1 - max).coerceAtLeast(f.section.start)
            assertEquals(f.bookText.substring(start, start + max), text.text)
            assertEquals(f.paragraph.start - start, text.highlightStart)
            assertEquals(f.paragraph.end + 1 - start, text.highlightEnd)
            assertEquals(f.hit.paragraph.text, text.text.substring(text.highlightStart, text.highlightEnd))
            assertEquals(start > f.section.start, text.cutBefore)
            assertEquals(start + max < f.section.end + 1, text.cutAfter)
        }
    }

    @Test
    fun aWindowAtTheSectionStartIsClampedThere() = runBlocking {
        RagEngine(context, db).use { engine ->
            val f = fixture(engine, "normalize source indentation tabs")
            // The section's first paragraph: the centered start would fall before the section.
            val first = SqliteStore(db.path, BundledSqlDriver()).use { store ->
                store.units(f.hit.bookId, UnitKind.PARAGRAPH).first { it.start >= f.section.start && it.end <= f.section.end }
            }
            val max = (first.end + 1 - first.start) + 2 * (first.start - f.section.start) + 50
            if (f.section.end + 1 - f.section.start <= max) return@runBlocking // section too short to cut: covered above
            val text = assertNotNull(engine.sectionText(f.hit.bookId, f.hit.chapter.unitKey, first.key, max))
            assertEquals(f.bookText.substring(f.section.start, f.section.start + max), text.text)
            assertEquals(first.start - f.section.start, text.highlightStart)
            assertEquals(false, text.cutBefore)
            assertEquals(true, text.cutAfter)
        }
    }

    @Test
    fun aParagraphLongerThanTheLimitStartsTheWindow() = runBlocking {
        RagEngine(context, db).use { engine ->
            val f = fixture(engine, "normalize source indentation tabs")
            assertTrue(f.paragraph.end + 1 - f.paragraph.start > 10)
            val text = assertNotNull(engine.sectionText(f.hit, maxChars = 10))
            assertEquals(f.bookText.substring(f.paragraph.start, f.paragraph.start + 10), text.text)
            assertEquals(0, text.highlightStart)
            assertEquals(10, text.highlightEnd)
            assertEquals(f.paragraph.start > f.section.start, text.cutBefore)
            assertEquals(true, text.cutAfter)
        }
    }

    @Test
    fun keysThatDontFitTogetherGiveNull() = runBlocking {
        RagEngine(context, db).use { engine ->
            val f = fixture(engine, "normalize source indentation tabs")
            // A paragraph of another section.
            val outside = SqliteStore(db.path, BundledSqlDriver()).use { store ->
                store.units(f.hit.bookId, UnitKind.PARAGRAPH).first { it.end < f.section.start || it.start > f.section.end }
            }
            assertNull(engine.sectionText(f.hit.bookId, f.hit.chapter.unitKey, outside.key))
            assertNull(engine.sectionText(f.hit.bookId, "no-such-key", f.hit.paragraph.unitKey))
            assertNull(engine.sectionText(f.hit.bookId + 99, f.hit.chapter.unitKey, f.hit.paragraph.unitKey))
        }
    }
}
