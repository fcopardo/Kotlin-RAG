package rag.code

/**
 * Kotlin's declarations (plan step 2b). Input is masked and normalized (see [FamilyRules]).
 *
 * **Prefix.** Both functions first skip the header's prefix, in this order, repeatedly:
 * - annotations: `@` + name characters (letters, digits, `_`, `.`, `:`), then optionally spaces and a
 *   parenthesized argument list (skip to its matching `)`), then spaces (`@Suppress( ) @JvmStatic fun …`);
 * - modifier words from [MODIFIERS], each followed by a space.
 * The rest is the "core".
 *
 * **classify(header)**, on the core:
 * - `class N`, `interface N`, `object N`, `fun interface N` → [Classified.Class] (N = the name token);
 * - `companion object` → Class(`Companion`); `companion object N` → Class(N) (a `:` after `object` means
 *   no name: `companion object : Creator` → `Companion`);
 * - `fun` (see "function names") → [Classified.Function], or null for an anonymous `fun(`;
 * - `constructor(` → Function(`constructor`);
 * - anything else (including `object : X`, `init`, `get()`, `if (…)`, lambdas) → null.
 * Kotlin has no [Classified.Transparent] blocks (`package` has no braces).
 *
 * **Function names.** After `fun `: skip a type parameter list `<…>` (balanced angle brackets) and
 * spaces. The text up to the first `(` that is outside `<…>` and backticks is the "signature name",
 * trimmed. If it contains a `.` outside `<…>` and backticks, keep the part after the last such `.`
 * (`List<T>.maxOrNull` → `maxOrNull`). A name in backticks loses them (`` `parse a list` `` →
 * `parse a list`). An empty name is an anonymous function.
 *
 * **bodylessFunction(statement)**: the core must be a named `fun` (as above). Find its parameter
 * list: the `(` after the name, then its matching `)` counting `(`/`)`, `[`/`]` and `{`/`}` together
 * (a default argument may hold a lambda: `cb: () -> Unit = {}`). Then scan the rest at depth 0 of
 * `()`, `[]`, `{}` and `<>` (where `->` is an arrow, not a `>`): the first `{` at depth 0 means a brace
 * body → null; the first `=` at depth 0 that is not part of `==`, `!=`, `<=` or `>=` → the name; the end
 * of the text → null (an abstract or interface function).
 */
internal object KotlinRules : FamilyRules {
    val MODIFIERS = setOf(
        "public", "private", "protected", "internal", "open", "final", "abstract", "sealed", "data", "enum",
        "annotation", "inner", "value", "override", "suspend", "inline", "operator", "infix", "tailrec",
        "external", "expect", "actual", "const", "lateinit", "noinline", "crossinline",
    )

    override val annotationPrefixes = listOf("@")

    /** The header without its leading annotations and modifier words. */
    private fun core(h: String): String {
        var i = 0
        while (true) {
            while (i < h.length && h[i] == ' ') i++
            if (i < h.length && h[i] == '@') {                       // annotation
                i++
                while (i < h.length && (h[i].isLetterOrDigit() || h[i] in "_.:")) i++
                var j = i; while (j < h.length && h[j] == ' ') j++
                if (j < h.length && h[j] == '(') i = matching(h, j) + 1 // skip its arguments
                continue
            }
            val word = h.substring(i).takeWhile { it.isLetterOrDigit() || it == '_' }
            if (word in MODIFIERS && h.startsWith("$word ", i)) { i += word.length + 1; continue }
            return h.substring(i)
        }
    }

    /** Index of the bracket closing the one at [open] ((), [], {} counted together), or h.length - 1. */
    private fun matching(h: String, open: Int): Int {
        val openChar = h[open]
        val closeChar = when (openChar) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '<' -> '>'
            else -> return h.length - 1
        }
        var depth = 1
        var i = open + 1
        while (i < h.length && depth > 0) {
            when (h[i]) {
                openChar -> depth++
                closeChar -> depth--
            }
            i++
        }
        return if (i <= h.length) i - 1 else h.length - 1
    }

    /** The name token at the start of [s]: `` `…` `` without the backticks, or letters/digits/_/$; "" if none. */
    private fun nameToken(s: String): String {
        val trimmed = s.trimStart()
        if (trimmed.isEmpty()) return ""
        
        // Check for backtick-quoted name
        if (trimmed[0] == '`') {
            val end = trimmed.indexOf('`', 1)
            if (end != -1) {
                return trimmed.substring(1, end)
            }
        }
        
        // Regular name: letters, digits, underscores, dollar signs
        val name = trimmed.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
        return if (name.isNotEmpty()) name else ""
    }

    /** For a core starting with "fun ": the function's name ("" for an anonymous one) and the index of its `(` in the core. */
    private fun function(core: String): Pair<String, Int>? {
        if (!core.startsWith("fun ")) return null
        
        var i = 4 // skip "fun "
        // Skip type parameters if present
        if (i < core.length && core[i] == '<') {
            val end = matching(core, i)
            i = end + 1
        }
        // Skip spaces
        while (i < core.length && core[i] == ' ') i++
        
        // Find the first '(' that is outside <...> and backticks
        var parenIndex = -1
        var depth = 0
        var inBackticks = false
        var j = i
        while (j < core.length) {
            val ch = core[j]
            when {
                ch == '`' -> inBackticks = !inBackticks
                !inBackticks && ch == '<' -> depth++
                !inBackticks && ch == '>' -> depth--
                !inBackticks && depth == 0 && ch == '(' -> {
                    parenIndex = j
                    break
                }
            }
            j++
        }
        
        if (parenIndex == -1) return null
        
        // Extract the signature name
        val sig = core.substring(i, parenIndex).trim()
        if (sig.isEmpty()) return Pair("", parenIndex)
        
        // Find the last '.' outside <...> and backticks to extract the name
        var nameStart = 0
        var nameDepth = 0
        var nameInBackticks = false
        for (k in sig.length - 1 downTo 0) {
            when {
                sig[k] == '`' -> nameInBackticks = !nameInBackticks
                !nameInBackticks && sig[k] == '<' -> nameDepth++
                !nameInBackticks && sig[k] == '>' -> nameDepth--
                !nameInBackticks && nameDepth == 0 && sig[k] == '.' -> {
                    nameStart = k + 1
                    break
                }
            }
        }
        
        val name = sig.substring(nameStart).trim()
        // If the name is backtick-quoted, remove the backticks
        val cleanName = if (name.startsWith("`") && name.endsWith("`")) {
            name.substring(1, name.length - 1)
        } else {
            name
        }
        return Pair(cleanName, parenIndex)
    }

    override fun classify(header: String): Classified? {
        val c = core(header)
        when {
            c.startsWith("fun interface ") -> return Classified.Class(nameToken(c.removePrefix("fun interface ")))
            c.startsWith("class ") || c.startsWith("interface ") || c.startsWith("object ") ->
                nameToken(c.substringAfter(' ')).takeIf { it.isNotEmpty() }?.let { return Classified.Class(it) }
            c == "companion object" || c.startsWith("companion object ") -> {
                val rest = c.removePrefix("companion object").trim()
                return Classified.Class(nameToken(rest).ifEmpty { "Companion" })
            }
            c.startsWith("fun ") -> function(c)?.first?.takeIf { it.isNotEmpty() }?.let { return Classified.Function(it) }
            c.startsWith("constructor(") || c.startsWith("constructor (") -> return Classified.Function("constructor")
        }
        return null
    }

    override fun bodylessFunction(statement: String): String? {
        val c = core(statement)
        if (!c.startsWith("fun ") || c.startsWith("fun interface ")) return null
        val (name, paren) = function(c) ?: return null
        if (name.isEmpty()) return null
        var i = matching(c, paren) + 1
        var depth = 0
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '-' && i + 1 < c.length && c[i + 1] == '>' -> i++           // an arrow, not a '>'
                ch in "([{<" -> { if (ch == '{' && depth == 0) return null; depth++ }
                ch in ")]}>" -> depth--
                ch == '=' && depth == 0 -> {
                    val prev = if (i > 0) c[i - 1] else ' '
                    val next = if (i + 1 < c.length) c[i + 1] else ' '
                    if (next != '=' && prev !in "=!<>") return name
                    if (next == '=') i++                                         // skip "=="
                }
            }
            i++
        }
        return null
    }
}
