package rag.embed

import ai.onnxruntime.OrtSession
import java.io.File

/**
 * An ONNX build of an embedding model, as files in a directory (SPEC §15.4, §15.9).
 *
 * [storedName] is the model name recorded with the vectors. It's the Ollama name only when the
 * build passed the parity test against Ollama (average cosine ≥ 0.99); otherwise it names the
 * variant, so its vectors are never mixed with Ollama's.
 */
data class OnnxModel(
    val config: EmbeddingModel,
    val storedName: String,
    val pooling: Pooling,
    /** Hugging Face repository the files come from, and the usual directory name under models/. */
    val repository: String,
    val modelFile: String = "onnx/model_quantized.onnx",
    val vocabFile: String = "vocab.txt",
) {
    val directoryName: String get() = repository.substringAfter('/')

    fun open(dir: File, sessionOptions: OrtSession.SessionOptions = OrtSession.SessionOptions()): OnnxEmbedder {
        val model = dir.resolve(modelFile)
        val vocab = dir.resolve(vocabFile)
        require(model.isFile && vocab.isFile) { "$dir needs $modelFile and $vocabFile (from huggingface.co/$repository)" }
        return OnnxEmbedder(model, WordPieceTokenizer.fromVocabFile(vocab), config, storedName, pooling, sessionOptions = sessionOptions)
    }
}

object OnnxModels {
    /** int8 matches Ollama at cosine 0.997: shares vectors with Ollama-built indexes. */
    val MXBAI_EMBED_LARGE_INT8 = OnnxModel(
        EmbeddingModel.MXBAI_EMBED_LARGE, "mxbai-embed-large", Pooling.CLS, "mixedbread-ai/mxbai-embed-large-v1",
    )

    /**
     * int8 is at cosine 0.974 to Ollama (q4: 0.856), under the 0.99 bar: its vectors are their own
     * model, built on the desktop with this same file (`rag embed --onnx-dir`), not by Ollama.
     */
    val NOMIC_EMBED_TEXT_INT8 = OnnxModel(
        EmbeddingModel.NOMIC_EMBED_TEXT, "nomic-embed-text@onnx-int8", Pooling.MEAN, "nomic-ai/nomic-embed-text-v1.5",
    )

    val ALL = listOf(MXBAI_EMBED_LARGE_INT8, NOMIC_EMBED_TEXT_INT8)

    /** The build for a model name: a stored name ("nomic-embed-text@onnx-int8") or a plain one ("nomic-embed-text"). */
    fun forName(name: String): OnnxModel? =
        ALL.firstOrNull { it.storedName == name } ?: ALL.firstOrNull { it.config.name == name.substringBefore('@') }
}
