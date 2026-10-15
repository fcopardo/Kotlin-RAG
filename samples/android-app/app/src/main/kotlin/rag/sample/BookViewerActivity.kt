package rag.sample

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rag.android.HitLocator
import rag.search.Hit
import java.io.File
import java.util.zip.ZipFile

/**
 * Opens a search hit in its book: a PDF at its page with the paragraph highlighted, or an EPUB at
 * its chapter document, scrolled to the passage and highlighted.
 */
class BookViewerActivity : ComponentActivity() {

    private lateinit var file: File
    private lateinit var paragraph: String
    private lateinit var sentence: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_FILE)!!)
        paragraph = intent.getStringExtra(EXTRA_PARAGRAPH).orEmpty()
        sentence = intent.getStringExtra(EXTRA_SENTENCE).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE)
        if (file.extension.equals("pdf", ignoreCase = true)) showPdf(intent.getIntExtra(EXTRA_PAGE, 1)) else showEpub()
    }

    // --- PDF: the page rendered by Android, the paragraph's lines highlighted ---

    private fun showPdf(hitPage: Int) {
        val header = TextView(this).apply { setPadding(32, 16, 32, 16); text = intent.getStringExtra(EXTRA_TITLE) }
        val image = ImageView(this).apply { adjustViewBounds = true }
        val scroll = ScrollView(this).apply { addView(image) }
        val pageLabel = TextView(this).apply { gravity = Gravity.CENTER }
        val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        var page = hitPage.coerceIn(1, renderer.pageCount)

        fun show() {
            pageLabel.text = "PDF page $page of ${renderer.pageCount}"
            lifecycleScope.launch {
                // Highlights only on the hit's page; finding them reads the PDF, so off the main thread.
                val highlights = if (page == hitPage) withContext(Dispatchers.IO) { HitLocator(this@BookViewerActivity).pdfHighlights(file, page, paragraph) } else emptyList()
                val bitmap = render(renderer, page, highlights)
                image.setImageBitmap(bitmap)
                highlights.firstOrNull()?.let { first ->
                    // Scroll so the highlight sits a little below the top.
                    scroll.post { scroll.scrollTo(0, ((first.top * image.height) - 120).toInt().coerceAtLeast(0)) }
                }
            }
        }

        val previous = Button(this).apply { text = "◀"; setOnClickListener { if (page > 1) { page--; show() } } }
        val next = Button(this).apply { text = "▶"; setOnClickListener { if (page < renderer.pageCount) { page++; show() } } }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(previous)
            addView(pageLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(next)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(bar)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }.also(::padForSystemBars))
        show()
    }

    /** Renders [page] at twice the screen width (sharp when zoomed by the system), with highlights. */
    private fun render(renderer: PdfRenderer, page: Int, highlights: List<RectF>): Bitmap = renderer.openPage(page - 1).use { p ->
        val width = resources.displayMetrics.widthPixels * 2
        val height = (width.toFloat() * p.height / p.width).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val paint = Paint().apply { color = Color.argb(90, 255, 213, 0) }
        val canvas = Canvas(bitmap)
        highlights.forEach { r -> canvas.drawRect(r.left * width, r.top * height, r.right * width, r.bottom * height, paint) }
        bitmap
    }

    // --- EPUB: the chapter's own XHTML, served from the zip, scrolled to the passage ---

    private fun showEpub() {
        val web = WebView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@BookViewerActivity).apply { setPadding(32, 16, 32, 16); text = intent.getStringExtra(EXTRA_TITLE) })
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }.also(::padForSystemBars))
        val zip = ZipFile(file)
        web.webViewClient = object : WebViewClient() {
            // The book's documents, styles and images, straight from the EPUB.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != HOST) return null
                val entry = zip.getEntry(request.url.path.orEmpty().removePrefix("/")) ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
                return WebResourceResponse(mimeType(entry.name), null, zip.getInputStream(entry))
            }

            override fun onPageFinished(view: WebView, url: String) = findPassage(view)
        }
        lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) { HitLocator(this@BookViewerActivity).epubDocument(file, paragraph) }
            web.loadUrl("https://$HOST/${document ?: zip.entries().asSequence().first { it.name.endsWith("html") }.name}")
        }
    }

    /** Highlights the passage with the WebView's find: the sentence's first words, else the paragraph's. */
    private fun findPassage(web: WebView) {
        val candidates = listOf(sentence, paragraph).map(::firstWords).filter { it.isNotBlank() }.distinct()
        var tried = 0
        web.setFindListener { _, matches, done ->
            if (!done) return@setFindListener
            if (matches > 0) {
                val before = web.scrollY
                web.findNext(true) // scrolls just enough to show the first match, i.e. to the bottom edge
                // When it had to scroll down, bring the match up to the middle.
                web.postDelayed({ if (web.scrollY > before) web.scrollBy(0, web.height / 2) }, 300)
            } else if (++tried < candidates.size) web.findAllAsync(candidates[tried])
        }
        candidates.firstOrNull()?.let(web::findAllAsync)
    }

    /** Up to six words without punctuation: what the rendered page most likely contains verbatim. */
    private fun firstWords(text: String): String =
        text.split(Regex("[\\s,;:.!?\"“”‘’()\\[\\]]+")).filter { it.isNotBlank() }.take(6).joinToString(" ")

    private fun mimeType(name: String): String = when (val ext = name.substringAfterLast('.').lowercase()) {
        "xhtml", "xht" -> "application/xhtml+xml"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        /** Apps targeting Android 15 draw under the system bars; keep the content clear of them. */
        fun padForSystemBars(view: android.view.View) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        private const val HOST = "book.local"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_PARAGRAPH = "paragraph"
        private const val EXTRA_SENTENCE = "sentence"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, file: File, hit: Hit, citation: String) {
            context.startActivity(Intent(context, BookViewerActivity::class.java)
                .putExtra(EXTRA_FILE, file.path)
                .putExtra(EXTRA_PAGE, hit.paragraph.pageStart)
                .putExtra(EXTRA_PARAGRAPH, hit.paragraph.text)
                .putExtra(EXTRA_SENTENCE, hit.phrase.text)
                .putExtra(EXTRA_TITLE, citation))
        }
    }
}
