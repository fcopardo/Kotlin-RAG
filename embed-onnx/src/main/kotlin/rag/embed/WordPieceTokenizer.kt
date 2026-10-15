package rag.embed

import java.io.File
import java.text.Normalizer

/** Token ids for one text: `[CLS] … [SEP]`, at most [WordPieceTokenizer.maxLength] long. */
data class Encoding(val ids: LongArray, val attentionMask: LongArray)

/**
 * BERT-uncased WordPiece tokenizer in pure Kotlin (SPEC §15.2), as defined by the model's
 * tokenizer.json: BertNormalizer (clean text, space out CJK characters, lowercase, strip accents),
 * BertPreTokenizer (split on whitespace and punctuation), then greedy longest-match WordPiece with
 * `##` continuations. No native tokenizer library, so it runs on Android unchanged.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    val maxLength: Int = 512,
    private val maxCharsPerWord: Int = 100,
) {
    private val unk = id("[UNK]")
    private val cls = id("[CLS]")
    private val sep = id("[SEP]")

    private fun id(token: String) = vocab[token] ?: throw IllegalArgumentException("vocabulary has no $token")

    fun encode(text: String): Encoding {
        val pieces = preTokenize(normalize(text)).flatMap(::wordPiece)
        val ids = (listOf(cls) + pieces.take(maxLength - 2) + listOf(sep)).map { it.toLong() }.toLongArray()
        return Encoding(ids, LongArray(ids.size) { 1L })
    }

    /** The tokens of [text] without `[CLS]`/`[SEP]`, for tests and debugging. */
    fun tokens(text: String): List<String> {
        val reverse = vocab.entries.associate { (k, v) -> v to k }
        return preTokenize(normalize(text)).flatMap(::wordPiece).map { reverse.getValue(it) }
    }

    /** BertNormalizer: clean text, handle Chinese characters, lowercase, strip accents. */
    fun normalize(text: String): String {
        val cleaned = StringBuilder()
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> cleaned.append(' ')
                isCjk(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val lower = cleaned.toString().lowercase()
        return Normalizer.normalize(lower, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    /** BertPreTokenizer: whitespace separates words; each punctuation character is its own word. */
    fun preTokenize(text: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words += current.toString()
            current.setLength(0)
        }
        text.codePoints().forEach { cp ->
            when {
                isWhitespace(cp) -> flush()
                isPunctuation(cp) -> { flush(); words += String(Character.toChars(cp)) }
                else -> current.appendCodePoint(cp)
            }
        }
        flush()
        return words
    }

    /** Greedy longest-match-first; a word that can't be fully matched becomes `[UNK]`. */
    private fun wordPiece(word: String): List<Int> {
        if (word.codePointCount(0, word.length) > maxCharsPerWord) return listOf(unk)
        val out = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var match: Int? = null
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(start, end)
                vocab[piece]?.let { match = it } ?: run { end = word.offsetByCodePoints(end, -1) }
                if (match != null) break
            }
            val id = match ?: return listOf(unk)
            out += id
            start = end
        }
        return out
    }

    private fun isWhitespace(cp: Int) = cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
        Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        val t = Character.getType(cp)
        return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()
    }

    /** ASCII symbols count as punctuation in BERT, besides Unicode punctuation categories. */
    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
        cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    companion object {
        /** Reads a vocab.txt: one token per line, the line number is its id. */
        fun fromVocabFile(file: File, maxLength: Int = 512) =
            WordPieceTokenizer(file.readLines().withIndex().associate { (i, token) -> token to i }, maxLength)
    }
}
