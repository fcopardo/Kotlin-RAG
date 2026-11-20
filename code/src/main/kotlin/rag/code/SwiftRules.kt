package rag.code

/**
 * Swift's declarations (plan step 2e-3). Input is masked and normalized (see [FamilyRules]).
 *
 * **Core.** Repeatedly drop from the start: spaces; an attribute `@` + name characters (letters,
 * digits, `_`) with an optional `(…)` (to its matching `)`); a word from [MODIFIERS] followed by a space,
 * or followed by `(…)` (`private(set)`: drop the word and its parentheses); `class ` when the next word is
 * `func`, `var`, `let`, `subscript`, `static`, `override` or `final` (a class member, not a class).
 *
 * **classify**, on the core:
 * - `class N`, `struct N`, `enum N`, `protocol N`, `actor N` → Class(N) (N = the leading identifier:
 *   letters, digits, `_`).
 * - `extension T…` → Class(`"extension " + T`), T = the text after `extension ` up to the first `:`,
 *   `<` or ` where`, trimmed (`extension Array where Element: Equatable` → `extension Array`).
 * - `func X`: X = the leading identifier, or, when the text after `func ` starts with an operator
 *   character, the run of characters up to a space, `(` or `<` (`static func + (lhs:…)` → `+`) →
 *   Function(X).
 * - `init` alone, or `init` followed by `(`, `?`, `!` or `<` → Function(`init`); `deinit` → Function(`deinit`);
 *   `subscript` followed by `(` or `<` → Function(`subscript`).
 * - Anything else → null (`var x: Int` computed properties, `get`, `set`, `willSet`, `didSet`, `if`,
 *   `guard`, `switch`, `for`, `while`, `repeat`, `do`, `catch`, `defer`, closures).
 *
 * No transparent blocks and no functions without braces ([bodylessFunction] is null).
 */
internal object SwiftRules : FamilyRules {
    val MODIFIERS = setOf(
        "public", "private", "fileprivate", "internal", "package", "open", "final", "static", "override",
        "mutating", "nonmutating", "lazy", "weak", "unowned", "required", "convenience", "dynamic", "indirect",
        "nonisolated", "optional", "prefix", "postfix", "infix",
    )

    override val annotationPrefixes = listOf("@")

    private fun ident(s: String, from: Int = 0): String {
        if (from >= s.length || !(s[from].isLetter() || s[from] == '_')) return ""
        var i = from
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(from, i)
    }

    private val MEMBER_AFTER_CLASS = setOf("func", "var", "let", "subscript", "static", "override", "final")

    private fun core(h: String): String {
        var i = 0
        while (true) {
            while (i < h.length && h[i] == ' ') i++
            if (i < h.length && h[i] == '@') {                                  // attribute
                i++
                while (i < h.length && (h[i].isLetterOrDigit() || h[i] == '_')) i++
                var j = i; while (j < h.length && h[j] == ' ') j++
                if (j < h.length && h[j] == '(') i = MethodHeaders.matching(h, j) + 1
                continue
            }
            val word = MODIFIERS.firstOrNull { h.startsWith("$it ", i) || h.startsWith("$it(", i) }
            if (word != null) {
                i += word.length
                if (i < h.length && h[i] == '(') i = MethodHeaders.matching(h, i) + 1    // private(set)
                continue
            }
            if (h.startsWith("class ", i) && ident(h, i + 6) in MEMBER_AFTER_CLASS) { i += 6; continue }
            return h.substring(i)
        }
    }

    override fun classify(header: String): Classified? {
        val c = core(header)
        for (kw in listOf("class ", "struct ", "enum ", "protocol ", "actor ")) if (c.startsWith(kw)) {
            val n = ident(c, kw.length)
            return if (n.isEmpty()) null else Classified.Class(n)
        }
        if (c.startsWith("extension ")) {
            var t = c.substring("extension ".length)
            val cut = listOf(t.indexOf(':'), t.indexOf('<'), t.indexOf(" where")).filter { it >= 0 }.minOrNull()
            if (cut != null) t = t.substring(0, cut)
            t = t.trim()
            return if (t.isEmpty()) null else Classified.Class("extension $t")
        }
        if (c.startsWith("func ")) {
            val s = c.substring(5).trimStart()
            val name = if (s.isNotEmpty() && (s[0].isLetter() || s[0] == '_')) ident(s)
                       else s.takeWhile { it != ' ' && it != '(' && it != '<' }
            return if (name.isEmpty()) null else Classified.Function(name)
        }
        if (c == "init" || (c.startsWith("init") && c.length > 4 && c[4] in "(?!<")) return Classified.Function("init")
        if (c == "deinit") return Classified.Function("deinit")
        if (c.startsWith("subscript") && c.length > 9 && c[9] in "(<") return Classified.Function("subscript")
        return null
    }

    override fun bodylessFunction(statement: String): String? = null
}
