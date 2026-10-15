package rag.structure

import rag.extract.ExtractedBook
import java.util.Locale

/** A phrase's inclusive char range in the book text. */
data class PhraseSpan(val start: Int, val end: Int)

/** A paragraph's inclusive char range in the book text, with its phrases. */
data class ParagraphSpan(val start: Int, val end: Int, val isCode: Boolean, val phrases: List<PhraseSpan>)

data class SegmentedChapter(val chapter: Chapter, val paragraphs: List<ParagraphSpan>)

/**
 * Splits chapters into paragraphs and phrases (SPEC §5.4–5.5). Pure: works on the book text.
 *
 * - A paragraph ends at a blank line and never crosses a chapter.
 * - Prose phrases are sentences: a `.`, `?`, `!` or `…` (plus closing quotes or brackets), then
 *   whitespace, then an uppercase letter, digit or opening quote/bracket. An abbreviation list keeps
 *   "Dr. Lee" one sentence. A code listing's phrases are its lines.
 *   Not java.text.BreakIterator: the JDK's and Android's (ICU) disagree, and an index must come
 *   out the same on both (SPEC §15.8). [locale] is kept for callers; these rules are language-neutral.
 * - A paragraph over [maxTokens] (≈ words × 1.3) is split between phrases. A single phrase over
 *   the cap is split between words.
 *
 * Every span points at the exact book text: `text.substring(start, end + 1)` is the unit's text,
 * with no leading or trailing whitespace.
 */
class Segmenter(@Suppress("UNUSED_PARAMETER") locale: Locale = Locale.ENGLISH, private val maxTokens: Int = 400) {

    private val blankLine = Regex("\\n[ \\t]*\\n")
    private val word = Regex("\\S+")

    fun segment(book: ExtractedBook, chapters: List<Chapter>): List<SegmentedChapter> =
        chapters.map { SegmentedChapter(it, paragraphs(book, it.charStart, it.charEnd)) }

    /** Paragraphs in `book.text[from..to]` (inclusive). */
    fun paragraphs(book: ExtractedBook, from: Int, to: Int): List<ParagraphSpan> {
        val text = book.text
        val blocks = mutableListOf<IntRange>()
        var start = from
        blankLine.findAll(text.subSequence(0, to + 1), from).forEach { gap ->
            blocks += start until gap.range.first
            start = gap.range.last + 1
        }
        blocks += start..to

        return blocks.mapNotNull { trim(text, it) }.flatMap { block ->
            val isCode = book.codeRanges.any { it.first <= block.last && block.first <= it.last }
            val pieces = if (isCode) lines(text, block) else sentences(text, block)
            pack(text, pieces).map { group ->
                ParagraphSpan(group.first().start, group.last().end, isCode, group)
            }
        }
    }

    // --- phrases ---

    private fun sentences(text: String, block: IntRange): List<PhraseSpan> {
        val spans = mutableListOf<PhraseSpan>()
        var s = block.first
        var i = block.first
        while (i <= block.last) {
            if (text[i] !in terminators) { i++; continue }
            var end = i
            while (end < block.last && (text[end + 1] in terminators || text[end + 1] in closers)) end++
            var next = end + 1
            while (next <= block.last && text[next].isWhitespace()) next++
            if (next > end + 1 && next <= block.last && startsSentence(text[next])) {
                trim(text, s..end)?.let { spans += PhraseSpan(it.first, it.last) }
                s = next
            }
            i = next
        }
        trim(text, s..block.last)?.let { spans += PhraseSpan(it.first, it.last) }
        return mergeAbbreviations(text, spans)
    }

    private val terminators = ".?!…"
    private val closers = "\"'”’)]»"
    private fun startsSentence(c: Char) = c.isUpperCase() || c.isDigit() || c in "\"'“‘([«¿¡"

    private fun lines(text: String, block: IntRange): List<PhraseSpan> {
        val spans = mutableListOf<PhraseSpan>()
        var s = block.first
        while (s <= block.last) {
            val nl = text.indexOf('\n', s).let { if (it == -1 || it > block.last) block.last + 1 else it }
            trim(text, s until nl)?.let { spans += PhraseSpan(it.first, it.last) }
            s = nl + 1
        }
        return spans
    }

    private val abbreviations = setOf(
        "dr.", "mr.", "mrs.", "ms.", "prof.", "st.", "sr.", "jr.", "vs.", "e.g.", "i.e.", "fig.",
        "figs.", "no.", "cf.", "approx.", "u.s.", "inc.", "ltd.", "vol.", "ch.", "sec.", "p.", "pp.",
    )
    private val initial = Regex("^\\p{Lu}\\.$")

    /** The rules split after "Dr." or an initial like "J."; join those with the next sentence. */
    private fun mergeAbbreviations(text: String, spans: List<PhraseSpan>): List<PhraseSpan> {
        val merged = mutableListOf<PhraseSpan>()
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && endsWithAbbreviation(text.substring(last.start, last.end + 1))) {
                merged[merged.size - 1] = PhraseSpan(last.start, span.end)
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun endsWithAbbreviation(phrase: String): Boolean {
        val lastWord = phrase.substringAfterLast(' ').trimStart('(', '"', '“', '\'')
        return lastWord.lowercase() in abbreviations || initial.matches(lastWord)
    }

    // --- size cap ---

    private fun tokens(text: String, span: PhraseSpan): Double =
        word.findAll(text.subSequence(span.start, span.end + 1)).count() * 1.3

    /** Greedily groups consecutive phrases into paragraphs of at most [maxTokens]. */
    private fun pack(text: String, phrases: List<PhraseSpan>): List<List<PhraseSpan>> {
        val groups = mutableListOf<MutableList<PhraseSpan>>()
        var size = 0.0
        phrases.flatMap { splitLong(text, it) }.forEach { phrase ->
            val t = tokens(text, phrase)
            if (groups.isEmpty() || size + t > maxTokens) {
                groups += mutableListOf(phrase)
                size = t
            } else {
                groups.last() += phrase
                size += t
            }
        }
        return groups
    }

    /** Splits a phrase longer than the cap between words. */
    private fun splitLong(text: String, phrase: PhraseSpan): List<PhraseSpan> {
        if (tokens(text, phrase) <= maxTokens) return listOf(phrase)
        val maxWords = (maxTokens / 1.3).toInt().coerceAtLeast(1)
        return word.findAll(text.subSequence(0, phrase.end + 1), phrase.start).toList()
            .chunked(maxWords)
            .map { PhraseSpan(it.first().range.first, it.last().range.last) }
    }

    /** The range without leading/trailing whitespace, or null if it's all whitespace. */
    private fun trim(text: String, range: IntRange): IntRange? {
        var s = range.first
        var e = range.last
        while (s <= e && text[s].isWhitespace()) s++
        while (e >= s && text[e].isWhitespace()) e--
        return if (s <= e) s..e else null
    }
}
