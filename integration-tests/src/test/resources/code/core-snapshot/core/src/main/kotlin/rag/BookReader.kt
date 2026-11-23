package rag

import java.io.File

/**
 * Reads any supported book, picking the [BookSource] by the file's content. By default it uses the
 * sources installed on the classpath (PDF and EPUB on desktop).
 */
class BookReader(private val sources: List<BookSource> = BookSources.installed()) {
    fun read(file: File, tocPages: Set<Int>? = null, options: ReadOptions = ReadOptions(tocPages = tocPages)): StructuredBook {
        val source = sourceFor(file)
        if (source.format != BookFormat.PDF && options.tocPages != null) {
            throw IllegalArgumentException("--toc-pages only applies to PDFs")
        }
        return source.read(file, options)
    }

    /** What identifies [file] for re-index detection: its source's fingerprint, else the SHA-256 of its bytes. */
    fun fingerprint(file: File): String = sourceFor(file).fingerprint(file) ?: rag.index.Indexer.sha256(file)

    /**
     * The source for [file]. Checked before content sniffing, which would call every `.kt` file plain text:
     * a folder, or a file with a source extension ([BookFormats.CODE_EXTENSIONS]), goes to the code source
     * when one is installed. Everything else is picked by its content, as before.
     */
    fun sourceFor(file: File): BookSource {
        val code = sources.firstOrNull { it.format == BookFormat.CODE }
        if (file.isDirectory) {
            return code ?: throw RagException.BookUnreadable("Reading a folder needs the code reader (:code): $file")
        }
        if (code != null && file.extension.lowercase() in BookFormats.CODE_EXTENSIONS) return code
        val format = BookFormats.sniff(file)
        return sources.firstOrNull { it.format == format }
            ?: throw RagException.BookUnreadable("No ${format.name} reader is installed: $file")
    }

    companion object {
        /** Parses `--toc-pages` values like "5-8" or "2-3,5". */
        fun parsePageRanges(spec: String): Set<Int> = spec.split(',').filter { it.isNotBlank() }.flatMap { part ->
            val bounds = part.trim().split('-').map { it.trim().toInt() }
            require(bounds.size in 1..2 && bounds.all { it >= 1 }) { "bad page range: '$part'" }
            (bounds.first()..bounds.last()).toList()
        }.toSet()
    }
}
