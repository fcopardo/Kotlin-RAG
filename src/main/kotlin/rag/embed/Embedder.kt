package rag.embed

import kotlin.math.sqrt

/** Produces L2-normalized embeddings (SPEC §8.1). */
interface Embedder {
    val model: String
    val dim: Int

    /** Embeds book paragraphs; applies the model's document prefix. */
    fun embedDocuments(texts: List<String>): List<FloatArray>

    /** Embeds a search query; applies the model's query prefix. */
    fun embedQuery(text: String): FloatArray

    /** Fails fast with a useful message when the backend can't be used. */
    fun checkAvailable() {}
}

/** Per-model settings. Some models need task prefixes; others use none. */
data class EmbeddingModel(
    val name: String,
    val documentPrefix: String = "",
    val queryPrefix: String = "",
) {
    companion object {
        val NOMIC_EMBED_TEXT = EmbeddingModel(
            name = "nomic-embed-text",
            documentPrefix = "search_document: ",
            queryPrefix = "search_query: ",
        )

        private val known = listOf(NOMIC_EMBED_TEXT).associateBy { it.name }

        /** Known config for [name], or one without prefixes. */
        fun forName(name: String): EmbeddingModel = known[name.substringBefore(':')] ?: EmbeddingModel(name)
    }
}

object Vectors {
    /** Returns a unit-length copy. A zero vector is returned unchanged. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        if (sum == 0.0) return v.copyOf()
        val inv = (1.0 / sqrt(sum)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
