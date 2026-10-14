package rag

/** Errors that map to a CLI exit code (SPEC §4). */
sealed class RagException(message: String, val exitCode: Int, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    class PdfUnreadable(message: String, cause: Throwable? = null) : RagException(message, 3, cause)

    class NoTextLayer(message: String) : RagException(message, 3)

    class EmbeddingBackendUnavailable(message: String, cause: Throwable? = null) : RagException(message, 5, cause)

    /** The query embedder isn't the model (or dimension) a book was embedded with. */
    class ModelMismatch(message: String) : RagException(message, 5)
}
