package rag.epub

import rag.BookFormat
import rag.BookSource
import rag.PageKind
import rag.RagException
import rag.ReadOptions
import rag.StructuredBook
import rag.extract.ExtractedBook
import rag.structure.ChapterPlan
import rag.structure.ChapterStart
import rag.structure.ChapterStrategy
import rag.structure.Chapters
import rag.structure.OutlineChapters
import rag.structure.OutlineEntry
import rag.structure.Segmenter
import java.io.File
import java.util.Locale

/** Reads a reflowable, DRM-free EPUB 2 or 3 (SPEC §16). */
class EpubBookSource(private val textBuilder: EpubTextBuilder = EpubTextBuilder()) : BookSource {
    override val format = BookFormat.EPUB
    override val extractor = "jsoup-${org.jsoup.Jsoup::class.java.`package`?.implementationVersion ?: "1"}/norm-v1"

    override fun read(file: File, options: ReadOptions): StructuredBook = EpubPackage.open(file).use { pkg ->
        // The nav document and documents a "Contents" entry points to are lists of titles, not text.
        fun all(entries: List<NavEntry>): List<NavEntry> = entries.flatMap { listOf(it) + all(it.children) }
        val contentsDocs = all(pkg.toc).filter { OutlineChapters.isContents(it.title) }.mapNotNull { it.path }.toSet()
        val documents = pkg.spine
            .filter { it.linear && !it.isNav && it.path !in contentsDocs }
            .mapNotNull { item -> pkg.read(item.path)?.let { item.path to it } }
        if (documents.isEmpty()) throw RagException.BookUnreadable("EPUB has no readable documents in its reading order: $file")

        fun key(path: String, fragment: String?) = "$path#${fragment.orEmpty()}"
        val wanted = documents.map { key(it.first, null) }.toSet() +
            all(pkg.toc).mapNotNull { e -> e.path?.let { key(it, e.fragment) } } +
            pkg.pageList.map { key(it.path, it.fragment) }
        val built = textBuilder.build(documents, wanted)
        if (built.text.isBlank()) throw RagException.BookUnreadable("EPUB has no text: $file")
        fun offset(path: String, fragment: String?) = built.targets[key(path, fragment)] ?: built.targets[key(path, null)]

        // Pages: printed pages from the page list, else fixed-size locations.
        val printed = pkg.pageList.mapNotNull { t -> offset(t.path, t.fragment)?.let { t.label to it } }.sortedBy { it.second }
        val (starts, labels, pageKind) = if (printed.size >= 2) {
            // Text before the first printed page counts as that page.
            Triple(IntArray(printed.size) { if (it == 0) 0 else printed[it].second }, printed.map { it.first }, PageKind.PRINT)
        } else {
            val size = options.locationSize.coerceAtLeast(1)
            val count = maxOf(1, (built.text.length + size - 1) / size)
            Triple(IntArray(count) { it * size }, List(count) { "loc ${it + 1}" }, PageKind.LOCATION)
        }
        val book = ExtractedBook(built.text, starts, built.codeRanges, labels)

        // Chapters: the TOC through the shared parts rule; else leading headings; else one chapter.
        val locationSize = options.locationSize.coerceAtLeast(1)
        fun outline(e: NavEntry, parent: NavEntry? = null): OutlineEntry {
            val at = e.path?.let { offset(it, e.fragment) }
            val ownDocument = parent?.let { e.path != null && e.path != it.path }
            return OutlineEntry(e.title, at?.let { it / locationSize + 1 }, e.children.map { outline(it, e) }, at, ownDocument)
        }
        val fromToc = OutlineChapters.flatten(pkg.toc.map { outline(it) }).chapters
        val headings = built.leadingHeadings.values.sortedBy { it.second }
            .map { (title, at) -> ChapterStart(title, at / locationSize + 1, at) }
        val plan = when {
            fromToc.size >= 2 -> ChapterPlan(if (pkg.tocKind == "nav") ChapterStrategy.NAV else ChapterStrategy.NCX, fromToc)
            headings.size >= 2 -> ChapterPlan(ChapterStrategy.HEADINGS, headings)
            else -> ChapterPlan(ChapterStrategy.FALLBACK, listOf(ChapterStart("Book", 1, 0)))
        }
        val chapters = Chapters.compute(book, plan.starts)
        val locale = options.locale ?: pkg.language?.let { Locale.forLanguageTag(it) } ?: Locale.ENGLISH
        StructuredBook(
            book, chapters, plan, Segmenter(locale).segment(book, chapters),
            title = pkg.title ?: file.nameWithoutExtension, format = BookFormat.EPUB, pageKind = pageKind, extractor = extractor,
        )
    }
}
