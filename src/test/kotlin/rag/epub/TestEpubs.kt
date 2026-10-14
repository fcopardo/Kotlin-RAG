package rag.epub

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUB files for tests. Paths are relative to OEBPS/. */
object TestEpubs {

    data class Doc(val path: String, val body: String, val linear: Boolean = true)

    /** A TOC entry: title, href (relative to OEBPS/, e.g. "ch1.xhtml#s2"), children. */
    data class Toc(val title: String, val href: String, val children: List<Toc> = emptyList())

    fun build(
        file: File,
        docs: List<Doc>,
        toc: List<Toc> = emptyList(),
        withNav: Boolean = true,
        withNcx: Boolean = false,
        navInSpine: Boolean = false,
        pageList: List<Pair<String, String>> = emptyList(), // (label, href)
        title: String? = "Test Book",
        language: String? = "en",
        fixedLayout: Boolean = false,
        extra: Map<String, String> = emptyMap(),           // full zip path -> content
    ): File {
        val manifest = StringBuilder()
        docs.forEachIndexed { i, d -> manifest.append("""<item id="d$i" href="${d.path}" media-type="application/xhtml+xml"/>""") }
        if (withNav) manifest.append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        if (withNcx) manifest.append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
        val spine = StringBuilder()
        if (withNav && navInSpine) spine.append("""<itemref idref="nav"/>""")
        docs.forEachIndexed { i, d -> spine.append("""<itemref idref="d$i"${if (d.linear) "" else " linear=\"no\""}/>""") }
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">test</dc:identifier>
                ${title?.let { "<dc:title>$it</dc:title>" } ?: ""}
                ${language?.let { "<dc:language>$it</dc:language>" } ?: ""}
                ${if (fixedLayout) """<meta property="rendition:layout">pre-paginated</meta>""" else ""}
              </metadata>
              <manifest>$manifest</manifest>
              <spine${if (withNcx) " toc=\"ncx\"" else ""}>$spine</spine>
            </package>"""

        ZipOutputStream(file.outputStream()).use { zip ->
            // The mimetype entry comes first and is stored uncompressed.
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
            }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", opf)
            docs.forEach { put("OEBPS/${it.path}", xhtml(it.body)) }
            if (withNav) put("OEBPS/nav.xhtml", xhtml(navBody(toc, pageList)))
            if (withNcx) put("OEBPS/toc.ncx", ncx(toc, pageList))
            extra.forEach { (path, content) -> put(path, content) }
        }
        return file
    }

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>t</title></head><body>$body</body></html>"""

    private fun navBody(toc: List<Toc>, pages: List<Pair<String, String>>): String {
        fun ol(entries: List<Toc>): String = if (entries.isEmpty()) "" else
            "<ol>" + entries.joinToString("") { "<li><a href=\"${it.href}\">${it.title}</a>${ol(it.children)}</li>" } + "</ol>"
        val pageNav = if (pages.isEmpty()) "" else
            """<nav epub:type="page-list"><ol>${pages.joinToString("") { "<li><a href=\"${it.second}\">${it.first}</a></li>" }}</ol></nav>"""
        return """<nav epub:type="toc"><h1>Contents</h1>${ol(toc)}</nav>$pageNav"""
    }

    private fun ncx(toc: List<Toc>, pages: List<Pair<String, String>>): String {
        var order = 0
        fun points(entries: List<Toc>): String = entries.joinToString("") {
            """<navPoint id="p${++order}" playOrder="$order"><navLabel><text>${it.title}</text></navLabel><content src="${it.href}"/>${points(it.children)}</navPoint>"""
        }
        val pageList = if (pages.isEmpty()) "" else
            "<pageList>" + pages.joinToString("") { """<pageTarget type="normal" value="${it.first}"><navLabel><text>${it.first}</text></navLabel><content src="${it.second}"/></pageTarget>""" } + "</pageList>"
        return """<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>T</text></docTitle><navMap>${points(toc)}</navMap>$pageList</ncx>"""
    }
}
