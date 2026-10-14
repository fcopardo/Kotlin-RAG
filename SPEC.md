# Spec: Book PDF → Hierarchical RAG Index (Kotlin/JVM)

## 1. Goal

A Kotlin/JVM command-line tool, a **digestor**, that reads a book (**PDF**, or **EPUB** from Phase 8, §16) and splits its text into a hierarchy: **phrases → paragraphs → chapters → book**. It stores that hierarchy in a local database, with an **embedding** for each paragraph, and gives an LLM a search function. Search combines exact, keyword (BM25) and meaning-based (vector) matching. Each result returns the matching phrase, its full paragraph and its chapter's data, with page numbers for citation.

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
- DRM-protected EPUBs (Adobe ADEPT, Apple FairPlay, …) and fixed-layout EPUBs. Detect them and fail with a clear error (§16.2). DRM is never removed or worked around.
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
- `jsoup`, for EPUB's XHTML (Phase 8). It tolerates imperfect markup and runs on Android too. Zip reading uses `java.util.zip` from the JDK.
- `org.xerial:sqlite-jdbc` **3.43 or later**. It bundles FTS5, and 3.43 is the first version with `contentless_delete`.
- **Ollama** running locally (default `http://localhost:11434`). It serves the embedding model (§8.1) and, optionally, the summary model (§5.7).
- HTTP client: `java.net.http.HttpClient`, from the JDK. JSON: `kotlinx-serialization-json`.
- CLI parsing: `clikt`.
- Tests: JUnit 5. Test PDFs are generated with PDFBox inside the tests, plus one public-domain fixture (§11). Tests that need Ollama go behind a tag (§11).
- No other runtime dependencies without a reason.

## 4. CLI

```
rag index  --file <pdf|epub> --db <file> [--toc-pages <a-b[,c-d]>] [--location-size 2000] [--locale en] [--reindex] [--no-embed]
           [--ollama-url http://localhost:11434] [--embed-model mxbai-embed-large] [--embed-dim 512]
rag embed  --db <file> --book <bookId> [--ollama-url …] [--embed-model …] [--embed-dim …]  # (re)build embeddings only
rag summarize --db <file> --book <bookId> [--summary-model llama3.2:3b] [--ollama-url …] [--redo]  # Phase 6, §5.7; prints progress
rag eval   --db <file> --set <questions.jsonl> [--book <bookId>] [--k 5] [--mode all|hybrid|lexical|vector] [--details] [--lexical-weight 0.5] [--rrf-k 5]
rag books  --db <file>                                                                  # list indexed books
rag search --db <file> --query "<text>" [--k 5] [--book <bookId>] [--mode hybrid|lexical|vector] [--no-summaries] [--json] [--lexical-weight 0.5] [--rrf-k 5]
rag dump   --db <file> --book <bookId> [--chapters | --chapter <n> | --summaries]
```

- `--file` takes a PDF or an EPUB. The format comes from the content (`%PDF` header, or a zip whose `mimetype` entry is `application/epub+zip`), not the extension. `--pdf` stays as an alias of `--file`.
- `--toc-pages`: PDF pages (1-based, inclusive) that contain the TOC. This is optional; see §5.3 for how chapters are found without it. It's a usage error (exit 2) for an EPUB.
- `--location-size`: characters per synthetic page ("location") for an EPUB without a page list (§16.5).
- `index` is **idempotent**. A book is identified by the SHA-256 of the PDF file. Re-indexing the same file does nothing unless `--reindex` is passed, which deletes and rebuilds that book.
- `index` fails with exit code `5` if Ollama can't be reached or the model isn't pulled. In that case nothing for that book is committed. The error message tells the user to run `ollama pull <model>`.
- `--no-embed` stores the structure only; run `rag embed` later.
- `embed` rebuilds the embeddings for an already-indexed book without re-parsing it. Use it to switch models or dimensions, or to resume after an interrupted run. Changing the model or `--embed-dim` drops the book's existing vectors first.
- `--mode` defaults to `hybrid`.
- `eval` runs an evaluation set (§11) in each mode and prints recall@k, MRR, chapter recall and recall per question kind; `--details` shows each question's rank.
- One DB can hold many books.
- Exit codes: `0` ok, `2` bad arguments, `3` PDF unreadable/encrypted/no text, `4` DB error, `5` embedding backend unavailable or model mismatch.

## 5. Pipeline

### 5.1 Extract
- Extract text page by page with `PDFTextStripper`, with `setSortByPosition(true)`. Keep where each page starts in the book text.
- **Title:** the PDF's metadata title, or the file name without its extension when there is none (CtCI has none).
- **Header/footer removal:** for each page, the first 2 and last 2 non-empty lines are candidates.
  - **Key:** candidates are compared by their words of 2+ letters, lowercased. Page numbers and OCR noise in separators (`… I 6th Edition 195` vs. `… \ 6th Edition 196`) then don't split one footer into many keys. A line with no such words keys on its text with digits replaced by `#`, which catches bare page numbers. A line with neither letters nor digits (e.g. `}`) is never boilerplate.
  - **Book-wide:** a key on more than 30% of pages, **and on at least 3 pages**, is boilerplate and is dropped. The 3-page floor protects short documents, where a single page is already more than 30%.
  - **Headings are protected:** on a chapter's first page, a line equal to the chapter's title is kept, because a heading and its running headers share a key.
- **Running chapter headers** repeat only within their chapter, so they usually stay under the 30% rule. They're removed after chapter detection (§5.3). On every page of a chapter except its first:
  - an edge line whose key is on at least **40%** of the chapter's other pages (and at least 3) is dropped, e.g. CtCI's `Solutions to Chapter 1 | Arrays and Strings`, which doesn't match the chapter's own title;
  - an edge line equal to the chapter's title, once page numbers at either end are stripped **from both**, is dropped even in short chapters. Examples: `1. Composable functions 4`, and `5 Writing your first class 26`, where the leading `5` is the chapter number, not a page number.
- **Footnotes** are cleaned on the raw page text, before NFKC turns superscripts into plain digits (`Birch¹` → `Birch1`):
  - A footnote is a line starting with superscript digits.
  - A footnote that is only a URL is removed, together with its wrapped continuation lines (no spaces, URL punctuation, not just a number).
  - A footnote with prose is kept, without its marker.
  - A superscript in the body is removed only when its number is a footnote on the same page, so real superscripts (`n²`) stay.
  - *Jetpack Compose internals* has 35 footnotes, all URL-only.
- **TOC pages** are left out of the book text. They stay as empty pages, so PDF page numbers and offsets still line up. Otherwise searches would hit the list of titles instead of the chapters.

### 5.2 Normalize
Applied in this order, before splitting and before hashing:
1. Unicode NFKC. This expands ligatures, e.g. `ﬁ` → `fi`.
2. Normalize line endings to `\n`. Remove zero-width characters (U+200B, U+2060 word joiner, U+FEFF); ZWJ and ZWNJ are kept because some scripts need them. Then remove soft hyphens (U+00AD), joining the word across the line end when one is at a line end. CtCI has 411 of them; Standard Ebooks puts word joiners in titles like `1–500`.
3. Rejoin words hyphenated at line ends: `exam-\nple` → `example`. Do this only when the letters on **both** sides are lowercase, so `well-\nKnown` and `COVID-\n19` are kept. Real compounds split at a line end (`well-\nknown`) get joined; that's an accepted trade-off.
4. Collapse runs of spaces and tabs into one space. Trim trailing spaces on each line. Collapse 3 or more newlines into 2, so a paragraph gap is always exactly one blank line.

A word hyphenated across a page break isn't rejoined, because pages are normalized one at a time to keep page offsets exact.

The result is the **book text**. It is stored in the DB as-is, and every offset refers to it.

**Hash normalization** is an extra step used only to compute hashes: lowercase, collapse all whitespace (including `\n`) to one space, and trim. The hash is the first 16 hex characters of SHA-256 over the UTF-8 bytes.

### 5.3 Chapters
Strategies are tried in order. The first one that produces at least 2 chapters wins.

1. **PDF outline.** Use `PDDocumentOutline` items that point to a page (two levels). The title is the outline title, and the start is that PDF page.
   - **Contents:** an item titled "Contents" or "Table of Contents", at either level, isn't a chapter; it marks where the TOC starts. The TOC then continues while the following pages still look like a TOC, up to the next outline item. A page looks like a TOC when at least half of its lines are entries, or dot-leader runs whose page number OCR lost. If `--toc-pages` is given, those pages are used instead.
   - **Parts** (shared with EPUB, §16.4; `OutlineChapters`): top-level items are chapters and their children are sections, which are ignored. The exception is a top-level item with children for which **both** of these hold:
     1. some other top-level items have no children, *or* its title looks like a part (`Part …`, `Book …`, or a Roman numeral like `IX.`);
     2. its children don't look like sections: they aren't numbered under it (`5` → `5.1`), and they average at least 3 pages each.

     Its children then become chapters titled `Part › Child`. The part keeps a chapter of its own only if it starts before its first child. Real cases:
     - CtCI: parts ("IX. Interview Questions" → 17 chapters) mixed with plain chapters ("VI. Big O").
     - Kotlin for Android Developers: chapters with numbered sections ("5 …" → "5.1 …") next to plain front-matter items. No parts.
     - Jetpack Compose internals: every chapter has unnumbered 1–2-page sections. No parts.
2. **TOC pages** (only if `--toc-pages` is given). Parse each line with
   `^(?<title>.+?)[\s.·…_-]{2,}(?<page>\d+|[ivxlcdm]+)\s*$` (case-insensitive).
   - Skip a line that is just "Contents" or "Table of Contents".
   - **Top level only:** read TOC lines with their x position. Entries indented more than 3pt past the leftmost entry are sections, not chapters.
   - Entries whose page number is a Roman numeral are front matter and are not chapters.
   - **Page offset:** look for the first Arabic-numbered entry's title as a line on the PDF pages *after* the last TOC page, comparing hash-normalized text. Then `offset = foundPdfPage - printedPage`, and `pdfPage = printedPage + offset` for every entry.
   - **Check:** for each entry, confirm the title appears as a line on `pdfPage` or within ±2 pages. If it is found somewhere else in that window, use that page for this entry and the ones after it. If it isn't found, keep the computed page and log a warning.
3. **Fallback.** The whole book is one chapter titled "Book".

**Title matching** (used for the offset, the check, and chapter boundaries): compare hash-normalized lines. A title may also match two consecutive lines, because long headings wrap.

**Chapter boundaries:** a chapter starts at the **offset of its title line** (its own title, without a part prefix) on its start page, or at the top of the page if the line isn't found. When two starts land on the same offset (a part page with no text, then its first chapter), the later, more specific one wins. It ends just before the next chapter starts. Text before the first chapter becomes chapter **0**, titled "Front matter". Chapters are numbered from 1 in reading order, which can differ from the book's own numbering. In *Jetpack Compose internals*, "Prelude" is chapter 1 and "1. Composable functions" is chapter 2. The title keeps the book's own number. A chapter's `page_start` is the first page with text, so an image-only cover isn't counted.

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
- **Embedding input:** `documentPrefix + chapterTitle + "\n" + paragraphText`. For prose, `paragraphText` has its newlines flattened to spaces; code keeps its lines. Prefixing the chapter title adds context that a short paragraph lacks. The prefixes come from the `EmbeddingModel` config (§8.1): the default `mxbai-embed-large` uses no document prefix and an instruction prefix on queries; `nomic-embed-text` uses `search_document: ` / `search_query: `.
- Send paragraphs in batches of **32** per request (`POST /api/embed` with `input: [...]`).
- **Matryoshka truncation:** keep the first `--embed-dim` dimensions (default **512**, chosen over 256 on 2026-10-14 after the measurements in §11) and L2-normalize again. Both supported models are trained for truncation: `mxbai-embed-large` (1,024 dims; also trained for binary quantization) and `nomic-embed-text` v1.5 (768/512/256/128/64). Ollama returns vectors that are already normalized, so truncation happens client-side after normalization, a close approximation of the models' recipes; the evaluation in §11 checks the loss. `--embed-dim` must be a multiple of 64 and at most the model's size.
- **Quantize and store two forms. Floats are never stored.**
  - **int8:** `q[i] = round(v[i] / scale)` with `scale = max|v| / 127`. That's 1 byte per dimension plus a 4-byte scale. It's used to rescore candidates.
  - **binary:** bit `i` is set when `v[i] > 0`, packed into 64-bit words. That's 1 bit per dimension. It's used for the first-pass scan.
- **Resumable:** embeddings are written in their own transactions, one per batch, after the structural data is committed. A paragraph without a row in `embedding` gets embedded on the next `rag embed` run. A book counts as fully indexed only when every paragraph has an embedding (`book.embed_status = 'complete'`).
- Retry policy: up to 3 tries with exponential backoff (1s, 2s, 4s) per batch. After that, stop with exit code 5. The structure stays committed and `embed_status` stays `partial`.

### 5.7 Summaries (Phase 6, optional, RAPTOR-style; built)

**Why.** Paragraph vectors answer "where does the book say X?". They can't answer "what is chapter 5 about?" or "what does this book cover?", because no single paragraph says that. Summaries can. They're made once at index time, so a query costs the same as before: one more small binary scan.

**Scope.** Summaries are an optional, separate step (`rag summarize`) on a book that is already indexed and embedded. Search works the same without them.

#### 5.7.1 Model and settings
- **Backend:** Ollama `POST /api/generate`, `stream: false`, behind a `Summarizer` interface (§8.1). Nothing leaves the machine.
- **Default model:** `llama3.2:3b`, about 2 GB of RAM, and only while `summarize` runs. Alternatives are `qwen2.5:3b`, or `gemma3:1b` for very small machines. The model must be pulled; otherwise exit code 5 with the `ollama pull` hint, as for embeddings.
- **Options:** `temperature: 0`, `seed: 42`, `num_ctx: 4096`, `num_predict` = 2 × the word cap. Results are as repeatable as Ollama allows.
- **Prompt version:** the prompts below are version `v1`, stored with each summary. Changing a prompt means bumping the version, and `summarize` then treats older summaries as stale.

#### 5.7.2 The summary tree
Built per chapter from the existing paragraphs. No re-parsing.
1. **Sections:** consecutive paragraphs of a chapter, packed greedily up to **1,500 tokens** (words × 1.3, as in §5.4). That leaves room in a 4,096-token context for the prompt and the answer. A code paragraph contributes at most its first **15 lines**, followed by `…`, so listings can't fill the context.
2. **Section summary:** at most **120 words**, made from the section's text.
3. **Chapter summary:** at most **200 words**, made from the chapter's section summaries in order.
   - If those summaries are more than 2,500 tokens together, they're first reduced in groups of at most 12 into **group** summaries (at most 150 words each). This repeats until they fit.
   - In *Jetpack Compose internals*, "4. Compose UI" (about 26k tokens, ~18 sections) needs one group level.
   - A chapter with a single section gets its chapter summary straight from the paragraphs; there's no separate section summary.
4. **Book summary:** at most **250 words**, made from the chapter summaries in order. Front matter is left out unless it is the only chapter.

Every summary records the span of book text it covers (`char_start`–`char_end`) and its pages. That's what makes summary hits citable.

#### 5.7.3 Prompts (`v1`)
System prompt, for every level:
> You summarize parts of a technical book for a search index. Use only the text you are given. Do not add facts, opinions or examples. Keep names of APIs, classes, functions and terms exactly as written. Write plain prose, no lists, no headings, no preamble.

User prompt per level (`{…}` are filled in):
- **section:** `Book: {bookTitle}. Chapter: {chapterTitle}.\nSummarize this passage in at most {maxWords} words, covering every main point:\n\n{text}`
- **group / chapter:** `Book: {bookTitle}. Chapter: {chapterTitle}.\nThese are summaries of consecutive parts of the chapter, in order. Combine them into one summary of at most {maxWords} words that says what the chapter covers:\n\n{numbered summaries}`
- **book:** `Book: {bookTitle}.\nThese are summaries of the book's chapters, in order. Write one summary of at most {maxWords} words that says what the book covers and how it is organized:\n\n{chapter title: summary, …}`

**Cleaning the output:**
- Strip a leading preamble line ("Here is a summary…", "Summary:").
- Trim whitespace.
- If the text is over the word cap, cut it at the last sentence end before the cap.
- An empty result counts as a failure and is retried. The retry policy is the same as embeddings: 3 tries, backoff 1s, 2s, 4s.

#### 5.7.4 Storage and embedding
- Summaries are generated text, so they're stored **with** their text (`summary` table, §7). This is the one exception to "text stored once".
- Each summary is embedded like a paragraph, with **the book's own embedding model and dimension**, so summary and paragraph vectors are comparable. A book without embeddings gets keyword-searchable summaries only. The input is `documentPrefix + chapterTitle + "\n" + summaryText` (the book title for the book summary). It's truncated and quantized like paragraphs (512 dimensions, int8 + bits) and indexed in `summary_fts`.
- `book.summary_model`, `book.summary_prompt` and `book.summary_status` (`none` / `partial` / `complete`) track progress.

#### 5.7.5 Resuming, idempotency, invalidation
- Each summary is committed as it's made, so an interrupted run resumes: existing summaries with the same model and prompt version are kept, and only missing ones are generated, bottom-up.
- A chapter summary is made only when all of its sections (and groups) exist, and the book summary only when all chapter summaries exist.
- `--redo`, or a different `--summary-model`, deletes the book's summaries first.
- `rag index --reindex` deletes them with the book (cascade), together with their FTS rows.

#### 5.7.6 Cost (measured 2026-10-14, *Jetpack Compose internals*, `llama3.2:3b`, Apple M4 Pro)
- **Calls and time:** 62 summaries (53 section, 8 chapter, 1 book) in **220 s**. The estimate was 75 calls and 5–10 minutes.
  - **No group level was needed:** "4. Compose UI" has 14 sections whose summaries total ~2,200 tokens, under the 2,500 limit. The estimate of one group level was wrong.
- **Memory:** `llama3.2:3b` used **2.5 GB** in Ollama (estimated 2.6–2.8 GB), plus `mxbai-embed-large` (687 MB) to embed the summaries. Peak ~3.2 GB, only during `rag summarize`.
- **Storage:** +156 KB (2.96 → 3.12 MB): 47 KB of summary text in `summary` (116 KB with vectors) plus 56 KB of `summary_fts`. In RAM during search: 62 × 64 B ≈ **4 KB** of binary vectors.
- **Word caps held:** sections at most 120, chapters at most 198 of 200, the book 170 of 250.

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
  embed_model   TEXT,                      -- e.g. "mxbai-embed-large"
  embed_dim     INTEGER,                   -- after truncation, e.g. 512
  embed_status  TEXT NOT NULL DEFAULT 'none' CHECK (embed_status IN ('none','partial','complete')),
  summary_model TEXT,                      -- Phase 6, e.g. "llama3.2:3b"
  summary_prompt TEXT,                     -- Phase 6, prompt version, e.g. "v1"
  summary_status TEXT NOT NULL DEFAULT 'none' CHECK (summary_status IN ('none','partial','complete')),
  created_at    TEXT NOT NULL
);

CREATE TABLE page (                      -- one row per page: a PDF page, a printed page or a location (§16.5)
  book_id INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
  pdf_page INTEGER NOT NULL,               -- page ordinal, 1-based (the PDF page for PDFs); renamed `page_no` in schema v3
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

CREATE TABLE summary (                   -- Phase 6 (§5.7)
  id         INTEGER PRIMARY KEY,
  book_id    INTEGER NOT NULL REFERENCES book(id) ON DELETE CASCADE,
  unit_id    INTEGER NOT NULL REFERENCES unit(id) ON DELETE CASCADE,  -- the chapter, or the book unit for level 'book'
  level      TEXT NOT NULL CHECK (level IN ('section','group','chapter','book')),
  depth      INTEGER NOT NULL DEFAULT 0,  -- group reduction round, 0 for other levels
  ordinal    INTEGER NOT NULL,            -- order within (unit, level, depth)
  char_start INTEGER NOT NULL,            -- span of book text it covers
  char_end   INTEGER NOT NULL,            -- inclusive
  page_start INTEGER NOT NULL,
  page_end   INTEGER NOT NULL,
  model      TEXT NOT NULL,
  prompt     TEXT NOT NULL,               -- prompt version, e.g. "v1"
  text       TEXT NOT NULL,               -- generated, so it is stored
  embed_model TEXT, dim INTEGER, scale REAL, vec_i8 BLOB, vec_bits BLOB,
  UNIQUE (unit_id, level, depth, ordinal)
);

CREATE VIRTUAL TABLE para_fts    USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
CREATE VIRTUAL TABLE phrase_fts  USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
CREATE VIRTUAL TABLE summary_fts USING fts5(text, content='', contentless_delete=1, tokenize='porter unicode61');
-- rowid = unit.id (para_fts: kind 'g', phrase_fts: kind 'p') or summary.id.
-- Re-indexing a book deletes its FTS rows by rowid before deleting its units.
```

**Schema version:** `PRAGMA user_version`. Phases 1–5 create version 1. Versions are numbered in the order they ship. Phase 8 (built before Phase 6) is **version 2**: `book.format` (`pdf` | `epub`), `book.page_kind` (`pdf` | `print` | `location`), and `page.label` (the page's display name, e.g. `xii` or `loc 37`), with `page.pdf_page` renamed `page_no`. Migrating fills `format = 'pdf'`, `page_kind = 'pdf'`, and `label` from the page number. Old databases are detected by their columns, because the earliest ones report version 0. Phase 6 is **version 3**: it adds the `book.summary_*` columns and recreates `summary`, which is always empty before Phase 6 (detected by its missing `char_start` column).

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
- Default model **`mxbai-embed-large`** (1,024 dimensions, ~669 MB download, ~687 MB loaded). No document prefix; query prefix `"Represent this sentence for searching relevant passages: "`. Chosen on 2026-10-14 over `nomic-embed-text` (768 dims, ~370 MB loaded, document/query prefixes `"search_document: "` / `"search_query: "`) because of the evaluation in §11. nomic stays supported with `--embed-model nomic-embed-text`. Unknown models get no prefixes.
- Truncation and quantization (§5.6) happen in a pure `Quantizer` after the embedder, so every `Embedder` gets them.
- **Model consistency:** a search must use the same model (and dimension) as the book's `embed_model` and `embed_dim`. If they don't match, vector search for that book fails with exit code 5 and a hint to run `rag embed`. It never silently mixes vectors from different models.
- Tests use a `FakeEmbedder`: a deterministic hashed bag-of-words vector, so they don't need Ollama (§11).

**Summary backend (Phase 6, §5.7):**
```kotlin
enum class SummaryLevel { SECTION, GROUP, CHAPTER, BOOK }
data class SummaryTask(val level: SummaryLevel, val bookTitle: String, val chapterTitle: String?,
                       val inputs: List<String>, val maxWords: Int)
interface Summarizer {
  val model: String
  fun checkAvailable() {}
  fun summarize(task: SummaryTask): String   // raw model output; cleaned by SummaryText.clean (§5.7.3)
}
```
- `OllamaSummarizer` builds the `v1` prompts from a `SummaryTask` and calls `/api/generate` with the options in §5.7.1. `FakeSummarizer` returns the first sentence of each input.

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
  val summaryLevel: String? = null,  // "section" | "group" | "chapter" | "book" for SUMMARY hits
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
   - **First pass:** compute the Hamming distance between the query's sign bits and every paragraph's `vec_bits` in scope. Use `Long.bitCount` on 64-bit words; at 512 dimensions that's 8 words per paragraph. Keep the `R = max(10·k, 200)` nearest. With 100 candidates the evaluation lost a hit that float search found; with 200, compact search matched float search.
   - **Rescore:** read those `R` rows' `vec_i8` and `scale` from SQLite and compute `dot(query, q·scale)`. Keep the top `N`.
   - **In RAM:** only the binary vectors (plus their unit ids), cached per book. That's 64 bytes per paragraph at 512 dimensions, about 106 KB for this book. int8 vectors are read only for the `R` candidates.
   - Summary vectors (§5.7) go through the same binary → int8 path, with their own small per-book cache.
   - If Ollama is unreachable in `HYBRID` mode, log a warning and fall back to lexical only. In `VECTOR` mode, fail with exit code 5.
4. **Fusion** (`HYBRID`): weighted reciprocal rank fusion, `score = Σ weight / (k + rank)`, with **k = 5**, weight **0.5** for the lexical list and **1** for the vector list. In single-path modes, use that path's own ranking. `--lexical-weight` and `--rrf-k` override both for calibration.
   - **Why 0.5:** on questions that share no words with their answer, keyword hits are noise. At equal weight they pushed good vector hits out of the top 5 (mxbai hybrid recall@5 0.65, below vector-only 0.80).
   - **Why k = 5:** with the common k = 60, scores are so flat that a paragraph in both lists, even 20th in each, outscores the vector list's first hit (1/80 + 0.5/80 > 1/61). That's why every weight from 0.2 to 0.5 gave identical results. Measured on three sets (2026-10-14, recall@5 / MRR):

     | Setting | Compose (PDF) | Art of War, Standard Ebooks | Art of War, Gutenberg |
     |---|---|---|---|
     | k 60, weight 0.5 | 0.80 / 0.65 | 0.70 / 0.59 | 0.60 / 0.47 |
     | **k 5, weight 0.5** | **0.80 / 0.69** | **0.75 / 0.62** | **0.65 / 0.52** |
     | vector only | 0.80 / 0.70 | 0.80 / 0.65 | 0.65 / 0.56 |

     k = 5 is better or equal on every set. **Hybrid still doesn't beat vector-only** on these sets with mxbai-embed-large: the keyword list adds nothing measurable there, and on paraphrases it costs one question on the Standard Ebooks set. It's kept because it finds rare exact identifiers that embeddings blur, and because it keeps search working when Ollama is down (§8.3 step 3). A confidence gate (use keyword hits only when they're strong or agree with the vector list) is the next candidate (§14).
   - **Summaries (Phase 6):** when the books in scope have summaries and `--no-summaries` isn't set, a third list joins the fusion. It's the summaries ranked by their own RRF of `summary_fts` BM25 and summary vectors.
   - A summary hit is `SUMMARY`, with `Hit.summary` set to the summary text and `Hit.chapter` set to its chapter (or, for the book summary, the chapter of its chosen paragraph).
   - At most **2** summary hits are returned per query, so passages aren't crowded out. They never replace exact hits.
5. **Closest phrase:** for each paragraph hit, the phrase with the best BM25 in `phrase_fts` limited to that paragraph's children. If no phrase matches lexically, which is common for vector-only hits, embed that paragraph's phrases on the fly and pick the one most similar to the query. For v1, phrase vectors are not stored.
   - For a `SUMMARY` hit, use the best paragraph inside the summary's span: the best BM25 paragraph whose offsets fall in `char_start..char_end`, else the span's first paragraph. Then take that paragraph's closest phrase as above.
   - Unit text comes from the cached book text by offsets. Only the books in the results are loaded, and an LRU cache holds at most 2 books.
6. Merge, remove duplicates by paragraph id with exact matches first, and return up to `k` hits.
7. The `--json` CLI output is `Hit` serialized as is. This is the contract an LLM tool or MCP wrapper will use.

The full chapter text is **not** returned. A caller that needs it gets it with `rag dump --chapter`.

## 9. Code layout (suggested)

```
src/main/kotlin/rag/
  Main.kt                 CLI
  extract/PdfExtractor.kt     §5.1
  epub/                       §16 (Phase 8): container/OPF/nav reading, DRM check, XHTML → book text
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
  summarize/Summarizer.kt     §5.7 interface, OllamaSummarizer, prompts, output cleaning
  summarize/SummaryTree.kt    §5.7.2 section packing and reduction (pure)
  summarize/SummaryIndexer.kt §5.7.4–5.7.5 generation, storage, embedding, resume
```
Keep pure logic (normalization, segmentation, TOC parsing, key generation, RRF) free of PDFBox, SQL and HTTP, so it can be unit-tested on plain strings.

## 10. Phases (each one ends with passing tests)

1. **Extract + normalize + embedding backend:** header/footer removal and hyphen rejoining, plus the `Embedder` interface, `OllamaEmbedder` and `FakeEmbedder`. The embedding client is built here so it's ready for later phases and its contract is pinned down early. A smoke test calls the real Ollama.
2. Chapter detection: outline, then TOC with offset, then fallback.
3. Paragraph + phrase segmentation, with the size cap.
4. Unit keys + offsets-only SQLite store with contentless FTS; idempotent `index`, `--reindex`; paragraph embedding with Matryoshka truncation + int8/binary quantization (§5.6); resumable `rag embed`; CLI `index`, `embed`, `books`, `dump`.
5. Search: exact path, FTS5 BM25, binary-then-int8 vector search, RRF fusion; `--mode`, `--json` output.
6. *(Optional)* RAPTOR-style summaries (§5.7). **Built** (2026-10-14):
   1. `Summarizer` interface, `OllamaSummarizer`, `FakeSummarizer`; prompts `v1`; output cleaning.
   2. Section packing and the summary tree (sections → groups → chapter → book), resumable, with schema version 3.
   3. Summary embedding + `summary_fts`; `rag summarize`, `rag dump --summaries`.
   4. `SUMMARY` hits in search, `--no-summaries`.
   5. Real-book run: measure time and size, and record them in §13.
7. *(Planned)* Portability: desktop and Android from one code base (§15).
8. EPUB input (§16). **Done** (2026-10-14), built before Phases 6 and 7. Its first step, the `BookSource` interface, is Phase 7's first step too.

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

**Integration tests** (JUnit tag `ollama`, skipped unless the env var `RAG_OLLAMA=1` is set and both `mxbai-embed-large` and `nomic-embed-text` are pulled):
- The models return 1,024 and 768 dimensions, and the default `OllamaEmbedder` is `mxbai-embed-large`.
- For both models, `embedQuery("bicycle speed")` is closer to `embedDocuments(["The bike went really fast."])` than to `["The soup was cold."]`.

**Real-book tests** (JUnit tag `book`, run only when `RAG_BOOK_PDF` points to a local copy of *Jetpack Compose internals*, Leanpub 2022-11-20). The book is copyrighted, so it's never copied into the repo.
- Outline → chapters: Front matter, Prelude, and 7 numbered chapters, starting at PDF pages 2, 9, 11, 25, 58, 88, 147, 173, 185. TOC pages 5–8.
- TOC pages 5–8 without the outline → the same start pages, with `page_offset = 8` and no warnings.
- No running headers (`<title> <page>`) and no TOC dot leaders left in the text. Every chapter starts at its heading, including chapter 7's, which wraps over two lines.
- Every paragraph and phrase is trimmed, nested in its parent, and under the cap. More than 100 code paragraphs, with no numbered code lines left.
- With `ollama` too (`RealBookSearchTest`):
  - A sentence copied from the book → `EXACT_PHRASE` on its page (p. 13).
  - 5 questions each have their expected chapter in the top 3.
  - **Compact vs. full vectors:** the average top-10 overlap between compact search and exact float search at the model's full size, over 10 paraphrase questions. With the default `mxbai-embed-large`, 512 dims vs. 1,024: **0.89** (test floor 0.85). Measured with `nomic-embed-text` against its 768 dims (2026-10-14):

    | Setting | Overlap |
    |---|---|
    | float 256 | 0.77 |
    | int8 256, full scan | ≈ float 256 |
    | binary → int8 256 | 0.74 |
    | float 512 | 0.85 |
    | binary → int8 512 (**default**) | 0.83 (0.84 after footnote cleanup) |

    The loss comes almost entirely from Matryoshka truncation (client-side, after Ollama's normalization); int8 adds none and the binary pass about 0.03. **Decision:** 512 dimensions, which costs about 0.5 MB more disk and 53 KB more RAM per book than 256. The original 0.9 target is nearly met with mxbai (0.89). Overlap with float search is a proxy: the evaluation set below measures recall against known answers.
- The DB file for the book is at most **3 MB** (§13).
- No footnote lines or footnote markers are left (35 URL footnotes; e.g. no `Birch1`).

**Phase 6, summaries** (`FakeSummarizer`, which returns the first sentence of each input, so tests are deterministic and need no model):
- **Packing:** sections are at most 1,500 tokens and never cross chapters, a code paragraph contributes at most 15 lines, and a one-section chapter has no separate section summary.
- **Reduction:** 30 section summaries of 150 words each → one group level of 3 groups → one chapter summary.
- **Output cleaning:** a preamble ("Here is a summary:") is stripped, a 300-word output is cut at a sentence end at or under the cap, and an empty output is retried and then fails with exit code 5.
- **Spans:** every summary's span lies inside its chapter, a chapter summary's span covers the whole chapter, and the book summary covers chapters 1…n.
- **Resume:** a summarizer that fails after 5 calls leaves `summary_status = 'partial'`; running `summarize` again makes only the missing summaries (count calls) and ends `complete`.
- **Invalidation:** `--redo` or a different model replaces all summaries, `--reindex` removes them, and no `summary_fts` rows are orphaned.
- **Search:** with summaries, a query naming a chapter's topic returns a `SUMMARY` hit with the summary text, its chapter and a paragraph inside the span. At most 2 `SUMMARY` hits are returned. `--no-summaries` returns none. Exact hits still come first.

**Phase 6 on the real book** (`RealBookSummaryTest`, tags `book` + `ollama`, plus `RAG_SUMMARY_MODEL`, e.g. `llama3.2:3b`; takes ~4 minutes). Results 2026-10-14:
- 8 chapter summaries (front matter has none) and 1 book summary, each within its word cap. ✅
- **Grounding:** at least 95% of the CamelCase identifiers in all summaries (e.g. `SlotTable`, `LaunchedEffect`) appear in the book text, a cheap check for invented API names. ✅ **111 of 112.**
- "what is the state snapshot system chapter about" → a `SUMMARY` hit for "5. State snapshot system" in the top 3. ✅ (#3, after two paragraphs of that chapter)
- "what does this book cover" → the book summary. ⚠️ It's at **#5**, not the top 3 the spec asked for. Front-matter boilerplate ("This book is for sale at…", dedications) ranks above it, and the Prelude's chapter summary ("This book is about…") is at #3. The test checks the top 5; front-matter weighting is an open question (§14).
- Quality, read by hand: chapter and section summaries are accurate and specific. One soft spot: chapter 3's summary says the runtime "is implemented by `AndroidComposeView`", a slight conflation of the book's integration-layer description.
- Hits carry `summaryLevel` (`section`, `group`, `chapter`, `book`), because a book summary's `chapter` is only the chapter of its chosen paragraph.

**More local books** (`MoreBooksTest`, tag `book`; each runs only when its environment variable points to the user's copy, and the books never enter the repo):
- `RAG_KOTLIN_PDF`, *Kotlin for Android Developers* (Leanpub): 35 chapters from the outline, with numbered sections staying inside them. No running footers (`5 Writing your first class 26`; 175 before the fixes).
- `RAG_CTCI_PDF`, *Cracking the Coding Interview*, 6th ed. (708 pages, OCR text layer, no metadata title):
  - 51 chapters from parts (`IX. Interview Questions › 1. Arrays and Strings`…); empty part pages don't stay chapters; the title comes from the file name.
  - At most 10 OCR-mangled footers left (385 before) and at most 5 running headers (470 before).
  - No soft hyphens, and no TOC pages in the text.

**Evaluation set** (`src/test/resources/eval/compose-internals.jsonl`, run by `rag eval` and by `RealBookSearchTest` with tags `book` + `ollama`). This replaces the planned public-domain fixture book: the real book gives realistic questions, and the set holds only questions and page numbers, no book text.
- 20 questions across all 7 chapters, each with its expected PDF page(s) and chapter. One JSON object per line: `{id, query, expectedPages, expectedChapter, kind}`. Phase 8 adds an optional, format-independent `expectedAnchor` (§16.7).
- `kind` is `keyword` (11) or `paraphrase` (9). A paraphrase shares **no content words** with its answer pages after simple stemming. This is checked mechanically against the pages' text, not judged by eye ("word processors" for the book's "text editors", "screen narrator" for "TalkBack").
- A question counts as found when one of the top k hits has a paragraph whose pages include an expected page.

Results on 2026-10-14 (512 dims, 200 rescoring candidates, lexical weight 0.5):

| Model | Mode | Recall@5 | MRR | Keyword | Paraphrase | Recall@10 |
|---|---|---|---|---|---|---|
| — | lexical | 0.55 | 0.52 | 1.00 | 0.00 | 0.55 |
| nomic-embed-text | vector | 0.70 | 0.67 | 0.91 | 0.44 | 0.75 |
| nomic-embed-text | hybrid | 0.75 | 0.57 | 1.00 | 0.44 | 0.75 |
| **mxbai-embed-large** | vector | 0.80 | 0.70 | 1.00 | 0.56 | 0.80 |
| **mxbai-embed-large** | **hybrid** | **0.80** | 0.65 | 1.00 | 0.56 | — |

- Full float vectors gave the same recall as compact ones for both models: compression costs nothing measurable here. mxbai at 1,024 dims scored the same as at 512.
- The 4 paraphrases no setting finds (q09, q10, q14, q16) are the hardest vocabulary gaps. Better embeddings or query expansion are the next levers.
- **Test floors** (default model): hybrid recall@5 ≥ **0.80**, keyword recall 1.00, hybrid ≥ lexical, and hybrid beats lexical on paraphrases. The original target of 0.85 isn't met; 0.80 is one question short of it.
- With 20 questions, one question is 0.05. Differences of one question are noise-level; the set should grow before finer tuning.

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
  - Embedding input (default model, no document prefix): `"Chapter 1 · The Ride\nThe bike went really fast. Dr. Lee watched from the hill."`
- Paragraph `g2`: "It was over in a minute." → phrase key `<h3>-0p-1c`, paragraph key `<h4>-0g-1c` (`g2`'s hash is `h4`).
- `search("how fast was the bike")` → a `HYBRID` hit, found by both BM25 and the vector path: phrase `p0`, paragraph `g1`, chapter `{1, "Chapter 1 · The Ride", 5–7}`.
- `search("the bicycle's speed")` → there's no word overlap, so BM25 misses. The vector path finds `g1`, so the hit is `VECTOR`. The closest phrase is `p0`, found by embedding the phrases on the fly (§8.3 step 5).

## 13. Memory budget

Measured on *Jetpack Compose internals*: 205 pages, 374 KB of text, 1,649 paragraphs, 4,861 phrases (after footnote cleanup).

| Part | Where it lives | Size |
|---|---|---|
| Book text (only copy) | disk; in RAM only while serving results from that book | ~374 KB |
| Units (offsets, 64-bit hashes, no stored keys) + indexes | disk | ~560 KB |
| FTS5 inverted indexes (contentless) | disk | ~450 KB |
| Vectors, int8 + bits, 512 dims | disk; int8 read for ~100 candidates per query | ~1.1 MB |
| Vectors, binary, 512 dims | **RAM** during search | 1,649 × 64 B ≈ **106 KB** |
| **Whole DB file** | disk | **~2.8 MB** (measured) |
| Summaries, Phase 6: text + vectors + FTS | disk | ~156 KB (measured) |
| Summaries, Phase 6: binary vectors | **RAM** during search | ~4 KB (measured) |
| Summary model (`llama3.2:3b`) | RAM, only during `rag summarize` | 2.5 GB (measured) |

For comparison, float32 vectors at 768 dimensions are about 5.1 MB, and a copy of the text per unit would double the text storage. Storing unit keys as text, with a unique index, cost about 470 KB more than deriving them (2.94 MB → 2.35 MB at 256 dims). At 256 dimensions the vectors take ~0.6 MB and 53 KB of RAM. The embedding model is the largest memory cost by far: `mxbai-embed-large` takes ~687 MB in Ollama (`nomic-embed-text` ~370 MB), and it's needed only while indexing and to embed each query. Embedding the whole book takes 32 s with mxbai and 16 s with nomic.

## 14. Open questions (defaults apply unless changed)
- Locale for sentence splitting: default `en`, set with `--locale`.
- Heading lines are kept as separate one-line paragraphs, not merged into the next paragraph. Default: keep. They are still embedded, and the chapter-title prefix duplicates them, which is harmless.
- Embedding model: default `mxbai-embed-large` (decided 2026-10-14). Candidates still to evaluate: `embeddinggemma` (built for on-device use, relevant to §15), `bge-m3` and `snowflake-arctic-embed2` (multilingual).
- Grow the evaluation set (more books, more questions) before tuning anything finer than one question in 20.
- Query expansion (e.g. a small local LLM rewriting the question) for the remaining paraphrase misses; it costs one generation per query.
- **Front matter in search:** copyright pages, "for sale at", dedications and social-media notes match generic questions ("what does this book cover") and outrank real answers, including the book summary (§11). Candidates: down-weight chapter 0, or drop paragraphs that are only links or boilerplate.
- **Confidence-gated fusion:** with mxbai-embed-large, hybrid never beat vector-only on three sets (§8.3). Candidates: use keyword hits only when they also appear in the vector top N, or when the query has rare terms (high IDF). Evaluate against all sets before changing the default.
- **OCR text layers** (e.g. CtCI) carry recognition errors: `ot` for `of`, `II` for `//`, `�`. Possible later steps: report an OCR-quality score at index time, and treat lines that are only dot leaders plus `pg N` (CtCI's printed cross-references) as noise.
- **Code without line numbers** (Kotlin for Android Developers, CtCI) stays prose paragraphs. Detecting it would need layout cues such as a monospaced font, through PDFBox's text positions. EPUB marks code explicitly (§16.3).
- Store phrase vectors (instead of computing them at query time) if the cost of finding the closest phrase shows up in profiling. Deferred.
- Nested sections (1.1) as their own level: deferred to v2.
- Smaller embedding models for low-RAM machines: `all-minilm` (384 dims, ~45 MB), or static embeddings (model2vec), which need no transformer at query time. Deferred until the evaluation in §11 exists to compare them.
- Matryoshka dimension: default 512 (decided). Revisit 256 if the §11 evaluation shows recall equal to 512's.

## 15. Portability: desktop and Android (Phase 7, planned)

**Goal.** One code base serves the desktop CLI and an Android library, with the same database format, so an index can be built on either side and searched on either side.

**Non-goals for Phase 7:** iOS; an Android UI (the deliverable is a library plus a small sample); generating summaries on the device (summaries built on desktop are stored in the DB and used as they are).

### 15.1 What is already portable
Normalization, boilerplate/footnote/code-listing cleanup, TOC parsing and location, chapter boundaries, segmentation, unit keys, quantization, fusion and evaluation are pure Kotlin using only JDK APIs that Android also has (`java.text`, `java.security.MessageDigest`, `java.nio`). They move into `core` unchanged. On Android, `android.icu.text.BreakIterator` replaces `java.text.BreakIterator` behind a small `SentenceSplitter` seam.

### 15.2 What becomes pluggable

| Concern | Interface (in `core`) | Desktop | Android |
|---|---|---|---|
| Book access: PDF page text with paragraph breaks, outline items with target pages, TOC lines with x position (and EPUB, §16) | `BookSource` | PDFBox (`org.apache.pdfbox`) | PdfBox-Android (`com.tom_roush.pdfbox`), the same API in another package. PDFBox itself depends on `java.awt`, which Android lacks. |
| Storage | `Store` (today's `SqliteStore` API) | `androidx.sqlite` with the **bundled** driver | the same code and driver |
| Embeddings | `Embedder` (exists) | `OllamaEmbedder` (exists), `OnnxEmbedder` | `OnnxEmbedder`; `OllamaEmbedder` can also reach an Ollama server on the local network |
| Summaries | `Summarizer` (§8.1) | `OllamaSummarizer` | not in Phase 7 |

- **Storage, one implementation for both:** `androidx.sqlite`'s `BundledSQLiteDriver` ships its own SQLite on the JVM and on Android. That gives FTS5 and version ≥ 3.43 (`contentless_delete`) everywhere, including Android versions whose built-in SQLite lacks them. It replaces `sqlite-jdbc`; the SQL and schema stay the same.
- **On-device embeddings with ONNX Runtime** (`onnxruntime` on the JVM, `onnxruntime-android`):
  - The model ships as an ONNX file, int8-quantized for phones.
  - Tokenization is BERT WordPiece, implemented in pure Kotlin from the model's `vocab.txt`, with no native tokenizer library.
  - Pooling is per model config: CLS for `mxbai-embed-large`, mean for `nomic-embed-text`. Prefixes, truncation and quantization stay as in §5.6.
  - The ONNX session loads the model memory-mapped, and inference runs in batches off the main thread.

### 15.3 Module layout

```
core/                 pure Kotlin: pipeline, keys, quantizer, fusion, search, eval, interfaces
store-sqlite/         Store on androidx.sqlite (bundled driver), JVM + Android
pdf-pdfbox/           BookSource for PDF on PDFBox (desktop)
pdf-pdfbox-android/   BookSource for PDF on PdfBox-Android
epub/                 BookSource for EPUB on jsoup + java.util.zip (JVM and Android, §16)
embed-ollama/         OllamaEmbedder, OllamaSummarizer
embed-onnx/           OnnxEmbedder + WordPiece tokenizer (JVM and Android artifacts)
cli/                  the `rag` command (desktop)
android/              Android library (AAR): a `RagEngine` facade, plus a sample app
```

`RagEngine` (Android) exposes `suspend` functions (`index`, `embed`, `search`) and runs blocking work on `Dispatchers.IO`. It reports progress per page and per batch, and checks for cancellation between batches. That fits `WorkManager` for indexing in the background.

### 15.4 Model identity across runtimes
Vectors are comparable only if they come from the same model weights and the same pooling. Ollama (GGUF) and ONNX builds of one model can differ slightly in numbers.
- The stored `embed_model` names the model and its variant, e.g. `mxbai-embed-large` or `mxbai-embed-large@onnx-int8`.
- Two variants count as the **same model**, so an index built with one can be searched with the other, only if a parity test shows average cosine ≥ **0.99** between their vectors for the same texts, and the evaluation set (§11) gives the same recall within one question.
- Otherwise they're different models, and the existing model check (exit code 5 on the desktop, an exception on Android) prevents mixing them.

### 15.5 Ways to run on a phone (from lightest to heaviest)
1. **Keyword only:** copy a desktop-built `.db`; no model on the device. In §11 this finds all keyword questions (lexical recall@5 0.55 overall, 1.00 on keyword questions).
2. **Query embedding only:** copy a desktop-built `.db` and ship the same model (as an ONNX file that passed parity), used only to embed each query. Full hybrid search.
3. **Full on-device indexing:** PDF → index → embeddings on the phone. This is the heaviest option: minutes per book, in the background.

### 15.6 Choosing the model for phones
- Candidates: `mxbai-embed-large` (335M parameters, the desktop default), `nomic-embed-text` (137M), `embeddinggemma` (~300M, designed for on-device use, Matryoshka-trained).
- Each is measured on the evaluation set (§11) and on a reference mid-range phone. Targets: embedding a query in **< 300 ms**, indexing a 200-page book in **< 10 min** in the background, and peak app memory under **400 MB** during indexing.
- The phone default may differ from the desktop default. An index used on both must use one model (§15.4).

### 15.7 Steps (each ends with passing tests; the desktop CLI keeps working throughout)
1. Introduce `BookSource` with the PDFBox adapter (shared with Phase 8, step 1). No behavior change: all current tests pass.
2. Replace `sqlite-jdbc` with `androidx.sqlite` + the bundled driver on desktop. Same schema; all tests pass, including real-book size and recall.
3. Split the Gradle modules as in §15.3.
4. Add `OnnxEmbedder` + WordPiece tokenizer on the JVM, plus the parity test against Ollama for the default model.
5. Add the Android library: PdfBox-Android adapter, `RagEngine`, instrumented tests on an emulator.
6. Measure the reference-phone numbers from §15.6, record them, and choose the phone model.

### 15.8 Acceptance criteria (Phase 7)
- After steps 1–3: every Phase 1–5 test passes unchanged on desktop (tests may move to the new modules).
- **Bundled SQLite:** `sqlite_version()` ≥ 3.43, and a contentless FTS5 table with `contentless_delete=1` can be created, matched and deleted from, on the JVM and on an Android emulator.
- **WordPiece tokenizer:** the same token ids as the model's reference tokenizer on 100 sample sentences (fixture file), including code and punctuation.
- **Parity:** `OnnxEmbedder` vs `OllamaEmbedder` for the default model, average cosine ≥ 0.99 on 50 paragraphs from the generated sample book. Evaluation-set recall within one question.
- **Same file on both platforms:** a DB built by the desktop CLI and copied into an Android instrumented test opens without migration and returns the same lexical results for the evaluation set. With the parity-checked model, it also returns the same hybrid results within one question.
- **Indexing on Android:** the generated 12-page sample book (§11) indexes on an emulator with the same chapters, paragraph count and unit keys as on desktop.
- **Cancellation:** cancelling indexing between batches leaves `embed_status = 'partial'`, and a later `embed` resumes it.


## 16. EPUB input (Phase 8, done)

**Goal.** Index EPUB books with the same pipeline as PDFs. Only the first stage changes, turning the file into book text plus chapter starts and page starts. Segmentation (§5.4–5.5), keys, storage, embeddings, summaries and search are unchanged, and a search can mix PDF and EPUB books in one database.

**Why it's worth it.** An EPUB is a zip of XHTML documents, so most of what §5.1–5.3 reconstructs from PDFs by heuristics is explicit markup:

| PDF heuristic | EPUB source |
|---|---|
| header/footer and page-number removal | none needed: there are no printed pages |
| paragraphs from blank lines and page breaks | block elements (`<p>`, headings, list items…) |
| hyphen rejoining at line ends | none needed: text reflows (soft hyphens U+00AD are removed) |
| chapters from bookmarks or TOC pages + offset | the navigation document, with direct links |
| numbered code listings | `<pre>` blocks |
| footnote markers glued to words | `noteref` links and `footnote` asides |

**Scope.** Reflowable EPUB 2 and EPUB 3. Fixed-layout EPUBs (`rendition:layout` = `pre-paginated`) and DRM-protected EPUBs are rejected (§16.2).

### 16.1 Reading the container
1. `META-INF/container.xml` → the package document (`.opf`) path.
2. **Package:**
   - Metadata: `dc:title` → `book.title`; `dc:language` → the sentence-splitting locale when `--locale` isn't given.
   - Manifest and **spine** (reading order). Spine items with `linear="no"` (pop-up notes, covers) are left out of the main text, but footnote lookups (§16.3) can still follow links into them.
3. **Navigation:**
   - EPUB 3: the manifest item with `properties="nav"`. Its `<nav epub:type="toc">` gives chapters, and its `<nav epub:type="page-list">` gives printed pages.
   - EPUB 2: `toc.ncx`. Its `navMap` gives chapters, and its `pageList` gives printed pages.
   - When both exist, EPUB 3's nav wins.
4. The book is identified by the SHA-256 of the file, as for PDFs.

### 16.2 Rejecting what can't be read (exit code 3)
- **DRM** if any of these hold:
  - `META-INF/rights.xml` exists (Adobe ADEPT);
  - `META-INF/sinf.xml` exists (Apple FairPlay);
  - `META-INF/encryption.xml` lists an `EncryptedData` whose target is a content document (XHTML, NCX or OPF) rather than a font.

  Font obfuscation (algorithms `http://www.idpf.org/2008/embedding` and `http://ns.adobe.com/pdf/enc#RC`) protects only fonts and is accepted. The message says the EPUB is DRM-protected and can't be indexed; it gives no workaround.
- **Fixed layout:** `rendition:layout` = `pre-paginated` in the package metadata.
- **Malformed:** not a zip, no `container.xml`, no package document, or no spine. An XHTML document that doesn't parse is skipped with a warning, unless that leaves no text.

### 16.3 From XHTML to book text
Spine documents are parsed with `jsoup` in their reading order, and their text is appended to the book text. Each document starts a new paragraph.
- **Blocks:** each block element becomes one paragraph, ending in a blank line (`\n\n`). Block elements are `p`, `h1`–`h6`, `li`, `dt`, `dd`, `blockquote` (its own text only, its child blocks separately), `figcaption`, `caption`, and `div`/`section` that contain text directly. Inline text is joined in order, and `<br>` becomes `\n`.
- **Headings** are their own one-line paragraphs, as with PDFs (§14).
- **Parsing:** content documents are XHTML and are parsed in jsoup's (lenient) **XML** mode. HTML mode would treat an empty `<span epub:type="pagebreak"/>` as an opening tag that swallows the rest of the document.
- **Code:** `<pre>` (and block-level `<code>`) keeps its line breaks and becomes a code paragraph (`is_code`, §5.4). Code gets a lighter normalization that keeps indentation: NFKC, line endings, zero-width and soft hyphens, trailing spaces. Blank lines are dropped so a listing stays one paragraph. If its lines are numbered n, n+1, …, the numbers are removed with the same rule as §5.4.
- **Tables:** one paragraph per table. Each row is one line, with its cells joined by ` | `. The size cap (§5.4) splits large tables between rows.
- **Left out:** `script`, `style`, `nav` inside content documents, `img` (only its `figcaption` is kept), and elements with `epub:type="pagebreak"`, whose position is recorded for §16.5.
- **Footnotes** (the same intent as §5.1):
  - A reference is `<a epub:type="noteref">`, or a superscript link (`<sup><a href="#…">`) to a note. The marker text is removed from the sentence.
  - A note is `<aside epub:type="footnote|endnote|rearnote">`, or an element with that `epub:type`, or the target of a reference.
  - A note that contains only a URL is dropped. A prose note is kept as a paragraph where it appears in the document (usually after its section), without its own marker.
  - A separate endnotes document follows the normal spine and navigation rules: it's a chapter if the TOC lists it, and it's left out if it's `linear="no"`.
- **Normalization** (§5.2) applies as for PDFs. Soft hyphens (U+00AD) are removed first. Hyphen rejoining finds nothing, because there are no line ends.
- Header/footer removal (§5.1) and the page-break paragraph rule (§5.4) don't apply.

### 16.4 Chapters
- **From the TOC:** each top-level entry links to a document and, optionally, an element id (`chapter3.xhtml#sec2`). Its offset is where that document's or element's text starts in the book text.
- **Parts:** the same rule as for PDF outlines (§5.3, `OutlineChapters`). Many EPUB TOCs nest sections under every chapter, and those must stay sections; real parts become `Part I › 3. The Road`. EPUB adds a stronger signal than size: children that are **separate documents** are chapters, and children that are anchors inside the parent's document (`ch1.xhtml#sec`) are sections. Only when that's mixed does the "at least 3 pages per child" test apply, with page spans from text length at the location size (§16.5). The size test alone misjudged Standard Ebooks' *The Art of War*, whose 13 chapters average 2.2 locations. Deeper levels are ignored (§14).
- **Boundaries** are as in §5.3: a chapter runs from its offset to the next one's, text before the first chapter is front matter (chapter 0), and chapters are numbered in reading order.
- **Fallback:** a TOC with fewer than 2 usable entries is ignored. Each spine document that starts with a heading then becomes a chapter titled by that heading; documents without one join the previous chapter. With no headings at all, the whole book is one chapter titled "Book", as in §5.3.
- `book.chapter_strategy` is `nav` (EPUB 3 nav), `ncx` (EPUB 2), `headings` or `fallback`.

### 16.5 Pages and citations
- **Printed pages:** when the EPUB has a page list, each entry's target (usually a `pagebreak` element) gives the offset where that printed page starts. `page.label` is the printed label (`"xii"`, `"57"`), and `book.page_kind = 'print'`. Citations then match the paper edition.
- **Locations:** without a page list, the book text is split into synthetic pages of `--location-size` characters (default 2,000). `page.label` is `"loc N"`, and `book.page_kind = 'location'`.
- Units and chapters keep storing `page_start` and `page_end` as **page ordinals** into `page`. `UnitRef` and `ChapterRef` (§8.2) gain `pageStartLabel` and `pageEndLabel` for display and citation, and the CLI prints labels. For PDFs the label is the PDF page number, so nothing changes for them.

### 16.6 Interface
```kotlin
interface BookSource {                  // shared with Phase 7 (§15.2)
  val format: String                    // "pdf" | "epub"
  fun read(options: ReadOptions): StructuredBook  // book text, chapter starts, page starts/labels, code ranges, title, locale
}
```
- `PdfBookSource` wraps today's `PdfExtractor` + `ChapterDetector`, with no behavior change. `EpubBookSource` implements §16.1–16.5.
- `BookReader` picks the source by sniffing the file, and everything after it is shared.

### 16.7 Evaluation across formats
Page numbers don't carry between a PDF and an EPUB edition, or between different EPUB page kinds. So an evaluation question may name its answer in a format-independent way:
- `expectedAnchor`: a short, distinctive phrase (at most 8 words) from the answer passage. A hit counts if its paragraph contains the anchor, comparing hash-normalized text. An anchor that spans two paragraphs never matches, so anchors are chosen within one paragraph. Each question must have `expectedPages`, `expectedAnchor`, or both; `expectedChapter` stays optional.
- Committed sets for **copyrighted** books keep using page numbers only, so no quotes from them enter the repository. Anchors are for public-domain fixtures and for local, uncommitted sets.
- `rag eval` reports which kind of target matched.

### 16.8 Fixtures
- **Generated EPUBs** (`TestEpubs`) for every rule above: EPUB 3 nav and EPUB 2 NCX on the same content (same unit keys), parts and numbered sections, page list mid-paragraph, footnotes/endnotes, `<pre>` with and without numbers, tables, soft hyphens, `linear="no"`, nav document in the spine, DRM (`rights.xml`, `sinf.xml`, encrypted content), font-only obfuscation, fixed layout, headings fallback, format sniffing.
- **Committed public-domain books** (`src/test/resources/epub/`, sources and licenses in its README): the same Lionel Giles translation of *The Art of War* in two editions.
  - `sun-tzu_the-art-of-war_lionel-giles.epub` (Standard Ebooks, EPUB 3, 593 KB): 27 chapters from the nav, the 13 chapters under the part "The Art of War", 794 endnotes kept as prose, no page list (locations).
  - `pg132.epub` (Project Gutenberg #132, EPUB 2, 213 KB): 23 chapters from the NCX, translator's notes woven into each chapter.
  - **Known limitation:** Gutenberg's note references are plain text (`… Sun Tzŭ: [1]`), not links, so their 107 `[n]` markers stay in the text. They're harmless for search; removing them would need a text heuristic.
- **Evaluation set** `src/test/resources/eval/art-of-war-se.jsonl`: 20 questions across the 13 chapters (11 keyword, 9 paraphrases checked to share no content words with the anchor's paragraph), each with an `expectedAnchor` (public-domain text, so quoting is fine) and its chapter. Run with the settings calibrated on *Compose* unchanged:

  | Edition | Lexical | Vector | Hybrid (k 60) | Hybrid (k 5, default) |
  |---|---|---|---|---|
  | Standard Ebooks | 0.55 | 0.80 | 0.70 | **0.75** |
  | Gutenberg (same questions) | 0.50 | 0.65 | 0.60 | **0.65** |

  - Keyword questions: 11/11 on Standard Ebooks, 10/11 on Gutenberg.
  - Hybrid dropped two questions below vector-only. That exceeded the one-question rule, so the fusion was re-tuned against all three sets (§8.3, k = 5).
  - `EpubEvalTest` (tag `ollama`) enforces hybrid ≥ 0.75, vector ≥ 0.80, and all keyword questions found.
- Index size for both editions together, with embeddings: 4.96 MB (about 780 KB of text, 2,661 paragraphs).

### 16.9 Steps (each ends with passing tests)
1. `BookSource` + `PdfBookSource`, used by `BookReader`. No behavior change (shared with Phase 7, step 1).
2. Container, package, navigation and the DRM / fixed-layout checks (§16.1–16.2).
3. XHTML → book text: blocks, code, tables, footnotes, soft hyphens (§16.3).
4. Chapters (§16.4), pages and labels with schema version 3 and its migration (§16.5).
5. CLI `--file` with format sniffing; `expectedAnchor` in `rag eval`; fixtures and their evaluation set (§16.7–16.8).
6. Record the evaluation results and the index sizes for the EPUB fixtures in §11 and §13.

### 16.10 Acceptance criteria (Phase 8)
- **No PDF regressions:** every Phase 1–5 test passes unchanged after step 1, and the real-book recall and size are unchanged.
- **Generated EPUBs:**
  - EPUB 3 nav and EPUB 2 NCX books give the same chapters, paragraphs and unit keys for the same content.
  - Parts give prefixed chapter titles, front matter is chapter 0, and a book without a TOC falls back to headings.
  - Footnote markers are gone from the text, URL-only notes are dropped, and prose notes are kept as paragraphs.
  - `<pre>` blocks are code paragraphs without line numbers, tables are one paragraph with one line per row, and no soft hyphen is left.
  - With a page list, every unit has print page labels, and a unit's labels match the `pagebreak`s around it. Without one, the location count is ⌈text length / location size⌉.
  - DRM (`rights.xml`, `sinf.xml`, or an encrypted content document) and fixed layout → exit code 3 with a clear message; font-only obfuscation indexes normally.
  - The offset invariant (§11) holds for every unit.
- **Mixed database:** a PDF book and an EPUB book in one DB can each be searched alone (`--book`) and together; results show the right page labels for each.
- **Migration:** a version-1 or version-2 database opens, migrates to version 3, and still answers the evaluation set with the same recall.
- **Fixture books:** both committed EPUBs index without warnings, and the Standard Ebooks evaluation set is recorded with the unchanged settings. Its hybrid recall@5 is a floor test; the spec records the measured value rather than a target.
