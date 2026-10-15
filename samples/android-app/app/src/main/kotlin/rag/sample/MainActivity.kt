package rag.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rag.RagException
import rag.search.Hit
import java.io.File

/**
 * A deliberately plain screen: index books, then search them, optionally by subject.
 *
 * Scriptable for testing (adb shell am start -n rag.sample/.MainActivity --ez indexFolder true
 * --es query "..." --es subject "..."): results are also logged with the tag RagSample.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var resultList: LinearLayout
    private lateinit var query: EditText
    private lateinit var subject: EditText

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importAndIndex) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildViews()
        observeIndexing()
        status.text = describeSetup()

        handleExtras(intent)
        addOnNewIntentListener(::handleExtras)
    }

    private fun handleExtras(i: Intent) {
        if (i.getBooleanExtra("indexFolder", false)) indexFolder()
        i.getStringExtra("query")?.let { q ->
            query.setText(q)
            subject.setText(i.getStringExtra("subject").orEmpty())
            search(openResult = i.getIntExtra("openResult", -1).takeIf { it >= 0 })
        }
    }

    private fun describeSetup(): String {
        val tier = RagLibrary.tier(this)
        val model = if (RagLibrary.hasModel(this)) "model ready" else "no model in ${RagLibrary.modelDir(this)}: keyword search only"
        return "Tier ${tier.name.lowercase()}, $model"
    }

    private fun indexFolder() {
        val books = RagLibrary.booksDir(this).listFiles { f -> f.extension.lowercase() in setOf("pdf", "epub") }.orEmpty()
        if (books.isEmpty()) status.text = "No PDF or EPUB in ${RagLibrary.booksDir(this)}"
        books.forEach { IndexWorker.enqueue(this, it) }
    }

    /** Copies a picked document into the books folder (the library reads files), then indexes it. */
    private fun importAndIndex(uri: Uri) {
        lifecycleScope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "book"
            val target = File(RagLibrary.booksDir(this@MainActivity), name)
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            IndexWorker.enqueue(this@MainActivity, target)
        }
    }

    private fun observeIndexing() {
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity).getWorkInfosByTagFlow(IndexWorker.TAG).collectLatest { works ->
                val running = works.filter { it.state == WorkInfo.State.RUNNING }
                val failed = works.filter { it.state == WorkInfo.State.FAILED }.mapNotNull { it.outputData.getString(IndexWorker.KEY_ERROR) }
                val lines = running.map { w ->
                    val done = w.progress.getInt(IndexWorker.KEY_DONE, 0)
                    val total = w.progress.getInt(IndexWorker.KEY_TOTAL, 0)
                    if (total > 0) "Embedding $done / $total" else "Reading the book…"
                } + failed.map { "Failed: $it" } +
                    "${works.count { it.state == WorkInfo.State.SUCCEEDED }} of ${works.size} books indexed"
                status.text = describeSetup() + "\n" + lines.joinToString("\n")
                Log.i(TAG, lines.joinToString("; "))
            }
        }
    }

    /** Searches, then lists the hits; tapping one opens the book there. `openResult` opens hit N (scripting). */
    private fun search(openResult: Int? = null) {
        val q = query.text.toString().trim().takeIf { it.isNotEmpty() } ?: return
        val s = subject.text.toString().trim().takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            resultList.removeAllViews()
            try {
                val started = System.nanoTime()
                val hits = RagLibrary.engine(this@MainActivity).search(q, k = 5, subject = s)
                val ms = (System.nanoTime() - started) / 1_000_000
                results.text = "${hits.size} results in $ms ms. Tap one to open the book there."
                hits.forEach { hit -> resultList.addView(resultView(hit)) }
                Log.i(TAG, "query '$q' subject '$s': ${results.text}\n" + hits.joinToString("\n") { citation(it) })
                openResult?.let { n -> hits.getOrNull(n)?.let { open(it) } }
            } catch (e: RagException) {
                results.text = "Search failed: ${e.message}"
            }
        }
    }

    private fun citation(hit: Hit): String {
        val pages = hit.paragraph.pageStartLabel.let { start ->
            if (start == hit.paragraph.pageEndLabel) "p. $start" else "pp. $start–${hit.paragraph.pageEndLabel}"
        }
        return "${hit.bookTitle} › ${hit.chapter.title}, $pages"
    }

    private fun resultView(hit: Hit) = TextView(this).apply {
        text = "${citation(hit)} [${hit.matchType}]\n${(hit.summary ?: hit.paragraph.text).take(300)}"
        setPadding(0, 24, 0, 24)
        isClickable = true
        // The system's ripple, so a tap shows.
        val ripple = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { lifecycleScope.launch { open(hit) } }
    }

    private suspend fun open(hit: Hit) {
        val file = RagLibrary.fileFor(this, hit.bookId)
        if (file == null) {
            results.text = "${hit.bookTitle} isn't in ${RagLibrary.booksDir(this)} on this device, so it can't be opened."
            return
        }
        Log.i(TAG, "opening ${file.name} at ${citation(hit)}")
        BookViewerActivity.open(this, file, hit, citation(hit))
    }

    private fun buildViews() {
        status = TextView(this)
        results = TextView(this)
        resultList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        query = EditText(this).apply { hint = "Ask something about your books" }
        subject = EditText(this).apply { hint = "Subject filter (optional), e.g. android" }
        val pick = Button(this).apply { text = "Add a book (PDF or EPUB)"; setOnClickListener { pickBook.launch(arrayOf("application/pdf", "application/epub+zip")) } }
        val folder = Button(this).apply { text = "Index the books folder"; setOnClickListener { indexFolder() } }
        val go = Button(this).apply { text = "Search"; setOnClickListener { search() } }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 48)
            listOf(status, pick, folder, query, subject, go, results, resultList).forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        }
        setContentView(ScrollView(this).apply { addView(column) }.also(BookViewerActivity::padForSystemBars))
    }

    private companion object {
        const val TAG = "RagSample"
    }
}
