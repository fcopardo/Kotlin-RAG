package rag.summarize

import rag.epub.TestEpubs
import rag.epub.TestEpubs.Doc
import rag.epub.TestEpubs.Toc
import java.io.File

/** Generated EPUBs for summary tests: one short chapter, one of a few sections, optionally a huge one. */
object SummaryBooks {

    /** [count] paragraphs of about 100 words, each starting with a distinct first sentence about [topic]. */
    private fun paragraphs(topic: String, count: Int) = (1..count).joinToString("") { i ->
        "<p>The $topic fact number $i is described here. " + (1..18).joinToString(" ") { "Detail $it of $topic item $i continues." } + "</p>"
    }

    fun build(file: File, huge: Boolean = false): File {
        val docs = mutableListOf(
            Doc("cover.xhtml", "<p>Cover page of the summary test book.</p>"),
            Doc("snap.xhtml", "<h1>Snapshots</h1><p>Snapshots isolate state changes so readers see a stable view.</p><p>A snapshot is applied when its changes are ready.</p>"),
            Doc("trees.xhtml", "<h1>Slot Tables</h1>" + paragraphs("slot table", 40)),
        )
        val toc = mutableListOf(Toc("Snapshots", "snap.xhtml"), Toc("Slot Tables", "trees.xhtml"))
        if (huge) {
            docs += Doc("huge.xhtml", "<h1>Huge Chapter</h1>" + paragraphs("huge", 400))
            toc += Toc("Huge Chapter", "huge.xhtml")
        }
        return TestEpubs.build(file, docs, toc, title = "Summary Test Book")
    }
}
