package rag.code

/**
 * C#'s declarations (plan step 2c-3). Input is masked and normalized (see [FamilyRules]).
 *
 * **Core.** Repeatedly drop from the start: spaces; an attribute group `[…]` (skip to its matching
 * `]`); a word from [MODIFIERS] followed by a space.
 *
 * **classify**, on the core:
 * - `namespace …` → [Classified.Transparent];
 * - `class N`, `interface N`, `struct N`, `enum N`, `record N`, `record struct N`, `record class N` →
 *   [Classified.Class] (N = the leading identifier after the keyword(s): letters, digits, `_`);
 * - an operator: the core contains the word `operator`; name = `"operator "` + the text between that
 *   word and the next `(`, trimmed (`operator +`, `operator int` for `implicit operator int(…)`);
 *   requires a `(` after it, else null → [Classified.Function];
 * - else [MethodHeaders.parse] with [CONTROL] → [Classified.Function];
 * - else null (properties `int X`, indexers `T this[int i]`, `get`, `set`, lambdas, control flow).
 *
 * **bodylessFunction(statement)** (expression-bodied members, `int Size() => n;`): take the core; find
 * the method as in classify (operator or [MethodHeaders.parse]) else null; then scan the core after its
 * parameters' `)` at depth 0 of `()`, `[]`, `{}`, `<>` (where `=>` is not a `>`): `=>` → the name; a
 * `{` or `;` first → null; the end → null. (A property `int Count => n;` has no parameters: null.)
 */
internal object CSharpRules : FamilyRules {
    val MODIFIERS = setOf(
        "public", "private", "protected", "internal", "static", "readonly", "abstract", "virtual", "override",
        "sealed", "partial", "async", "extern", "unsafe", "const", "volatile", "required", "file", "implicit", "explicit",
    )
    val CONTROL = setOf(
        "if", "for", "foreach", "while", "switch", "catch", "using", "lock", "fixed", "checked", "unchecked", "try",
        "else", "do", "return", "new", "throw", "yield", "await", "when", "case", "nameof", "typeof", "sizeof", "default",
    )

    override val annotationPrefixes = listOf("[")

    private fun core(h: String): String {
        var i = 0
        while (true) {
            while (i < h.length && h[i] == ' ') i++
            if (i < h.length && h[i] == '[') { i = MethodHeaders.matching(h, i) + 1; continue }   // [Attribute( )]
            val word = MODIFIERS.firstOrNull { h.startsWith("$it ", i) }
            if (word != null) { i += word.length + 1; continue }
            return h.substring(i)
        }
    }

    /** An operator: name and the index of its '(' in the core, or null. */
    private fun operator(c: String): Pair<String, Int>? {
        val at = Regex("""(^|\s)operator(\s|\(|$)""").find(c) ?: return null
        val start = c.indexOf("operator", at.range.first)
        val paren = c.indexOf('(', start)
        if (paren < 0) return null
        return ("operator " + c.substring(start + "operator".length, paren).trim()).trim() to paren
    }

    /** The declared method: name and the index of its parameters' ')' in the core. */
    private fun method(c: String): Pair<String, Int>? {
        operator(c)?.let { (name, paren) -> return name to MethodHeaders.matching(c, paren) }
        return MethodHeaders.parse(c, CONTROL)?.let { it.name to it.paramsClose }
    }

    override fun classify(header: String): Classified? {
        val c = core(header)
        if (c == "namespace" || c.startsWith("namespace ")) return Classified.Transparent
        for (keyword in listOf("record struct ", "record class ", "class ", "interface ", "struct ", "enum ", "record ")) {
            if (c.startsWith(keyword)) {
                val name = c.substring(keyword.length).trimStart().takeWhile { it.isLetterOrDigit() || it == '_' }
                return if (name.isEmpty()) null else Classified.Class(name)
            }
        }
        return method(c)?.let { Classified.Function(it.first) }
    }

    override fun bodylessFunction(statement: String): String? {
        val c = core(statement)
        val (name, close) = method(c) ?: return null
        var depth = 0
        var i = close + 1
        while (i < c.length) {
            val ch = c[i]
            if (ch == '=' && i + 1 < c.length && c[i + 1] == '>') { if (depth == 0) return name; i += 2; continue }
            when (ch) {
                '(', '[', '{', '<' -> { if (ch == '{' && depth == 0) return null; depth++ }
                ')', ']', '}', '>' -> depth--
                ';' -> if (depth == 0) return null
            }
            i++
        }
        return null
    }
}
