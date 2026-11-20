package rag.code

/**
 * Go's declarations (plan step 2e-1). Input is masked and normalized (see [FamilyRules]).
 * Methods stay where they are written: a method's parent is its file, not its receiver type.
 *
 * **classify(header)**:
 * - `func` followed by a space or `(`: drop `func`; if a `(` follows (a receiver), drop it up to its
 *   matching `)` ([MethodHeaders.matching]); then the name = the leading identifier (letters, digits,
 *   `_`). A name followed (after spaces) by `(` or `[` (type parameters) → Function(name). Otherwise
 *   (an anonymous `func(`) → null.
 * - Otherwise, after dropping a leading `type `: the leading identifier N, then an optional type
 *   parameter list `[…]` (to its matching `]`), then exactly `struct` or `interface` → Class(N). This
 *   also covers grouped declarations (`type ( … )`), where the header is just `N struct`.
 * - Anything else → null (`if`, `for`, `switch`, `select`, composite literals `Point`, `map[string]int`,
 *   `var x = Point`).
 *
 * Go has no transparent blocks, no functions without braces ([bodylessFunction] is null) and no
 * annotations (`//go:` directives are comments).
 */
internal object GoRules : FamilyRules {
    override val annotationPrefixes = emptyList<String>()

    private fun ident(s: String, from: Int = 0): String {
        if (from >= s.length || !(s[from].isLetter() || s[from] == '_')) return ""
        var i = from
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(from, i)
    }

    override fun classify(header: String): Classified? {
        val c = header
        if (c == "func" || c.startsWith("func ") || c.startsWith("func(")) {
            var s = c.substring(4).trimStart()
            if (s.startsWith("(")) s = s.substring(MethodHeaders.matching(s, 0) + 1).trimStart()   // receiver
            val name = ident(s)
            if (name.isEmpty()) return null
            val rest = s.substring(name.length).trimStart()
            return if (rest.startsWith("(") || rest.startsWith("[")) Classified.Function(name) else null
        }
        val s = c.removePrefix("type ").trimStart()
        val name = ident(s)
        if (name.isEmpty()) return null
        var rest = s.substring(name.length).trimStart()
        if (rest.startsWith("[")) rest = rest.substring(MethodHeaders.matching(rest, 0) + 1).trimStart()
        return if (rest == "struct" || rest == "interface") Classified.Class(name) else null
    }

    override fun bodylessFunction(statement: String): String? = null
}
