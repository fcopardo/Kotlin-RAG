package rag.scale

import rag.BookFormat
import rag.BookReader
import rag.PageKind
import rag.StructuredBook
import rag.extract.ExtractedBook
import rag.structure.ChapterPlan
import rag.structure.ChapterStart
import rag.structure.ChapterStrategy
import rag.structure.Chapters
import rag.structure.Segmenter
import java.io.File
import kotlin.random.Random

/**
 * Many distinct books for scale tests (SPEC §8.4), built straight into a [StructuredBook] without
 * parsing files. Each paragraph is a few sentences drawn at random from the committed fixture
 * EPUBs, so books read like prose but don't repeat each other's paragraphs.
 */
class SyntheticBooks(epubs: List<File>) {

    private val sentences: List<String> = epubs.flatMap { file ->
        val book = BookReader().read(file)
        book.segments.flatMap { s -> s.paragraphs.filter { !it.isCode }.flatMap { p -> p.phrases.map { book.text(it.start, it.end) } } }
    }.map { it.replace('\n', ' ') }.filter { it.length in 40..400 }.distinct()

    /** Book [seed] with [paragraphs] paragraphs over [chapters] chapters, about five paragraphs a page. */
    fun build(seed: Int, paragraphs: Int, chapters: Int = 10): StructuredBook {
        val random = Random(seed)
        val text = StringBuilder()
        val pageStarts = mutableListOf<Int>()
        val starts = mutableListOf<ChapterStart>()
        val perChapter = (paragraphs + chapters - 1) / chapters
        for (p in 0 until paragraphs) {
            if (p % 5 == 0) pageStarts += text.length
            if (p % perChapter == 0) {
                val number = p / perChapter + 1
                starts += ChapterStart("Chapter $number", pageStarts.size, charOffset = text.length)
                text.append("Chapter $number\n\n")
            }
            text.append(List(2 + random.nextInt(3)) { sentences[random.nextInt(sentences.size)] }.joinToString(" "))
            text.append("\n\n")
        }
        val book = ExtractedBook(text.toString().trimEnd(), pageStarts.toIntArray())
        val chapterList = Chapters.compute(book, starts)
        return StructuredBook(
            book, chapterList, ChapterPlan(ChapterStrategy.HEADINGS, starts), Segmenter().segment(book, chapterList),
            title = "Synthetic book $seed", format = BookFormat.EPUB, pageKind = PageKind.PDF, extractor = "synthetic",
        )
    }
}
