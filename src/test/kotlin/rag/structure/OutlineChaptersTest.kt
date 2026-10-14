package rag.structure

import kotlin.test.Test
import kotlin.test.assertEquals

class OutlineChaptersTest {

    private fun e(title: String, page: Int?, vararg children: OutlineEntry) = OutlineEntry(title, page, children.toList())

    @Test
    fun `parts mixed with plain chapters become their children (CtCI shape)`() {
        val outline = listOf(
            e("Introduction", 7, e("Table of Contents", 7), e("Foreword", 12)),
            e("VI. Big O", 49),
            e("IX. Interview Questions", 98, e("1. Arrays and Strings", 99), e("2. Linked Lists", 103)),
            e("X. Solutions", 202, e("1. Solutions to Arrays and Strings", 203), e("2. Solutions to Linked Lists", 219)),
            e("XI. Advanced Topics", 639),
        )

        val flat = OutlineChapters.flatten(outline)

        assertEquals(
            listOf(
                "Introduction" to 7, "Introduction › Foreword" to 12, "VI. Big O" to 49,
                "IX. Interview Questions" to 98, "IX. Interview Questions › 1. Arrays and Strings" to 99,
                "IX. Interview Questions › 2. Linked Lists" to 103,
                "X. Solutions" to 202, "X. Solutions › 1. Solutions to Arrays and Strings" to 203,
                "X. Solutions › 2. Solutions to Linked Lists" to 219, "XI. Advanced Topics" to 639,
            ),
            flat.chapters.map { it.title to it.pdfPage },
        )
        assertEquals(listOf("Table of Contents" to 7), flat.contents.map { it.title to it.pdfPage })
    }

    @Test
    fun `chapters with numbered sections stay chapters even next to plain entries (Kotlin shape)`() {
        val outline = listOf(
            e("I. About this book", 10),
            e("5 Writing your first class", 34, e("5.1 Creating the layout", 34), e("5.2 The Recycler Adapter", 35)),
            e("6 Variables and properties", 39, e("6.1 Basic types", 39)),
        )

        assertEquals(
            listOf("I. About this book", "5 Writing your first class", "6 Variables and properties"),
            OutlineChapters.flatten(outline).chapters.map { it.title },
        )
    }

    @Test
    fun `chapters whose children all have sections stay chapters (Compose shape)`() {
        val outline = listOf(
            e("Table of Contents", 5),
            e("Prelude", 9, e("Why to read this book", 9), e("Keep the sources close", 10)),
            e("1. Composable functions", 11, e("The meaning of Composable functions", 11), e("Calling context", 12)),
        )

        val flat = OutlineChapters.flatten(outline)

        assertEquals(listOf("Prelude", "1. Composable functions"), flat.chapters.map { it.title })
        assertEquals(listOf(5), flat.contents.map { it.pdfPage })
    }

    @Test
    fun `part-like titles are parts even when every entry has children`() {
        val outline = listOf(
            e("Part I: Basics", 10, e("Chapter 1", 11), e("Chapter 2", 30)),
            e("Part II: Advanced", 60, e("Chapter 3", 61), e("Chapter 4", 90)),
        )

        assertEquals(
            listOf("Part I: Basics", "Part I: Basics › Chapter 1", "Part I: Basics › Chapter 2",
                "Part II: Advanced", "Part II: Advanced › Chapter 3", "Part II: Advanced › Chapter 4"),
            OutlineChapters.flatten(outline).chapters.map { it.title },
        )
    }

    @Test
    fun `short children are sections even under a part-like title`() {
        val outline = listOf(e("II. Getting Started", 10, e("Install", 10), e("Run", 11), e("Test", 12)), e("III. Next", 13))

        assertEquals(listOf("II. Getting Started", "III. Next"), OutlineChapters.flatten(outline).chapters.map { it.title })
    }

    @Test
    fun `own title drops the part prefix`() {
        assertEquals("1. Arrays and Strings", OutlineChapters.ownTitle("IX. Interview Questions › 1. Arrays and Strings"))
        assertEquals("Prelude", OutlineChapters.ownTitle("Prelude"))
    }
}
