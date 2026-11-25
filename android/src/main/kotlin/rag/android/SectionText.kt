package rag.android

/**
 * The stored text of a hit's section, for showing a passage in place without the original file
 * ([RagEngine.sectionText]): documents, code, and books whose file isn't on the device.
 *
 * [highlightStart] and [highlightEnd] are the hit paragraph's offsets in [text] (`text.substring(highlightStart,
 * highlightEnd)` is the paragraph). A section longer than the limit is cut to a window around the paragraph;
 * [cutBefore] / [cutAfter] say whether text of the section was left out before / after the window.
 */
data class SectionText(
    val title: String,
    val text: String,
    val highlightStart: Int,
    val highlightEnd: Int,
    val cutBefore: Boolean,
    val cutAfter: Boolean,
)
