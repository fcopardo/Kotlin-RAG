# Spec: Book PDF → Hierarchical RAG Index (Kotlin/JVM)

## 1. Goal

A Kotlin/JVM command-line tool, a **digestor**, that reads a PDF book and splits its text into a hierarchy: **phrases → paragraphs → chapters → book**. It stores that hierarchy in a local database, with an **embedding** for each paragraph, and gives an LLM a search function. Search combines exact, keyword (BM25) and meaning-based (vector) matching. Each result returns the matching phrase, its full paragraph and its chapter's data, with page numbers for citation.

**Design goal: low memory.** The index should stay small on disk and in RAM, so many books fit on modest hardware. Each piece of text is stored once, and everything else points at it by offset. The full-text index doesn't store text. Vectors are truncated and quantized, and search keeps only compact binary vectors in RAM. §13 gives the budget.

### Known techniques this follows
- **Small-to-big / parent-document retrieval** (LangChain parent-document retriever; LlamaIndex hierarchical parser and auto-merging retriever): match small units, return the larger unit around them.
- **Sentence-window retrieval:** the closest phrase plus its whole paragraph.
- **Contextual retrieval** (Anthropic, 2024): add document context before embedding. Here that's the chapter-title prefix (§5.6).
- **Hybrid BM25 + dense search with reciprocal rank fusion** (§8.3).
- **RAPTOR** (Sarthi et al., 2024): a tree of summaries, so chapter- and book-level questions can be answered (§5.7, optional).
- **Matryoshka embeddings + binary quantization with rescoring** for small vectors (§5.6, §8.3).
- **Content-addressed dedup:** the master record (§6.3).

### Non-goals (v1)
- Scanned or image-only PDFs. There is no OCR. Fail with a clear error if a page range has no text layer.
- PDFs that need a user password to open. Fail with a clear error. PDFs encrypted with only an owner password (permission restrictions, common in published books) open without one and are accepted.
- Multi-column layout reconstruction beyond what PDFBox gives by default.
- A remote or server database, and remote embedding APIs. Everything runs locally.
- An approximate-nearest-neighbour (ANN) vector index. Brute-force similarity is enough at single-book scale (§8.3).

## 2. Terminology

The original spec used "index" for two different things. Use these terms instead:

| Term | Meaning |
|---|---|
| **TOC** | The book's table of contents ("Contents" pages). |
| **Unit** | One phrase, paragraph, chapter or book. |
| **Unit key** | The string identifier for one occurrence of a unit (§6). |
| **Master record** | All occurrences that share the same normalized text hash (§6.3). |
| **Book text** | The full normalized text of the book, stored in the DB. All offsets point into it. |
| **Embedding** | A float vector from the embedding model that represents a text's meaning. Texts with similar meaning have vectors with high cosine similarity. |
| **Matryoshka truncation** | Keeping only the first *d* dimensions of an embedding from a model trained so its leading dimensions carry the most meaning, then re-normalizing. |
| **int8 / binary vector** | A vector compressed to 1 byte per dimension (with a per-vector scale) or to 1 bit per dimension (the sign). |
| **PDF page** | Absolute page number in the PDF, 1-based. |
| **Printed page** | The page number printed in the TOC or on the page. |

## 3. Tech stack

- Kotlin 2.x, JVM 21, Gradle with the Kotlin DSL.
- Apache PDFBox **3.0.x**, for text extraction and the outline.
- `org.xerial:sqlite-jdbc` **3.43 or later**. It bundles FTS5, and 3.43 is the first version with `contentless_delete`.
- **Ollama** running locally (default `http://localhost:11434`). It serves the embedding model (§8.1) and, optionally, the summary model (§5.7).
- HTTP client: `java.net.http.HttpClient`, from the JDK. JSON: `kotlinx-serialization-json`.
- CLI parsing: `clikt`.
- Tests: JUnit 5. Test PDFs are generated with PDFBox inside the tests, plus one public-domain fixture (§11). Tests that need Ollama go behind a tag (§11).
- No other runtime dependencies without a reason.

## 4. CLI

```
rag index  --pdf <file> --db <file> [--toc-pages <a-b[,c-d]>] [--locale en] [--reindex] [--no-embed]
           [--ollama-url http://localhost:11434] [--embed-model nomic-embed-text] [--embed-dim 256]
rag embed  --db <file> --book <bookId> [--ollama-url …] [--embed-model …] [--embed-dim …]  # (re)build embeddings only
rag summarize --db <file> --book <bookId> [--summary-model llama3.2:3b]                  # optional, §5.7
rag books  --db <file>                                                                  # list indexed books
rag search --db <file> --query "<text>" [--k 5] [--book <bookId>] [--mode hybrid|lexical|vector] [--json]
rag dump   --db <file> --book <bookId> [--chapters | --chapter <n>]
```

- `--toc-pages`: PDF pages (1-based, inclusive) that contain the TOC. This is optional; see §5.3 for how chapters are found without it.
- `index` is **idempotent**. A book is identified by the SHA-256 of the PDF file. Re-indexing the same file does nothing unless `--reindex` is passed, which deletes and rebuilds that book.
- `index` fails with exit code `5` if Ollama can't be reached or the model isn't pulled. In that case nothing for that book is committed. The error message tells the user to run `ollama pull <model>`.
- `--no-embed` stores the structure only; run `rag embed` later.
- `embed` rebuilds the embeddings for an already-indexed book without re-parsing it. Use it to switch models or dimensions, or to resume after an interrupted run. Changing the model or `--embed-dim` drops the book's existing vectors first.
- `--mode` defaults to `hybrid`.
- One DB can hold many books.
- Exit codes: `0` ok, `2` bad arguments, `3` PDF unreadable/encrypted/no text, `4` DB error, `5` embedding backend unavailable or model mismatch.

## 5. Pipeline

### 5.1 Extract
- Extract text page by page with `PDFTextStripper`, with `setSortByPosition(true)`. Keep where each page starts in the book text.
- **Header/footer removal:** for each page, take the first 2 and last 2 non-empty lines and replace digits with `#`. Any line that appears on more than 30% of pages, **and on at least 3 pages**, is boilerplate and is dropped. The 3-page floor protects short documents, where a single page is already more than 30%. This also removes printed page numbers.
- **Running chapter titles** repeat only within their chapter, so they usually stay under the 30% rule. They're removed after chapter detection (§5.3). On every page after a chapter's first page, an edge line is dropped when it equals the chapter's title once a leading or trailing page number is stripped, e.g. `1. Composable functions 4`. The chapter's first page keeps its heading.
- **TOC pages** are left out of the book text. They stay as empty pages, so PDF page numbers and offsets still line up. Otherwise searches would hit the list of titles instead of the chapters.

### 5.2 Normalize
Applied in this order, before splitting and before hashing:
1. Unicode NFKC. This expands ligatures, e.g. `ﬁ` → `fi`.
2. Normalize line endings to `\n`.
3. Rejoin words hyphenated at line ends: `exam-\nple` → `example`. Do this only when the letters on **both** sides are lowercase, so `well-\nKnown` and `COVID-\n19` are kept. Real compounds split at a line end (`well-\nknown`) get joined; that's an accepted trade-off.
4. Collapse runs of spaces and tabs into one space. Trim trailing spaces on each line. Collapse 3 or more newlines into 2, so a paragraph gap is always exactly one blank line.

A word hyphenated across a page break isn't rejoined, because pages are normalized one at a time to keep page offsets exact.

The result is the **book text**. It is stored in the DB as-is, and every offset refers to it.

**Hash normalization** is an extra step used only to compute hashes: lowercase, collapse all whitespace (including `\n`) to one space, and trim. The hash is the first 16 hex characters of SHA-256 over the UTF-8 bytes.

### 5.3 Chapters
Strategies are tried in order. The first one that produces at least 2 chapters wins.

1. **PDF outline.** Use `PDDocumentOutline` top-level items that point to a page. The title is the outline title, and the start is that PDF page. An item titled "Contents" or "Table of Contents" isn't a chapter; it marks where the TOC starts. The TOC then continues while the following pages still look like a TOC (at least half of their lines are entries), up to the next outline item. If `--toc-pages` is given, those pages are used instead.
2. **TOC pages** (only if `--toc-pages` is given). Parse each line with
   `^(?<title>.+?)[\s.·…_-]{2,}(?<page>\d+|[ivxlcdm]+)\s*$` (case-insensitive).
   - Skip a line that is just "Contents" or "Table of Contents".
   - **Top level only:** read TOC lines with their x position. Entries indented more than 3pt past the leftmost entry are sections, not chapters.
   - Entries whose page number is a Roman numeral are front matter and are not chapters.
   - **Page offset:** look for the first Arabic-numbered entry's title as a line on the PDF pages *after* the last TOC page, comparing hash-normalized text. Then `offset = foundPdfPage - printedPage`, and `pdfPage = printedPage + offset` for every entry.
   - **Check:** for each entry, confirm the title appears as a line on `pdfPage` or within ±2 pages. If it is found somewhere else in that window, use that page for this entry and the ones after it. If it isn't found, keep the computed page and log a warning.
3. **Fallback.** The whole book is one chapter titled "Book".

**Title matching** (used for the offset, the check, and chapter boundaries): compare hash-normalized lines. A title may also match two consecutive lines, because long headings wrap.

**Chapter boundaries:** a chapter starts at the **offset of its title line** on its start page, or at the top of the page if the line isn't found. It ends just before the next chapter starts. Text before the first chapter becomes chapter **0**, titled "Front matter". Chapters are numbered from 1 in reading order, which can differ from the book's own numbering. In *Jetpack Compose internals*, "Prelude" is chapter 1 and "1. Composable functions" is chapter 2. The title keeps the book's own number. A chapter's `page_start` is the first page with text, so an image-only cover isn't counted.

Only one level of chapters is supported in v1. Nested entries (`1.1 …`) are treated as top-level chapters only if they appear in the TOC or outline at the top level.

### 5.4 Paragraphs
- A paragraph boundary is a **blank line**, i.e. `\n\s*\n`. PDFBox has to produce these, so set `setParagraphStart("")` and `setParagraphEnd("\n\n")` on the stripper. A plain `\n` is only a wrapped line.
- **Stored text is the exact substring.** A unit's text is always `bookText.substring(start, end + 1)`, trimmed, with its wrapped `\n`s kept. Newlines are flattened to spaces only where that matters: sentence splitting, and the embedding input (§5.6).
- **Page breaks:** pages are joined with `\n`, which continues the paragraph. The break starts a new paragraph (`\n\n`) when the previous page ends with `.?!:` and the next page doesn't start in lowercase, or when exactly one side of the break is a code listing.
- **Code listings:** during assembly, a run of 2 or more lines numbered n, n+1, … (blank lines between them allowed, plus up to 2 unnumbered wrapped lines) becomes one code block. The line numbers are removed from the book text itself, so offsets stay exact. A listing that continues onto the next page stays one block. Code paragraphs are flagged `is_code`, and their phrases are their lines.
- Paragraphs never cross chapter boundaries.
- **Size cap:** a paragraph longer than **400 tokens** is split at sentence boundaries into consecutive paragraphs of 400 tokens or fewer. For v1, approximate tokens as words × 1.3. This also keeps every paragraph inside the embedding model's context window.
- Empty paragraphs (only whitespace) are dropped.

### 5.5 Phrases
- Split each paragraph with `java.text.BreakIterator.getSentenceInstance(locale)`. Then rejoin splits after common abbreviations (`Dr.`, `e.g.`, `Fig.` …) and single initials (`J.`), because the JDK iterator has no abbreviation handling. A period followed by a lowercase word isn't a sentence end.
- A phrase includes its ending punctuation (`.`, `?`, `!`, and any closing quote or bracket) and has its outer whitespace trimmed.
- **A phrase never crosses a paragraph boundary.** If a sentence spans a paragraph break, the break wins.
- A paragraph with no sentence terminator is one phrase.

### 5.6 Embed
- Embed every **paragraph**. Phrases, chapters and the book are not embedded in v1. At retrieval time a phrase is too short to carry much meaning, and a chapter is too long for one vector to represent.
- **Embedding input:** `"search_document: " + chapterTitle + "\n" + paragraphText`. For prose, `paragraphText` has its newlines flattened to spaces; code keeps its lines. Prefixing the chapter title adds context that a short paragraph lacks. The `search_document:` prefix is what `nomic-embed-text` requires for documents. It comes from the `EmbeddingModel` config (§8.1) so another model can use a different prefix or none.
- Send paragraphs in batches of **32** per request (`POST /api/embed` with `input: [...]`).
- **Matryoshka truncation:** keep the first `--embed-dim` dimensions (default **256**, out of 768) and L2-normalize again. `nomic-embed-text` v1.5 is trained for 768/512/256/128/64. Ollama returns vectors that are already normalized, so truncation happens client-side after normalization. That's a close approximation of nomic's recipe (layer norm → truncate → normalize), and the evaluation in §11 checks the loss. `--embed-dim` must be a multiple of 64.
- **Quantize and store two forms. Floats are never stored.**
  - **int8:** `q[i] = round(v[i] / scale)` with `scale = max|v| / 127`. That's 1 byte per dimension plus a 4-byte scale. It's used to rescore candidates.
  - **binary:** bit `i` is set when `v[i] > 0`, packed into 64-bit words. That's 1 bit per dimension. It's used for the first-pass scan.
- **Resumable:** embeddings are written in their own transactions, one per batch, after the structural data is committed. A paragraph without a row in `embedding` gets embedded on the next `rag embed` run. A book counts as fully indexed only when every paragraph has an embedding (`book.embed_status = 'complete'`).
- Retry policy: up to 3 tries with exponential backoff (1s, 2s, 4s) per batch. After that, stop with exit code 5. The structure stays committed and `embed_status` stays `partial`.

### 5.7 Summaries (optional, RAPTOR-style)
Paragraph vectors can't answer "what is chapter 5 about?". Summaries can, and since they're made once at index time, query cost doesn't change.
- `rag summarize` uses a small local model through Ollama `POST /api/generate` (default `llama3.2:3b`). Nothing leaves the machine.
- **Tree:**
  1. Split each chapter's paragraphs into consecutive **sections** of about 2,000 tokens.
  2. Summarize each section in at most 120 words.
  3. Summarize the section summaries into a **chapter summary** of at most 200 words.
  4. Summarize the chapter summaries into a **book summary** of at most 250 words.

  The input never exceeds a small model's context window.
- Summaries are generated text, so they're stored **with** their text (`summary` table, §7). They're embedded and quantized like paragraphs, and they're indexed in `summary_fts`.
- A summary hit returns the summary text plus its chapter (or the book). The phrase and paragraph come from the chapter's best-matching paragraph for the same query (§8.3).
- It's resumable and idempotent like `embed`: existing summaries for the same model are kept.

## 6. Unit keys and master record

### 6.1 Unit key format
```
<hash>-<n><type>-<m><scope>
```
| Part | Meaning |
|---|---|
| `hash` | Unit hash (§5.2), 16 hex characters. |
| `n` | Occurrence count of this hash **within the scope**, **0-based**. |
| `type` | `p` phrase, `g` paragraph, `c` chapter, `b` book. |
| `m` | Ordinal of the scope unit: chapter number for `c`, book id for `b`. Front matter is chapter `0`. |
| `scope` | `c` for phrases and paragraphs, `b` for chapters. |

The book key is just `<hash>-0b`.

Examples:
- The phrase "The bike went really fast." first seen in chapter 1 → `3fa9c0d1e2b47a10-0p-1c`
- The same phrase seen a second time in chapter 1 → `…-1p-1c`
- The same phrase in chapter 2 → `…-0p-2c`

The key is a **derived identifier**, and it is unique per book. **It isn't stored.** It's rebuilt from `hash`, `occurrence`, `kind` and `chapter` (or `book_id` for chapters) when a unit is shown. A lookup by key parses it back into those parts and uses the hash index (`store.findByKey`). Keys stay stable across re-indexing, unlike row ids, so they're what citations and callers should hold on to. The parent link (phrase → paragraph → chapter → book) is stored explicitly as `parent_id`, not encoded in the key.

### 6.2 Unit value
Every unit stores `"<start>-<end>"`. These are character offsets into the book text: 0-based, `start` inclusive, `end` **inclusive**, pointing at the first and last character of the unit. Each unit also stores `page_start` and `page_end` (PDF pages).

### 6.3 Master record
`hash → [unit keys…]`, the list of every occurrence of that text across the whole DB. It comes from an index on `unit.hash`, so there is no separate table to keep in sync. It is used to find duplicates and boilerplate, and for the exact-match path in search.

Paragraphs with the same hash, the same embedding model and the same dimension share the same embedding. Before calling Ollama, look for an existing `embedding` row with that model and dimension on any unit with that hash, and copy it instead of embedding again.

## 7. Storage (SQLite)

**Store text once.** The book text lives only in `book.text`. Units store offsets and no text; their text is `book.text.substring(char_start, char_end + 1)`, cut in Kotlin because offsets count UTF-16 code units and SQL `substr` counts code points. The FTS tables are **contentless** (`content=''`): they hold only the inverted index, which is enough for `MATCH` and `bm25()`. `contentless_delete=1` lets a re-indexed book's rows be deleted. Only summaries (generated text, §5.7) store their own text.

```sql
PRAGMA foreign_keys = ON;

CREATE TABLE book (
  id            INTEGER PRIMARY KEY,
  file_sha256   TEXT NOT NULL UNIQUE,
  title         TEXT,
  text          TEXT NOT NULL,             -- the book text (§5.2): the only copy
  extractor     TEXT NOT NULL,             -- e.g. "pdfbox-3.0.8/norm-v1"
  chapter_strategy TEXT NOT NULL,          -- outline | toc | fallback
  page_offset   INTEGER,                   -- TOC strategy only
  embed_model   TEXT,                      -- e.g. "nomic-embed-text"
  embed_dim     INTEGER,                   -- after truncation, e.g. 256
  embed_status  TEXT NOT NULL DEFAULT 'none' CHECK (embed_status IN ('none','partial','complete')),
  created_at    TEXT NOT NULL
);

CREATE TABLE page (
  book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
  pdf_page INTEGER NOT NULL,
  char_start INTEGER NOT NULL,
  PRIMARY KEY (book_id, pdf_page)
);

CREATE TABLE unit (
  id          INTEGER PRIMARY KEY,
  book_id     INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL CHECK (kind IN ('p','g','c','b')),
  hash        INTEGER NOT NULL,            -- 64-bit unit hash (§5.2), shown as 16 hex chars
  occurrence  INTEGER NOT NULL,            -- n in the key (§6.1)
  chapter     INTEGER,                     -- chapter number; NULL for the book unit
  parent_id   INTEGER REFERENCES unit(id) ON DELETE CASCADE,
  ordinal     INTEGER NOT NULL,            -- position within parent, 0-based; for chapters, the chapter number
  char_start  INTEGER NOT NULL,
  char_end    INTEGER NOT NULL,            -- inclusive
  page_start  INTEGER NOT NULL,
  page_end    INTEGER NOT NULL,
  title       TEXT,                        -- chapters only
  is_code     INTEGER NOT NULL DEFAULT 0,  -- paragraphs only: 1 for a code listing
  -- no text column (offsets into book.text) and no key column (keys are derived, §6.1)
);
CREATE INDEX unit_hash   ON unit(hash);          -- master record
CREATE INDEX unit_parent ON unit(parent_id, ordinal);
CREATE INDEX unit_book_kind ON unit(book_id, kind);

CREATE TABLE embedding (
  unit_id  INTEGER PRIMARY KEY REFERENCES unit(id) ON DELETE CASCADE,  -- paragraphs only
  model    TEXT NOT NULL,
  dim      INTEGER NOT NULL,             -- after truncation
  scale    REAL NOT NULL,                -- int8 scale: v[i] ≈ q[i] * scale
  vec_i8   BLOB NOT NULL,                -- dim bytes
  vec_bits BLOB NOT NULL                 -- dim / 8 bytes: sign bits, 64-bit words, little-endian
);

CREATE TABLE summary (
  id       INTEGER PRIMARY KEY,
  book_id  INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
  unit_id  INTEGER NOT NULL REFERENCES unit(id) ON DELETE CASCADE,   -- the chapter or book unit
  level    TEXT NOT NULL CHECK (level IN ('section','chapter','book')),
  ordinal  INTEGER NOT NULL,             -- section order within the chapter
  model    TEXT NOT NULL,
  text     TEXT NOT NULL,                -- generated, so it is stored
  embed_model TEXT, dim INTEGER, scale REAL, vec_i8 BLOB, vec_bits BLOB
);

CREATE VIRTUAL TABLE para_fts    USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
CREATE VIRTUAL TABLE phrase_fts  USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
CREATE VIRTUAL TABLE summary_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
-- rowid = unit.id (para_fts: kind 'g', phrase_fts: kind 'p') or summary.id.
-- Re-indexing a book deletes its FTS rows by rowid before deleting its units.
```

The structural data for a book (book, pages, units, FTS) is written in **one transaction**. Embeddings are written afterwards, one transaction per batch (§5.6). The DB uses WAL mode.

## 8. Search ("peek")

### 8.1 Embedding backend

```kotlin
interface Embedder {
  val model: String
  val dim: Int
  fun embedDocuments(texts: List<String>): List<FloatArray>   // applies the document prefix
  fun embedQuery(text: String): FloatArray                     // applies the query prefix
}
```
- `OllamaEmbedder`: sends `POST {ollamaUrl}/api/embed` with body `{"model": m, "input": [...]}` and reads `embeddings`. At startup it checks the model with `GET /api/tags` and gets `dim` from a probe embedding.
- Default model **`nomic-embed-text`** (768 dimensions). Document prefix `"search_document: "`, query prefix `"search_query: "`. Prefixes are per-model config so `mxbai-embed-large` or `bge-m3` can be swapped in.
- Truncation and quantization (§5.6) happen in a pure `Quantizer` after the embedder, so every `Embedder` gets them.
- **Model consistency:** a search must use the same model (and dimension) as the book's `embed_model` and `embed_dim`. If they don't match, vector search for that book fails with exit code 5 and a hint to run `rag embed`. It never silently mixes vectors from different models.
- Tests use a `FakeEmbedder`: a deterministic hashed bag-of-words vector, so they don't need Ollama (§11).

### 8.2 API

`search(query: String, k: Int = 5, bookId: Long? = null, mode: Mode = HYBRID): List<Hit>`

```kotlin
enum class Mode { HYBRID, LEXICAL, VECTOR }
data class Hit(
  val score: Double,
  val matchType: MatchType,          // EXACT_BOOK | EXACT_CHAPTER | EXACT_PARAGRAPH | EXACT_PHRASE | LEXICAL | VECTOR | HYBRID | SUMMARY
  val phrase: UnitRef,               // closest phrase
  val paragraph: UnitRef,            // full paragraph text
  val chapter: ChapterRef,           // number, title, page range, unitKey (no full text)
  val bookId: Long,
  val bookTitle: String?,
  val summary: String? = null,       // set for SUMMARY hits (§5.7)
)
data class UnitRef(val unitKey: String, val text: String, val pageStart: Int, val pageEnd: Int)
data class ChapterRef(val number: Int, val title: String, val pageStart: Int, val pageEnd: Int, val unitKey: String)
```

### 8.3 Steps
1. **Exact path** (this keeps the original spec's peek order). Hash the query (§5.2). Look it up in the master record, checking kinds in the order **book → chapter → paragraph → phrase**. Every match becomes a hit with score `1.0`:
   - a phrase match is that phrase, its paragraph and its chapter;
   - a paragraph match uses the paragraph's best phrase from step 4;
   - a chapter or book match uses its first paragraph and that paragraph's first phrase.
2. **Lexical path** (`HYBRID`, `LEXICAL`). Run FTS5 BM25 over `para_fts` and take the top `N = max(4k, 20)`. The query is the question's distinct words, each quoted (so FTS syntax is inert) and joined with `OR`. English stop words (`how`, `the`, `which` …) are dropped unless nothing else is left, because in an OR query they match nearly every paragraph and push the meaningful words down.
3. **Vector path** (`HYBRID`, `VECTOR`). Binary first pass, then int8 rescoring:
   - Call `embedQuery(query)`, then truncate and normalize it. The query stays float.
   - **First pass:** compute the Hamming distance between the query's sign bits and every paragraph's `vec_bits` in scope. Use `Long.bitCount` on 64-bit words; at 256 dimensions that's 4 words per paragraph. Keep the `R = max(10·k, 100)` nearest.
   - **Rescore:** read those `R` rows' `vec_i8` and `scale` from SQLite and compute `dot(query, q·scale)`. Keep the top `N`.
   - **In RAM:** only the binary vectors (plus their unit ids), cached per book. That's 32 bytes per paragraph at 256 dimensions, about 53 KB for this book. int8 vectors are read only for the `R` candidates.
   - Summary vectors (§5.7) go through the same path, and their hits become `SUMMARY` hits.
   - If Ollama is unreachable in `HYBRID` mode, log a warning and fall back to lexical only. In `VECTOR` mode, fail with exit code 5.
4. **Fusion** (`HYBRID`): reciprocal rank fusion, `score = Σ 1 / (60 + rank)` over the lexical and vector lists. In single-path modes, use that path's own ranking.
5. **Closest phrase:** for each paragraph hit, the phrase with the best BM25 in `phrase_fts` limited to that paragraph's children. If no phrase matches lexically, which is common for vector-only hits, embed that paragraph's phrases on the fly and pick the one most similar to the query. For v1, phrase vectors are not stored.
   - For a `SUMMARY` hit, use the best paragraph inside that chapter by step 2/3 ranking, or the chapter's first paragraph.
   - Unit text comes from the cached book text by offsets. Only the books in the results are loaded, and an LRU cache holds at most 2 books.
6. Merge, remove duplicates by paragraph id with exact matches first, and return up to `k` hits.
7. The `--json` CLI output is `Hit` serialized as is. This is the contract an LLM tool or MCP wrapper will use.

The full chapter text is **not** returned. A caller that needs it gets it with `rag dump --chapter`.

## 9. Code layout (suggested)

```
src/main/kotlin/rag/
  Main.kt                 CLI
  extract/PdfExtractor.kt     §5.1
  extract/Normalizer.kt       §5.2
  structure/ChapterDetector.kt  §5.3 (OutlineStrategy, TocStrategy, Fallback)
  structure/Segmenter.kt      §5.4–5.5
  embed/Embedder.kt           §8.1 interface + FakeEmbedder (test fixtures)
  embed/OllamaEmbedder.kt     §8.1
  embed/Quantizer.kt          §5.6 truncation, int8, binary, Hamming
  index/UnitKeys.kt           keys (§6)
  index/Indexer.kt            index + embed orchestration
  store/SqliteStore.kt        §7
  search/Searcher.kt          §8
  summarize/Summarizer.kt     §5.7 (optional)
```
Keep pure logic (normalization, segmentation, TOC parsing, key generation, RRF) free of PDFBox, SQL and HTTP, so it can be unit-tested on plain strings.

## 10. Phases (each one ends with passing tests)

1. **Extract + normalize + embedding backend:** header/footer removal and hyphen rejoining, plus the `Embedder` interface, `OllamaEmbedder` and `FakeEmbedder`. The embedding client is built here so it's ready for later phases and its contract is pinned down early. A smoke test calls the real Ollama.
2. Chapter detection: outline, then TOC with offset, then fallback.
3. Paragraph + phrase segmentation, with the size cap.
4. Unit keys + offsets-only SQLite store with contentless FTS; idempotent `index`, `--reindex`; paragraph embedding with Matryoshka truncation + int8/binary quantization (§5.6); resumable `rag embed`; CLI `index`, `embed`, `books`, `dump`.
5. Search: exact path, FTS5 BM25, binary-then-int8 vector search, RRF fusion; `--mode`, `--json` output.
6. *(Optional)* RAPTOR-style summaries (§5.7): `rag summarize`, summary vectors and FTS, `SUMMARY` hits.

## 11. Acceptance criteria

**Unit tests (plain strings, no Ollama):**
- `"Dr. Smith paid $3.14 for it. Was it worth it? Yes!"` → 3 phrases.
- `"exam-\nple"` → `"example"`. `"well-\nKnown"` is left unchanged.
- TOC lines `"Introduction ........ 1"`, `"Chapter 2 · The Road   17"`, `"Preface ... ix"` → 2 chapters (the `ix` entry is skipped).
- Key generation: the same phrase twice in chapter 1 and once in chapter 2 → `-0p-1c`, `-1p-1c`, `-0p-2c`. The master record lists all 3.
- For every unit: `bookText.substring(start, end + 1) == unit.text`. This is the invariant that ties offsets to the stored text.
- RRF: fusing the known lists `[a,b,c]` and `[c,a,d]` gives `a, c, b, d`.
- Quantization: truncating a vector gives unit length (± 1e-5). The int8 round-trip error per dimension is at most `scale / 2`. The binary vector's bits match the signs. Hamming distance of a vector to itself is 0, and to its negation is `dim`.
- BLOB round-trip: int8 and binary vectors → BLOB → back are identical.

**Generated-PDF tests** (built with PDFBox in the test, using `FakeEmbedder`):
- A 12-page PDF: cover, 2 TOC pages that start chapters at printed page 1, chapters at PDF pages 5, 8 and 11, plus a running header and page-number footer.
  - With an outline → chapter strategy `outline`, 3 chapters plus front matter.
  - Without an outline, with `--toc-pages 2-3` → strategy `toc`, `page_offset = 4`, correct chapter start pages.
  - No unit text contains the running header or the footer numbers.
- Indexing twice without `--reindex` → the second run inserts nothing.
- An embedder that fails after the first batch → the structure is committed and `embed_status = 'partial'`. Running `rag embed` afterwards → `complete`, and only the missing paragraphs are embedded (count the embedder's calls).
- A search with a different model than the book's `embed_model` → exit code 5.
- **Text stored once:** no table except `book` and `summary` has a text column. Every unit's text equals `book.text.substring(char_start, char_end + 1)`. FTS `MATCH` finds paragraphs and phrases although the FTS tables hold no content.
- `--reindex` leaves no orphaned FTS rows (count rows before and after).
- Identical paragraphs are embedded once (count the embedder's inputs).

**Integration tests** (JUnit tag `ollama`, skipped unless the env var `RAG_OLLAMA=1` is set and `nomic-embed-text` is pulled):
- `OllamaEmbedder` returns 768-dimensional vectors. `embedQuery("bicycle speed")` is closer to `embedDocuments(["The bike went really fast."])` than to `["The soup was cold."]`.

**Real-book tests** (JUnit tag `book`, run only when `RAG_BOOK_PDF` points to a local copy of *Jetpack Compose internals*, Leanpub 2022-11-20). The book is copyrighted, so it's never copied into the repo.
- Outline → chapters: Front matter, Prelude, and 7 numbered chapters, starting at PDF pages 2, 9, 11, 25, 58, 88, 147, 173, 185. TOC pages 5–8.
- TOC pages 5–8 without the outline → the same start pages, with `page_offset = 8` and no warnings.
- No running headers (`<title> <page>`) and no TOC dot leaders left in the text. Every chapter starts at its heading, including chapter 7's, which wraps over two lines.
- Every paragraph and phrase is trimmed, nested in its parent, and under the cap. More than 100 code paragraphs, with no numbered code lines left.
- With `ollama` too (`RealBookSearchTest`):
  - A sentence copied from the book → `EXACT_PHRASE` on its page (p. 13).
  - 5 questions each have their expected chapter in the top 3.
  - **Compact vs. full vectors:** the average top-10 overlap between compact search and exact float search at 768 dimensions, over 10 paraphrase questions. Measured (2026-10-14):

    | Setting | Overlap |
    |---|---|
    | float 256 | 0.77 |
    | int8 256, full scan | ≈ float 256 |
    | binary → int8 256 (default) | **0.74** |
    | float 512 | 0.85 |
    | binary → int8 512 | 0.83 |

    The loss comes almost entirely from Matryoshka truncation (client-side, after Ollama's normalization); int8 adds none and the binary pass about 0.03. The test floor is 0.70 at the default dimension. The original 0.9 target was not met by either dimension. Overlap with float search is a proxy: recall against known answers (the evaluation set below) decides the default dimension.
- The DB file for the book is at most **2.5 MB** (§13).

**Fixture book** (a public-domain PDF in `src/test/resources`, e.g. from Project Gutenberg) plus `eval.jsonl` with **20** lines of `{query, expectedPdfPage, expectedChapter}`. At least 8 of the queries must be **paraphrases** that share no content words with the target paragraph. Run with the `ollama` tag:
- `hybrid` recall@5 (the expected page is within some hit's page range) ≥ **0.85**.
- `hybrid` recall@5 ≥ `lexical` recall@5. On the paraphrase subset, `hybrid` must beat `lexical`.
- Searching for a phrase copied word-for-word from the book → the top hit is `EXACT_PHRASE` with the right page.

## 12. Worked example

PDF page 5 (printed page 1), after header and footer removal:
```
Chapter 1 · The Ride

The bike went really fast. Dr. Lee
watched from the hill.

It was over in a minute.
```
- Chapter 1 starts at the "Chapter 1 · The Ride" line, with title "Chapter 1 · The Ride".
- Paragraph `g0`: "Chapter 1 · The Ride" (a heading line is kept as its own paragraph).
- Paragraph `g1`: "The bike went really fast. Dr. Lee\nwatched from the hill." The stored text keeps the wrapped `\n`; it's flattened to a space only for sentence splitting and the embedding input.
  - Phrase `p0`: "The bike went really fast." → key `<h1>-0p-1c`
  - Phrase `p1`: "Dr. Lee watched from the hill." → key `<h2>-0p-1c`
  - Embedding input: `"search_document: Chapter 1 · The Ride\nThe bike went really fast. Dr. Lee watched from the hill."`
- Paragraph `g2`: "It was over in a minute." → phrase key `<h3>-0p-1c`, paragraph key `<h4>-0g-1c` (`g2`'s hash is `h4`).
- `search("how fast was the bike")` → a `HYBRID` hit, found by both BM25 and the vector path: phrase `p0`, paragraph `g1`, chapter `{1, "Chapter 1 · The Ride", 5–7}`.
- `search("the bicycle's speed")` → there's no word overlap, so BM25 misses. The vector path finds `g1`, so the hit is `VECTOR`. The closest phrase is `p0`, found by embedding the phrases on the fly (§8.3 step 5).

## 13. Memory budget

Measured on *Jetpack Compose internals*: 205 pages, 374 KB of text, 1,663 paragraphs, 4,875 phrases.

| Part | Where it lives | Size |
|---|---|---|
| Book text (only copy) | disk; in RAM only while serving results from that book | ~374 KB |
| Units (offsets, 64-bit hashes, no stored keys) + indexes | disk | ~560 KB |
| FTS5 inverted indexes (contentless) | disk | ~450 KB |
| Vectors, int8 + bits, 256 dims | disk; int8 read for ~100 candidates per query | ~608 KB |
| Vectors, binary, 256 dims | **RAM** during search | 1,663 × 32 B ≈ **53 KB** |
| **Whole DB file** | disk | **~2.35 MB** (measured) |

For comparison, float32 vectors at 768 dimensions are about 5.1 MB, and a copy of the text per unit would double the text storage. Storing unit keys as text, with a unique index, cost about 470 KB more than deriving them (2.94 MB → 2.35 MB). The embedding model (Ollama, ~270 MB) is the largest memory cost by far, and it's needed only while indexing and to embed each query.

## 14. Open questions (defaults apply unless changed)
- Locale for sentence splitting: default `en`, set with `--locale`.
- Heading lines are kept as separate one-line paragraphs, not merged into the next paragraph. Default: keep. They are still embedded, and the chapter-title prefix duplicates them, which is harmless.
- Embedding model: default `nomic-embed-text`. Use `bge-m3` or `mxbai-embed-large` for non-English or higher-accuracy needs.
- Store phrase vectors (instead of computing them at query time) if the cost of finding the closest phrase shows up in profiling. Deferred.
- Nested sections (1.1) as their own level: deferred to v2.
- Smaller embedding models for low-RAM machines: `all-minilm` (384 dims, ~45 MB), or static embeddings (model2vec), which need no transformer at query time. Deferred until the evaluation in §11 exists to compare them.
- Matryoshka dimension: default 256. Drop to 128 if the §11 evaluation shows no loss.
