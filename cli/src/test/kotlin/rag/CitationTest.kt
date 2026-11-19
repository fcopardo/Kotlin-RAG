package rag

import kotlin.test.Test
import kotlin.test.assertEquals

class CitationTest {

    @Test
    fun `citation for file pages`() {
        // Single file
        assertEquals("a/B.kt", citation("file", "a/B.kt", "a/B.kt"))
        
        // File range
        assertEquals("a/B.kt–c/D.kt", citation("file", "a/B.kt", "c/D.kt"))
        
        // PDF single
        assertEquals("p. 13", citation("pdf", "13", "13"))
        
        // PDF range
        assertEquals("p. 13-15", citation("pdf", "13", "15"))
        
        // Location single
        assertEquals("loc 3", citation("location", "loc 3", "loc 3"))
        
        // Location range
        assertEquals("loc 3-5", citation("location", "loc 3", "loc 5"))
    }
}