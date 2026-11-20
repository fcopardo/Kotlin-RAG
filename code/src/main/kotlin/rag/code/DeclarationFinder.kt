package rag.code

/**
 * Finds the classes and functions of a source file (SPEC §18.4, plan step 2). The algorithm is shared
 * by every brace language; only [FamilyRules] differ. Python, which nests by indentation, comes later.
 *
 * All offsets are into the original text. Every step works on [MaskingLexer]'s masked text, so braces,
 * parentheses and semicolons inside comments and literals never count.
 *
 * 1. **Blocks.** One pass over the masked text with a stack. `{` opens a block, `}` closes the innermost
 *    open one; blocks form a tree. A `}` with nothing open is ignored and adds the warning
 *    `"line N: unmatched }"`. A block still open at the end closes at the last non-whitespace character
 *    of the original text and adds `"line N: unclosed {"` (N = the line of its `{`). The walk also counts `(`/`)`
 *    (never below 0): a `{` opened while that count is above 0 (`fun f(cb: () -> Unit = {}) {`,
 *    `@Ann({"x"})`) is never a declaration body.
 *
 * 2. **Header** of a block: the signature in front of its `{`.
 *    - On the `{`'s own line, it starts after the last `;`, `{` or `}` that comes before the `{` at
 *      parenthesis depth 0 counted from the line start; else at the line start.
 *    - If it started at the line start, it extends upward one line at a time while: the text collected
 *      so far is blank (the `{` is alone on its line), OR it has more `)`/`]` than `(`/`[`, OR it starts
 *      with one of `:` `->` `,` `where` `throws` `extends` `implements`. A line is never prepended when
 *      it is blank or its masked text ends with `;`, `{` or `}`, and never past the parent block's `{`.
 *    - The header is that text with every run of whitespace (line breaks included) turned into one
 *      space, trimmed. It goes to [FamilyRules.classify].
 *
 * 3. **Functions without braces.** In every region where declarations can appear (step 4) and outside
 *    its child blocks, each line is the start of a statement: the line from its first non-blank
 *    character, extended over the next lines while `(`/`[` are unbalanced, normalized like a header. If
 *    [FamilyRules.bodylessFunction] names it, that line starts a FUNCTION, even if a `{` follows on the
 *    line. Its first line's indentation is I; it runs over the lines after its statement until the first line that is
 *    non-blank in the ORIGINAL text (so a comment line counts), indented at most I, and whose first
 *    non-blank character is not inside a block opened within the function (a block's range includes its
 *    `{` and `}`); it never runs past its region's closing `}`. It ends at the last character before
 *    that point that is non-blank in the MASKED text (a trailing comment is not part of it). Blocks
 *    opened within it are part of it and are never classified.
 *
 * 4. **What is emitted.** Classify blocks top-down. A block becomes a [Declaration] when its parenthesis
 *    count was 0, [FamilyRules.classify] returns [Classified.Class] or [Classified.Function], and every
 *    enclosing block is a class or [Classified.Transparent]: the file, classes and transparent blocks are
 *    the regions where declarations can appear. So nothing inside a function is emitted (local
 *    functions, lambdas and local classes are part of its text), nor anything inside a block that
 *    declares nothing (`init {}`, a lambda at class level). [Classified.Transparent] blocks are not
 *    emitted, and their contents count as if they were directly in the enclosing region.
 *    A block declaration ends at its closing `}`.
 *
 * 5. **Leading docs.** A declaration's start moves up over the lines directly above it, stopping at
 *    the first blank line, while each is a comment line (blank in the masked text, not blank in the
 *    original) or an annotation line (its original text, trimmed, starts with one of
 *    [FamilyRules.annotationPrefixes]). It never moves onto a line holding the parent block's `{` or
 *    any character of an earlier declaration (between its start and its end). Start = the first
 *    non-blank character of the topmost such line, or of the declaration itself.
 *
 * 6. **Result.** Items sorted by start; `depth` = number of emitted ancestors; `parent` = index of the
 *    nearest emitted ancestor. Warnings: the lexer's, then the walk's. Never throws; linear time.
 *    Without rules (a family not done yet, [Language.OTHER]) there are no items.
 */
object DeclarationFinder {
    fun find(text: String, language: Language): Declarations = find(text, language, rulesFor(language))

    internal fun find(text: String, language: Language, rules: FamilyRules?): Declarations {
        val lexed = MaskingLexer.mask(text, language)
        val warnings = lexed.warnings.toMutableList()
        if (rules == null || text.isEmpty()) return Declarations(emptyList(), warnings)
        val walk = Walk(text, lexed.text, Lines(text), rules)
        val roots = BlockTree.build(lexed.text, text, walk.lines, warnings)
        walk.region(from = 0, to = text.length, blocks = roots, parentOpen = -1, parent = null)
        return Declarations(walk.items, warnings)
    }

    private class Walk(val text: String, val masked: String, val lines: Lines, val rules: FamilyRules) {
        val items = mutableListOf<Declaration>()

        /**
         * Emits the declarations directly in the region [from, to) whose child blocks are [blocks], then
         * recurses into classes and transparent blocks. [parentOpen] = offset of the region's `{` (-1 for the
         * file); [parent] = index in [items] of the enclosing declaration, or null.
         */
        fun region(from: Int, to: Int, blocks: List<Block>, parentOpen: Int, parent: Int?) {
            val bodyless = Bodyless.find(masked, text, lines, from, to, blocks, rules)
            val depth = if (parent == null) 0 else items[parent].depth + 1
            var floor = parentOpen          // last offset an item's docs may not reach (Docs.start's floor)
            var bi = 0                      // next block
            var fi = 0                      // next bodyless function
            while (bi < blocks.size || fi < bodyless.size) {
                // Take whichever comes first: a bodyless function (by start) or a block (by open).
                val takeFunction = fi < bodyless.size && (bi >= blocks.size || bodyless[fi].start < blocks[bi].open)
                if (takeFunction) {
                    val f = bodyless[fi++]
                    // Blocks opened inside this function are part of it: skip them.
                    while (bi < blocks.size && blocks[bi].open <= f.end) bi++
                    add(DeclarationKind.FUNCTION, f.name, Docs.start(masked, text, lines, f.start, floor, rules.annotationPrefixes), f.end, depth, parent)
                    floor = f.end
                    continue
                }
                val b = blocks[bi++]
                if (!b.inParens) {
                    val h = Headers.of(masked, lines, b.open, parentOpen)
                    when (val c = rules.classify(h.text)) {
                        is Classified.Class -> {
                            val index = add(DeclarationKind.CLASS, c.name, Docs.start(masked, text, lines, h.start, floor, rules.annotationPrefixes), b.close, depth, parent)
                            region(b.open + 1, b.close, b.children, b.open, index)
                        }
                        is Classified.Function ->
                            add(DeclarationKind.FUNCTION, c.name, Docs.start(masked, text, lines, h.start, floor, rules.annotationPrefixes), b.close, depth, parent)
                        Classified.Transparent -> region(b.open + 1, b.close, b.children, b.open, parent)
                        null -> {}
                    }
                }
                floor = b.close
            }
        }

        /** Appends a declaration and returns its index. */
        private fun add(kind: DeclarationKind, name: String, start: Int, end: Int, depth: Int, parent: Int?): Int {
            items += Declaration(kind, name, start, end, depth, parent)
            return items.size - 1
        }
    }
}
