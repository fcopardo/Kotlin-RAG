package rag.sample

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import rag.RagException
import java.io.File

/**
 * Indexes one book in the background. Cancelling the work (or the system stopping it) stops between
 * embedding batches; enqueueing the same file again finishes the embedding instead of starting over.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val file = File(inputData.getString(KEY_FILE) ?: return Result.failure())
        val engine = RagLibrary.engine(applicationContext)
        return try {
            val result = engine.index(file) { done, total ->
                setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            }
            // Already indexed (same file content): finish its embeddings if an earlier run was stopped.
            if (result.skipped && RagLibrary.hasModel(applicationContext)) {
                val book = engine.books().first { it.id == result.bookId }
                if (book.embedStatus != "complete") engine.embed(book.id) { done, total ->
                    setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                }
            }
            Result.success(workDataOf(KEY_BOOK to result.bookId))
        } catch (e: RagException) {
            // Not a PDF/EPUB, no text layer, DRM, ...: retrying won't help.
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    companion object {
        const val TAG = "index"
        const val KEY_FILE = "file"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_BOOK = "book"
        const val KEY_ERROR = "error"

        /** One work per file name; enqueueing a file that's already queued or running does nothing. */
        fun enqueue(context: Context, file: File) {
            val request = OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(workDataOf(KEY_FILE to file.path))
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("index:${file.name}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
