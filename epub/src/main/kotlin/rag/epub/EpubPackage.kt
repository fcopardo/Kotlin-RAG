package rag.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import rag.RagException
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** A navigation entry: label, target document path (inside the zip) and optional fragment. */
data class NavEntry(val title: String, val path: String?, val fragment: String?, val children: List<NavEntry> = emptyList())

/** A page-list entry: the printed page label and where it starts. */
data class PageTarget(val label: String, val path: String, val fragment: String?)

data class SpineItem(val path: String, val linear: Boolean, val isNav: Boolean)

/**
 * The parts of an EPUB that matter for indexing (SPEC §16.1–16.2): metadata, reading order,
 * table of contents and page list. Rejects DRM-protected and fixed-layout books.
 */
class EpubPackage private constructor(
    private val zip: ZipFile,
    val title: String?,
    val language: String?,
    val spine: List<SpineItem>,
    val toc: List<NavEntry>,
    val pageList: List<PageTarget>,
    /** "nav" (EPUB 3) or "ncx" (EPUB 2), or null without a usable TOC. */
    val tocKind: String?,
    /** `dc:subject` values, as written. */
    val subjects: List<String> = emptyList(),
) : AutoCloseable {

    /** The document at [path] inside the zip, as text; null if it's missing. */
    fun read(path: String): String? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

    override fun close() = zip.close()

    companion object {
        private val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

        fun open(file: File): EpubPackage {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw RagException.BookUnreadable("Damaged EPUB (not a valid zip): $file", e)
            }
            try {
                return parse(zip, file)
            } catch (e: Throwable) {
                zip.close()
                throw e
            }
        }

        private fun parse(zip: ZipFile, file: File): EpubPackage {
            fun text(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            checkDrm(zip, ::text, file)

            val container = text("META-INF/container.xml") ?: throw RagException.BookUnreadable("Damaged EPUB (no META-INF/container.xml): $file")
            val opfPath = xml(container).allElements.firstOrNull { local(it) == "rootfile" }?.attr("full-path")?.takeIf { it.isNotBlank() }
                ?: throw RagException.BookUnreadable("Damaged EPUB (no package document in container.xml): $file")
            val opf = xml(text(opfPath) ?: throw RagException.BookUnreadable("Damaged EPUB (missing $opfPath): $file"))

            val layout = opf.allElements.firstOrNull { local(it) == "meta" && it.attr("property") == "rendition:layout" }?.text()?.trim()
            if (layout == "pre-paginated") throw RagException.BookUnreadable("Fixed-layout EPUBs aren't supported (rendition:layout = pre-paginated): $file")

            val title = opf.allElements.firstOrNull { local(it) == "title" && it.tagName().startsWith("dc:") }?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val language = opf.allElements.firstOrNull { local(it) == "language" && it.tagName().startsWith("dc:") }?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val subjects = opf.allElements.filter { local(it) == "subject" && it.tagName().startsWith("dc:") }.map { it.text().trim() }.filter { it.isNotEmpty() }

            data class Item(val id: String, val path: String, val mediaType: String, val properties: Set<String>)
            val manifest = opf.allElements.filter { local(it) == "item" }.mapNotNull { e ->
                val href = e.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Item(e.attr("id"), resolve(opfPath, href).first, e.attr("media-type"), e.attr("properties").split(' ').filter { it.isNotBlank() }.toSet())
            }.associateBy { it.id }
            val spineElement = opf.allElements.firstOrNull { local(it) == "spine" }
                ?: throw RagException.BookUnreadable("Damaged EPUB (no spine): $file")
            val navItem = manifest.values.firstOrNull { "nav" in it.properties }
            val spine = spineElement.children().filter { local(it) == "itemref" }.mapNotNull { ref ->
                val item = manifest[ref.attr("idref")] ?: return@mapNotNull null
                SpineItem(item.path, linear = ref.attr("linear") != "no", isNav = item == navItem)
            }
            if (spine.isEmpty()) throw RagException.BookUnreadable("Damaged EPUB (empty spine): $file")

            // EPUB 3 nav wins over EPUB 2 NCX when both exist.
            val nav = navItem?.let { item -> text(item.path)?.let { parseNav(it, item.path) } }
            val ncxItem = manifest[spineElement.attr("toc")] ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            val ncx = if (nav?.first?.isNotEmpty() == true && nav.second.isNotEmpty()) null
            else ncxItem?.let { item -> text(item.path)?.let { parseNcx(it, item.path) } }

            val (toc, tocKind) = when {
                nav != null && nav.first.isNotEmpty() -> nav.first to "nav"
                ncx != null && ncx.first.isNotEmpty() -> ncx.first to "ncx"
                else -> emptyList<NavEntry>() to null
            }
            val pageList = nav?.second?.takeIf { it.isNotEmpty() } ?: ncx?.second.orEmpty()
            return EpubPackage(zip, title, language, spine, toc, pageList, tocKind, subjects)
        }

        /** DRM: Adobe (rights.xml), Apple (sinf.xml), or encrypted content beyond font obfuscation. */
        private fun checkDrm(zip: ZipFile, text: (String) -> String?, file: File) {
            val protected = zip.getEntry("META-INF/rights.xml") != null || zip.getEntry("META-INF/sinf.xml") != null ||
                text("META-INF/encryption.xml")?.let { enc ->
                    xml(enc).allElements.filter { local(it) == "EncryptionMethod" }.any { it.attr("Algorithm") !in fontObfuscation }
                } == true
            if (protected) throw RagException.BookUnreadable("This EPUB is DRM-protected and can't be indexed: $file")
        }

        /** EPUB 3 navigation document: the toc nav and the page-list nav. */
        private fun parseNav(html: String, navPath: String): Pair<List<NavEntry>, List<PageTarget>> {
            val doc = Jsoup.parse(html, "", Parser.xmlParser()) // XHTML, like content documents
            fun navOf(type: String, role: String) = doc.select("nav").firstOrNull { n ->
                n.attr("epub:type").split(' ').contains(type) || n.attr("role") == role
            }
            fun entries(ol: Element?): List<NavEntry> = ol?.children()?.filter { it.tagName() == "li" }?.mapNotNull { li ->
                val label = li.children().firstOrNull { it.tagName() == "a" || it.tagName() == "span" } ?: return@mapNotNull null
                val target = label.attr("href").takeIf { label.tagName() == "a" && it.isNotBlank() }?.let { resolve(navPath, it) }
                NavEntry(label.text().trim(), target?.first, target?.second, entries(li.children().firstOrNull { it.tagName() == "ol" }))
            }.orEmpty()
            val toc = entries(navOf("toc", "doc-toc")?.selectFirst("ol"))
            val pages = navOf("page-list", "doc-pagelist")?.select("a[href]")?.map { a ->
                val (path, fragment) = resolve(navPath, a.attr("href"))
                PageTarget(a.text().trim(), path, fragment)
            }.orEmpty()
            return toc to pages
        }

        /** EPUB 2 NCX: navMap (nested navPoints) and pageList. */
        private fun parseNcx(text: String, ncxPath: String): Pair<List<NavEntry>, List<PageTarget>> {
            val doc = xml(text)
            fun label(e: Element) = e.children().firstOrNull { local(it) == "navLabel" }?.text()?.trim().orEmpty()
            fun src(e: Element) = e.children().firstOrNull { local(it) == "content" }?.attr("src")?.takeIf { it.isNotBlank() }?.let { resolve(ncxPath, it) }
            fun points(parent: Element?): List<NavEntry> = parent?.children()?.filter { local(it) == "navPoint" }?.map { p ->
                val target = src(p)
                NavEntry(label(p), target?.first, target?.second, points(p))
            }.orEmpty()
            val toc = points(doc.allElements.firstOrNull { local(it) == "navMap" })
            val pages = doc.allElements.filter { local(it) == "pageTarget" }.mapNotNull { t ->
                val (path, fragment) = src(t) ?: return@mapNotNull null
                PageTarget(label(t), path, fragment)
            }
            return toc to pages
        }

        private fun xml(text: String): Document = Jsoup.parse(text, "", Parser.xmlParser())

        /** Element name without a namespace prefix ("opf:item" → "item"). */
        private fun local(e: Element) = e.tagName().substringAfter(':')

        /** Resolves [href] against the document at [base]; returns (zip path, fragment). */
        fun resolve(base: String, href: String): Pair<String, String?> {
            val target = try {
                URI(href)
            } catch (e: URISyntaxException) {
                URI(null, null, href.substringBefore('#'), href.substringAfter('#', "").ifEmpty { null })
            }
            val resolved = URI(null, null, base, null).resolve(target)
            val path = (resolved.path ?: "").removePrefix("/").ifEmpty { base }
            return path to resolved.fragment?.takeIf { it.isNotEmpty() }
        }
    }
}
