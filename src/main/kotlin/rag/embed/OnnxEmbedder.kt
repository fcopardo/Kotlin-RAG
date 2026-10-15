package rag.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/** How token vectors become one text vector. mxbai-embed-large uses CLS; nomic-embed-text uses mean. */
enum class Pooling { CLS, MEAN }

/**
 * [Embedder] that runs an ONNX model in-process with ONNX Runtime (SPEC §15.2): the path to
 * on-device embeddings. Prefixes, truncation and quantization stay as in §5.6.
 *
 * [model] is the name stored with a book's vectors. Use the Ollama name ("mxbai-embed-large") only
 * for a build that passed the parity test (§15.4); otherwise name the variant, e.g.
 * "mxbai-embed-large@onnx-int8", so its vectors are never mixed with others.
 */
class OnnxEmbedder(
    modelFile: File,
    private val tokenizer: WordPieceTokenizer,
    private val config: EmbeddingModel,
    override val model: String,
    private val pooling: Pooling = Pooling.CLS,
    private val batchSize: Int = 8,
) : Embedder, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelFile.path, OrtSession.SessionOptions())

    override val dim: Int by lazy { embed(listOf("dimension probe")).single().size }

    override fun embedDocuments(texts: List<String>): List<FloatArray> =
        texts.map { config.documentPrefix + it }.chunked(batchSize).flatMap(::embed)

    override fun embedQuery(text: String): FloatArray = embed(listOf(config.queryPrefix + text)).single()

    private fun embed(texts: List<String>): List<FloatArray> {
        val encodings = texts.map(tokenizer::encode)
        val length = encodings.maxOf { it.ids.size }
        // Pad to the longest text of the batch; padding has attention 0.
        fun tensor(values: (Encoding) -> LongArray) = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(LongArray(texts.size * length).also { flat ->
                encodings.forEachIndexed { b, e -> values(e).copyInto(flat, b * length) }
            }),
            longArrayOf(texts.size.toLong(), length.toLong()),
        )
        val inputs = buildMap {
            put("input_ids", tensor { it.ids })
            put("attention_mask", tensor { it.attentionMask })
            if ("token_type_ids" in session.inputNames) put("token_type_ids", tensor { LongArray(it.ids.size) })
        }
        try {
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = result.get(0).value as Array<Array<FloatArray>> // [batch][token][hidden]
                return hidden.mapIndexed { b, tokens ->
                    val v = when (pooling) {
                        Pooling.CLS -> tokens[0].copyOf()
                        Pooling.MEAN -> {
                            val n = encodings[b].ids.size
                            FloatArray(tokens[0].size) { d -> (0 until n).sumOf { tokens[it][d].toDouble() }.toFloat() / n }
                        }
                    }
                    Vectors.l2Normalize(v)
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()
}
