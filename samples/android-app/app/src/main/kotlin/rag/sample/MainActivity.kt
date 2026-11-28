package rag.sample

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rag.BookFormat
import rag.BookFormats
import rag.RagException
import rag.search.Hit
import java.io.File

/**
 * A deliberately plain screen: index books, documents and source files, then search them, optionally by
 * subject and kind.
 *
 * Scriptable for testing (adb shell am start -n rag.sample/.MainActivity --ez indexFolder true
 * --es query "..." --es subject "..." --es kind code --es language es): results are also logged with the tag RagSample.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var resultList: LinearLayout
    private lateinit var query: EditText
    private lateinit var subject: EditText
    private lateinit var kindFilter: Spinner
    private lateinit var languageFilter: Spinner

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
            KINDS.indexOfFirst { it.second == i.getStringExtra("kind") }.takeIf { it > 0 }?.let { kindFilter.setSelection(it) }
            LANGUAGES.indexOfFirst { it.second == i.getStringExtra("language") }.takeIf { it > 0 }?.let { languageFilter.setSelection(it) }
            search(openResult = i.getIntExtra("openResult", -1).takeIf { it >= 0 })
        }
    }

    private fun describeSetup(): String {
        val tier = RagLibrary.tier(this)
        val model = if (RagLibrary.hasModel(this)) "model ready" else "no model in ${RagLibrary.modelDir(this)}: keyword search only"
        return "Tier ${tier.name.lowercase()}, $model"
    }

    /**
     * Indexes every book, document and source file in the books folder. Source files are recognized by
     * extension, the rest by content: names may lack an extension.
     */
    private fun indexFolder() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) {
                RagLibrary.booksDir(this@MainActivity).listFiles().orEmpty().filter { f -> f.isFile && formatOf(f) != null }
            }
            if (books.isEmpty()) status.text = "No book, document or source file in ${RagLibrary.booksDir(this@MainActivity)}"
            books.forEach { IndexWorker.enqueue(this@MainActivity, it) }
        }
    }

    /**
     * Copies a picked document into the books folder (the library reads files), then indexes it.
     * A content provider's URI doesn't carry the file name (e.g. `.../document/msf:42`), so the name
     * comes from the provider's display name, and the extension from the content itself.
     */
    private fun importAndIndex(uri: Uri) {
        lifecycleScope.launch {
            val name = displayName(uri)
            val imported = withContext(Dispatchers.IO) {
                val books = RagLibrary.booksDir(this@MainActivity)
                val temp = File.createTempFile("import", null, cacheDir)
                try {
                    contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    // A source file keeps its name: its extension says the language (the content can't).
                    val format = (if (isSourceFile(name)) BookFormat.CODE else formatOf(temp)) ?: return@withContext null
                    val target = uniqueFile(books, withExtension(name, format))
                    temp.copyTo(target)
                    target
                } finally {
                    temp.delete()
                }
            }
            if (imported == null) {
                status.text = "$name isn't a book, a document or a source file."
                return@launch
            }
            Log.i(TAG, "indexing ${imported.name}")
            IndexWorker.enqueue(this@MainActivity, imported)
        }
    }

    /** The document's name as the provider shows it ("Compose internals.pdf"), or a fallback. */
    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace(Regex("[/\\\\:*?\"<>|]"), "_")?.takeIf { it.isNotBlank() } ?: "book"

    private fun isSourceFile(name: String) = name.substringAfterLast('.', "").lowercase() in BookFormats.CODE_EXTENSIONS

    /** The file's format: [BookFormat.CODE] for a source file name, else by content; null if unreadable. */
    private fun formatOf(file: File): BookFormat? = if (isSourceFile(file.name)) BookFormat.CODE else try {
        BookFormats.sniff(file)
    } catch (e: RagException) {
        null
    }

    /**
     * [name] ending in an extension of its format: "Compose internals" → "Compose internals.pdf",
     * "notes" (text) → "notes.txt". A name that already has one keeps it ("notes.md", "page.htm", "Main.kt").
     */
    private fun withExtension(name: String, format: BookFormat): String {
        val extensions = EXTENSIONS.getValue(format)
        if (format == BookFormat.CODE || extensions.any { name.endsWith(".$it", ignoreCase = true) }) return name
        return "$name.${extensions.first()}"
    }

    /** [name] in [dir], or "name (2).pdf", "name (3).pdf"… if another book already has it. */
    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        return generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) name else "$base ($n).$extension") }
            .first { !it.exists() }
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

    /**
     * Searches, then lists the hits; tapping one opens the book there. With a kind selected, its best 5.
     * With All, the best 3 of each kind in its own section ("Books", "Code"…), in a fixed order, so a
     * book's passages don't crowd out the code; one kind found shows as a plain list. `openResult`
     * opens hit N of the list as shown, counting across sections (scripting).
     */
    private fun search(openResult: Int? = null) {
        val q = query.text.toString().trim().takeIf { it.isNotEmpty() } ?: return
        val s = subject.text.toString().trim().takeIf { it.isNotEmpty() }
        val kind = KINDS[kindFilter.selectedItemPosition].second
        val languages = LANGUAGES[languageFilter.selectedItemPosition].second?.let { listOf(it) }
        lifecycleScope.launch {
            resultList.removeAllViews()
            try {
                val engine = RagLibrary.engine(this@MainActivity)
                val started = System.nanoTime()
                val sections: Map<String, List<Hit>> =
                    if (kind != null) mapOf(kind to engine.search(q, k = 5, subject = s, kind = kind, languages = languages))
                    else engine.searchByKind(q, kPerKind = 3, subject = s, languages = languages)
                val ms = (System.nanoTime() - started) / 1_000_000
                val hits = sections.values.flatten()
                val headed = kind == null && sections.size > 1
                results.text = if (hits.isEmpty()) "No results ($ms ms)." else "${hits.size} results in $ms ms. Tap one to open it."
                val log = StringBuilder("query '$q' subject '$s' kind '$kind' languages $languages: ${results.text}")
                sections.forEach { (k, kindHits) ->
                    if (headed) {
                        resultList.addView(sectionHeader(k))
                        log.append("\n== ").append(kindLabel(k))
                    }
                    kindHits.forEach { hit ->
                        resultList.addView(resultView(hit))
                        log.append("\n").append(citation(hit)).append(" [").append(hit.language).append("]")
                    }
                }
                Log.i(TAG, log.toString())
                openResult?.let { n -> hits.getOrNull(n)?.let { open(it) } }
            } catch (e: RagException) {
                results.text = "Search failed: ${e.message}"
            }
        }
    }

    /** "Books", "Papers"… as in the kind filter; an unknown kind as it is. */
    private fun kindLabel(kind: String) = KINDS.firstOrNull { it.second == kind }?.first ?: kind

    private fun sectionHeader(kind: String) = TextView(this).apply {
        text = kindLabel(kind)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        textSize = 18f
        setPadding(0, 32, 0, 8)
    }

    private fun citation(hit: Hit): String {
        // A source file's page label is its path, cited as it is (a passage never spans two files).
        val pages = if (hit.pageKind == "file") hit.paragraph.pageStartLabel else hit.paragraph.pageStartLabel.let { start ->
            if (start == hit.paragraph.pageEndLabel) "p. $start" else "pp. $start–${hit.paragraph.pageEndLabel}"
        }
        return "${hit.bookTitle} › ${hit.chapter.title}, $pages"
    }

    private fun resultView(hit: Hit) = TextView(this).apply {
        text = "${citation(hit)} [${hit.matchType}, ${hit.language}]\n${(hit.summary ?: hit.paragraph.text).take(300)}"
        setPadding(0, 24, 0, 24)
        isClickable = true
        // The system's ripple, so a tap shows.
        val ripple = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { lifecycleScope.launch { open(hit) } }
    }

    /**
     * Opens [hit]: a PDF or EPUB on this device in the book viewer, at its page; anything else (documents,
     * source files, a book whose file isn't here) in its section's stored text.
     */
    private suspend fun open(hit: Hit) {
        val file = RagLibrary.fileFor(this, hit.bookId)
        val format = file?.let { withContext(Dispatchers.IO) { formatOf(it) } }
        if (file != null && (format == BookFormat.PDF || format == BookFormat.EPUB)) {
            Log.i(TAG, "opening ${file.name}, ${file.path}, ${file.extension} at ${citation(hit)}")
            BookViewerActivity.open(this, file, hit, citation(hit))
        } else {
            Log.i(TAG, "opening the stored text at ${citation(hit)}")
            TextViewerActivity.open(this, hit, citation(hit))
        }
    }

    private fun buildViews() {
        status = TextView(this)
        results = TextView(this)
        resultList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        query = EditText(this).apply { hint = "Ask something about your books" }
        subject = EditText(this).apply { hint = "Subject filter (optional), e.g. android" }
        kindFilter = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, KINDS.map { it.first })
        }
        languageFilter = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, LANGUAGES.map { it.first })
        }
        val pick = Button(this).apply { text = "Add a book, document or source file"; setOnClickListener { pickBook.launch(BOOK_TYPES) } }
        val folder = Button(this).apply { text = "Index the books folder"; setOnClickListener { indexFolder() } }
        val go = Button(this).apply { text = "Search"; setOnClickListener { search() } }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 48)
            listOf(status, pick, folder, query, subject, kindFilter, languageFilter, go, results, resultList).forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        }
        setContentView(ScrollView(this).apply { addView(column) }.also(BookViewerActivity::padForSystemBars))
    }

    private companion object {
        const val TAG = "RagSample"

        /**
         * What the system file picker may offer: books, documents (Markdown, text, HTML, Word) and source
         * files (any text type; JavaScript has its own). A file without an extension, and many source files,
         * are typed application/octet-stream, so it's allowed too; [formatOf] then checks the file.
         */
        val BOOK_TYPES = arrayOf(
            "application/pdf", "application/epub+zip", "text/*", "application/javascript",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream",
        )

        /** Extensions an imported file may end in, per format; the first is added when it has none. */
        val EXTENSIONS = mapOf(
            BookFormat.PDF to listOf("pdf"), BookFormat.EPUB to listOf("epub"), BookFormat.DOCX to listOf("docx"),
            BookFormat.HTML to listOf("html", "htm", "xhtml"), BookFormat.TEXT to listOf("txt", "md", "markdown"),
            BookFormat.CODE to emptyList(),
        )

        /** The kind filter's entries: label, and the kind passed to search (null = everything, the default). */
        private val KINDS = listOf("All" to null, "Books" to "book", "Papers" to "paper", "Contracts" to "contract", "Code" to "code")

        /** The language filter's entries: label, and the language passed to search (null = every language). Code always matches. */
        private val LANGUAGES = listOf("Any language" to null, "English" to "en", "Spanish" to "es")
    }
}
