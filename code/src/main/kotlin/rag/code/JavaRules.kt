package rag.code

/**
 * Java's declarations (plan step 2c-2). Input is masked and normalized (see [FamilyRules]).
 *
 * **Core.** Repeatedly drop from the start: spaces; an annotation `@` + name characters (letters,
 * digits, `_`, `.`) with an optional `(…)` argument list (skip to its matching `)`), EXCEPT `@interface`,
 * which is kept; a word from [MODIFIERS] followed by a space.
 *
 * **classify**, on the core:
 * - `class N`, `interface N`, `enum N`, `record N`, `@interface N` → [Classified.Class] (N = the leading
 *   identifier of what follows: letters, digits, `_`, `$`);
 * - else [MethodHeaders.parse] with [CONTROL] → [Classified.Function] with its name;
 * - else null (`static {}`, a compact record constructor `Point`, `if (…)`, enum constants `RED(1)`…).
 * Java has no transparent blocks and no functions without braces: [bodylessFunction] is always null.
 */
internal object JavaRules : FamilyRules {
    val MODIFIERS = setOf(
        "public", "private", "protected", "static", "final", "abstract", "synchronized", "native", "strictfp",
        "transient", "volatile", "default", "sealed", "non-sealed",
    )
    val CONTROL = setOf("if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new", "throw", "assert", "yield", "case")

    override val annotationPrefixes = listOf("@")

    private fun core(h: String): String {
        var i = 0
        while (true) {
            while (i < h.length && h[i] == ' ') i++
            if (h.startsWith("@interface", i)) return h.substring(i)        // an annotation TYPE: keep
            if (i < h.length && h[i] == '@') {
                i++
                while (i < h.length && (h[i].isLetterOrDigit() || h[i] == '_' || h[i] == '.')) i++
                var j = i; while (j < h.length && h[j] == ' ') j++
                if (j < h.length && h[j] == '(') i = MethodHeaders.matching(h, j) + 1
                continue
            }
            val word = MODIFIERS.firstOrNull { h.startsWith("$it ", i) }
            if (word != null) { i += word.length + 1; continue }
            return h.substring(i)
        }
    }

    override fun classify(header: String): Classified? {
        val c = core(header)
        for (keyword in listOf("class ", "interface ", "enum ", "record ", "@interface ")) {
            if (c.startsWith(keyword)) {
                val name = c.substring(keyword.length).trimStart().takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
                return if (name.isEmpty()) null else Classified.Class(name)
            }
        }
        return MethodHeaders.parse(c, CONTROL)?.let { Classified.Function(it.name) }
    }

    override fun bodylessFunction(statement: String): String? = null
}
