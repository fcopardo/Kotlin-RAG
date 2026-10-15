package rag.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import rag.RagException
import rag.search.Mode
import java.io.BufferedReader
import java.io.Writer

/**
 * A Model Context Protocol server over stdio (SPEC §17): one JSON-RPC 2.0 message per line in and
 * out. stdout carries only protocol messages; warnings go to stderr. Tools are read-only.
 */
class McpServer(
    private val service: RagService,
    private val input: BufferedReader,
    private val output: Writer,
    private val version: String = "0.1.0",
) {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    fun run() {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            handle(line)?.let {
                output.write(it.toString())
                output.write("\n")
                output.flush()
            }
        }
    }

    /** The response to one message, or null for notifications. */
    fun handle(line: String): JsonObject? {
        val message = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return error(JsonNull, -32700, "Parse error: ${e.message}")
        }
        val id = message["id"] ?: return null // a notification, e.g. notifications/initialized
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (val method = (message["method"] as? JsonPrimitive)?.content) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", TOOLS) })
            "tools/call" -> result(id, call(params))
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject = buildJsonObject {
        // Answer with the client's protocol version when it's one this server follows.
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        put("protocolVersion", requested?.takeIf { it in PROTOCOL_VERSIONS } ?: PROTOCOL_VERSIONS.last())
        putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
        putJsonObject("serverInfo") {
            put("name", "rag")
            put("version", version)
        }
        put("instructions", "Search the user's local library of indexed books (PDF and EPUB). Use search_books " +
            "for questions about their content and cite book, chapter and pages. Use list_books to see what's there.")
    }

    /** Runs a tool. Failures (bad arguments, unknown book) are tool results with isError, as MCP asks. */
    private fun call(params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.content
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            val value: JsonElement = when (name) {
                "search_books" -> buildJsonObject {
                    put("passages", json.encodeToJsonElement(service.passages(
                        query = args.string("query") ?: throw IllegalArgumentException("query is required"),
                        k = args.int("k") ?: 5,
                        bookId = args.long("book_id"),
                        subject = args.string("subject")?.takeIf { it.isNotBlank() },
                        mode = args.string("mode")?.let { Mode.valueOf(it.uppercase()) } ?: Mode.HYBRID,
                    )))
                }
                "list_books" -> buildJsonObject { put("books", json.encodeToJsonElement(service.books())) }
                "list_chapters" -> buildJsonObject {
                    put("chapters", json.encodeToJsonElement(service.chapters(args.long("book_id") ?: throw IllegalArgumentException("book_id is required"))))
                }
                "read_chapter" -> json.encodeToJsonElement(service.readChapter(
                    args.long("book_id") ?: throw IllegalArgumentException("book_id is required"),
                    args.int("chapter") ?: throw IllegalArgumentException("chapter is required"),
                    args.int("offset") ?: 0,
                ))
                else -> throw IllegalArgumentException("unknown tool: $name")
            }
            buildJsonObject {
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", value.toString()) }) }
                put("structuredContent", value)
                put("isError", false)
            }
        } catch (e: Exception) {
            if (e !is IllegalArgumentException && e !is NoSuchElementException && e !is RagException) {
                System.err.println("Tool $name failed: $e")
            }
            buildJsonObject {
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", e.message ?: e.javaClass.simpleName) }) }
                put("isError", true)
            }
        }
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    // Small models send optional arguments as "null", "none" or "", and numbers as strings ("k": "10"):
    // read those as absent and as numbers, instead of filtering by a subject called "null".
    private fun JsonObject.present(key: String) = (this[key] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull || it.content.isBlank() || it.content.lowercase() in setOf("null", "none") }
    private fun JsonObject.string(key: String) = present(key)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String) = present(key)?.intOrNull
    private fun JsonObject.long(key: String) = present(key)?.longOrNull

    companion object {
        /** Protocol versions this server follows, oldest first. */
        val PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18")

        private fun schema(required: List<String>, vararg properties: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { properties.forEach { (name, p) -> put(name, p) } }
            putJsonArray("required") { required.forEach { add(it) } }
        }

        private fun prop(type: String, description: String, extra: JsonObject.() -> JsonObject = { this }) =
            buildJsonObject { put("type", type); put("description", description) }.extra()

        private fun tool(name: String, title: String, description: String, input: JsonObject) = buildJsonObject {
            put("name", name)
            put("title", title)
            put("description", description)
            put("inputSchema", input)
            putJsonObject("annotations") { put("readOnlyHint", true) }
        }

        val TOOLS = buildJsonArray {
            add(tool(
                "search_books", "Search books",
                "Find passages in the user's indexed books (PDF and EPUB) that answer a question. Works with " +
                    "questions, keywords or a sentence from a book. Each passage comes with its book, chapter and " +
                    "pages: quote it and cite them. Use list_books first to see what's available or to pick a subject.",
                schema(
                    listOf("query"),
                    "query" to prop("string", "What to look for: a question, keywords, or a sentence."),
                    "k" to prop("integer", "Number of passages, 1 to 20. Default 5.") { JsonObject(this + ("minimum" to JsonPrimitive(1)) + ("maximum" to JsonPrimitive(20))) },
                    "subject" to prop("string", "Only books about this subject, e.g. \"android\" or \"military strategy\" (matched by name or meaning)."),
                    "book_id" to prop("integer", "Only this book (ids from list_books)."),
                    "mode" to prop("string", "hybrid (default), lexical (keywords only) or vector (meaning only).") {
                        JsonObject(this + ("enum" to buildJsonArray { add("hybrid"); add("lexical"); add("vector") }))
                    },
                ),
            ))
            add(tool(
                "list_books", "List books",
                "List the indexed books: id, title, format, number of chapters and subjects (main, tangential, broad).",
                schema(emptyList()),
            ))
            add(tool(
                "list_chapters", "List chapters",
                "List a book's chapters with their numbers and pages, to read one with read_chapter.",
                schema(listOf("book_id"), "book_id" to prop("integer", "Book id from list_books.")),
            ))
            add(tool(
                "read_chapter", "Read a chapter",
                "Read a chapter's text, up to 12,000 characters at a time. When nextOffset is set, call again " +
                    "with that offset for the rest. Prefer search_books for specific questions.",
                schema(
                    listOf("book_id", "chapter"),
                    "book_id" to prop("integer", "Book id from list_books."),
                    "chapter" to prop("integer", "Chapter number from list_chapters (0 is front matter)."),
                    "offset" to prop("integer", "Character offset to continue from (nextOffset of the previous call)."),
                ),
            ))
        }
    }
}
