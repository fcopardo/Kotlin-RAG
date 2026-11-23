package rag.code

import java.util.regex.Pattern

/**
 * The `.gitignore` rules of a project folder (SPEC §18.4; plan step 4, part 4a), as Git reads them.
 *
 * **What it must do.** Collect the rules of every `.gitignore` in the project (the root one and nested
 * ones) and answer whether a path is ignored. Paths are relative to the project root and `/`-separated.
 * [isIgnored] judges the path itself only: the folder walker asks about each folder before entering it,
 * so a file inside an ignored folder is never asked about (as in Git, a file cannot be re-included when
 * its folder is excluded).
 *
 * **How.**
 * 1. Each line of a `.gitignore` is one rule. Lines may end in `\r\n`. A blank line or one starting with
 *    `#` is no rule. Trailing spaces are dropped unless the last one is escaped with a backslash
 *    (`foo\ ` keeps one space). A leading `\#` or `\!` stands for a literal `#` or `!`.
 * 2. A leading `!` negates the rule: a path it matches is not ignored.
 * 3. A trailing `/` means the rule matches folders only; remove it before reading the rest.
 * 4. If what is left still contains a `/` (at the start or in the middle), the rule is anchored: it is
 *    matched against the path relative to the `.gitignore`'s folder, from its start (a leading `/` is
 *    dropped). Otherwise it is matched against the last segment (the name) of the path, at any depth
 *    below that folder.
 * 5. Patterns: `*` matches any run of characters except `/`; `?` matches one character except `/`;
 *    `[…]` matches one character from a set or range, `[!…]` one not in it. `**` is special only as a
 *    whole segment: at the start, followed by `/`, it matches in every folder (zero or more folders); at
 *    the end, after a `/`, it matches everything inside (but not the folder itself); between two `/` in
 *    the middle it matches zero or more folders. A match covers the whole path or name, not part of it. A backslash makes the next character
 *    literal.
 * 6. A `.gitignore` in folder B only applies to paths inside B. Rules are tried in order: shallower
 *    `.gitignore` files first, each file's rules top to bottom; the LAST rule that matches decides. No
 *    match means not ignored.
 *
 * Rules are compiled once in [add]; [isIgnored] must not re-parse them.
 */
class IgnoreRules {

    private data class Rule(
        val pattern: GitPattern,
        val negated: Boolean,
        val folderOnly: Boolean,
        val anchored: Boolean,
        val base: String,
    )

    /** Compiled gitignore pattern that can match a path segment or full path. */
    private sealed class GitPattern {
        /** Matches a single path segment (no slashes inside). */
        abstract fun matchesSegment(segment: String): Boolean
        /** Matches a full relative path (may contain slashes). */
        abstract fun matchesPath(path: String): Boolean
    }

    /** A pattern that matches a single path segment (no slashes). */
    private class SegmentPattern(private val regex: Pattern) : GitPattern() {
        override fun matchesSegment(segment: String): Boolean = regex.matcher(segment).matches()
        override fun matchesPath(path: String): Boolean = !path.contains('/') && regex.matcher(path).matches()
    }

    /** A pattern that matches a full path, handling `**` between slashes. */
    private class FullPathPattern(private val regex: Pattern) : GitPattern() {
        override fun matchesSegment(segment: String): Boolean = regex.matcher(segment).matches()
        override fun matchesPath(path: String): Boolean = regex.matcher(path).matches()
    }

    private val rules = mutableListOf<Rule>()

    fun add(base: String, text: String) {
        val lines = text.lines()
        for (line in lines) {
            val rule = parseLine(base, line) ?: continue
            rules.add(rule)
        }
    }

    private fun parseLine(base: String, line: String): Rule? {
        // Strip trailing \r if present (lines() already handles \n, but \r\n may leave \r)
        val raw = line.trimEnd('\r')

        // Blank lines are no rules
        if (raw.isBlank()) return null

        // Lines starting with # are comments
        if (raw.startsWith('#')) return null

        var text = raw

        // Handle leading \# or \! → literal # or !
        // Then handle leading ! for negation
        var negated = false
        if (text.startsWith('!')) {
            negated = true
            text = text.substring(1)
        } else if (text.startsWith("\\!")) {
            text = "\\!" + text.substring(2) // keep the escaped !
            // Actually: \! means literal !, so strip the backslash
            text = text.substring(1)
        }
        if (text.startsWith("\\#")) {
            text = text.substring(1)
        }

        // Strip trailing spaces (unless escaped with \)
        var trailing = text.length
        while (trailing > 0 && text[trailing - 1] == ' ') {
            // Count consecutive backslashes immediately before this space
            var bs = 0
            var p = trailing - 2  // start before the space
            while (p >= 0 && text[p] == '\\') {
                bs++
                p--
            }
            if (bs % 2 == 1) {
                // Odd number of backslashes: space is escaped, keep it
                break
            }
            trailing--
        }
        text = text.take(trailing)

        // Handle trailing /
        var folderOnly = false
        if (text.endsWith('/')) {
            folderOnly = true
            text = text.removeSuffix("/")
        }


        // Decide anchored: contains a / anywhere
        val anchored = text.contains('/')

        // Compile the pattern
        val pattern = compilePattern(text, anchored)

        return Rule(pattern, negated, folderOnly, anchored, base)
    }

    private fun compilePattern(text: String, anchored: Boolean): GitPattern {
        if (anchored) {
            // Strip leading /
            val patternText = if (text.startsWith('/')) text.substring(1) else text
            val regex = compilePathPattern(patternText)
            return FullPathPattern(regex)
        } else {
            // Match against the name (last segment) at any depth
            val regex = compileSegmentPattern(text)
            return SegmentPattern(regex)
        }
    }

    /** Compile a pattern into a regex that matches a full path (may contain /). */
    private fun compilePathPattern(pattern: String): Pattern {
        val sb = StringBuilder()
        var i = 0
        val len = pattern.length
        while (i < len) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < len -> {
                    // Escape: next char is literal
                    sb.appendPatternChar(pattern[i + 1])
                    i += 2
                }
                c == '*' && i + 1 < len && pattern[i + 1] == '*' -> {
                    // ** pattern
                    val after = if (i + 2 < len) pattern[i + 2] else 0.toChar()
                    val before = if (i > 0) pattern[i - 1] else 0.toChar()
                    if (after == '/' && (i + 2 == len || (i + 3 < len && pattern[i + 3] == '*'))) {
                        // **/ at start or between slashes — match zero or more directories
                        sb.append("(?:.+/)?")
                        i += 3
                    } else if (after == '/' && i + 2 < len) {
                        sb.append("(?:.+/)?")
                        i += 3
                    } else if (before == '/' && (i + 2 == len || after == '/')) {
                        // /** at end or /**/ in middle — match everything inside
                        if (i + 2 == len) {
                            sb.append(".*")
                            i += 2
                        } else if (i + 3 < len && pattern[i + 3] == '/') {
                            sb.append(".*")
                            i += 4
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                    } else {
                        // `**` that is not a whole segment is an ordinary `*`.
                        sb.append("[^/]*")
                        i += 2
                    }
                }
                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                c == '[' -> {
                    // Character class
                    sb.append('[')
                    var j = i + 1
                    if (j < len && (pattern[j] == '!' || pattern[j] == '^')) {
                        sb.append('^')
                        j++
                    }
                    // Handle ] as first char in class
                    if (j < len && pattern[j] == ']') {
                        sb.appendPatternChar(']')
                        j++
                    }
                    while (j < len && pattern[j] != ']') {
                        if (pattern[j] == '\\' && j + 1 < len) {
                            sb.appendPatternChar(pattern[j + 1])
                            j += 2
                        } else {
                            sb.appendPatternChar(pattern[j])
                            j++
                        }
                    }
                    if (j < len) {
                        sb.append(']')
                        i = j + 1
                    } else {
                        // No closing ] - treat [ as literal
                        sb.appendPatternChar('[')
                        i++
                    }
                }
                else -> {
                    sb.appendPatternChar(c)
                    i++
                }
            }
        }
        val regexStr = "^${sb.toString()}\$"
        return Pattern.compile(regexStr)
    }

    /** Compile a pattern into a regex that matches a single path segment (no slashes). */
    private fun compileSegmentPattern(pattern: String): Pattern {
        val sb = StringBuilder()
        var i = 0
        val len = pattern.length
        while (i < len) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < len -> {
                    sb.appendPatternChar(pattern[i + 1])
                    i += 2
                }
                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                c == '[' -> {
                    sb.append('[')
                    var j = i + 1
                    if (j < len && (pattern[j] == '!' || pattern[j] == '^')) {
                        sb.append('^')
                        j++
                    }
                    if (j < len && pattern[j] == ']') {
                        sb.appendPatternChar(']')
                        j++
                    }
                    while (j < len && pattern[j] != ']') {
                        if (pattern[j] == '\\' && j + 1 < len) {
                            sb.appendPatternChar(pattern[j + 1])
                            j += 2
                        } else {
                            sb.appendPatternChar(pattern[j])
                            j++
                        }
                    }
                    if (j < len) {
                        sb.append(']')
                        i = j + 1
                    } else {
                        sb.appendPatternChar('[')
                        i++
                    }
                }
                else -> {
                    sb.appendPatternChar(c)
                    i++
                }
            }
        }
        val regexStr = "^${sb.toString()}\$"
        return Pattern.compile(regexStr)
    }

    private fun StringBuilder.appendPatternChar(c: Char) {
        // Escape regex special chars
        when (c) {
            '.', '$', '^', '{', '}', '(', ')', '+', '|', '[', ']', '?', '*', ' ' -> {
                append('\\')
                append(c)
            }
            '\\' -> {
                append("\\\\")
            }
            else -> append(c)
        }
    }

    fun isIgnored(path: String, isDir: Boolean): Boolean {
        var result = false
        for (rule in rules) {
            if (!matches(rule, path, isDir)) continue
            // negated=true means the rule un-ignores (re-includes) the path
            result = !rule.negated
        }
        return result
    }

    private fun matches(rule: Rule, path: String, isDir: Boolean): Boolean {
        // Rule only applies to paths inside its base folder
        if (path.isEmpty()) return false
        // A rule from folder B applies only to paths strictly inside B (not to B itself).
        if (rule.base.isNotEmpty() && !path.startsWith(rule.base + "/")) return false

        // The relative part of the path to match
        val relative = if (rule.base.isEmpty()) path else path.substring(rule.base.length + 1)

        // Folder only rules only match directories
        if (rule.folderOnly && !isDir) return false

        if (rule.anchored) {
            return rule.pattern.matchesPath(relative)
        } else {
            val name = relative.substringAfterLast('/')
            return rule.pattern.matchesSegment(name)
        }
    }
}
