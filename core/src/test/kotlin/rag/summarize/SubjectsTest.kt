package rag.summarize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubjectsTest {

    @Test
    fun `labelled lines become weighted subjects, other lines are ignored`() {
        val output = """
            Here is the catalog entry:
            Main: Jetpack Compose, Android UI
            **Tangential:** Kotlin, Compose compiler.
            - Broad: Programming, Kotlin
            Some rambling without a label
        """.trimIndent()

        assertEquals(
            listOf(
                BookSubject("Jetpack Compose", Subjects.MAIN, "llm"),
                BookSubject("Android UI", Subjects.MAIN, "llm"),
                BookSubject("Kotlin", Subjects.TANGENTIAL, "llm"),
                BookSubject("Compose compiler", Subjects.TANGENTIAL, "llm"),
                BookSubject("Programming", Subjects.BROAD, "llm"),
            ),
            Subjects.parse(output),
        )
    }

    @Test
    fun `empty answers and numbers aren't subjects`() {
        assertEquals(listOf("Programming"), Subjects.parse("Main: 10\nTangential: none\nBroad: Programming").map { it.subject })
    }

    @Test
    fun `library of congress headings keep the topic`() {
        assertEquals(
            listOf("Military art and science", "War", "Kotlin"),
            Subjects.fromMetadata(listOf("Military art and science -- Early works to 1800", "War -- Early works to 1800", "Kotlin; war")).map { it.subject },
        )
    }

    @Test
    fun `a filter names a subject when all its words are there`() {
        assertTrue(Subjects.nameMatches("compose", "Jetpack Compose"))
        assertTrue(Subjects.nameMatches("Kotlin", "Kotlin coroutines"))
        assertTrue(Subjects.nameMatches("c++", "C++ programming"))
        assertFalse(Subjects.nameMatches("kotlin coroutines", "Kotlin"))
        assertFalse(Subjects.nameMatches("war", "Software"))
        assertFalse(Subjects.nameMatches("  ", "War"))
    }
}
