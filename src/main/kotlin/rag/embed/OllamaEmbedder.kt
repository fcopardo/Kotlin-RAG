package rag.embed

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import rag.RagException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [Embedder] backed by a local Ollama server (`POST /api/embed`), SPEC §5.6 and §8.1.
 *
 * Each batch is retried up to [maxAttempts] times with exponential backoff (1s, 2s, 4s…) on
 * network errors and 5xx responses. A 4xx (e.g. model not pulled) fails immediately.
 */
class OllamaEmbedder(
    private val config: EmbeddingModel = EmbeddingModel.NOMIC_EMBED_TEXT,
    baseUrl: String = DEFAULT_URL,
    private val batchSize: Int = 32,
    private val maxAttempts: Int = 3,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : Embedder {

    private val base = baseUrl.trimEnd('/')

    override val model: String get() = config.name

    /** Found by embedding a probe string, so it always matches what the server returns. */
    override val dim: Int by lazy { embedBatch(listOf("dimension probe")).single().size }

    /**
     * Checks that the server is reachable and the model is pulled.
     * Call before a long indexing run so it fails fast with a useful hint.
     */
    override fun checkAvailable() {
        val response = send(HttpRequest.newBuilder(URI.create("$base/api/tags")).GET())
        if (response.statusCode() != 200) {
            throw RagException.EmbeddingBackendUnavailable("Ollama at $base returned HTTP ${response.statusCode()} for /api/tags")
        }
        val tags = decode<TagsResponse>(response.body())
        val pulled = tags.models.any { it.name == model || it.name.substringBefore(':') == model }
        if (!pulled) {
            throw RagException.EmbeddingBackendUnavailable("Model '$model' is not pulled in Ollama. Run: ollama pull $model")
        }
    }

    override fun embedDocuments(texts: List<String>): List<FloatArray> =
        texts.map { config.documentPrefix + it }
            .chunked(batchSize)
            .flatMap(::embedBatch)

    override fun embedQuery(text: String): FloatArray = embedBatch(listOf(config.queryPrefix + text)).single()

    private fun embedBatch(inputs: List<String>): List<FloatArray> {
        val body = json.encodeToString(EmbedRequest(model, inputs))
        val request = HttpRequest.newBuilder(URI.create("$base/api/embed"))
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))

        var lastError: String? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) sleep(1000L shl (attempt - 1))
            val response = try {
                http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                lastError = "cannot reach Ollama at $base (${e.message ?: e.javaClass.simpleName})"
                continue
            }
            when (response.statusCode()) {
                200 -> return parseEmbeddings(response.body(), inputs.size)
                in 400..499 -> throw RagException.EmbeddingBackendUnavailable(
                    "Ollama rejected the request (HTTP ${response.statusCode()}): ${errorMessage(response.body())}" +
                        if (response.statusCode() == 404) ". Run: ollama pull $model" else ""
                )
                else -> lastError = "HTTP ${response.statusCode()}: ${errorMessage(response.body())}"
            }
        }
        throw RagException.EmbeddingBackendUnavailable("Embedding failed after $maxAttempts attempts: $lastError")
    }

    private fun parseEmbeddings(body: String, expected: Int): List<FloatArray> {
        val embeddings = decode<EmbedResponse>(body).embeddings
        if (embeddings.size != expected) {
            throw RagException.EmbeddingBackendUnavailable("Ollama returned ${embeddings.size} embeddings for $expected inputs")
        }
        if (embeddings.map { it.size }.distinct().size > 1) {
            throw RagException.EmbeddingBackendUnavailable("Ollama returned embeddings of different dimensions")
        }
        return embeddings.map { Vectors.l2Normalize(it.toFloatArray()) }
    }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> = try {
        http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
        throw RagException.EmbeddingBackendUnavailable(
            "Cannot reach Ollama at $base. Is it running? (ollama serve)", e
        )
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw RagException.EmbeddingBackendUnavailable("Unexpected response from Ollama: ${body.take(200)}", e)
    } catch (e: IllegalArgumentException) {
        throw RagException.EmbeddingBackendUnavailable("Unexpected response from Ollama: ${body.take(200)}", e)
    }

    private fun errorMessage(body: String): String =
        runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull() ?: body.take(200)

    @Serializable
    private data class EmbedRequest(val model: String, val input: List<String>)

    @Serializable
    private data class EmbedResponse(val embeddings: List<List<Float>>)

    @Serializable
    private data class TagsResponse(val models: List<Tag> = emptyList())

    @Serializable
    private data class Tag(val name: String)

    @Serializable
    private data class ErrorResponse(val error: String)

    companion object {
        const val DEFAULT_URL = "http://localhost:11434"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
