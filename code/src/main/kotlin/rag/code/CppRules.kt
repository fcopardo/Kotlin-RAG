package rag.code

/**
 * C and C++ declarations (plan step 2f). Input is masked and normalized (see [FamilyRules]).
 * [classify] is fixed glue: types and namespaces go to [typeHeader] (part 2f-1), the rest to
 * [functionName] (part 2f-2). There are no functions without braces (`= default;` has no body).
 */
internal object CppRules : FamilyRules {
    val MODIFIERS = setOf(
        "static", "inline", "virtual", "explicit", "constexpr", "consteval", "constinit", "extern", "friend",
        "typedef", "mutable", "thread_local", "register", "volatile", "__inline", "__forceinline",
    )

    /** Words that make a header control flow or an expression when they come first (or right before the name). */
    val CONTROL = setOf(
        "if", "for", "while", "switch", "catch", "return", "sizeof", "alignof", "static_assert", "new", "delete",
        "throw", "case", "do", "else", "typeid", "co_return", "co_await", "co_yield", "using", "goto", "try",
    )

    /** `template <…>` lines and attributes above a declaration belong to it. */
    override val annotationPrefixes = listOf("[[", "template", "__attribute__")

    override fun classify(header: String): Classified? {
        val c = core(header)
        if (c == "extern" || c == "namespace" || listOf("namespace ", "class ", "struct ", "union ", "enum ").any { c.startsWith(it) }) {
            return typeHeader(c)
        }
        return functionName(c)?.let { Classified.Function(it) }
    }

    override fun bodylessFunction(statement: String): String? = null

    /**
     * Part 2f-1. The header without its leading template list, attributes and modifiers. Repeatedly drop
     * from the start: spaces; `template` followed (after optional spaces) by `<`: skip to the matching `>`
     * (count `<` and `>`); `[[`: skip to just past the next `]]`; `__attribute__`, `__declspec` or `alignas`
     * followed (after optional spaces) by `(`: skip the word and its parentheses
     * ([MethodHeaders.matching]); a word from [MODIFIERS] followed by a space. The rest is the core.
     * (`extern "C" {` arrives as `extern`: no space follows, so it stays.)
     */
    fun core(h: String): String {
        var i = 0
        outer@ while (true) {
            while (i < h.length && h[i] == ' ') i++
            if (i >= h.length) return ""
            if (h.startsWith("template", i)) {
                var j = i + "template".length
                while (j < h.length && h[j] == ' ') j++
                if (j < h.length && h[j] == '<') {
                    i = skipAngles(h, j)
                    continue 
                }
            }
            if (h.startsWith("[[", i)) {
                val e = h.indexOf("]]", i + 2)
                i = if (e < 0) h.length else e + 2
                continue
            }
            for (kw in listOf("__attribute__", "__declspec", "alignas")) if (h.startsWith(kw, i)) {
                var j = i + kw.length
                while (j < h.length && h[j] == ' ') j++
                if (j < h.length && h[j] == '(') { 
                    i = MethodHeaders.matching(h, j) + 1
                    continue@outer 
                }
            }
            val w = MODIFIERS.firstOrNull { h.startsWith("$it ", i) }
            if (w != null) { 
                i += w.length + 1
                continue 
            }
            return h.substring(i)
        }
    }

    /**
     * Part 2f-1. A core that starts with a type or namespace keyword (see [classify]):
     * - `namespace` alone or `namespace …` (`namespace a::b`, `inline namespace v1` once `inline` is
     *   dropped) → [Classified.Transparent]; `extern` alone (an `extern "C"` block) → Transparent.
     * - `enum class `, `enum struct `, `class `, `struct `, `union `, `enum ` (check the two-word ones
     *   first): N = the identifier after it (letters, digits, `_`); none → null. After N (trimmed): skip a
     *   template argument list `<…>` (count `<` and `>`) and trim again; then the rest must be empty, or
     *   start with `:` (a base list or an enum's underlying type) or with the word `final` → Class(N).
     *   Anything else (`struct Point p =`, a variable of that type) → null.
     * - Any other core (`struct` alone, `enum`, `class`, a core with no keyword) → null.
     */
    fun typeHeader(core: String): Classified? {
        if (core == "namespace" || core.startsWith("namespace ") || core == "extern") return Classified.Transparent
        val kw = listOf("enum class ", "enum struct ", "class ", "struct ", "union ", "enum ")
            .firstOrNull { core.startsWith(it) } ?: return null
        val n = ident(core, kw.length)
        if (n.isEmpty()) return null
        var rest = core.substring(kw.length + n.length).trim()
        if (rest.startsWith("<")) rest = rest.substring(skipAngles(rest, 0)).trim()
        val ok = rest.isEmpty() || rest.startsWith(":") || rest == "final" ||
            rest.startsWith("final ") || rest.startsWith("final:")
        return if (ok) Classified.Class(n) else null
    }

    private fun ident(s: String, from: Int): String {
        if (from >= s.length || !(s[from].isLetter() || s[from] == '_')) return ""
        var i = from
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(from, i)
    }

    /** [open] is a '<'. The index just after its matching '>' (counting only '<' and '>'), or s.length. */
    private fun skipAngles(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            if (s[i] == '<') depth++
            if (s[i] == '>') { depth--; if (depth == 0) return i + 1 }
        }
        return s.length
    }

    private fun isIdent(s: String) =
        s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' }

    /** s with every <...> group removed (nested ones too): "Box<std::pair<A,B>>::put" -> "Box::put". */
    private fun removeAngles(s: String): String {
        val b = StringBuilder(); var depth = 0
        for (ch in s) { when { ch == '<' -> depth++; ch == '>' && depth > 0 -> depth--; depth == 0 -> b.append(ch) } }
        return b.toString()
    }

    /** Index of the first '(' outside <...> and [...], or -1. */
    private fun firstParen(c: String): Int {
        var angle = 0; var square = 0; var paren = -1
        for (i in c.indices) {
            when (c[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '[' -> square++
                ']' -> if (square > 0) square--
                '(' -> if (angle == 0 && square == 0) { paren = i; break }
            }
        }
        return paren
    }
    
    /** true if '=' occurs outside <...>. */
    private fun topLevelEquals(s: String): Boolean {
        var depth = 0
        for (i in 0 until s.length) {
            when (s[i]) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                '=' -> if (depth == 0) return true
            }
        }
        return false
    }
    
    /** s split at ' ' where the <...> depth is 0, empty pieces dropped. */
    private fun wordsOutsideAngles(s: String): List<String> {
        val words = mutableListOf<String>()
        var start = 0
        var depth = 0
        for (i in 0..s.length) {
            if (i < s.length) {
                when (s[i]) {
                    '<' -> depth++
                    '>' -> if (depth > 0) depth--
                }
            }
            if ((i == s.length || s[i] == ' ') && depth == 0) {
                if (i > start) words.add(s.substring(start, i))
                start = i + 1
            }
        }
        return words
    }

    /** Index of the word `operator` (see KDoc rule 1), or -1. */
    private fun operatorAt(c: String): Int {
        var k = c.indexOf("operator")
        while (k >= 0) {
            val before = k == 0 || c[k - 1] in " :*&"
            val a = k + "operator".length
            val after = a >= c.length || !(c[a].isLetterOrDigit() || c[a] == '_')
            if (before && after) return k
            k = c.indexOf("operator", k + 1)
        }
        return -1
    }

    private fun operatorName(c: String, k: Int): String? {
        var i = k + "operator".length
        while (i < c.length && c[i] == ' ') i++
        val sym: String
        if (c.startsWith("()", i) || c.startsWith("[]", i)) { 
            sym = c.substring(i, i + 2); i += 2 
        }
        else if (i < c.length && (c[i].isLetter() || c[i] == '_')) {
            val e = c.indexOf('(', i); if (e < 0) return null
            sym = c.substring(i, e).trim(); i = e
        } else {
            var e = i
            while (e < c.length && c[e] != '(' && c[e] != ' ') e++
            sym = c.substring(i, e); if (sym.isEmpty()) return null; i = e
        }
        while (i < c.length && c[i] == ' ') i++
        if (i >= c.length || c[i] != '(') return null
        val wordStart = if (k == 0) 0 else c.lastIndexOf(' ', k - 1) + 1
        val prefix = removeAngles(c.substring(wordStart, k)).trimStart('*', '&')
        val letter = sym[0].isLetter() || sym[0] == '_'
        return prefix + "operator" + (if (letter) " $sym" else sym)
    }

    /**
     * Part 2f-2. The name of the function a core declares, or null.
     * 0. The identifier at the start of the core in [CONTROL] → null (`if constexpr (…)`, `sizeof(x)`).
     * 1. **Operators.** If the word `operator` occurs (at the start, or after a space, `:`, `*` or `&`, and
     *    not followed by a letter, digit or `_` that makes it a longer word): after it and any spaces, the
     *    symbol is `()` or `[]` if the text starts with one of those, else a conversion type if it starts
     *    with a letter (take letters, digits, `_`, `:`, `<`, `>`, `*`, `&` and spaces up to the next `(`,
     *    trimmed), else the run of characters up to the next `(` or space. The next non-space character
     *    after the symbol must be `(`, else null. Name = prefix + `operator` + symbol, where a letter
     *    symbol gets one space (`operator bool`) and others none (`operator==`, `operator()`), and prefix =
     *    the text from the start of the word holding `operator` (after the last space before it) up to
     *    `operator`, with every `<…>` removed (`Point::` in `Point Point::operator+(…)`).
     * 2. **Everything else.** `paren` = the first `(` outside `<…>` and `[…]`; none → null. `pre` =
     *    `core[0, paren)` trimmed; empty → null; a `=` outside `<…>` → null (a lambda or initializer).
     *    `words` = `pre` split at spaces outside `<…>`. `q` = the last word with its leading `*` and `&`
     *    characters dropped (`*dup` in `char *dup(…)`), then every `<…>` group removed
     *    (`Box<int>::put` → `Box::put`), then a leading `::` dropped (`::global_fn` → `global_fn`). Split `q` at `::`: every
     *    part must be an identifier, except that the last may be `~` + identifier; otherwise → null.
     *    The last part (without `~`) in [CONTROL] → null; the word before the last word in [CONTROL] → null.
     *    If `words` has one word, `q` has no `::` and does not start with `~` (a constructor in its class,
     *    or a call, or a macro like `TEST(A, B)`): require [MethodHeaders.parse] of the core with [CONTROL]
     *    to be non-null (it checks the parameters are declarations), else null.
     *    → `q` (`Point::length`, `~Circle`, `Shape::~Shape`, `clamp`).
     */
    fun functionName(core: String): String? {
        if (ident(core, 0) in CONTROL) return null
        val k = operatorAt(core)
        if (k >= 0) return operatorName(core, k)
        val paren = firstParen(core)
        if (paren < 0) return null
        val pre = core.substring(0, paren).trim()
        if (pre.isEmpty() || topLevelEquals(pre)) return null
        val words = wordsOutsideAngles(pre)
        val q = removeAngles(words.last().trimStart('*', '&')).removePrefix("::")
        val parts = q.split("::")
        for ((idx, p) in parts.withIndex()) {
            val name = if (idx == parts.lastIndex) p.removePrefix("~") else p
            if (!isIdent(name)) return null
        }
        if (parts.last().removePrefix("~") in CONTROL) return null
        if (words.size >= 2 && words[words.size - 2] in CONTROL) return null
        if (words.size == 1 && "::" !in q && !q.startsWith("~") && MethodHeaders.parse(core, CONTROL) == null) return null
        return q
    }
}
