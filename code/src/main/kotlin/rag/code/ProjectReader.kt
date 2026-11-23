package rag.code

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** A file or folder the reader left out, and why. A folder's path ends with `/`. */
data class SkippedFile(val path: String, val reason: String)

/**
 * What [ProjectReader.read] found: the project's [name], the [files] to index (sorted by path), what it
 * [skipped] (sorted by path), and the project's [fingerprint].
 */
class ProjectRead(val name: String, val files: List<SourceFile>, val skipped: List<SkippedFile>, val fingerprint: String)

/**
 * Reads a code project from disk (SPEC §18.4; plan step 4, part 4b): a folder, or a single file.
 *
 * **What it must do.** Turn a folder into the list of source files worth indexing, leaving out build
 * output, dependencies, hidden files, what `.gitignore` excludes, and files that are binary, minified,
 * lockfiles or too large; say what it left out and why; and give the project a fingerprint that changes
 * exactly when an indexed file's path or bytes change. The same folder gives the same result on macOS
 * and Linux. A single file is read as it is, with nothing skipped.
 *
 * **How.**
 * 1. A single file: one [SourceFile] named after the file; the name is the file name; the fingerprint is
 *    the lowercase hex SHA-256 of its bytes (the same value as `rag.index.Indexer.sha256`).
 * 2. A folder: the name is the folder's own name (of its canonical path, so `.` gives a real name). Walk it
 *    top-down without following symbolic links, using paths relative to the folder, `/`-separated.
 * 3. Folders below the root are not entered when, checked in this order: their name starts with `.` (`hidden`),
 *    the `.gitignore` rules so far ignore them (`gitignore`), or their name is in [SKIPPED_FOLDERS] (`build folder`). Each
 *    such folder is recorded once, with a trailing `/`. Before looking inside a folder it does enter (the
 *    root included), add its `.gitignore`, if it has one, to an [IgnoreRules] with that folder as the base.
 * 4. A symbolic link, to a file or a folder, is never followed or entered: it is recorded with its own path
 *    (no trailing `/`) and reason `symlink`, before any other check.
 *    Each other file gets the first reason that applies, in this order: its name
 *    starts with `.` (`hidden`); the `.gitignore` rules ignore it (`gitignore`); its name is in [LOCKFILES]
 *    or ends with `.lock` (`lockfile`); it is larger than [maxFileBytes] (`too large`); its first 1,024
 *    bytes hold a NUL byte, or are neither valid UTF-8 (a multi-byte character cut off at the end is fine)
 *    nor at least 95% printable (bytes ≥ 0x20, tab, line feed, carriage return) (`binary`); its name
 *    contains `.min.`, or its text averages more than 300 characters per line (`minified`). A file with no
 *    reason is read in full.
 * 5. Fingerprint of a folder: the lowercase hex SHA-256 of the UTF-8 text made of one line
 *    `<path>\t<SHA-256 of the file's bytes>\n` per indexed file, in path order. Skipped files don't count.
 */
class ProjectReader(private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES) {

    fun read(root: File): ProjectRead {
        // Single file case: nothing is skipped
        if (root.isFile) {
            val bytes = root.readBytes()
            val file = SourceFile(root.name, bytes)
            val fp = sha256(bytes)
            return ProjectRead(root.name, listOf(file), emptyList(), fp)
        }

        // Folder case
        require(root.isDirectory) { "not a file or directory: $root" }

        val name = root.canonicalFile.name
        val ignoreRules = IgnoreRules()
        val keptFiles = mutableListOf<SourceFile>()
        val skipped = mutableListOf<SkippedFile>()

        // Load root .gitignore before walking
        loadGitignore(root, "", ignoreRules)

        // Walk the folder top-down
        walk(root, root, "", ignoreRules, keptFiles, skipped)

        // Sort both lists by path
        keptFiles.sortBy { it.path }
        skipped.sortBy { it.path }

        // Compute fingerprint
        val fingerprint = buildFingerprint(keptFiles)

        return ProjectRead(name, keptFiles, skipped, fingerprint)
    }

    /** Load a `.gitignore` from [folder] into [rules] with [relPath] as base. */
    private fun loadGitignore(folder: File, relPath: String, rules: IgnoreRules) {
        val gitignore = File(folder, ".gitignore")
        if (gitignore.isFile) {
            rules.add(relPath, gitignore.readText())
        }
    }

    /** Walk [dir] (under [projectRoot]), accumulating kept files and skipped entries. */
    private fun walk(
        projectRoot: File,
        dir: File,
        relPath: String,
        rules: IgnoreRules,
        keptFiles: MutableList<SourceFile>,
        skipped: MutableList<SkippedFile>,
    ) {
        val entries = dir.listFiles()?.sortedBy { it.name } ?: return

        for (entry in entries) {
            val entryName = entry.name
            val entryRel = if (relPath.isEmpty()) entryName else "$relPath/$entryName"

            // Check symlink first
            if (Files.isSymbolicLink(entry.toPath())) {
                skipped.add(SkippedFile(entryRel, "symlink"))
                continue
            }

            if (entry.isDirectory) {
                // Decide whether to enter this folder
                val folderSkipped = decideFolder(entry, entryName, entryRel, rules)
                if (folderSkipped != null) {
                    skipped.add(folderSkipped)
                    continue
                }

                // Load .gitignore before looking inside
                loadGitignore(entry, entryRel, rules)

                // Recurse
                walk(projectRoot, entry, entryRel, rules, keptFiles, skipped)
            } else {
                // It's a regular file: check reasons in order
                val reason = decideFile(entry, entryName, entryRel, rules)
                if (reason != null) {
                    skipped.add(SkippedFile(entryRel, reason))
                } else {
                    // Read the file
                    val bytes = entry.readBytes()
                    keptFiles.add(SourceFile(entryRel, bytes))
                }
            }
        }
    }

    /** Decide whether a folder should be entered. Returns a SkippedFile if skipped, null if entered. */
    private fun decideFolder(
        entry: File,
        name: String,
        relPath: String,
        rules: IgnoreRules,
    ): SkippedFile? {
        // Hidden
        if (name.startsWith(".")) return SkippedFile("$relPath/", "hidden")
        // Gitignore
        if (rules.isIgnored(relPath, true)) return SkippedFile("$relPath/", "gitignore")
        // Build folder
        if (SKIPPED_FOLDERS.contains(name)) return SkippedFile("$relPath/", "build folder")
        return null
    }

    /** Decide a file's fate. Returns the reason string if skipped, null if kept. */
    private fun decideFile(
        entry: File,
        name: String,
        relPath: String,
        rules: IgnoreRules,
    ): String? {
        // Hidden
        if (name.startsWith(".")) return "hidden"
        // Gitignore
        if (rules.isIgnored(relPath, false)) return "gitignore"
        // Lockfile
        if (LOCKFILES.contains(name) || name.endsWith(".lock")) return "lockfile"
        // Too large
        if (entry.length() > maxFileBytes) return "too large"
        // Binary check (first 1024 bytes)
        val head = entry.inputStream().use { input ->
            val all = input.readBytes()
            if (all.size <= 1024) all else all.sliceArray(0 until 1024)
        }
        if (isBinary(head)) return "binary"
        // Minified check
        if (name.contains(".min.")) return "minified"
        if (isMinified(entry)) return "minified"
        return null
    }

    /** Check if the first 1024 bytes are binary. */
    private fun isBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        // Check for NUL byte
        if (bytes.any { it == 0.toByte() }) return true
        // Check valid UTF-8 or at least 95% printable
        val validUtf8 = isValidUtf8(bytes)
        if (validUtf8) return false
        // Check printable percentage
        val printable = bytes.count { isPrintable(it) }
        return (printable * 100 / bytes.size) < 95
    }

    /** Check if bytes are valid UTF-8. */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            val codePointLen = when {
                b and 0x80 == 0 -> 1          // 0xxxxxxx
                b and 0xE0 == 0xC0 -> 2       // 110xxxxx
                b and 0xF0 == 0xE0 -> 3       // 1110xxxx
                b and 0xF8 == 0xF0 -> 4       // 11110xxx
                else -> return false          // invalid start byte
            }
            if (i + codePointLen > len) return false  // truncated
            // Check continuation bytes
            for (j in 1 until codePointLen) {
                val cont = bytes[i + j].toInt() and 0xFF
                if (cont and 0xC0 != 0x80) return false  // not 10xxxxxx
            }
            i += codePointLen
        }
        return true
    }

    /** Check if a byte is "printable" for the purposes of the binary check. */
    private fun isPrintable(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v == 0x09 || v == 0x0A || v == 0x0D || v >= 0x20
    }

    /** Check if a file is minified by average line length > 300. */
    private fun isMinified(file: File): Boolean {
        val maxRead = minOf(maxFileBytes + 1, 1024 * 1024L) // read up to 1MB
        val text = file.inputStream().use { input ->
            val all = input.readBytes()
            if (all.size <= maxRead) all else all.sliceArray(0 until maxRead.toInt())
        }.decodeToString()
        if (text.isEmpty()) return false
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return false
        val totalChars = lines.sumOf { it.length }
        val avg = totalChars / lines.size
        return avg > 300
    }

    /** Build the fingerprint from the indexed files. */
    private fun buildFingerprint(files: List<SourceFile>): String {
        val sb = StringBuilder()
        for (f in files) {
            sb.append(f.path).append('\t').append(sha256(f.bytes)).append('\n')
        }
        return sha256(sb.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 1_000_000L

        /** Build output, dependencies and IDE state. */
        val SKIPPED_FOLDERS = setOf("build", "out", "target", "dist", "node_modules", "__pycache__", "venv")

        /** Dependency lockfiles: long, generated, and never what a question is about. */
        val LOCKFILES = setOf(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "Cargo.lock", "Gemfile.lock", "poetry.lock",
            "composer.lock", "go.sum", "Podfile.lock", "gradle.lockfile", "Pipfile.lock", "uv.lock",
        )

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
