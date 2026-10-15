package rag

import java.io.File

/**
 * Reads any supported book, picking the [BookSource] by the file's content. By default it uses the
 * sources installed on the classpath (PDF and EPUB on desktop).
 */
class BookReader(private val sources: List<BookSource> = BookSources.installed()) {
    fun read(file: File, tocPages: Set<Int>? = null, options: ReadOptions = ReadOptions(tocPages = tocPages)): StructuredBook {
        val format = BookFormats.sniff(file)
        if (format != BookFormat.PDF && options.tocPages != null) {
            throw IllegalArgumentException("--toc-pages only applies to PDFs")
        }
        val source = sources.firstOrNull { it.format == format }
            ?: throw RagException.BookUnreadable("No ${format.name} reader is installed: $file")
        return source.read(file, options)
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
