package rag.code

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import rag.index.Indexer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Part 4b: reading a project folder. */
@Timeout(10)
class ProjectReaderTest {
    @TempDir
    lateinit var tmp: File

    private fun put(path: String, text: String) = File(tmp, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun putBytes(path: String, bytes: ByteArray) = File(tmp, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun project(): File {
        put(".gitignore", "*.log\nout/\n!keep.log\n")
        put("src/main/kotlin/A.kt", "class A\n")
        put("src/main/kotlin/B.kt", "class B\n")
        put("build/x.kt", "x")
        put("node_modules/m.js", "m")
        put(".idea/w.xml", "<w/>")
        put("out/o.kt", "o")
        put("app.log", "log")
        put("keep.log", "kept")
        put("sub/.gitignore", "*.tmp\n")
        put("sub/a.tmp", "t")
        put("sub/b.kt", "fun b() {}\n")
        put("sub/deep/c.tmp", "t")
        put(".env", "SECRET=1")
        put("package-lock.json", "{}")
        put("Cargo.lock", "x")
        put("big.txt", "a".repeat(1001))
        put("exact.txt", ("b".repeat(9) + "\n").repeat(100))   // exactly 1,000 bytes, short lines
        putBytes("img.png", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 1, 2))
        putBytes("latin1.txt", "café crème".toByteArray(Charsets.ISO_8859_1))
        put("app.min.js", "a=1")
        put("long.js", "x".repeat(400))
        put("dup1.kt", "same\n")
        put("dup2.kt", "same\n")
        File(tmp, "empty").mkdirs()
        return tmp
    }

    @Test
    fun `what is read and what is skipped`() {
        val r = ProjectReader(maxFileBytes = 1000).read(project())
        assertEquals(
            listOf("dup1.kt", "dup2.kt", "exact.txt", "keep.log", "latin1.txt", "src/main/kotlin/A.kt", "src/main/kotlin/B.kt", "sub/b.kt"),
            r.files.map { it.path },
        )
        assertEquals(
            listOf(
                ".env hidden", ".gitignore hidden", ".idea/ hidden", "Cargo.lock lockfile", "app.log gitignore",
                "app.min.js minified", "big.txt too large", "build/ build folder", "img.png binary", "long.js minified",
                "node_modules/ build folder", "out/ gitignore", "package-lock.json lockfile", "sub/.gitignore hidden",
                "sub/a.tmp gitignore", "sub/deep/c.tmp gitignore",
            ),
            r.skipped.map { "${it.path} ${it.reason}" },
        )
        assertEquals(tmp.canonicalFile.name, r.name)
        assertEquals("class A\n", String(r.files.first { it.path == "src/main/kotlin/A.kt" }.bytes))
        assertEquals("café crème", String(r.files.first { it.path == "latin1.txt" }.bytes, Charsets.ISO_8859_1))
    }

    @Test
    fun `the fingerprint covers indexed paths and bytes only`() {
        val root = project()
        val r = ProjectReader(1000).read(root)
        val expected = sha(r.files.joinToString("") { "${it.path}\t${sha(it.bytes)}\n" }.toByteArray(Charsets.UTF_8))
        assertEquals(expected, r.fingerprint)
        assertEquals(r.fingerprint, ProjectReader(1000).read(root).fingerprint, "stable")
        put("app2.log", "more")
        put("build/y.kt", "y")
        assertEquals(r.fingerprint, ProjectReader(1000).read(root).fingerprint, "skipped files don't count")
        put("src/main/kotlin/B.kt", "class B2\n")
        assertNotEquals(r.fingerprint, ProjectReader(1000).read(root).fingerprint, "a changed file changes it")
    }

    @Test
    fun `a renamed file changes the fingerprint even with the same bytes`() {
        val root = project()
        val before = ProjectReader(1000).read(root).fingerprint
        File(root, "dup2.kt").renameTo(File(root, "dup3.kt"))
        assertNotEquals(before, ProjectReader(1000).read(root).fingerprint)
    }

    @Test
    fun `a single file is read as it is`() {
        val f = put("Solo.kt", "fun main() {}\n")
        val r = ProjectReader().read(f)
        assertEquals(listOf("Solo.kt"), r.files.map { it.path })
        assertEquals(emptyList(), r.skipped)
        assertEquals("Solo.kt", r.name)
        assertEquals(Indexer.sha256(f), r.fingerprint)
        val min = put("x.min.js", "a=1")
        assertEquals(listOf("x.min.js"), ProjectReader().read(min).files.map { it.path }, "nothing is skipped for a single file")
    }

    @Test
    fun `the default size limit is one megabyte`() {
        put("ok.txt", ("a".repeat(99) + "\n").repeat(10_000))   // exactly 1,000,000 bytes
        put("over.txt", ("a".repeat(99) + "\n").repeat(10_000) + "a")
        val r = ProjectReader().read(tmp)
        assertEquals(listOf("ok.txt"), r.files.map { it.path })
        assertEquals(listOf("over.txt too large"), r.skipped.map { "${it.path} ${it.reason}" })
    }

    @Test
    fun `utf-8 cut at the 1 KB boundary is still text`() {
        val text = ("a".repeat(49) + "\n").repeat(20) + "a".repeat(23) + "é" + "\nbbb\n"   // é is 2 bytes: 1023 and 1024
        put("cut.txt", text)
        assertEquals(listOf("cut.txt"), ProjectReader().read(tmp).files.map { it.path })
    }

    @Test
    fun `symbolic links are not followed`() {
        put("real/r.kt", "r")
        val linked = try {
            Files.createSymbolicLink(File(tmp, "link").toPath(), File(tmp, "real").toPath())
            Files.createSymbolicLink(File(tmp, "l.kt").toPath(), File(tmp, "real/r.kt").toPath()); true
        } catch (e: Exception) { false }
        assumeTrue(linked, "symbolic links not supported here")
        val r = ProjectReader().read(tmp)
        assertEquals(listOf("real/r.kt"), r.files.map { it.path })
        assertEquals(listOf("l.kt symlink", "link symlink"), r.skipped.map { "${it.path} ${it.reason}" })
    }
}
