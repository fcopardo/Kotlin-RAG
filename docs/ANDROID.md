# Using the library in an Android app

This guide adds book indexing and search to an Android app: PDF and EPUB books, keyword and
meaning-based search, optional subject filters, all on the device. Every step here is what the
sample app in [`samples/android-app`](../samples/android-app) does; that app was built as a minified
release and run on a Galaxy S25 (Android 16).

## 1. Requirements

| | Minimum | Notes |
|---|---|---|
| `minSdk` | 26 (Android 8.0) | |
| `compileSdk` | 35 | Current AndroidX libraries need 36 anyway. |
| Kotlin | 2.2 | The library targets Kotlin 2.2 (AGP 9's built-in version), so apps on 2.2 or newer can read it. |
| Java target | 17 | The sample builds with Java 17 and Kotlin `jvmTarget` 17. |
| Android Gradle Plugin | 9.x | Tested with 9.4.1. |
| Device RAM | about 3 GB | Phones with less than ~5 GiB get the small model (§4). |

## 2. Get the library

The library is published as Maven artifacts under the group `rag`. Until it's on a shared Maven
repository, publish it to your machine's local Maven repository from a clone of this project:

```bash
./gradlew publishToMavenLocal
```

This writes `rag:android:0.1.0` and the modules it depends on to `~/.m2/repository/rag/`.

In your app's `settings.gradle.kts`, add `mavenLocal()` for the `rag` group only, so nothing else is
resolved from your local Maven repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("rag") } }
        google()
        mavenCentral()
    }
}
```

## 3. Gradle setup

In the app module's `build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        // ONNX Runtime and SQLite ship native code for four ABIs (~150 MB); phones need arm64 only.
        // Add "x86_64" to run on an emulator.
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        // Several library modules register services under the same META-INF/services names.
        resources.merges += "META-INF/services/**"
    }
}

dependencies {
    implementation("rag:android:0.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0") // for background indexing (§6)
}
```

- **Size:** with arm64 only, the sample's release APK is 41 MB. Most of that is ONNX Runtime and the
  bundled SQLite.
- **R8:** nothing to add. The library ships consumer rules:
  - one that keeps ONNX Runtime's classes, which its native code finds by name (without it a minified
    app crashed on the first embedding);
  - one that silences PdfBox-Android's optional JPEG 2000 decoder.
- **Dependencies it brings in:** PdfBox-Android, jsoup, the AndroidX bundled SQLite driver, ONNX
  Runtime for Android, kotlinx-coroutines and kotlinx-serialization.

## 4. Model files

Keyword search needs no model. Meaning-based search needs an embedding model. The library picks one
from the phone's RAM; the app provides the files.

| Tier | Phones | Model | Files from Hugging Face | Size |
|---|---|---|---|---|
| Large | about 6 GB of RAM or more | `mxbai-embed-large` int8 | [`mixedbread-ai/mxbai-embed-large-v1`](https://huggingface.co/mixedbread-ai/mxbai-embed-large-v1): `onnx/model_quantized.onnx`, `vocab.txt` | 337 MB |
| Small | the rest | `nomic-embed-text` int8 | [`nomic-ai/nomic-embed-text-v1.5`](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5): `onnx/model_quantized.onnx`, `vocab.txt` | 137 MB |

Both models are licensed Apache-2.0.

```kotlin
val tier = RagModels.tier(RagModels.memory(context))   // DeviceTier.LARGE or DeviceTier.SMALL
val model = RagModels.model(tier)                      // model.repository, model.modelFile, model.vocabFile
val dir = File(modelsRoot, model.directoryName)        // e.g. .../mxbai-embed-large-v1/
val embedder = RagModels.open(tier, dir)               // applies the tier's ONNX Runtime settings
```

Keep each file's path inside `dir` as in the repository: `onnx/model_quantized.onnx` and `vocab.txt`.

**Ways to get the files onto the phone:**
- **While developing:** push them with adb into the app's external files folder, which needs no
  permissions:

  ```bash
  adb push model_quantized.onnx /sdcard/Android/data/<your.app>/files/models/mxbai-embed-large-v1/onnx/
  ```
  ```bash
  adb push vocab.txt /sdcard/Android/data/<your.app>/files/models/mxbai-embed-large-v1/
  ```

- **In production:** don't put them in the APK (Google Play's base APK limit is 200 MB). Instead:
  - download them on first use into `filesDir`, checking their SHA-256 and size; or
  - deliver them as a Play Asset Delivery on-demand asset pack, then pass the pack's folder.

The model loads when it's first used, not when the embedder is created. It takes about 390 MB
(large) or 180 MB (small) while loaded.

## 5. One engine per process

`RagEngine` holds the SQLite connection and runs one call at a time on `Dispatchers.IO`. Create one
per database and share it, from your DI graph or an object like this:

```kotlin
object RagLibrary {
    @Volatile private var engine: RagEngine? = null

    fun engine(context: Context): RagEngine = engine ?: synchronized(this) {
        engine ?: create(context.applicationContext).also { engine = it }
    }

    private fun create(context: Context): RagEngine {
        val tier = RagModels.tier(RagModels.memory(context))
        val dir = File(context.getExternalFilesDir(null), "models/${RagModels.model(tier).directoryName}")
        // Without model files the engine still indexes and searches by keywords.
        val embedder = if (File(dir, RagModels.model(tier).modelFile).isFile) RagModels.open(tier, dir) else null
        return RagEngine(context, File(context.filesDir, "library.db"), embedder)
    }
}
```

- **Closing:** `RagEngine.close()` closes the database but not the embedder; close that yourself
  (`OnnxEmbedder.close()`). An engine that lives as long as the process needs neither.
- **Two engines on the same file** (e.g. in different processes) wait up to 10 seconds for each
  other's writes instead of failing.

## 6. Index books in the background

Indexing a 205-page PDF on a Galaxy S25 takes about 1.8 minutes (large tier) or 1.1 minutes
(small): a few seconds to read the book, the rest to embed it. Run it in WorkManager:

```kotlin
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val file = File(inputData.getString("file") ?: return Result.failure())
        val engine = RagLibrary.engine(applicationContext)
        return try {
            val result = engine.index(file) { done, total ->
                setProgressAsync(workDataOf("done" to done, "total" to total))
            }
            // Same file already indexed: finish its embeddings if an earlier run was stopped.
            if (result.skipped) {
                val book = engine.books().first { it.id == result.bookId }
                if (book.embedStatus == "partial") engine.embed(book.id)
            }
            Result.success(workDataOf("book" to result.bookId))
        } catch (e: RagException) {
            Result.failure(workDataOf("error" to e.message)) // not a PDF/EPUB, no text layer, DRM...
        }
    }
}
```

- **Input is a `File`.** Copy books picked with the system file picker (`ACTION_OPEN_DOCUMENT`) into
  app storage first, as the sample does.
- **Stopping:** cancelling the work stops it between embedding batches. The book stays searchable by
  keywords, and its embedding status stays `partial` until a later `embed()` finishes it.
- **Re-adding a book:** a file with the same content is detected by its SHA-256 and not indexed again
  (`result.skipped`). Pass `reindex = true` to rebuild it.
- **Lower memory:** `index(file, embed = false)` stores only the structure, and keyword search works
  right away. Calling `embed(bookId)` later adds the vectors. This keeps reading a PDF and running the
  model from needing memory at the same time.
- **Long books on slow phones:** WorkManager stops ordinary work after 10 minutes. For longer jobs,
  call `setForeground(...)` with a notification.
- **Failures:** `RagException` subclasses say what went wrong. A scanned PDF without text needs OCR,
  which is out of scope.

## 7. Search

```kotlin
val hits: List<Hit> = RagLibrary.engine(context).search(
    query = "what is the slot table",
    k = 5,                    // passages; up to 2 summary hits may be added
    subject = "android",      // optional: only books about this (§8)
    // mode = Mode.LEXICAL    // keywords only: no model needed
)
for (hit in hits) {
    hit.bookTitle                 // "Jetpack Compose internals"
    hit.chapter.title             // "3. The Compose runtime"
    hit.paragraph.text            // the passage
    hit.phrase.text               // the sentence in it that matches best
    hit.paragraph.pageStartLabel  // "59", "xii", or "loc 38" (EPUBs without page numbers)
    hit.matchType                 // EXACT_*, LEXICAL, VECTOR, HYBRID or SUMMARY
}
```

- **Timing:** search takes about 50 ms on the S25 once the model is loaded; the first search after
  launch takes about 600 ms, mostly loading the model.
- **Giving results to an LLM:** `Hit` is `@Serializable`, so `Json.encodeToString(hits)` produces the
  same JSON the desktop CLI prints.
- **Books embedded with another model** than the phone's are searched by keywords only, with a
  warning in logcat. §9 explains how one database can carry both tiers' vectors.

### Open a result in its book

`HitLocator` finds a hit in the book file, so the app can open the book there:

```kotlin
val locator = HitLocator(context)

// PDF: the paragraph's lines on its page, as fractions of the page (0..1 from the top left).
// Render the page with android.graphics.pdf.PdfRenderer and draw the rectangles over it.
val rects: List<RectF> = locator.pdfHighlights(file, hit.paragraph.pageStart, hit.paragraph.text)

// EPUB: the XHTML document (path inside the zip) that holds the passage. Show it in a WebView
// that serves the zip's files, then highlight the passage with WebView.findAllAsync(...).
val document: String? = locator.epubDocument(file, hit.paragraph.text)
```

- **Matching:** both match letters and digits only, so hyphenation, ligatures and spacing
  differences between the stored text and the file don't break them.
- **Threads:** both read the file, so call them off the main thread.
- **Finding the file:** the database stores each book's SHA-256 (`BookRow.fileSha256`), not a path.
  The sample finds the file by hashing the books it keeps (`RagLibrary.fileFor`).
- **Full viewer:** `samples/android-app`'s `BookViewerActivity` is a complete one, in about 200
  lines:
  - **PDF:** the page rendered with the paragraph highlighted, and previous/next buttons.
  - **EPUB:** the chapter's own XHTML, with CSS and images served from the zip, scrolled to the
    passage and highlighted.

## 8. Subjects

`engine.subjects(bookId)` lists what a book is about, each subject with a weight (main 1.0,
tangential 0.5, broad 0.25). The `subject` filter keeps a search to matching books, by name
("compose" matches "Jetpack Compose") or by meaning ("software" matches the programming books).

- **EPUBs** bring their own subjects, stored when indexing: *The Art of War* has "War" and "Military
  art and science".
- **Subjects from a language model** need Ollama, so they're made on the desktop with `rag subjects`.
  For *Jetpack Compose internals* they are Jetpack Compose (main), Kotlin and Android (tangential),
  and UI Development (broad). They reach the phone inside a desktop-built database (§9).

## 9. Ship a database built on the desktop

Indexing on the phone is optional. A database built with the desktop CLI works on the phone as is,
and can carry vectors for both tiers and the model-made subjects:

```bash
rag index --db books.db --file book.pdf
```
```bash
rag embed --db books.db --book 1 --embed-model nomic-embed-text --onnx-dir models/nomic-embed-text-v1.5 --add
```
```bash
rag subjects --db books.db --book 1
```

1. `rag index` stores the book and embeds it with mxbai through Ollama (the large tier).
2. `rag embed … --add` adds nomic int8 vectors through ONNX (the small tier). On Apple silicon this
   step needs an arm64 JDK.
3. `rag subjects` adds the model-made subjects.

Copy `books.db` into `filesDir` before creating the engine, from your assets or a download. A file
from an older version of the library is upgraded automatically when opened. The full CLI is in the
[README](../README.md).

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Module was compiled with an incompatible version of Kotlin … expected version is 2.0/2.1` | The app's Kotlin is older than 2.2. Update Kotlin (or AGP 9, which brings 2.2). |
| `2 files found with path 'META-INF/services/…'` | Add `packaging.resources.merges += "META-INF/services/**"` (§3). |
| `UnsatisfiedLinkError` for `onnxruntime` or SQLite on an emulator | `abiFilters` lacks the emulator's ABI. Add `"x86_64"`. |
| `Dependency 'androidx.…' requires … compileSdk 36` | That comes from AndroidX, not this library. Use `compileSdk = 36`. |
| Search gives only `LEXICAL` hits | No model files (check `RagModels.open`'s folder), or the books were embedded with another model (look for the warning in logcat). |
| `BookUnreadable` / `PdfUnreadable` / `NoTextLayer` | Not a PDF or EPUB, DRM-protected, needs a password, or a scan without text. |

## 11. The sample app

[`samples/android-app`](../samples/android-app) is a standalone build that uses the library only
through `rag:android:0.1.0`, as an app would. The steps below assume you're in the repository root.

1. Publish the library and build the sample:

   ```bash
   ./gradlew publishToMavenLocal
   ```
   ```bash
   ./gradlew -p samples/android-app :app:installRelease
   ```

2. Push a book (the model goes in `models/<repository name>/`, as in §4):

   ```bash
   adb push book.pdf /sdcard/Android/data/rag.sample/files/books/
   ```

3. Index the books folder, then search. The results also go to logcat with the tag `RagSample`:

   ```bash
   adb shell am start -S -n rag.sample/.MainActivity --ez indexFolder true
   ```
   ```bash
   adb shell "am start -S -n rag.sample/.MainActivity --es query 'what is the slot table' --es subject android"
   ```

You can do the same from the screen:
- **Add a book** opens the system file picker.
- **Index the books folder** indexes what you pushed.
- **Search** runs a query with an optional subject.
- **Tapping a result** opens the book there: a PDF at its page with the paragraph highlighted, an
  EPUB at the passage.

Add `--ei openResult 0` to the search command to open the first result directly.
