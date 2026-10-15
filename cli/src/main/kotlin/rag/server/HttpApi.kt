package rag.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import rag.RagException
import rag.search.Mode
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/** POST /search body; the same fields as GET /search's query parameters. */
@Serializable
data class SearchRequest(
    val query: String,
    val k: Int = 5,
    val book: Long? = null,
    val subject: String? = null,
    val mode: String = "hybrid",
    /** Passages (few tokens, for language models) instead of full hits. */
    val compact: Boolean = false,
)

/**
 * A small JSON API over [RagService] on the JDK's HTTP server (SPEC §17). Read-only, and bound to
 * localhost unless told otherwise.
 *
 * - `GET /health`
 * - `GET /books`, `GET /books/{id}/chapters`, `GET /books/{id}/chapters/{n}?offset=`
 * - `GET /search?q=…&k=&book=&subject=&mode=&compact=`, `POST /search` with a [SearchRequest]
 */
class HttpApi(private val service: RagService, host: String, port: Int) : AutoCloseable {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
        // One request at a time: the service has one SQLite connection.
        executor = Executors.newSingleThreadExecutor()
        createContext("/") { exchange -> exchange.use { respond(it) } }
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdown()
    }

    private val chapterPath = Regex("^/books/(\\d+)/chapters(?:/(\\d+))?/?$")

    private fun respond(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val query = params(exchange.requestURI.rawQuery)
        val (status, body) = try {
            when {
                path == "/health" -> 200 to buildJsonObject { put("status", "ok"); put("books", service.books().size) }
                path == "/books" -> 200 to json.encodeToJsonElement(service.books())
                path == "/search" -> 200 to search(
                    if (exchange.requestMethod == "POST") json.decodeFromString<SearchRequest>(exchange.requestBody.readBytes().decodeToString())
                    else SearchRequest(
                        query = query["q"] ?: throw IllegalArgumentException("q is required"),
                        k = query["k"]?.toInt() ?: 5,
                        book = query["book"]?.toLong(),
                        subject = query["subject"]?.takeIf { it.isNotBlank() },
                        mode = query["mode"] ?: "hybrid",
                        compact = query["compact"] == "true",
                    ),
                )
                chapterPath.matches(path) -> {
                    val (book, chapter) = chapterPath.matchEntire(path)!!.destructured
                    200 to if (chapter.isEmpty()) json.encodeToJsonElement(service.chapters(book.toLong()))
                    else json.encodeToJsonElement(service.readChapter(book.toLong(), chapter.toInt(), query["offset"]?.toInt() ?: 0))
                }
                else -> 404 to problem("no such endpoint: $path")
            }
        } catch (e: NoSuchElementException) {
            404 to problem(e.message)
        } catch (e: IllegalArgumentException) {
            400 to problem(e.message) // also bad numbers and JSON
        } catch (e: kotlinx.serialization.SerializationException) {
            400 to problem(e.message)
        } catch (e: RagException) {
            503 to problem(e.message)
        } catch (e: Exception) {
            System.err.println("Request $path failed: $e")
            500 to problem(e.message ?: e.javaClass.simpleName)
        }
        val bytes = body.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun search(r: SearchRequest) = Mode.valueOf(r.mode.uppercase()).let { mode ->
        if (r.compact) json.encodeToJsonElement(service.passages(r.query, r.k, r.book, r.subject, mode))
        else json.encodeToJsonElement(service.search(r.query, r.k, r.book, r.subject, mode))
    }

    private fun problem(message: String?) = JsonObject(mapOf("error" to JsonPrimitive(message ?: "error")))

    private fun params(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }
}
