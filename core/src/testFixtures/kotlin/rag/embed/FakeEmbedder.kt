package rag.embed

/**
 * Deterministic test [Embedder]: a hashed bag of words. Texts that share words get similar
 * vectors, so search tests behave sensibly without Ollama. It knows nothing about meaning.
 */
class FakeEmbedder(
    override val dim: Int = 768, // like nomic-embed-text, so the default 512-dim truncation fits
    override val model: String = "fake-bow",
) : Embedder {
    private val word = Regex("\\p{L}+")

    /** Inputs seen by [embedDocuments], one list per call, for asserting batching and resume. */
    val documentCalls = mutableListOf<List<String>>()

    override fun embedDocuments(texts: List<String>): List<FloatArray> {
        documentCalls += texts
        return texts.map(::vector)
    }

    override fun embedQuery(text: String): FloatArray = vector(text)

    private fun vector(text: String): FloatArray {
        val v = FloatArray(dim)
        word.findAll(text.lowercase()).forEach {
            val h = it.value.hashCode()
            v[Math.floorMod(h, dim)] += if ((h ushr 31) == 0) 1f else -1f
        }
        return Vectors.l2Normalize(v)
    }
}
