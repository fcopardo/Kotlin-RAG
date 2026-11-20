package rag.code

/**
 * JavaScript and TypeScript declarations (plan step 2d). Input is masked and normalized (see
 * [FamilyRules]): strings, template literals and regexes are spaces.
 *
 * **Core.** Repeatedly drop from the start: spaces; a decorator `@` + name characters (letters,
 * digits, `_`, `$`, `.`) with an optional `(…)` (skip to its matching `)`); a word from [MODIFIERS]
 * followed by a space; `get ` or `set ` when the next character starts an identifier (`get length()`,
 * but not `get(key)`).
 *
 * **classify**, on the core, first match wins:
 * 1. `const enum N` → Class(N).
 * 2. `namespace …`, `module …`, `module`, `global`, `global …` → [Classified.Transparent] (TS
 *    `namespace A.B`, `declare module "x"`, `declare global`).
 * 3. `class` alone or `class N …` → Class(N, or `default` when anonymous); `interface N`, `enum N` → Class(N).
 * 4. `function` followed by a space, `*` or `(` (not `functionsOf(…)`): the name after it (and an optional `*`), followed by `(` or `<` →
 *    Function(name); anonymous → null.
 * 5. An assignment (see below) `name = rhs`: with `async ` dropped from rhs, rhs starting with
 *    `function` → Function(name); starting with `class` → Class(name); an arrow head whose body is
 *    empty (the block is its body: `(a, b) =>`, `x =>`, `async <T>(x: T): Promise<T> =>`) → Function(name);
 *    else null (object literals, calls, values).
 * 6. A method: optional `*`, then a name (letters, digits, `_`, `$`, `#`; not starting with a digit), an
 *    optional `<…>`, then `(`; the name not in [CONTROL]; after the matching `)` nothing, or a `:` return
 *    type → Function(name). (`constructor(…)`, `#tick()`, `add(o: Vec): Vec`.)
 * 7. null.
 *
 * **Assignment** (`name = rhs`): drop a leading `const `, `let ` or `var `; `name` = the leading
 * identifier (letters, digits, `_`, `$`, `#`; not starting with a digit). Scan on for the first `=` at
 * depth 0 of `()`, `[]`, `{}`, `<>`, skipping `=>` (an arrow, not an `=` nor a `>`): it must not be
 * part of `==`, and the text between the name and it must be empty or a type starting with `:`
 * (`handler: (e: Event) => void = …`). rhs = the text after it, trimmed. No such `=` → not an assignment.
 *
 * **Arrow head** of an rhs: drop `async `; skip a type parameter list `<…>`; then a parameter list
 * `(…)` (to its matching `)`) or one identifier; then optional spaces and a `:` return type, which runs to
 * the first `=>` at depth 0; then `=>` must follow. The arrow's body = the text after `=>`, trimmed. Any
 * other shape → not an arrow.
 *
 * **bodylessFunction(statement)**: an assignment whose rhs is an arrow with a body that does not start
 * with `{` → its name (an empty body counts: the expression continues on the next line). Function
 * declarations, methods and everything else → null.
 */
internal object JsRules : FamilyRules {
    val MODIFIERS = setOf(
        "export", "default", "declare", "async", "static", "public", "private", "protected", "readonly",
        "override", "abstract", "accessor",
    )
    val CONTROL = setOf(
        "if", "for", "while", "switch", "catch", "with", "function", "return", "typeof", "new", "delete", "void",
        "throw", "await", "yield", "else", "do", "try", "finally", "case", "super", "import",
    )

    override val annotationPrefixes = listOf("@")

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$' || c == '#'
    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '#'
    private fun ident(s: String, from: Int = 0): String =
        if (from < s.length && isIdentStart(s[from])) s.substring(from).takeWhile(::isIdentChar) else ""

    private fun core(h: String): String {
        var i = 0
        while (true) {
            while (i < h.length && h[i] == ' ') i++
            if (i < h.length && h[i] == '@') {                                   // decorator
                i++
                while (i < h.length && (h[i].isLetterOrDigit() || h[i] in "_$.")) i++
                var j = i; while (j < h.length && h[j] == ' ') j++
                if (j < h.length && h[j] == '(') i = MethodHeaders.matching(h, j) + 1
                continue
            }
            val word = MODIFIERS.firstOrNull { h.startsWith("$it ", i) }
            if (word != null) { i += word.length + 1; continue }
            if ((h.startsWith("get ", i) || h.startsWith("set ", i)) && i + 4 < h.length && isIdentStart(h[i + 4])) { i += 4; continue }
            return h.substring(i)
        }
    }

    /** `name = rhs` → name to rhs (see the KDoc "Assignment"), or null. */
    private fun assignment(c: String): Pair<String, String>? {
        var s = c
        for (kw in listOf("const ", "let ", "var ")) if (s.startsWith(kw)) { s = s.substring(kw.length); break }
        val name = ident(s)
        if (name.isEmpty()) return null
        var depth = 0
        var i = name.length
        while (i < s.length) {
            val ch = s[i]
            if (ch == '=' && i + 1 < s.length && s[i + 1] == '>') { i += 2; continue }     // arrow
            when (ch) {
                '(', '[', '{', '<' -> depth++
                ')', ']', '}', '>' -> depth--
                '=' -> if (depth == 0) {
                    if (i + 1 < s.length && s[i + 1] == '=') return null                      // ==, ===
                    val between = s.substring(name.length, i).trim()
                    if (between.isNotEmpty() && !between.startsWith(":")) return null
                    return name to s.substring(i + 1).trim()
                }
            }
            i++
        }
        return null
    }

    /** The body after `=>` of an arrow head (see the KDoc "Arrow head"), or null when rhs is not an arrow. */
    private fun arrowBody(rhs: String): String? {
        var s = rhs.removePrefix("async ").trim()
        var i = 0
        if (s.startsWith("<")) {                                  // type parameters
            var d = 0
            while (i < s.length) { if (s[i] == '<') d++ else if (s[i] == '>') { d--; if (d == 0) { i++; break } }; i++ }
        }
        if (i < s.length && s[i] == '(') i = MethodHeaders.matching(s, i) + 1
        else { val p = ident(s, i); if (p.isEmpty()) return null; i += p.length }
        while (i < s.length && s[i] == ' ') i++
        if (i < s.length && s[i] == ':') {                        // return type: up to the first "=>" at depth 0
            var d = 0; i++
            while (i < s.length && !(d == 0 && s.startsWith("=>", i))) {
                when (s[i]) { '(', '[', '{', '<' -> d++; ')', ']', '}', '>' -> d-- }
                i++
            }
        }
        if (!s.startsWith("=>", i)) return null
        return s.substring(i + 2).trim()
    }

    override fun classify(header: String): Classified? {
        val c = core(header)
        if (c.startsWith("const enum ")) return ident(c, "const enum ".length).takeIf { it.isNotEmpty() }?.let { Classified.Class(it) }
        if (c == "module" || c == "global" || listOf("namespace ", "module ", "global ").any { c.startsWith(it) }) return Classified.Transparent
        if (c == "class") return Classified.Class("default")
        for (kw in listOf("class ", "interface ", "enum ")) if (c.startsWith(kw)) {
            val n = ident(c, kw.length)
            return if (n.isNotEmpty()) Classified.Class(n) else if (kw == "class ") Classified.Class("default") else null
        }
        if (c == "function" || c.startsWith("function ") || c.startsWith("function*") || c.startsWith("function(")) {
            var i = "function".length
            while (i < c.length && (c[i] == ' ' || c[i] == '*')) i++
            val n = ident(c, i)
            if (n.isEmpty()) return null
            var j = i + n.length; while (j < c.length && c[j] == ' ') j++
            return if (j < c.length && (c[j] == '(' || c[j] == '<')) Classified.Function(n) else null
        }
        assignment(c)?.let { (name, rhs0) ->
            val rhs = rhs0.removePrefix("async ").trim()
            if (rhs.startsWith("function")) return Classified.Function(name)
            if (rhs == "class" || rhs.startsWith("class ")) return Classified.Class(name)
            return if (arrowBody(rhs) == "") Classified.Function(name) else null
        }
        return method(c)?.let { Classified.Function(it) }
    }

    private fun method(c: String): String? {
        val s = c.removePrefix("*").trimStart()
        val name = ident(s)
        if (name.isEmpty() || name in CONTROL) return null
        var i = name.length
        while (i < s.length && s[i] == ' ') i++
        if (i < s.length && s[i] == '<') { var d = 0; while (i < s.length) { if (s[i] == '<') d++ else if (s[i] == '>') { d--; if (d == 0) { i++; break } }; i++ } }
        while (i < s.length && s[i] == ' ') i++
        if (i >= s.length || s[i] != '(') return null
        val rest = s.substring(MethodHeaders.matching(s, i) + 1).trim()
        return if (rest.isEmpty() || rest.startsWith(":")) name else null
    }

    override fun bodylessFunction(statement: String): String? {
        val (name, rhs) = assignment(core(statement)) ?: return null
        val body = arrowBody(rhs) ?: return null
        return if (body.startsWith("{")) null else name
    }
}
