package rag.document

import rag.BookFormat
import rag.BookSource
import rag.RagException
import rag.ReadOptions
import rag.StructuredBook
import rag.extract.CodeListings
import rag.extract.DocumentCharset
import rag.extract.Normalizer
import java.io.File

/**
 * Plain text and Markdown (SPEC §18.4). Markdown is read as the readable text it is: headings give
 * the sections, fenced blocks and indented blocks are code, and inline marks are left in place.
 * A file with no headings is divided by topic instead ([TextTiling]).
 */
class TextBookSource(private val documents: DocumentBuilder = DocumentBuilder()) : BookSource {
    override val format = BookFormat.TEXT
    override val extractor = "text/norm-v1"

    override fun read(file: File, options: ReadOptions): StructuredBook {
        val raw = DocumentCharset.read(file)
        if (raw.isBlank()) throw RagException.BookUnreadable("Empty document: $file")
        val parsed = MarkdownText.parse(raw)
        if (parsed.text.isBlank()) throw RagException.BookUnreadable("Document has no text: $file")
        return documents.build(
            parsed.text, parsed.title ?: file.nameWithoutExtension, format, extractor,
            parsed.headings, parsed.codeRanges, options,
        )
    }
}

/** A document's text with the headings and code blocks found in it. */
data class ParsedText(val text: String, val title: String?, val headings: List<Heading>, val codeRanges: List<IntRange>)

/**
 * Markdown as plain text. Only what changes the *structure* is interpreted: ATX headings (`#`),
 * Setext headings (an `===` or `---` underline) and fenced code (``` or ~~~). Inline marks like
 * `**bold**` stay, because they read fine and removing them would shift every offset.
 */
object MarkdownText {

    private val atx = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val setext = Regex("^(=+|-{2,})\\s*$")
    private val fence = Regex("^\\s*(```|~~~)(.*)$")

    fun parse(raw: String): ParsedText {
        val lines = Normalizer.normalize(raw).split('\n')
        val out = StringBuilder()
        val headings = mutableListOf<Heading>()
        val codeRanges = mutableListOf<IntRange>()
        val code = mutableListOf<String>()
        var fenced = false

        fun flushCode() {
            if (code.isEmpty()) return
            val body = Normalizer.normalizeCode(code.joinToString("\n")).trim('\n')
            code.clear()
            if (body.isBlank()) return
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length
            out.append(body)
            codeRanges += start until out.length
        }

        fun append(paragraph: String) {
            if (paragraph.isBlank()) return
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(paragraph.trim())
        }

        val paragraph = StringBuilder()
        fun flushParagraph() {
            append(paragraph.toString())
            paragraph.setLength(0)
        }

        lines.forEachIndexed { i, line ->
            val fenceMatch = fence.matchEntire(line)
            when {
                fenceMatch != null -> {
                    if (fenced) flushCode() else flushParagraph()
                    fenced = !fenced
                }
                fenced -> code += line
                else -> {
                    val heading = atx.matchEntire(line)
                    // A Setext underline turns the line above into a heading.
                    val underline = setext.matchEntire(line) != null && paragraph.isNotBlank() && !paragraph.contains('\n')
                    when {
                        heading != null -> {
                            flushParagraph()
                            val title = heading.groupValues[2].trim()
                            if (title.isNotEmpty()) {
                                if (out.isNotEmpty()) out.append("\n\n")
                                headings += Heading(title, heading.groupValues[1].length, out.length)
                                out.append(title)
                            }
                        }
                        underline -> {
                            val title = paragraph.toString().trim()
                            paragraph.setLength(0)
                            if (out.isNotEmpty()) out.append("\n\n")
                            headings += Heading(title, if (line.startsWith("=")) 1 else 2, out.length)
                            out.append(title)
                        }
                        line.isBlank() -> flushParagraph()
                        else -> paragraph.append(if (paragraph.isEmpty()) "" else "\n").append(line)
                    }
                }
            }
            if (i == lines.lastIndex) {
                flushParagraph()
                flushCode()
            }
        }
        // The title is a lone top heading at the very start, as in most Markdown documents.
        val title = headings.firstOrNull()?.takeIf { it.offset == 0 && it.level == 1 }?.title
        return ParsedText(out.toString(), title, headings, codeRanges)
    }
}

