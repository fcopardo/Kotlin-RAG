# Kotlin-RAG

A command-line **digestor** that turns PDF and EPUB books into a small, local, searchable index for
retrieval-augmented generation (RAG). It runs entirely on your machine: the book is parsed (PDFBox
for PDF, jsoup for EPUB), stored in a single SQLite file, and embedded with a local model through
[Ollama](https://ollama.com). Nothing is sent to a remote service.

Each book is split into a hierarchy (**book → chapters → paragraphs → phrases**). Search
combines three ways of matching and returns, for each result, the closest sentence, its full
paragraph, and its chapter, with page numbers for citation: PDF pages, the printed pages of an EPUB
that has a page list, or "locations" for other EPUBs.

The index is designed to be **small**: the book text is stored once and everything else points
into it by offset; the full-text index stores no text; vectors are truncated and quantized, and
search keeps only 1-bit vectors in RAM. A 205-page technical book indexes to a **~2.9 MB** file
and needs about **100 KB** of RAM for vector search.

The full design, decisions and measurements are in [SPEC.md](SPEC.md).

## Requirements

- **JDK 21 or later** to run. Builds use a JDK 21 toolchain: an installed JDK 21, or one Gradle
  downloads once. The Gradle wrapper is included, so no separate Gradle install is needed.
- **Ollama** for embeddings (not needed for keyword-only search):

  ```bash
  brew install ollama
  ollama serve                      # leave it running
  ollama pull mxbai-embed-large     # ~670 MB, the default embedding model
  ollama pull nomic-embed-text      # optional: ~270 MB, lighter alternative
  ```

- **Books:**
  - **PDFs with a text layer.** Scanned (image-only) PDFs aren't supported, since there's no OCR; PDFs that need a password to open are rejected. OCR text layers work, with their recognition errors.
  - **DRM-free EPUB 2 or 3** (reflowable). DRM-protected and fixed-layout EPUBs are detected and rejected. Many store-bought EPUBs have DRM; DRM-free sources include Leanpub, many technical publishers, Standard Ebooks and Project Gutenberg.

## Build

```bash
./gradlew installDist
```

This produces the launcher at `cli/build/install/rag/bin/rag`. The examples below call it `rag`;
add `cli/build/install/rag/bin` to your `PATH`, or use the full path.

## Quick start

```bash
# 1. Index a book, PDF or EPUB (parses, stores, and embeds; about 40 s for a 200-page book)
rag index --file ~/Books/my-book.pdf --db books.db
rag index --file ~/Books/another-book.epub --db books.db

# 2. See what's in the database
rag books --db books.db
rag dump --db books.db --book 1

# 3. Search
rag search --db books.db -q "which thread runs recomposition"
```

Example output:

```
1. [HYBRID] 3. The Compose runtime · p. 81  (score 0.0308)
   phrase: It also determines what thread to compose or recompose on, and what thread to use for applying the changes.
   paragraph: …
   key: 6f1c0d…-0p-4c
```

## Commands

### `rag index`: add a book

```bash
rag index --file <pdf|epub> --db <file> [options]
```

| Option | Default | Meaning |
|---|---|---|
| `--file` | required | The PDF or EPUB to index; the format is detected from the content. `--pdf` still works as an alias. |
| `--db` | required | SQLite database file; created if missing. One file can hold many books. |
| `--toc-pages` | none | PDF only: pages (1-based) that hold the table of contents, e.g. `5-8` or `2-3,5`. Only needed when the PDF has no bookmarks. |
| `--location-size` | `2000` | EPUB without a page list: characters per location. |
| `--reindex` | off | Rebuild a book that is already indexed. |
| `--no-embed` | off | Store the structure only; run `rag embed` later. |
| `--embed-model` | `mxbai-embed-large` | Ollama embedding model. `nomic-embed-text` is a lighter alternative. |
| `--embed-dim` | `512` | Dimensions kept per vector (multiple of 64, at most the model's size). |
| `--ollama-url` | `http://localhost:11434` | Ollama server. |

- **Idempotent:** a book is identified by the SHA-256 of the file, so indexing the same PDF
  again does nothing unless you pass `--reindex`.
- **Chapters (PDF):** from the PDF's bookmarks; failing that, from the table of contents pages you
  give with `--toc-pages` (the offset between printed and PDF page numbers is worked out
  automatically); failing that, the whole book is one chapter. "Part" levels become
  `Part › Chapter` titles; sections stay inside their chapters.
- **Chapters (EPUB):** from the EPUB's table of contents (EPUB 3 nav or EPUB 2 NCX), else from
  each document's leading heading.
- **Cleanup:** running headers and footers (including OCR variants), page numbers, URL footnotes and
  their markers, soft hyphens and the table of contents are removed; numbered code listings become
  code blocks without their line numbers. In EPUBs, `<pre>` blocks keep their indentation.
- **Ollama down:** if Ollama can't be reached, nothing is stored and the command exits with
  code 5.

### `rag embed`: (re)embed a book

```bash
rag embed --db <file> --book <id> [--embed-model …] [--embed-dim …] [--ollama-url …]
```

Embeds the paragraphs that don't have a vector yet. Use it to finish an interrupted `index`, to
embed a book indexed with `--no-embed`, or to switch model or dimensions (the old vectors are
dropped first). Identical paragraphs are embedded once and share the vector.

### `rag search`: find passages

```bash
rag search --db <file> -q "<question or text>" [options]
```

| Option | Default | Meaning |
|---|---|---|
| `-q`, `--query` | required | What to look for: a question, keywords, or a sentence copied from the book. |
| `--k` | `5` | Number of results. |
| `--book` | all books | Only search this book id. |
| `--mode` | `hybrid` | `hybrid` (keywords + meaning), `lexical` (keywords only, no Ollama needed) or `vector` (meaning only). |
| `--json` | off | Print results as JSON, for scripts and LLM tools. |
| `--embed-model` | the model the books were embedded with | Override the query embedding model. |
| `--ollama-url` | `http://localhost:11434` | Ollama server. |
| `--lexical-weight`, `--rrf-k` | `0.5`, `5` | Fusion settings, for calibration; see SPEC.md §8.3. |
| `--no-summaries` | off | Search paragraphs only, even if the book has summaries. |
| `--front-matter-weight`, `--summary-weight`, `--max-summary-hits` | `0.5`, `1.0`, `2` | More calibration knobs (SPEC.md §8.3). |

How a search works:

1. **Exact match:** if the query is exactly a sentence, paragraph or chapter of a book (ignoring
   case and spacing), that hit comes first (`EXACT_PHRASE`, `EXACT_PARAGRAPH`, …).
2. **Keywords:** BM25 full-text search over paragraphs, ignoring common words like "the" or "how".
3. **Meaning:** the query is embedded and compared with every paragraph's 1-bit vector; the
   closest ~200 are re-scored with 8-bit vectors.
4. **Fusion:** keyword and meaning rankings are merged (reciprocal rank fusion, k = 5), with the
   keyword ranking at half weight. Each hit says how it was found: `HYBRID` (both), `LEXICAL` or
   `VECTOR`.
5. For each paragraph, the **closest phrase** (sentence) is picked.
6. If the book has summaries (`rag summarize`), up to 2 summary hits are **added** to the k
   passages when they rank among the top k, so a result has at most k + 2 hits. Front-matter
   paragraphs (copyright, dedications) count half, so they don't bury real answers.

If Ollama is down, `hybrid` search falls back to keywords with a warning; `vector` search fails.

**JSON output** (`--json`) is a list of hits:

```json
[
  {
    "score": 1.0,
    "matchType": "EXACT_PHRASE",
    "phrase":    { "unitKey": "8270da367b74fc5b-0p-2c", "text": "…", "pageStart": 13, "pageEnd": 13, "pageStartLabel": "13", "pageEndLabel": "13" },
    "paragraph": { "unitKey": "…-0g-2c", "text": "…", "pageStart": 13, "pageEnd": 13, "pageStartLabel": "13", "pageEndLabel": "13" },
    "chapter":   { "number": 2, "title": "1. Composable functions", "pageStart": 11, "pageEnd": 24, "unitKey": "…", "pageStartLabel": "11", "pageEndLabel": "24" },
    "bookId": 1,
    "bookTitle": "…",
    "pageKind": "pdf"
  }
]
```

**Unit keys** such as `8270da367b74fc5b-0p-2c` read as *hash – 1st occurrence (0) of this
phrase (p) – in chapter 2 (c)*. They're derived from the text, so they stay the same when a
book is re-indexed, which makes them good for citations.

**Pages:** `pageStart`/`pageEnd` are page ordinals; cite with the labels. `pageKind` says what the
labels are: `pdf` (PDF page numbers), `print` (the printed edition's pages, from an EPUB page list)
or `location` (`loc 37`: fixed-size chunks of an EPUB without a page list).

### `rag eval`: measure search quality

```bash
rag eval --db <file> --set <questions.jsonl> [--book <id>] [--k 5] [--mode all|hybrid|lexical|vector] [--details] [--lexical-weight 0.5] [--rrf-k 5]
```

Runs a set of questions with known answers and reports, per mode: recall@k (share of questions
whose answer page is in the top k), MRR, chapter recall, and recall per question kind.
`--details` shows each question's rank. Each line of the set is one question:

```json
{"id":"q02","query":"positional memoization","expectedPages":[17,18],"expectedChapter":"1. Composable functions","kind":"keyword"}
```

Instead of (or besides) `expectedPages`, a question can give `expectedAnchor`: a short phrase from
the answer passage, matched inside the hit's paragraph. Anchors work across formats and editions.
`kind` is free text; the repository's sets use `keyword` and `paraphrase` (a question that shares
no content words with its answer). Sets in [src/test/resources/eval/](src/test/resources/eval/):

| Set | Book | Hybrid recall@5 (default settings) |
|---|---|---|
| `compose-internals.jsonl` | *Jetpack Compose internals* (PDF, not in the repo) | 0.80 |
| `art-of-war-se.jsonl` | *The Art of War*, Standard Ebooks (EPUB, in the repo) | 0.75 |

### `rag summarize`: chapter and book summaries (optional)

```bash
ollama pull llama3.2:3b           # ~2.0 GB, once
rag summarize --db <file> --book <id> [--summary-model llama3.2:3b] [--redo]
```

Writes summaries with a small local model: one per ~1,500-token section, one per chapter, and one
for the whole book. They let search answer "what is chapter 5 about?" or "what does this book
cover?", which no single paragraph answers. Summaries are embedded with the book's own embedding
model and show up in search as `[SUMMARY section|chapter|book]` hits (at most 2 per query) with the
summary text and the best paragraph inside it. They're added on top of the k passages, so they
never push answers to specific questions out of the results.

- For a 205-page book: 62 summaries in about 4 minutes on an Apple M4 Pro, +156 KB of storage.
  The model uses ~2.5 GB of RAM, only while summarizing.
- Interrupted runs resume where they stopped; `--redo` or another model starts over.
- `rag dump --db <file> --book <id> --summaries` prints them; `rag search --no-summaries` leaves them out.

### `rag books`: list books

```bash
rag books --db <file>
```

Shows each book's id, title, how its chapters were found, and its embedding status.

### `rag dump`: look inside a book

```bash
rag dump --db <file> --book <id>                 # list chapters with page ranges
rag dump --db <file> --book <id> --chapter 3     # print chapter 3's text
rag dump --db <file> --book <id> --summaries     # print its summaries
```

Chapters are numbered in reading order: `0` is the front matter (cover, preface…), then `1, 2, …`.
This can differ from the book's own numbering; titles keep the book's numbers.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success. |
| `2` | Bad arguments. |
| `3` | The PDF can't be read: not a PDF, password-protected, or no text layer. |
| `4` | Database error. |
| `5` | Embedding backend unavailable (Ollama down, model not pulled), or a model mismatch between the query and the book's vectors. |

## Disk and memory

Measured on a 205-page technical book (374 KB of text, 1,649 paragraphs):

| Part | Size |
|---|---|
| Whole database file | ~2.9 MB |
| Vectors on disk (512 dims, int8 + 1-bit) | ~1.1 MB |
| RAM for vector search (1-bit vectors) | ~106 KB |
| Ollama with `mxbai-embed-large` (default) | ~687 MB while loaded, needed while indexing and to embed each query |
| Ollama with `nomic-embed-text` | ~370 MB while loaded |
| Summaries (optional) | +156 KB disk, ~4 KB RAM for search; `llama3.2:3b` uses 2.5 GB only while summarizing |

Compact vectors agree with full-precision search on 89% of the top-10 results, and give the same
recall on the evaluation set; see SPEC.md §11.

## Development

```bash
./gradlew test               # every module; SQLite through sqlite-jdbc
./gradlew testBundledSqlite  # the integration tests again on androidx's bundled SQLite (the Android driver)
./gradlew check              # both
```

The Android modules need the Android SDK: set `sdk.dir` in `local.properties` (not committed) or
`ANDROID_HOME`. Their instrumented tests run on a connected phone or a running emulator:

```bash
./gradlew :android:connectedDebugAndroidTest
```

They compare against goldens the desktop builds first (`:integration-tests:androidGoldens`, run
automatically): the same books must give the same units, and a desktop-built DB the same results.

Unit tests run without Ollama or any real book. Two groups of extra tests are opt-in through
environment variables:

| Variable | Enables |
|---|---|
| `RAG_OLLAMA=1` | Tests against a real local Ollama (`mxbai-embed-large` and `nomic-embed-text` must be pulled). `RAG_OLLAMA_URL` overrides the server URL. |
| `RAG_BOOK_PDF=/path/to/book.pdf` | Tests against *Jetpack Compose internals* (Jorge Castillo, Leanpub, 2022-11-20). The book is copyrighted and isn't part of this repository; use your own copy. |
| `RAG_SUMMARY_MODEL=llama3.2:3b` | With `RAG_BOOK_PDF` and `RAG_OLLAMA=1`: summarizes the book and checks word caps, grounding and summary hits (~4 minutes). |
| `RAG_ONNX_MODEL_DIR=models/mxbai-embed-large-v1` | With `RAG_OLLAMA=1`: ONNX Runtime vs Ollama parity for mxbai (the on-device path). Needs `vocab.txt` and `onnx/model.onnx` + `onnx/model_quantized.onnx` from [mixedbread-ai/mxbai-embed-large-v1](https://huggingface.co/mixedbread-ai/mxbai-embed-large-v1) (Apache-2.0); `models/` is git-ignored. |
| `RAG_KOTLIN_PDF`, `RAG_CTCI_PDF` | Structure tests against *Kotlin for Android Developers* (Antonio Leiva) and *Cracking the Coding Interview*, 6th ed. (Gayle Laakmann McDowell), from your own copies. |

The EPUB tests use two public-domain editions of *The Art of War* committed in
[integration-tests/src/test/resources/epub/](integration-tests/src/test/resources/epub/) (sources and licenses in its README), so they run anywhere;
their evaluation needs `RAG_OLLAMA=1`.

```bash
RAG_OLLAMA=1 RAG_BOOK_PDF=~/Downloads/composeinternals.pdf ./gradlew test
```

Gradle modules (package names are the same in all of them):

```
core/               pipeline, chapters, segmentation, keys, store logic, search, evaluation, summary tree
pdf-pdfbox/         PDF books (PDFBox)
epub/               EPUB books (jsoup)
store-jdbc/         SQLite driver for desktop (sqlite-jdbc)
store-bundled/      SQLite driver for Android (androidx bundled)
embed-ollama/       embeddings and summaries through Ollama
embed-onnx/         in-process embeddings (ONNX Runtime) + WordPiece tokenizer
cli/                the `rag` command
integration-tests/  end-to-end tests, fixture EPUBs, evaluation sets, Android goldens
pdf-pdfbox-android/ PDF books on Android (PdfBox-Android; sources generated from pdf-pdfbox)
android/            the Android library: RagEngine (minSdk 26)
```

The core has no dependency on PDFBox, jsoup, SQLite drivers or HTTP; format readers and drivers
plug in through `META-INF/services`.

### Android

`RagEngine` indexes and searches on the device with the same index format as the desktop, so a
`.db` built with `rag index` can be copied to a phone and searched there:

```kotlin
val engine = RagEngine(context, File(context.filesDir, "books.db"))      // keyword search only
val hits = engine.search("attack by stratagem", k = 5)

// With on-device embeddings (an ONNX model that passed the parity test, SPEC.md §15.4):
val embedder = OnnxEmbedder(modelFile, WordPieceTokenizer.fromVocabFile(vocab),
    EmbeddingModel.MXBAI_EMBED_LARGE, model = "mxbai-embed-large")
RagEngine(context, dbFile, embedder).use { it.index(bookFile) { done, total -> /* progress */ } }
```

All functions are `suspend` and run on `Dispatchers.IO`. Cancelling `index` or `embed` stops
between embedding batches, and `embed(bookId)` resumes later. Apps that depend on more than one
module should merge service files: `packaging.resources.merges += "META-INF/services/**"`.

## Status and roadmap

- **Done (phases 1–5):** extraction, chapter detection, segmentation, storage, embeddings,
  search, CLI.
- **Done:** a calibrated evaluation set (`rag eval`), which chose the default model, the vector
  size and the fusion weights.
- **Done (Phase 6, optional):** chapter and book summaries made by a small local model, so
  questions like "what is chapter 5 about?" can be answered (SPEC.md §5.7).
- **Phase 7 (steps 1–5 done):** desktop and Android from one code base, sharing the same index
  file, with on-device embeddings through ONNX Runtime. The Android library passes its
  instrumented tests on a phone. Next: measuring speed and memory on the phone (SPEC.md §15).
- **Done (Phase 8):** EPUB input (EPUB 2 and 3, DRM-free), with chapters from the EPUB's own
  table of contents and citations by printed page where the EPUB has a page list (SPEC.md §16).
