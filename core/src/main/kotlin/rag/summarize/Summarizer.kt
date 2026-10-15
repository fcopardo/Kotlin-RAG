package rag.summarize


enum class SummaryLevel(val db: String) { SECTION("section"), GROUP("group"), CHAPTER("chapter"), BOOK("book") }

/** One summarization request (SPEC §8.1). [inputs] are raw text (section) or summaries (other levels). */
data class SummaryTask(
    val level: SummaryLevel,
    val bookTitle: String,
    val chapterTitle: String?,
    val inputs: List<String>,
    val maxWords: Int,
)

/** Makes one summary. Returns the model's raw output; [SummaryText.clean] tidies it. */
interface Summarizer {
    val model: String
    fun checkAvailable() {}
    fun summarize(task: SummaryTask): String

    /** A free-form prompt, e.g. for book subjects (SPEC §8.5). Not every summarizer supports it. */
    fun complete(system: String, prompt: String, maxTokens: Int = 200): String =
        throw UnsupportedOperationException("$model only writes summaries")
}

/** The `v1` prompts (SPEC §5.7.3). Changing them means bumping [VERSION]. */
object Prompts {
    const val VERSION = "v1"

    const val SYSTEM = "You summarize parts of a technical book for a search index. Use only the text you are given. " +
        "Do not add facts, opinions or examples. Keep names of APIs, classes, functions and terms exactly as written. " +
        "Write plain prose, no lists, no headings, no preamble."

    fun user(task: SummaryTask): String = when (task.level) {
        SummaryLevel.SECTION ->
            "Book: ${task.bookTitle}. Chapter: ${task.chapterTitle}.\n" +
                "Summarize this passage in at most ${task.maxWords} words, covering every main point:\n\n" +
                task.inputs.joinToString("\n\n")
        SummaryLevel.GROUP, SummaryLevel.CHAPTER ->
            "Book: ${task.bookTitle}. Chapter: ${task.chapterTitle}.\n" +
                "These are summaries of consecutive parts of the chapter, in order. Combine them into one summary of at most " +
                "${task.maxWords} words that says what the chapter covers:\n\n" +
                task.inputs.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n\n")
        SummaryLevel.BOOK ->
            "Book: ${task.bookTitle}.\n" +
                "These are summaries of the book's chapters, in order. Write one summary of at most ${task.maxWords} words " +
                "that says what the book covers and how it is organized:\n\n" +
                task.inputs.joinToString("\n\n")
    }
}

/** Output cleaning (SPEC §5.7.3). Pure. */
object SummaryText {
    private val preamble = Regex(
        "^\\s*(here('s| is)[^\\n:]*:|summary:|sure[,!.][^\\n]*\\n|certainly[,!.][^\\n]*\\n)\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val word = Regex("\\S+")
    private val sentenceEnd = Regex("[.!?][\"”’)\\]]?(?=\\s|$)")

    /** Strips a preamble, trims, and cuts to at most [maxWords] words, at the last sentence end that fits. */
    fun clean(raw: String, maxWords: Int): String {
        val text = preamble.replace(raw.trim(), "").trim()
        val words = word.findAll(text).toList()
        if (words.size <= maxWords) return text
        val limit = words[maxWords - 1].range.last + 1
        val cut = sentenceEnd.findAll(text.substring(0, limit)).lastOrNull()?.range?.last?.plus(1)
        return (if (cut != null) text.substring(0, cut) else text.substring(0, limit)).trim()
    }
}
