package rag

import java.io.File
import java.util.Locale
import java.util.ServiceLoader
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class BookFormat { PDF, EPUB }

/** What a book's pages are: PDF pages, printed pages from an EPUB page list, or synthetic locations. */
enum class PageKind { PDF, PRINT, LOCATION }

data class ReadOptions(
    /** PDF pages holding the table of contents (PDF only). */
    val tocPages: Set<Int>? = null,
    /** Characters per location for an EPUB without a page list (SPEC §16.5). */
    val locationSize: Int = DEFAULT_LOCATION_SIZE,
    /** Sentence-splitting locale; null uses the book's language, else English. */
    val locale: Locale? = null,
) {
    companion object {
        const val DEFAULT_LOCATION_SIZE = 2000
    }
}

/** Turns a book file into a [StructuredBook] (SPEC §16.6). Everything after this is format-independent. */
interface BookSource {
    val format: BookFormat

    /** Recorded with each book, e.g. "pdfbox-3.0.8/norm-v1": what produced its text. */
    val extractor: String

    fun read(file: File, options: ReadOptions = ReadOptions()): StructuredBook
}

/**
 * The book sources on the classpath (SPEC §15.3): each format module registers its source in
 * `META-INF/services/rag.BookSource`, so the core never depends on PDFBox or jsoup.
 */
object BookSources {
    fun installed(): List<BookSource> = ServiceLoader.load(BookSource::class.java).toList()
}

object BookFormats {
    private const val EPUB_MIMETYPE = "application/epub+zip"

    /** Detects the format from the content, not the extension. */
    fun sniff(file: File): BookFormat {
        if (!file.isFile) throw RagException.BookUnreadable("Not a file: $file")
        val head = file.inputStream().use { it.readNBytes(1024) }
        if (String(head, Charsets.ISO_8859_1).contains("%PDF-")) return BookFormat.PDF
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val mimetype = try {
                ZipFile(file).use { zip -> zip.getEntry("mimetype")?.let { e -> zip.getInputStream(e).use { String(it.readAllBytes()).trim() } } }
            } catch (e: ZipException) {
                throw RagException.BookUnreadable("Damaged zip file: $file (${e.message})", e)
            }
            if (mimetype == EPUB_MIMETYPE) return BookFormat.EPUB
        }
        throw RagException.BookUnreadable("Unsupported file (not a PDF or an EPUB): $file")
    }
}
