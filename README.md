# Kotlin-RAG

A command-line **digestor** that turns PDF books into a small, local, searchable index for
retrieval-augmented generation (RAG). It runs entirely on your machine: the PDF is parsed with
PDFBox, stored in a single SQLite file, and embedded with a local model through
[Ollama](https://ollama.com). Nothing is sent to a remote service.

Each book is split into a hierarchy (**book → chapters → paragraphs → phrases**). Search
combines three ways of matching and returns, for each result, the closest sentence, its full
paragraph, and its chapter, with PDF page numbers for citation.

The index is designed to be **small**: the book text is stored once and everything else points
into it by offset; the full-text index stores no text; vectors are truncated and quantized, and
search keeps only 1-bit vectors in RAM. A 205-page technical book indexes to a **~2.9 MB** file
and needs about **100 KB** of RAM for vector search.

The full design, decisions and measurements are in [SPEC.md](SPEC.md).

## Requirements

- **JDK 21 or later.** The Gradle wrapper is included, so no separate Gradle install is needed.
- **Ollama** for embeddings (not needed for keyword-only search):

  ```bash
  brew install ollama
  ollama serve                      # leave it running
  ollama pull mxbai-embed-large     # ~670 MB, the default embedding model
  ollama pull nomic-embed-text      # optional: ~270 MB, lighter alternative
  ```

- **PDFs with a text layer.** Scanned (image-only) PDFs aren't supported, since there's no OCR.
  PDFs that need a password to open are rejected.

## Build

```bash
./gradlew installDist
```

This produces the launcher at `build/install/rag/bin/rag`. The examples below call it `rag`;
add `build/install/rag/bin` to your `PATH`, or use the full path.

## Quick start

```bash
# 1. Index a book (parses, stores, and embeds; about 40 s for a 200-page book)
rag index --pdf ~/Books/my-book.pdf --db books.db

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
rag index --pdf <file> --db <file> [options]
```

| Option | Default | Meaning |
|---|---|---|
| `--pdf` | required | The PDF to index. |
| `--db` | required | SQLite database file; created if missing. One file can hold many books. |
| `--toc-pages` | none | PDF pages (1-based) that hold the table of contents, e.g. `5-8` or `2-3,5`. Only needed when the PDF has no bookmarks. |
| `--reindex` | off | Rebuild a book that is already indexed. |
| `--no-embed` | off | Store the structure only; run `rag embed` later. |
| `--embed-model` | `mxbai-embed-large` | Ollama embedding model. `nomic-embed-text` is a lighter alternative. |
| `--embed-dim` | `512` | Dimensions kept per vector (multiple of 64, at most the model's size). |
| `--ollama-url` | `http://localhost:11434` | Ollama server. |

- **Idempotent:** a book is identified by the SHA-256 of the file, so indexing the same PDF
  again does nothing unless you pass `--reindex`.
- **Chapters:** chapters are found from the PDF's bookmarks; failing that, from the table of
  contents pages you give with `--toc-pages` (the offset between printed and PDF page numbers is
  worked out automatically); failing that, the whole book is one chapter.
- **Cleanup:** running headers and footers, page numbers, URL footnotes and the table of contents
  are removed; numbered code listings are merged into code blocks without their line numbers.
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

How a search works:

1. **Exact match:** if the query is exactly a sentence, paragraph or chapter of a book (ignoring
   case and spacing), that hit comes first (`EXACT_PHRASE`, `EXACT_PARAGRAPH`, …).
2. **Keywords:** BM25 full-text search over paragraphs, ignoring common words like "the" or "how".
3. **Meaning:** the query is embedded and compared with every paragraph's 1-bit vector; the
   closest ~200 are re-scored with 8-bit vectors.
4. **Fusion:** keyword and meaning rankings are merged (reciprocal rank fusion), with the keyword
   ranking at half weight. Each hit says how it was found: `HYBRID` (both), `LEXICAL` or `VECTOR`.
5. For each paragraph, the **closest phrase** (sentence) is picked.

If Ollama is down, `hybrid` search falls back to keywords with a warning; `vector` search fails.

**JSON output** (`--json`) is a list of hits:

```json
[
  {
    "score": 1.0,
    "matchType": "EXACT_PHRASE",
    "phrase":    { "unitKey": "8270da367b74fc5b-0p-2c", "text": "…", "pageStart": 13, "pageEnd": 13 },
    "paragraph": { "unitKey": "…-0g-2c", "text": "…", "pageStart": 13, "pageEnd": 13 },
    "chapter":   { "number": 2, "title": "1. Composable functions", "pageStart": 11, "pageEnd": 24, "unitKey": "…" },
    "bookId": 1,
    "bookTitle": "…"
  }
]
```

**Unit keys** such as `8270da367b74fc5b-0p-2c` read as *hash – 1st occurrence (0) of this
phrase (p) – in chapter 2 (c)*. They're derived from the text, so they stay the same when a
book is re-indexed, which makes them good for citations.

### `rag eval`: measure search quality

```bash
rag eval --db <file> --set <questions.jsonl> [--book <id>] [--k 5] [--mode all|hybrid|lexical|vector] [--details]
```

Runs a set of questions with known answers and reports, per mode: recall@k (share of questions
whose answer page is in the top k), MRR, chapter recall, and recall per question kind.
`--details` shows each question's rank. Each line of the set is one question:

```json
{"id":"q02","query":"positional memoization","expectedPages":[17,18],"expectedChapter":"1. Composable functions","kind":"keyword"}
```

`kind` is free text; the repository's set uses `keyword` and `paraphrase` (a question that shares
no content words with its answer). [src/test/resources/eval/compose-internals.jsonl](src/test/resources/eval/compose-internals.jsonl)
holds 20 questions for *Jetpack Compose internals*. Current results with the default model:
hybrid recall@5 **0.80**, all keyword questions found, 5 of 9 paraphrases.

### `rag books`: list books

```bash
rag books --db <file>
```

Shows each book's id, title, how its chapters were found, and its embedding status.

### `rag dump`: look inside a book

```bash
rag dump --db <file> --book <id>                 # list chapters with page ranges
rag dump --db <file> --book <id> --chapter 3     # print chapter 3's text
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

Compact vectors agree with full-precision search on 89% of the top-10 results, and give the same
recall on the evaluation set; see SPEC.md §11.

## Development

```bash
./gradlew test
```

Unit tests run without Ollama or any real book. Two groups of extra tests are opt-in through
environment variables:

| Variable | Enables |
|---|---|
| `RAG_OLLAMA=1` | Tests against a real local Ollama (`mxbai-embed-large` and `nomic-embed-text` must be pulled). `RAG_OLLAMA_URL` overrides the server URL. |
| `RAG_BOOK_PDF=/path/to/book.pdf` | Tests against *Jetpack Compose internals* (Jorge Castillo, Leanpub, 2022-11-20). The book is copyrighted and isn't part of this repository; use your own copy. |
| `RAG_KOTLIN_PDF`, `RAG_CTCI_PDF` | Structure tests against *Kotlin for Android Developers* (Antonio Leiva) and *Cracking the Coding Interview*, 6th ed. (Gayle Laakmann McDowell), from your own copies. |

```bash
RAG_OLLAMA=1 RAG_BOOK_PDF=~/Downloads/composeinternals.pdf ./gradlew test
```

Code layout:

```
src/main/kotlin/rag/
  Main.kt            CLI
  BookReader.kt      PDF → chapters → paragraphs → phrases
  extract/           PDF text, header/footer/footnote/code-listing cleanup, normalization
  structure/         chapter detection (bookmarks, TOC), segmentation
  embed/             Ollama client, truncation and quantization
  index/             unit keys, indexing and embedding
  store/             SQLite schema and queries
  search/            exact, BM25 and vector search, rank fusion
```

## Status and roadmap

- **Done (phases 1–5):** extraction, chapter detection, segmentation, storage, embeddings,
  search, CLI.
- **Done:** a calibrated evaluation set (`rag eval`), which chose the default model, the vector
  size and the fusion weights.
- **Planned (Phase 6, optional):** chapter and book summaries made by a small local model, so
  questions like "what is chapter 5 about?" can be answered. Design in SPEC.md §5.7.
- **Planned (Phase 7):** desktop and Android from one code base, sharing the same index file,
  with on-device embeddings through ONNX Runtime. Design in SPEC.md §15.
- **Planned (Phase 8):** EPUB input (EPUB 2 and 3, DRM-free), with chapters from the EPUB's own
  table of contents and citations by printed page where the EPUB has a page list. Design in
  SPEC.md §16.
