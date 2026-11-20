package rag.code

/**
 * Rust's declarations (plan step 2e-2). Input is masked and normalized (see [FamilyRules]).
 *
 * **Core.** Repeatedly drop from the start: spaces; an attribute `#[…]` or `#![…]` (skip from its `[`
 * to the matching `]`, [MethodHeaders.matching]); `pub(…)` (the word `pub` and its parenthesized
 * scope, `pub(crate)`); a word from [MODIFIERS] followed by a space. (`extern "C"` arrives as `extern`
 * and spaces: both are dropped.)
 *
 * **classify**, on the core:
 * - `struct N`, `enum N`, `union N`, `trait N` → Class(N) (N = the leading identifier: letters, digits, `_`).
 * - `impl` alone, `impl …` or `impl<…`: drop `impl`, then a leading `<…>` (to its matching `>`, where
 *   `->` is an arrow, not a `>`), then trim; cut at the first ` where` (as a word) and drop trailing
 *   commas and spaces → Class(`"impl " + rest`): `impl fmt::Display for Point`, `impl Wrapper<T>`.
 *   An empty rest (a bare `impl`) → null.
 * - `mod N` → [Classified.Transparent].
 * - `fn N` (N = the leading identifier, then `(` or `<`) → Function(N).
 * - Anything else → null (`match`, `if`, `loop`, `unsafe`, closures `|x|`, match arms, `macro_rules!`).
 *
 * No functions without braces: [bodylessFunction] is null. A trait method without a body (`fn area(&self) -> f64;`)
 * is not a block, so it is never emitted.
 */
internal object RustRules : FamilyRules {
    val MODIFIERS = setOf("pub", "async", "const", "unsafe", "extern", "default")

    override val annotationPrefixes = listOf("#[")

    private fun ident(s: String, from: Int = 0): String {
        if (from >= s.length || !(s[from].isLetter() || s[from] == '_')) return ""
        var i = from
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(from, i)
    }

    private fun core(h: String): String {
        var i = 0
        while (true) {
            while (i < h.length && h[i] == ' ') i++
            if (h.startsWith("#[", i)) { i = MethodHeaders.matching(h, i + 1) + 1; continue }
            if (h.startsWith("#![", i)) { i = MethodHeaders.matching(h, i + 2) + 1; continue }
            if (h.startsWith("pub(", i)) { i = MethodHeaders.matching(h, i + 3) + 1; continue }
            val word = MODIFIERS.firstOrNull { h.startsWith("$it ", i) }
            if (word != null) { i += word.length + 1; continue }
            return h.substring(i)
        }
    }

    /** Index just past the `>` closing the `<` at [open], where `->` is not a `>`. */
    private fun pastAngles(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            if (s[i] == '-' && i + 1 < s.length && s[i + 1] == '>') { i += 2; continue }
            if (s[i] == '<') depth++
            if (s[i] == '>') { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return s.length
    }

    override fun classify(header: String): Classified? {
        val c = core(header)
        for (kw in listOf("struct ", "enum ", "union ", "trait ")) if (c.startsWith(kw)) {
            val n = ident(c, kw.length)
            return if (n.isEmpty()) null else Classified.Class(n)
        }
        if (c == "impl" || c.startsWith("impl ") || c.startsWith("impl<")) {
            var s = c.substring(4).trimStart()
            if (s.startsWith("<")) s = s.substring(pastAngles(s, 0)).trimStart()
            val where = Regex("""(^|\s)where(\s|$)""").find(s)
            if (where != null) s = s.substring(0, where.range.first)
            s = s.trim().trimEnd(',', ' ')
            return if (s.isEmpty()) null else Classified.Class("impl $s")
        }
        if (c.startsWith("mod ")) return Classified.Transparent
        if (c.startsWith("fn ")) {
            val n = ident(c, 3)
            if (n.isEmpty()) return null
            val rest = c.substring(3 + n.length).trimStart()
            return if (rest.startsWith("(") || rest.startsWith("<")) Classified.Function(n) else null
        }
        return null
    }

    override fun bodylessFunction(statement: String): String? = null
}
