package rag.sample

import android.content.Context
import rag.android.DeviceTier
import rag.android.RagEngine
import rag.android.RagModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rag.index.Indexer
import java.io.File

/**
 * One [RagEngine] per process, shared by the UI and the indexing worker: the engine runs one call
 * at a time over a single SQLite connection.
 *
 * Files live in the app's external files dir so they can be pushed with adb while developing:
 * - `models/<repository name>/` holds the tier's model (`onnx/model_quantized.onnx`, `vocab.txt`);
 * - `books/` holds books to index.
 * The database is private, in `filesDir`.
 */
object RagLibrary {
    @Volatile
    private var engine: RagEngine? = null

    /** This phone's tier: the large model with about 6 GB of RAM or more, the small one below. */
    fun tier(context: Context): DeviceTier = RagModels.tier(RagModels.memory(context))

    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models/${RagModels.model(tier(context)).directoryName}")

    fun hasModel(context: Context): Boolean {
        val model = RagModels.model(tier(context))
        val dir = modelDir(context)
        return File(dir, model.modelFile).isFile && File(dir, model.vocabFile).isFile
    }

    fun booksDir(context: Context): File = File(context.getExternalFilesDir(null), "books").apply { mkdirs() }

    /** Book file by content hash: the database knows each book's SHA-256, not where the file is. */
    private val filesByHash = HashMap<String, File>()

    /** The file of book [bookId] in the books folder, or null if it isn't on this device. */
    suspend fun fileFor(context: Context, bookId: Long): File? {
        val sha = engine(context).books().firstOrNull { it.id == bookId }?.fileSha256 ?: return null
        filesByHash[sha]?.takeIf { it.isFile }?.let { return it }
        return withContext(Dispatchers.IO) {
            booksDir(context).listFiles().orEmpty().filter { it.isFile && it !in filesByHash.values }.forEach { f ->
                filesByHash[Indexer.sha256(f)] = f
            }
            filesByHash[sha]
        }
    }

    fun engine(context: Context): RagEngine = engine ?: synchronized(this) {
        engine ?: create(context.applicationContext).also { engine = it }
    }

    private fun create(context: Context): RagEngine {
        // Without the model files the app still works, with keyword search only. The model loads on
        // first use, not here, so indexing reads each book before the model takes memory.
        val embedder = if (hasModel(context)) RagModels.open(tier(context), modelDir(context)) else null
        return RagEngine(context, File(context.filesDir, "library.db"), embedder)
    }
}
