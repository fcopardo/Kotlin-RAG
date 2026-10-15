package rag.embed

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WordPieceTokenizerTest {

    private val tiny = WordPieceTokenizer(
        listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", ",", "!", "un", "##aff", "##able", "cafe", "(", ")", "日")
            .withIndex().associate { (i, t) -> t to i },
        maxLength = 8,
    )

    @Test
    fun `lowercases, strips accents and splits punctuation`() {
        assertEquals(listOf("hello", ",", "world", "!"), tiny.tokens("Hello, WORLD!"))
        assertEquals(listOf("cafe"), tiny.tokens("Café"))
    }

    @Test
    fun `greedy longest match with continuation pieces`() {
        assertEquals(listOf("un", "##aff", "##able"), tiny.tokens("unaffable"))
    }

    @Test
    fun `an unmatched word becomes UNK, CJK characters are separate words`() {
        assertEquals(listOf("[UNK]", "日", "[UNK]"), tiny.tokens("xyz日本"))
    }

    @Test
    fun `encoding adds CLS and SEP and truncates to the max length`() {
        val e = tiny.encode("hello world hello world hello world hello world")

        assertEquals(8, e.ids.size)
        assertEquals(2L, e.ids.first())
        assertEquals(3L, e.ids.last())
        assertEquals(List(8) { 1L }, e.attentionMask.toList())
    }

    @Test
    fun `control characters are dropped and odd whitespace is a separator`() {
        assertEquals(listOf("hello", "world"), tiny.tokens("hello\u0000​ world"))
    }

    /** The real BERT-uncased vocabulary of mxbai-embed-large; set RAG_ONNX_MODEL_DIR to run. */
    @Test
    fun `real vocabulary matches BERT's reference tokenization`() {
        val dir = System.getenv("RAG_ONNX_MODEL_DIR")?.let(::File)
        assumeTrue(dir?.resolve("vocab.txt")?.isFile == true, "set RAG_ONNX_MODEL_DIR")
        val real = WordPieceTokenizer.fromVocabFile(dir!!.resolve("vocab.txt"))

        assertEquals(listOf("una", "##ffa", "##ble"), real.tokens("unaffable")) // BERT's own example
        assertEquals(listOf("hello", ",", "world", "!"), real.tokens("Hello, World!"))
        assertEquals(listOf("cafe", "cr", "##eme", "br", "##ule", "##e"), real.tokens("Café crème brûlée"))
        assertEquals(listOf("remember", "##cor", "##out", "##ines", "##cope", "(", ")"), real.tokens("rememberCoroutineScope()"))
        assertEquals(512, real.encode(List(600) { "word" }.joinToString(" ")).ids.size)
    }
}
