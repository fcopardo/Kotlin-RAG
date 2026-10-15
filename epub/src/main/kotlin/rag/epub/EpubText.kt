package rag.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import rag.extract.CodeListings
import rag.extract.Normalizer

/** The book text built from XHTML documents, with the offsets the navigation needs. */
class EpubText(
    val text: String,
    val codeRanges: List<IntRange>,
    /** Offset of each requested target: "path#" for a document's start, "path#id" for an element. */
    val targets: Map<String, Int>,
    /** Each document's first heading and its offset, when the document starts with one. */
    val leadingHeadings: Map<String, Pair<String, Int>>,
)

/**
 * XHTML → book text (SPEC §16.3). Each block element becomes one paragraph, separated by a blank
 * line; `<pre>` becomes a code paragraph; a table becomes one paragraph with a line per row.
 * Footnote markers are dropped, URL-only notes are dropped, prose notes are kept.
 *
 * Positions of chapter and page targets must survive normalization, which changes lengths. So a
 * marker (private-use characters around a number) is put where a target is, each paragraph is
 * normalized, and the markers' final positions are read and removed.
 */
class EpubTextBuilder(private val codeListings: CodeListings = CodeListings()) {

    private val markerStart = ''
    private val markerEnd = ''
    private val marker = Regex("$markerStart(\\d+)$markerEnd")

    private val blocks = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "blockquote", "li", "ul", "ol", "dl", "dt", "dd",
        "h1", "h2", "h3", "h4", "h5", "h6", "figure", "figcaption", "caption", "aside", "address", "hr", "body", "nav",
    )
    private val headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val skipped = setOf("script", "style", "head", "svg", "math", "audio", "video", "object", "iframe")
    private val noteTypes = setOf("footnote", "endnote", "rearnote", "note")
    private val url = Regex("^(https?://|www\\.)\\S*$", RegexOption.IGNORE_CASE)
    private val noteNumber = Regex("^\\[?\\d+\\]?[.)]?\\s*")

    /**
     * @param documents (zip path, XHTML) in reading order.
     * @param wanted target keys ("path#id" or "path#") whose offsets are needed.
     */
    fun build(documents: List<Pair<String, String>>, wanted: Set<String>): EpubText {
        val state = State(wanted)
        documents.forEach { (path, html) -> state.document(path, html) }
        state.finish()
        return EpubText(state.text.toString(), state.codeRanges, state.targets, state.leadingHeadings)
    }

    private inner class State(private val wanted: Set<String>) {
        val text = StringBuilder()
        val codeRanges = mutableListOf<IntRange>()
        val targets = HashMap<String, Int>()
        val leadingHeadings = HashMap<String, Pair<String, Int>>()

        private val pending = StringBuilder()
        private val markerKeys = mutableListOf<String>()
        /** Markers waiting for the next paragraph (e.g. a page break between blocks). */
        private val carried = mutableListOf<String>()
        private var path = ""
        private var firstBlockInDocument = true

        fun document(path: String, html: String) {
            flush()
            this.path = path
            firstBlockInDocument = true
            // EPUB content is XHTML: parse it as XML so empty elements like <span/> stay empty
            // (HTML parsing would open a span that swallows the rest of the document).
            val doc = Jsoup.parse(html, "", Parser.xmlParser())
            mark("$path#")
            walk(doc.selectFirst("body") ?: doc)
            flush()
        }

        fun finish() {
            flush()
            // Targets after the last text point at its end.
            (carried + markerKeys).forEach { targets.putIfAbsent(it, maxOf(0, text.length - 1)) }
        }

        private fun mark(key: String) {
            if (key !in wanted) return
            markerKeys += key
            pending.append(markerStart).append(markerKeys.size - 1).append(markerEnd)
        }

        private fun walk(node: Node) {
            when (node) {
                is TextNode -> pending.append(node.text())
                is Element -> element(node)
            }
        }

        private fun element(e: Element) {
            val tag = e.normalName()
            if (tag in skipped) return
            // Blocks start a new paragraph before their id marker, so the marker lands on their text.
            if (tag in blocks || tag == "pre" || tag == "table") flush()
            if (e.id().isNotEmpty()) mark("$path#${e.id()}")
            val types = (e.attr("epub:type").split(' ') + e.attr("role").removePrefix("doc-")).filter { it.isNotBlank() }.toSet()
            when {
                "pagebreak" in types -> return
                isNoteRef(e, types) -> return
                types.any { it in noteTypes } -> note(e)
                tag == "br" -> pending.append('\n')
                tag == "pre" -> code(e)
                tag == "table" -> table(e)
                tag in blocks -> {
                    flush()
                    e.childNodes().forEach(::walk)
                    flush(heading = tag in headings)
                }
                else -> e.childNodes().forEach(::walk)
            }
        }

        /** A link to a note: epub:type/role noteref, or a superscript link to a fragment. */
        private fun isNoteRef(e: Element, types: Set<String>): Boolean {
            if ("noteref" in types) return true
            if (e.normalName() != "a" || !e.attr("href").contains('#')) return false
            val inSup = e.parents().any { it.normalName() == "sup" } || e.children().any { it.normalName() == "sup" }
            return inSup && e.text().trim().matches(Regex("\\[?\\d+\\]?"))
        }

        private fun note(e: Element) {
            val copy = e.clone()
            copy.select("a").filter { a ->
                val t = (a.attr("epub:type") + " " + a.attr("role")).lowercase()
                "backlink" in t || a.text().trim() in setOf("↩", "↩︎", "↑", "^", "Back", "back")
            }.forEach { it.remove() }
            val content = noteNumber.replace(Normalizer.normalize(copy.text()).trim(), "")
            if (content.isEmpty() || url.matches(content)) return
            pending.append(content)
            flush()
        }

        private fun code(e: Element) {
            // The element's own id marker is already pending; it lands at the code's start.
            val raw = e.wholeText().trim('\n')
            val numbered = codeListings.clean(raw)
            val body = if (numbered.codeRanges.size == 1 && numbered.text.lines().size <= raw.lines().size) numbered.text else raw
            pending.append(body)
            flush(code = true)
        }

        private fun table(e: Element) {
            flush()
            val rows = e.select("tr").map { tr ->
                tr.children().filter { it.normalName() in setOf("td", "th") }.joinToString(" | ") { Normalizer.normalize(it.text()).trim() }
            }.filter { it.isNotBlank() }
            pending.append(rows.joinToString("\n"))
            flush()
        }

        /** Emits the pending text as one paragraph, resolving its markers to offsets. */
        fun flush(code: Boolean = false, heading: Boolean = false) {
            val raw = pending.toString()
            pending.setLength(0)
            val keys = markerKeys.toList()
            markerKeys.clear()
            val normalized = if (code) Normalizer.normalizeCode(raw) else Normalizer.normalize(raw)

            // Remove markers, remembering where each was; never leave two spaces behind.
            val positions = mutableListOf<Pair<String, Int>>()
            val out = StringBuilder()
            var last = 0
            marker.findAll(normalized).forEach { m ->
                out.append(normalized, last, m.range.first)
                positions += keys[m.groupValues[1].toInt()] to out.length
                last = m.range.last + 1
                if ((out.isEmpty() || out.last().isWhitespace()) && normalized.getOrNull(last) == ' ') last++
            }
            out.append(normalized, last, normalized.length)
            val cleaned = out.toString()
            if (cleaned.isBlank()) {
                carried += positions.map { it.first }
                return
            }
            val paragraph = if (code) cleaned.trimStart('\n').trimEnd() else cleaned.trim()
            val lead = if (code) cleaned.length - cleaned.trimStart('\n').length else cleaned.length - cleaned.trimStart().length

            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            carried.forEach { targets.putIfAbsent(it, start) }
            carried.clear()
            positions.forEach { (key, at) -> targets.putIfAbsent(key, start + (at - lead).coerceIn(0, paragraph.length - 1)) }
            text.append(paragraph)
            if (code) codeRanges += start until text.length
            if (heading && firstBlockInDocument) leadingHeadings[path] = paragraph to start
            firstBlockInDocument = false
        }
    }
}
