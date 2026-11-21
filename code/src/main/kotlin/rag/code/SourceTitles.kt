package rag.code

/**
 * The title of a source file in a project, as parts to join with ` › ` (plan step 3a):
 * `core/src/main/kotlin/rag/search/Searcher.kt` → `[core, rag/search, Searcher.kt]`. The source-set
 * folders (`src/main/kotlin`) are noise in every title, so they go; the module and package stay.
 */
internal object SourceTitles {
    /** Folders that only say which language a source set holds (`src/main/kotlin`, `src/test/java`). */
    val LANGUAGE_DIRS = setOf(
        "kotlin", "java", "scala", "groovy", "swift", "objc", "cpp", "c", "js", "ts", "javascript",
        "typescript", "python", "go", "rust", "csharp", "resources",
    )

    /**
     * Part 3a. [path] is relative and `/`-separated. Segments = [path] split at `/`, empty ones dropped;
     * the last is the file name F, the others the folders D.
     * - When D has no segment `src`: `[D joined by "/", F]`, leaving out the first part when D is empty.
     * - Else, with i = the index of the FIRST `src` in D: module M = `D[0 until i]` joined by `/` (may be
     *   empty), and R = `D[i+1 until end]`.
     *   - If R has at least 2 segments and `R[1]` is in [LANGUAGE_DIRS], `R[0]` is a source set S: unless
     *     S is `main`, M becomes `M (S)` (just `S` when M is empty). Drop the first two segments of R.
     *   - Else, if R is not empty and `R[0]` is in [LANGUAGE_DIRS], drop it.
     *   - Result: `[M, R joined by "/", F]`, leaving out M and the R part when they are empty.
     */
    fun of(path: String): List<String> {
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return emptyList()
        val file = segs.last()
        val dirs = segs.dropLast(1)
        val i = dirs.indexOf("src")
        if (i < 0) return listOfNotNull(dirs.joinToString("/").ifEmpty { null }, file)
        var module = dirs.subList(0, i).joinToString("/")
        var rest = dirs.subList(i + 1, dirs.size)
        if (rest.size >= 2 && rest[1] in LANGUAGE_DIRS) {
            val set = rest[0]
            if (set != "main") module = if (module.isEmpty()) set else "$module ($set)"
            rest = rest.drop(2)
        } else if (rest.isNotEmpty() && rest[0] in LANGUAGE_DIRS) {
            rest = rest.drop(1)
        }
        return listOfNotNull(module.ifEmpty { null }, rest.joinToString("/").ifEmpty { null }, file)
    }
}
