package rag.code

/** A method or constructor header: its [name], and the offsets of its parameter list's `(` and `)`. */
internal data class MethodHeader(val name: String, val paramsOpen: Int, val paramsClose: Int)

/**
 * Method and constructor headers of the C-style languages (Java, C#, later C++), plan step 2c-1.
 * Input is a header's "core": masked, normalized (single spaces, trimmed), its leading annotations,
 * attributes and modifier words already removed by the caller (but modifiers left in are harmless).
 */
internal object MethodHeaders {
    /** A token right before the name that makes the header an expression, not a declaration (`new Foo()`). */
    val EXPRESSION_STARTERS = setOf("new", "return", "throw", "await", "yield", "else", "case", "goto", "typeof", "sizeof", "nameof")

    /** Parameter modifiers and markers skipped before counting a parameter's words. */
    val PARAMETER_MODIFIERS = setOf("final", "ref", "out", "in", "params", "this", "scoped", "readonly")

    /**
     * The method or constructor declared by [core], or null.
     *
     * 1. `paren` = the first `(` outside `<…>` and `[…]` (count both kinds of bracket). None → null.
     * 2. `pre` = `core[0, paren)` trimmed. Empty → null. A `=` in `pre` outside `<…>` → null (an
     *    initializer or a lambda: `Runnable r = () -> …`).
     * 3. Split `pre` into words at spaces outside `<…>` (so `Map<String, List<Integer>>` and
     *    `<T extends Shape>` are one word each). `name` = the last word, without a trailing type
     *    parameter list (C#/C++ `Find<TKey>` → `Find`: cut at its first `<`). It must be an identifier:
     *    a letter, `_` or `$`, then letters, digits, `_` or `$`. Otherwise → null (`list.forEach(…)`).
     * 4. `name` in [controlWords] → null. The word just before `name` in [EXPRESSION_STARTERS] → null.
     * 5. `close` = [matching] of `paren`.
     * 6. If `name` is the only word (a constructor, or a call like `foo(x)`, or an enum constant
     *    `RED(1)`): the parameter text `core(paren, close)` must be blank, or every parameter (split at
     *    commas outside `()`, `<>`, `[]`, `{}`) must have at least two words once its leading annotations
     *    (`@X`, `@X(…)`), attributes (`[…]`) and [PARAMETER_MODIFIERS] are dropped (`int x` yes, `1` no,
     *    `x` no, `String... args` yes, `int x = 0` yes). Otherwise → null.
     * 7. → `MethodHeader(name, paren, close)`.
     */
    fun parse(core: String, controlWords: Set<String>): MethodHeader? {
        // 1. first '(' outside <> and []
        var angle = 0; var square = 0; var paren = -1
        for (i in core.indices) {
            when (core[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '[' -> square++
                ']' -> if (square > 0) square--
                '(' -> if (angle == 0 && square == 0) { paren = i; break }
            }
        }
        if (paren < 0) return null
        val pre = core.substring(0, paren).trim()
        if (pre.isEmpty() || hasTopLevelEquals(pre)) return null          // '=' outside <...>
        val words = wordsOutsideAngles(pre)                               // split at ' ' when angle depth == 0
        val name = words.last().substringBefore('<')
        if (!isIdentifier(name)) return null                              // [A-Za-z_$][A-Za-z0-9_$]*
        if (name in controlWords) return null
        if (words.size >= 2 && words[words.size - 2] in EXPRESSION_STARTERS) return null
        val close = matching(core, paren)
        if (words.size == 1 && !declaredParameters(core.substring(paren + 1, close))) return null
        return MethodHeader(name, paren, close)
    }

    /**
     * The index of the bracket that closes the one at [open], counting `(`/`)`, `[`/`]` and `{`/`}`
     * together as one depth, or `s.length - 1` if it never closes.
     */
    fun matching(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return s.length - 1
    }

    /** Checks if a string is a valid identifier. */
    private fun isIdentifier(s: String): Boolean {
        if (s.isEmpty()) return false
        val first = s[0]
        if (!(first.isLetter() || first == '_' || first == '$')) return false
        for (i in 1 until s.length) {
            val ch = s[i]
            if (!(ch.isLetterOrDigit() || ch == '_' || ch == '$')) return false
        }
        return true
    }

    /** Checks if there's an equals sign outside angle brackets. */
    private fun hasTopLevelEquals(s: String): Boolean {
        var angle = 0
        for (i in s.indices) {
            when (s[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '=' -> if (angle == 0) return true
            }
        }
        return false
    }

    /** Splits a string into words at spaces, but not inside angle brackets. */
    private fun wordsOutsideAngles(s: String): List<String> {
        val words = mutableListOf<String>()
        var start = 0
        var angle = 0
        for (i in s.indices) {
            when (s[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                ' ' -> if (angle == 0) {
                    if (start < i) {
                        words.add(s.substring(start, i))
                    }
                    start = i + 1
                }
            }
        }
        if (start < s.length) {
            words.add(s.substring(start))
        }
        return words
    }

    /** Blank, or every comma-separated parameter has >= 2 words after dropping @annotations(...), [attributes] and PARAMETER_MODIFIERS. */
    private fun declaredParameters(params: String): Boolean {
        // Split at commas when depth of (),<>,[],{} is 0
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var angle = 0
        var square = 0
        var curly = 0
        for (i in params.indices) {
            when (params[i]) {
                '(', '[', '{' -> {
                    when (params[i]) {
                        '(' -> depth++
                        '[' -> square++
                        '{' -> curly++
                    }
                }
                ')', ']', '}' -> {
                    when (params[i]) {
                        ')' -> depth--
                        ']' -> square--
                        '}' -> curly--
                    }
                }
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                ',' -> if (depth == 0 && angle == 0 && square == 0 && curly == 0) {
                    parts.add(params.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        parts.add(params.substring(start).trim())
        
        // Check each parameter
        for (param in parts) {
            if (param.isEmpty()) continue
            
            // Drop leading annotations (@X, @X(...))
            var p = param
            while (p.startsWith("@")) {
                val next = p.indexOf('(', 1)
                if (next != -1) {
                    val end = matching(p, next)
                    if (end != -1) {
                        p = p.substring(0, next) + p.substring(end + 1).trim()
                    } else {
                        p = p.substring(1)
                    }
                } else {
                    p = p.substring(1)
                }
            }
            
            // Drop leading attributes ([...])
            if (p.startsWith("[")) {
                val end = matching(p, 0)
                if (end != -1) {
                    p = p.substring(end + 1).trim()
                }
            }
            
            // Drop leading parameter modifiers
            val words = p.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (words.isEmpty()) return false
            
            // Check if we have at least 2 words after dropping modifiers
            var wordCount = 0
            for (word in words) {
                if (word !in PARAMETER_MODIFIERS) {
                    wordCount++
                }
            }
            if (wordCount < 2) return false
        }
        return true
    }
}
