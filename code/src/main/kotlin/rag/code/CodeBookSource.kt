package rag.code

import rag.BookFormat
import rag.BookSource
import rag.ReadOptions
import rag.StructuredBook
import java.io.File

/**
 * Source code as a document (SPEC §18.4): a project folder, or a single source file as a project of one.
 * `BookReader` sends folders and files with a source extension here before content sniffing.
 */
class CodeBookSource(private val reader: ProjectReader = ProjectReader()) : BookSource {
    override val format = BookFormat.CODE
    override val extractor = CodeBookBuilder.EXTRACTOR

    override fun read(file: File, options: ReadOptions): StructuredBook {
        val project = reader.read(file)
        return CodeBookBuilder.build(project.name, project.files, options, project.skipped.map { "${it.path}: ${it.reason}" })
    }

    /** The project's fingerprint (a single file's is its SHA-256), so an unchanged project is not indexed twice. */
    override fun fingerprint(file: File): String = reader.read(file).fingerprint
}
