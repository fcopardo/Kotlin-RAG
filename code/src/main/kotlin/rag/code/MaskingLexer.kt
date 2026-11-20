package rag.code

/**
 * What [MaskingLexer.mask] returns: the masked [text], anything it could not close, and [uncommented]:
 * the source with only its comments blanked (literals kept), same length and line breaks. It tells a
 * comment line from a line inside a multi-line string, and lets a declaration end on a literal.
 */
class Masked(val text: String, val warnings: List<String>, val uncommented: String = text)

/**
 * Blanks out comments and string, character, template and regex literals, so declarations can be
 * found by scanning for keywords and braces (SPEC §18.4, plan step 2a).
 *
 * - Every masked character becomes a space, except `'\n'`, which is kept. The result has the same
 *   length and the same line breaks as the input: offsets never move.
 * - Delimiters are masked with their literal or comment: quotes, `//`, `/*` and `*/`, `#`, prefixes
 *   such as `r#"`, `@"`, `u8R"` or `f"`.
 * - An interpolation inside a string (`${…}`, `\(…)`, `{…}`) is masked with the string, but it is
 *   scanned to find where the string really ends: strings nested inside it don't end the outer one.
 * - [Language.OTHER] text is returned unchanged.
 * - Never throws. A literal that cannot span lines (a `"…"` or `'…'` string in every family but
 *   Rust) left open ends at its line break. Any other comment or literal left open is masked to the
 *   end of the text. Either adds one warning naming the line it started on
 *   ("line 3: unterminated block comment").
 * - One pass, linear in the length of the text.
 */
object MaskingLexer {
    fun mask(text: String, language: Language): Masked =
        if (language == Language.OTHER || text.isEmpty()) Masked(text, emptyList()) else Scanner(text, language).run()
}

/** How a string holds code: Kotlin/JS `${…}`, Swift `\(…)`, C# `{…}` (with `{{` and `}}` as braces). */
private enum class Holes { DOLLAR_BRACE, BACKSLASH_PAREN, BRACE }

private val REGEX_KEYWORDS = setOf("return", "typeof", "case", "in", "of", "new", "delete", "void", "throw", "yield", "await")
private const val REGEX_AFTER = "(,=:[!&|?{};+-*%<>~^"
private val PYTHON_PREFIXES = setOf("r", "b", "f", "u", "rb", "br", "fr", "rf")

/** One pass over [text]: code is copied, comments and literals come out as spaces (line breaks kept). */
private class Scanner(private val text: String, private val language: Language) {
    private val n = text.length
    private val out = text.toCharArray()
    private val uncommented = text.toCharArray()
    private val warnings = mutableListOf<String>()

    /** Set by [literal]: whether the range it just returned was a comment. */
    private var wasComment = false

    /** The last code character that wasn't whitespace (JS regex rule); its offset, or -1 after a literal. */
    private var lastSig = '\u0000'
    private var lastSigPos = -1

    fun run(): Masked {
        code(0, null)
        return Masked(String(out), warnings, String(uncommented))
    }

    private fun at(k: Int) = if (k in 0 until n) text[k] else '\u0000'

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun blank(from: Int, to: Int) {
        for (k in from until minOf(to, n)) if (out[k] != '\n') out[k] = ' '
    }

    private fun warn(start: Int, what: String) {
        var line = 1
        for (k in 0 until start) if (text[k] == '\n') line++
        warnings += "line $line: unterminated $what"
    }

    /**
     * Scans code from [from]. With a [closer] (inside an interpolation), stops at the first [closer]
     * that isn't matched by an opener and returns its offset; otherwise returns [n].
     */
    private fun code(from: Int, closer: Char?): Int {
        val opener = when (closer) { '}' -> '{'; ')' -> '('; else -> '\u0000' }
        var depth = 0
        var i = from
        while (i < n) {
            val c = text[i]
            if (closer != null) {
                if (c == closer) {
                    if (depth == 0) return i
                    depth--
                } else if (c == opener) {
                    depth++
                }
            }
            val end = literal(i)
            if (end > i) {
                blank(i, end)
                if (wasComment) for (k in i until minOf(end, n)) if (uncommented[k] != '\n') uncommented[k] = ' '
                i = end
                continue
            }
            if (!c.isWhitespace()) {
                lastSig = c
                lastSigPos = i
            }
            i++
        }
        return n
    }

    /** The end (exclusive) of a comment or literal starting at [i], or -1. */
    private fun literal(i: Int): Int {
        wasComment = false
        if (language == Language.PYTHON) {
            if (text[i] == '#') wasComment = true
            return python(i)
        }
        val c = text[i]
        if (c == '/' && (at(i + 1) == '/' || at(i + 1) == '*')) {
            wasComment = true
            return if (at(i + 1) == '/') lineEnd(i) else blockComment(i)
        }
        val end = when (language) {
            Language.KOTLIN -> kotlin(i)
            Language.JAVA -> java(i)
            Language.JS_TS -> js(i)
            Language.C_CPP -> cpp(i)
            Language.CSHARP -> csharp(i)
            Language.GO -> go(i)
            Language.RUST -> rust(i)
            Language.SWIFT -> swift(i)
            else -> -1
        }
        if (end > i) {
            // A literal is an operand: a `/` after it divides.
            lastSig = 'x'
            lastSigPos = -1
        }
        return end
    }

    private fun lineEnd(i: Int): Int {
        var j = i
        while (j < n && text[j] != '\n') j++
        return j
    }

    private fun blockComment(i: Int): Int {
        val nests = language == Language.KOTLIN || language == Language.RUST || language == Language.SWIFT
        var depth = 1
        var j = i + 2
        while (j < n) {
            if (text[j] == '*' && at(j + 1) == '/') {
                j += 2
                if (--depth == 0) return j
            } else if (nests && text[j] == '/' && at(j + 1) == '*') {
                j += 2
                depth++
            } else {
                j++
            }
        }
        warn(i, "block comment")
        return n
    }

    /**
     * The end of a literal that started at [start] and whose body starts at [from]: just past [close].
     * [doubled]: the close quote written twice is a quote (C# verbatim). A one-line literal left open
     * ends at its line break; anything left open is reported.
     */
    private fun body(
        start: Int, from: Int, close: String, what: String,
        escapes: Boolean = true, multiLine: Boolean = false, holes: Holes? = null, doubled: Boolean = false,
    ): Int {
        var j = from
        while (j < n) {
            val c = text[j]
            if (escapes && c == '\\') {
                if (holes == Holes.BACKSLASH_PAREN && at(j + 1) == '(') {
                    j = past(code(j + 2, ')'))
                } else {
                    j += 2
                }
                continue
            }
            if (holes == Holes.DOLLAR_BRACE && c == '$' && at(j + 1) == '{') {
                j = past(code(j + 2, '}'))
                continue
            }
            if (holes == Holes.BRACE && c == '{') {
                j = if (at(j + 1) == '{') j + 2 else past(code(j + 1, '}'))
                continue
            }
            if (holes == Holes.BRACE && c == '}' && at(j + 1) == '}') {
                j += 2
                continue
            }
            if (doubled && c == close[0] && at(j + 1) == close[0]) {
                j += 2
                continue
            }
            if (text.startsWith(close, j)) return j + close.length
            if (c == '\n' && !multiLine) {
                warn(start, what)
                return j
            }
            j++
        }
        warn(start, what)
        return n
    }

    private fun past(closer: Int) = if (closer >= n) n else closer + 1

    private fun quotes(k: Int): Int {
        var j = k
        while (j < n && text[j] == '"') j++
        return j - k
    }

    // --- one function per family: the literal starting at i, or -1 ---

    private fun kotlin(i: Int): Int = when (text[i]) {
        '"' -> if (text.startsWith("\"\"\"", i)) {
            // A raw string may end with more than three quotes: the extra ones are its content.
            var end = body(i, i + 3, "\"\"\"", "raw string", escapes = false, multiLine = true, holes = Holes.DOLLAR_BRACE)
            while (end < n && text[end] == '"') end++
            end
        } else {
            body(i, i + 1, "\"", "string", holes = Holes.DOLLAR_BRACE)
        }
        '\'' -> body(i, i + 1, "'", "character literal")
        else -> -1
    }

    private fun java(i: Int): Int = when (text[i]) {
        '"' -> if (text.startsWith("\"\"\"", i)) body(i, i + 3, "\"\"\"", "text block", multiLine = true)
        else body(i, i + 1, "\"", "string")
        '\'' -> body(i, i + 1, "'", "character literal")
        else -> -1
    }

    private fun js(i: Int): Int = when (text[i]) {
        '"', '\'' -> body(i, i + 1, text[i].toString(), "string")
        '`' -> body(i, i + 1, "`", "template literal", multiLine = true, holes = Holes.DOLLAR_BRACE)
        '/' -> if (regexAllowed()) regex(i) else -1
        else -> -1
    }

    /** A `/` starts a regex where an expression starts: after an operator, an opener or a keyword. */
    private fun regexAllowed(): Boolean {
        if (lastSig == '\u0000') return true
        if (lastSig in REGEX_AFTER) return true
        if (lastSigPos >= 0 && isIdent(lastSig)) {
            var s = lastSigPos
            while (s > 0 && isIdent(text[s - 1])) s--
            return text.substring(s, lastSigPos + 1) in REGEX_KEYWORDS
        }
        return false
    }

    private fun regex(i: Int): Int {
        var j = i + 1
        var inClass = false
        while (j < n) {
            val c = text[j]
            if (c == '\\') {
                j += 2
                continue
            }
            if (c == '\n') {
                warn(i, "regex")
                return j
            }
            if (c == '[') inClass = true
            else if (c == ']') inClass = false
            else if (c == '/' && !inClass) return j + 1
            j++
        }
        warn(i, "regex")
        return n
    }

    private fun cpp(i: Int): Int = when (text[i]) {
        'R', 'u', 'U', 'L' -> cppRawString(i)
        '"' -> body(i, i + 1, "\"", "string")
        '\'' -> if (digitSeparator(i)) -1 else body(i, i + 1, "'", "character literal")
        else -> -1
    }

    /** `R"delim( … )delim"`, optionally after `u8`, `u`, `U` or `L`. */
    private fun cppRawString(i: Int): Int {
        if (i > 0 && isIdent(text[i - 1])) return -1
        var j = i
        if (text.startsWith("u8", j)) j += 2 else if (text[j] != 'R') j++
        if (at(j) != 'R' || at(j + 1) != '"') return -1
        val open = text.indexOf('(', j + 2)
        if (open < 0 || open - (j + 2) > 16) return -1
        val delimiter = text.substring(j + 2, open)
        if (delimiter.any { it.isWhitespace() || it == ')' || it == '\\' || it == '"' }) return -1
        return body(i, open + 1, ")$delimiter\"", "raw string", escapes = false, multiLine = true)
    }

    /** `1'000'000`, `0xFF'FF`: a `'` between two hex digits of a token that started with a digit. */
    private fun digitSeparator(i: Int): Boolean {
        if (!at(i - 1).isHexDigit() || !at(i + 1).isHexDigit()) return false
        var s = i - 1
        while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '\'' || text[s - 1] == '.')) s--
        return text[s].isDigit()
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun csharp(i: Int): Int {
        val c = text[i]
        val next = at(i + 1)
        return when {
            (c == '$' && next == '@' || c == '@' && next == '$') && at(i + 2) == '"' ->
                body(i, i + 3, "\"", "verbatim string", escapes = false, multiLine = true, holes = Holes.BRACE, doubled = true)
            c == '@' && next == '"' -> body(i, i + 2, "\"", "verbatim string", escapes = false, multiLine = true, doubled = true)
            c == '$' && next == '"' -> {
                val q = quotes(i + 1)
                if (q >= 3) body(i, i + 1 + q, "\"".repeat(q), "raw string", escapes = false, multiLine = true, holes = Holes.BRACE)
                else body(i, i + 2, "\"", "string", holes = Holes.BRACE)
            }
            c == '"' -> {
                val q = quotes(i)
                if (q >= 3) body(i, i + q, "\"".repeat(q), "raw string", escapes = false, multiLine = true)
                else body(i, i + 1, "\"", "string")
            }
            c == '\'' -> body(i, i + 1, "'", "character literal")
            else -> -1
        }
    }

    private fun go(i: Int): Int = when (text[i]) {
        '`' -> body(i, i + 1, "`", "raw string", escapes = false, multiLine = true)
        '"' -> body(i, i + 1, "\"", "string")
        '\'' -> body(i, i + 1, "'", "rune")
        else -> -1
    }

    private fun rust(i: Int): Int {
        val c = text[i]
        if (c == 'r' || c == 'b') {
            if (i > 0 && isIdent(text[i - 1])) return -1
            var j = i
            val isByte = c == 'b'
            if (isByte) {
                j++
                if (at(j) == '\'') return body(i, j + 1, "'", "byte literal")
                if (at(j) == '"') return body(i, j + 1, "\"", "byte string", multiLine = true)
            }
            if (at(j) != 'r') return -1
            j++
            var hashes = 0
            while (at(j + hashes) == '#') hashes++
            if (at(j + hashes) != '"') return -1   // e.g. the raw identifier r#type
            return body(i, j + hashes + 1, "\"" + "#".repeat(hashes), "raw string", escapes = false, multiLine = true)
        }
        if (c == '"') return body(i, i + 1, "\"", "string", multiLine = true)
        if (c == '\'') {
            if (at(i + 1) == '\\') return body(i, i + 1, "'", "character literal")
            if (i + 1 < n) {
                val width = Character.charCount(text.codePointAt(i + 1))
                if (at(i + 1 + width) == '\'') return i + 2 + width
            }
            return -1   // a lifetime ('a, 'static) or a loop label ('outer:)
        }
        return -1
    }

    private fun swift(i: Int): Int {
        val c = text[i]
        if (c == '#') {
            var h = 0
            while (at(i + h) == '#') h++
            if (at(i + h) != '"') return -1
            val hashes = "#".repeat(h)
            return if (text.startsWith("\"\"\"", i + h)) body(i, i + h + 3, "\"\"\"$hashes", "raw string", escapes = false, multiLine = true)
            else body(i, i + h + 1, "\"$hashes", "raw string", escapes = false)
        }
        if (c == '"') {
            return if (text.startsWith("\"\"\"", i)) body(i, i + 3, "\"\"\"", "string", multiLine = true, holes = Holes.BACKSLASH_PAREN)
            else body(i, i + 1, "\"", "string", holes = Holes.BACKSLASH_PAREN)
        }
        return -1
    }

    private fun python(i: Int): Int {
        val c = text[i]
        if (c == '#') return lineEnd(i)
        var quoteAt = i
        if (c.isLetter()) {
            if (i > 0 && isIdent(text[i - 1])) return -1
            var j = i
            while (j < n && j - i < 2 && text[j].isLetter()) j++
            if (at(j) != '"' && at(j) != '\'') return -1
            if (text.substring(i, j).lowercase() !in PYTHON_PREFIXES) return -1
            quoteAt = j
        } else if (c != '"' && c != '\'') {
            return -1
        }
        val quote = text[quoteAt]
        val triple = "$quote$quote$quote"
        return if (text.startsWith(triple, quoteAt)) body(i, quoteAt + 3, triple, "string", multiLine = true)
        else body(i, quoteAt + 1, quote.toString(), "string")
    }
}
