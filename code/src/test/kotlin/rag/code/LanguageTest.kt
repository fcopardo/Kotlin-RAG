package rag.code

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTest {
    @Test
    fun `extensions pick the family, case-insensitive, unknown is OTHER`() {
        assertEquals(Language.KOTLIN, Language.of("kt"))
        assertEquals(Language.KOTLIN, Language.of("KTS"))
        assertEquals(Language.JS_TS, Language.of("tsx"))
        assertEquals(Language.C_CPP, Language.of("hpp"))
        assertEquals(Language.CSHARP, Language.of("cs"))
        assertEquals(Language.PYTHON, Language.of("py"))
        assertEquals(Language.OTHER, Language.of("md"))
        assertEquals(Language.OTHER, Language.of(""))
    }
}
