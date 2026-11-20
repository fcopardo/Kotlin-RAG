package rag.code

/**
 * Classes and functions of a Python file (plan step 2g): Python nests by indentation, so it has its
 * own finder over [PyBlocks]. The result follows [DeclarationFinder]'s contract: nothing inside a
 * function is emitted; blocks that declare nothing (`if`, `try`, `with`…) count as transparent, so
 * `def` inside `if TYPE_CHECKING:` at module level is found.
 */
internal object PythonFinder {
    private val whitespaceRegex = Regex("\\s+")

    private fun word(s: String, from: Int): String {
        var e = from
        while (e < s.length && (s[e].isLetterOrDigit() || s[e] == '_')) e++
        return s.substring(from, e)
    }

    /**
     * Part 2g-2. What the block whose header is [header] declares. [header] is M from the block's `header`
     * up to (not including) its `colon`, every whitespace run turned into one space, trimmed.
     * Drop a leading `async `. Then, when the first word (letters, digits, `_`) is `class` or `def`: the
     * identifier N after it and one space (letters, digits, `_`) → Class(N) / Function(N); none (`class`
     * alone) → null. Otherwise, a first word in [PyBlocks.COMPOUND] → [Classified.Transparent]; else null.
     */
    fun classify(header: String): Classified? {
        val h = header.removePrefix("async ")
        val w = word(h, 0)
        if (w == "class" || w == "def") {
            val n = if (h.length > w.length && h[w.length] == ' ') word(h, w.length + 1) else ""
            if (n.isEmpty() || n[0].isDigit()) return null
            return if (w == "class") Classified.Class(n) else Classified.Function(n)
        }
        return if (w in PyBlocks.COMPOUND) Classified.Transparent else null
    }

    /**
     * Part 2g-2. Empty [text] → no items. Else `lines = Lines(text)`, `roots = PyBlocks.build(masked, lines)`,
     * then walk regions like [DeclarationFinder] step 4, starting with `region(roots, parent = null, floor = -1)`:
     * for each block b in order, classify its header (see [classify]);
     * - Class → add CLASS with `start = Docs.start(masked.uncommented, text, lines, b.start, floor, listOf("@"))`,
     *   `end = b.end`, depth = parent's depth + 1 (0 without parent), then `region(b.children, that item, b.colon)`;
     * - Function → add FUNCTION the same way (its children are never walked);
     * - Transparent → `region(b.children, parent, b.colon)`; null → nothing;
     * then `floor = b.end`. Warnings = `masked.warnings`. Items come out sorted by start.
     */
    fun find(text: String, masked: Masked): Declarations {
        if (text.isEmpty()) return Declarations(emptyList(), masked.warnings)
        val lines = Lines(text)
        val items = mutableListOf<Declaration>()
        fun region(blocks: List<PyBlock>, parent: Int?, floorStart: Int) {
            var floor = floorStart
            val depth = if (parent == null) 0 else items[parent].depth + 1
            for (b in blocks) {
                val header = masked.text.substring(b.header, b.colon).replace(whitespaceRegex, " ").trim()
                when (val c = classify(header)) {
                    is Classified.Class, is Classified.Function -> {
                        val start = Docs.start(masked.uncommented, text, lines, b.start, floor, listOf("@"))
                        val kind = if (c is Classified.Class) DeclarationKind.CLASS else DeclarationKind.FUNCTION
                        val name = if (c is Classified.Class) c.name else (c as Classified.Function).name
                        items += Declaration(kind, name, start, b.end, depth, parent)
                        if (c is Classified.Class) region(b.children, items.size - 1, b.colon)
                    }
                    Classified.Transparent -> region(b.children, parent, b.colon)
                    null -> {}
                }
                floor = b.end
            }
        }
        region(PyBlocks.build(masked, lines), null, -1)
        return Declarations(items, masked.warnings)
    }
}
