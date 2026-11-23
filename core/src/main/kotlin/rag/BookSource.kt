package rag

import java.io.File
import java.util.Locale
import java.util.ServiceLoader
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class BookFormat { PDF, EPUB, TEXT, HTML, DOCX, CODE }

/** What a book's pages are: PDF pages, printed pages from an EPUB page list, or synthetic locations. */
enum class PageKind { PDF, PRINT, LOCATION, FILE }

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

    /**
     * What identifies this input for re-index detection (SPEC §18.4), or null for the SHA-256 of the
     * file's bytes. A code project is a folder, so its source gives a fingerprint over its files.
     */
    fun fingerprint(file: File): String? = null
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

    /**
     * Extensions read as source code when a [BookFormat.CODE] source is installed (SPEC §18.4). Kept equal
     * to the extensions of `rag.code.Language` (a test in `:code` checks it).
     */
    val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "c", "h", "cc", "cpp", "cxx",
        "hpp", "hh", "hxx", "cs", "go", "rs", "swift", "py", "pyi",
    )

    /** Enough of a document to tell its format; also enough to tell text from binary. */
    private const val HEAD_BYTES = 1024

    /** Detects the format from the content, not the extension. */
    fun sniff(file: File): BookFormat {
        if (!file.isFile) throw RagException.BookUnreadable("Not a file: $file")
        // Not readNBytes/readAllBytes: those are API 33 on Android (minSdk 26).
        val head = file.inputStream().use { input -> ByteArray(1024).let { buf -> buf.copyOf(maxOf(0, input.read(buf))) } }
        if (String(head, Charsets.ISO_8859_1).contains("%PDF-")) return BookFormat.PDF
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val zipped = try {
                ZipFile(file).use { zip ->
                    val mimetype = zip.getEntry("mimetype")?.let { e -> zip.getInputStream(e).use { String(it.readBytes()).trim() } }
                    when {
                        mimetype == EPUB_MIMETYPE -> BookFormat.EPUB
                        // A .docx is a zip whose main part is word/document.xml (SPEC §18.4).
                        zip.getEntry("word/document.xml") != null -> BookFormat.DOCX
                        else -> null
                    }
                }
            } catch (e: ZipException) {
                throw RagException.BookUnreadable("Damaged zip file: $file (${e.message})", e)
            }
            if (zipped != null) return zipped
        }
        textFormat(head)?.let { return it }
        throw RagException.BookUnreadable("Unsupported file (not a PDF, EPUB, Word, HTML or text file): $file")
    }

    /**
     * [BookFormat.HTML] or [BookFormat.TEXT] when [head] looks like text, else null. Text means no
     * NUL byte and either valid UTF-8 or almost all printable bytes (a Latin-1 file reads fine).
     */
    private fun textFormat(head: ByteArray): BookFormat? {
        if (head.isEmpty() || head.contains(0)) return null
        val utf8 = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(head)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null // may be a truncated multi-byte char at the 1 KB boundary, or another charset
        }
        val printable = head.count { val c = it.toInt() and 0xFF; c >= 0x20 || c == 0x09 || c == 0x0A || c == 0x0D }
        if (utf8 == null && printable < head.size * 95 / 100) return null
        val text = (utf8 ?: String(head, Charsets.ISO_8859_1)).trimStart('\uFEFF', ' ', '\t', '\n', '\r').lowercase()
        val html = text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<?xml") &&
            (text.contains("<html") || text.contains("<body"))
        return if (html) BookFormat.HTML else BookFormat.TEXT
    }
}
