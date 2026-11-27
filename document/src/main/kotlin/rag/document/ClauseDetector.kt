package rag.document

/**
 * Finds the clause structure of a contract (SPEC §18.4): numbered items that nest, like `4.`,
 * `4.2`, `(a)`, `ARTICLE IV` or `§ 7`.
 *
 * Numbering alone is not enough — an academic paper numbers its sections the same way — so a
 * document is only treated as a contract when it *also* reads like one. That keeps "1. Introduction
 * / 2. Method" a paper.
 */
object ClauseDetector {

    /** `4.`, `4.2`, `4.2.1`, optionally followed by the clause's name on the same line. */
    private val decimal = Regex("^(\\d+(?:\\.\\d+)*)(\\.)?[ \\t]+(\\S.*)?$")

    /** `ARTICLE IV`, `Article 4`, `Section 7`, `§ 7`, `§7.2`. */
    private val named = Regex("^(article|section|clause|§)\\s*([0-9]+(?:\\.[0-9]+)*|[ivxlcIVXLC]+)\\.?[ \\t]*(\\S.*)?$", RegexOption.IGNORE_CASE)

    /** `(a)`, `(ii)`, `(3)` — a sub-item of whatever came before it. */
    private val lettered = Regex("^\\(([a-z]{1,2}|[ivxlc]{1,4}|\\d{1,2})\\)[ \\t]+(\\S.*)$", RegexOption.IGNORE_CASE)

    private val recital = Regex("^(whereas|now,? therefore)\\b.*", RegexOption.IGNORE_CASE)

    /** Words that say this is an agreement rather than an article with numbered sections. */
    private val legalWords = setOf(
        "shall", "hereby", "herein", "hereof", "hereto", "hereunder", "thereof", "whereas",
        "agreement", "party", "parties", "obligations", "liability", "indemnify", "indemnification",
        "warranty", "warranties", "termination", "terminate", "governing", "jurisdiction",
        "covenant", "breach", "confidential", "pursuant", "notwithstanding", "foregoing",
    )
    private val word = Regex("[\\p{L}']+")

    /**
     * The contract's clauses, or null when [text] is not a contract. [text] is the normalized
     * document text; each returned [Heading] points at the start of its clause.
     *
     * @param minClauses the fewest numbered items a contract must have.
     * @param minLegalRate the share of words that must be legal vocabulary (1 in 200 by default).
     */
    fun detect(text: String, minClauses: Int = 5, minLegalRate: Double = 0.005): List<Heading>? {
        if (text.isBlank()) return null
        val headings = mutableListOf<Heading>()
        var numberedDepth = 1
        var offset = 0
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            // Only a line that starts its paragraph can open a clause.
            val startsParagraph = offset == 0 || text.getOrNull(offset - 1) == '\n'
            if (startsParagraph && trimmed.isNotEmpty()) {
                val heading = heading(trimmed, numberedDepth)
                if (heading != null) {
                    val (title, depth, numbered) = heading
                    headings += Heading(title, depth, offset + (line.length - line.trimStart().length))
                    // Only a numbered clause moves the level that lettered items hang from, so
                    // (a), (b), (c) under one clause stay siblings.
                    if (numbered) numberedDepth = depth
                }
            }
            offset += line.length + 1
        }
        if (headings.size < minClauses) return null

        val words = word.findAll(text.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return null
        val legal = words.count { it in legalWords }
        if (legal.toDouble() / words.size < minLegalRate) return null
        return headings
    }

    /** A line's clause title, depth and whether it is numbered (rather than lettered). */
    private fun heading(line: String, numberedDepth: Int): Triple<String, Int, Boolean>? {
        decimal.matchEntire(line)?.let { m ->
            val number = m.groupValues[1]
            // Depth comes from the number itself; the trailing dot is only how it is written.
            val depth = number.count { it == '.' } + 1
            return Triple(title(number + m.groupValues[2], m.groupValues[3]), depth, true)
        }
        named.matchEntire(line)?.let { m ->
            val label = "${m.groupValues[1].trim()} ${m.groupValues[2]}".trim()
            return Triple(title(label, m.groupValues[3]), 1, true)
        }
        lettered.matchEntire(line)?.let { m ->
            // A lettered item sits one level under the numbered clause it follows.
            return Triple(title("(${m.groupValues[1]})", m.groupValues[2]), (numberedDepth + 1).coerceAtMost(6), false)
        }
        if (recital.matches(line)) return Triple(title("Recital", line.take(60)), 1, true)
        return null
    }

    /**
     * "4.2 Notice", or just "4.2" when the number stands alone on its line. When the clause's body
     * runs on from its name ("2. Grant of Copyright License. Subject to the terms…"), the title
     * keeps the name and drops the body.
     */
    private fun title(number: String, rest: String, max: Int = 70): String {
        var tail = rest.trim()
        sentenceEnd.find(tail)?.let { tail = tail.take(it.range.first) }
        tail = tail.trimEnd('.', ',', ';', ':')
        if (tail.isEmpty()) return number
        val short = if (tail.length <= max) tail else tail.take(max).substringBeforeLast(' ') + "…"
        return "$number $short"
    }

    /**
     * The end of the clause's name: a period, then a space and a capital (possibly after an opening quote,
     * as in a definition: `1.1 Affiliate. "Affiliate" means…`) or the line's end.
     */
    private val sentenceEnd = Regex("\\.(?=\\s+[\"“‘']?\\p{Lu}|\\s*$)")
}
