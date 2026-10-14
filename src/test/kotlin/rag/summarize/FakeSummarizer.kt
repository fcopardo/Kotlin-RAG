package rag.summarize

import rag.RagException

/**
 * Deterministic [Summarizer] for tests: the first sentence of each input, joined. With [padTo],
 * the output is padded to that many words (to exercise group reduction).
 */
class FakeSummarizer(
    override val model: String = "fake-summarizer",
    private val padTo: Map<SummaryLevel, Int> = emptyMap(),
    private val failAfter: Int? = null,
    private val output: ((SummaryTask) -> String)? = null,
) : Summarizer {
    val tasks = mutableListOf<SummaryTask>()

    override fun summarize(task: SummaryTask): String {
        if (failAfter != null && tasks.size >= failAfter) throw RagException.EmbeddingBackendUnavailable("summary model went away")
        tasks += task
        output?.let { return it(task) }
        val first = task.inputs.joinToString(" ") { input ->
            input.replace(Regex("^\\d+\\. "), "").substringBefore(". ").trim().removeSuffix(".") + "."
        }
        val pad = padTo[task.level] ?: return first
        // Pad with whole sentences, so output cleaning (which cuts at sentence ends) keeps the length.
        val text = StringBuilder(first)
        while (text.split(Regex("\\s+")).size + 5 <= pad) text.append(" Padding words fill this sentence.")
        return text.toString()
    }
}
