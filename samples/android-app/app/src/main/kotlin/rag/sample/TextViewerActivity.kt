package rag.sample

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import rag.search.Hit

/**
 * Shows a hit in its section's stored text (RagEngine.sectionText), the paragraph highlighted and scrolled
 * into view: for documents and source files, which the book viewer doesn't render, and for any book whose
 * file isn't on this device. Code shows in a monospace font.
 */
class TextViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val header = TextView(this).apply { setPadding(32, 16, 32, 16); text = intent.getStringExtra(EXTRA_TITLE) }
        val body = TextView(this).apply {
            setPadding(32, 16, 32, 48)
            setTextIsSelectable(true)
            if (intent.getStringExtra(EXTRA_KIND) == "code") { typeface = Typeface.MONOSPACE; textSize = 13f }
        }
        val scroll = ScrollView(this).apply { addView(body) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }.also(BookViewerActivity::padForSystemBars))

        lifecycleScope.launch {
            val section = RagLibrary.engine(this@TextViewerActivity).sectionText(
                intent.getLongExtra(EXTRA_BOOK, -1),
                intent.getStringExtra(EXTRA_SECTION).orEmpty(),
                intent.getStringExtra(EXTRA_PARAGRAPH).orEmpty(),
            )
            if (section == null) {
                body.text = "This passage isn't in the index any more."
                return@launch
            }
            val text = SpannableStringBuilder()
            if (section.cutBefore) text.append("…\n")
            val offset = text.length
            text.append(section.text)
            if (section.cutAfter) text.append("\n…")
            text.setSpan(BackgroundColorSpan(Color.argb(90, 255, 213, 0)), offset + section.highlightStart,
                offset + section.highlightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.text = text
            Log.i(TAG, "showing '${section.title}': ${section.text.length} chars, paragraph at " +
                "${section.highlightStart}–${section.highlightEnd}, cut before ${section.cutBefore}, after ${section.cutAfter}")
            // Once laid out, put the paragraph's first line a little below the top.
            body.post {
                val line = body.layout?.getLineForOffset(offset + section.highlightStart) ?: return@post
                scroll.scrollTo(0, (body.layout.getLineTop(line) + body.paddingTop - 120).coerceAtLeast(0))
            }
        }
    }

    companion object {
        private const val TAG = "RagSample"
        private const val EXTRA_BOOK = "book"
        private const val EXTRA_SECTION = "section"
        private const val EXTRA_PARAGRAPH = "paragraph"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, hit: Hit, citation: String) {
            context.startActivity(Intent(context, TextViewerActivity::class.java)
                .putExtra(EXTRA_BOOK, hit.bookId)
                .putExtra(EXTRA_SECTION, hit.chapter.unitKey)
                .putExtra(EXTRA_PARAGRAPH, hit.paragraph.unitKey)
                .putExtra(EXTRA_KIND, hit.kind)
                .putExtra(EXTRA_TITLE, citation))
        }
    }
}
