package rag.summarize

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rag.RagException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [Summarizer] backed by Ollama `POST /api/generate` (SPEC §5.7.1): temperature 0, seed 42,
 * 4,096-token context, at most 2 × the word cap in tokens. Empty answers and 5xx/network errors
 * are retried 3 times with backoff 1s, 2s, 4s; a 4xx (e.g. model not pulled) fails at once.
 */
class OllamaSummarizer(
    override val model: String = DEFAULT_MODEL,
    baseUrl: String = "http://localhost:11434",
    private val maxAttempts: Int = 3,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : Summarizer {
    private val base = baseUrl.trimEnd('/')

    override fun checkAvailable() {
        val response = try {
            http.send(HttpRequest.newBuilder(URI.create("$base/api/tags")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw RagException.EmbeddingBackendUnavailable("Cannot reach Ollama at $base. Is it running? (ollama serve)", e)
        }
        val tags = runCatching { json.decodeFromString<Tags>(response.body()) }.getOrNull()
        // "llama3.2:3b" must match exactly; a name without a tag ("gemma3") matches any of its tags.
        val pulled = tags?.models?.any { it.name == model || (':' !in model && it.name.substringBefore(':') == model) } == true
        if (!pulled) {
            throw RagException.EmbeddingBackendUnavailable("Model '$model' is not pulled in Ollama. Run: ollama pull $model")
        }
    }

    override fun summarize(task: SummaryTask): String = generate(Prompts.SYSTEM, Prompts.user(task), task.maxWords * 2)

    override fun complete(system: String, prompt: String, maxTokens: Int): String = generate(system, prompt, maxTokens)

    private fun generate(system: String, prompt: String, numPredict: Int): String {
        val body = json.encodeToString(Request(model, system, prompt, stream = false,
            options = Options(temperature = 0.0, seed = 42, numCtx = 4096, numPredict = numPredict)))
        var last: String? = null
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) sleep(1000L shl (attempt - 1))
            val response = try {
                http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/generate")).timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (e: IOException) {
                last = "cannot reach Ollama at $base (${e.message ?: e.javaClass.simpleName})"
                continue
            }
            when (response.statusCode()) {
                200 -> {
                    val text = runCatching { json.decodeFromString<Response>(response.body()).response }.getOrNull()?.trim()
                    if (!text.isNullOrEmpty()) return text
                    last = "empty summary"
                }
                in 400..499 -> throw RagException.EmbeddingBackendUnavailable(
                    "Ollama rejected the request (HTTP ${response.statusCode()}): ${response.body().take(200)}" +
                        if (response.statusCode() == 404) ". Run: ollama pull $model" else ""
                )
                else -> last = "HTTP ${response.statusCode()}: ${response.body().take(200)}"
            }
        }
        throw RagException.EmbeddingBackendUnavailable("Summarizing failed after $maxAttempts attempts: $last")
    }

    @Serializable
    private data class Options(
        val temperature: Double,
        val seed: Int,
        @kotlinx.serialization.SerialName("num_ctx") val numCtx: Int,
        @kotlinx.serialization.SerialName("num_predict") val numPredict: Int,
    )

    @Serializable
    private data class Request(val model: String, val system: String, val prompt: String, val stream: Boolean, val options: Options)

    @Serializable
    private data class Response(val response: String = "")

    @Serializable
    private data class Tags(val models: List<Tag> = emptyList())

    @Serializable
    private data class Tag(val name: String)

    companion object {
        const val DEFAULT_MODEL = "llama3.2:3b"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
