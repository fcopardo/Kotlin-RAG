# Documents: papers and contracts

Besides PDF and EPUB books, `rag index` reads plain documents: Markdown and plain text, standalone HTML pages,
and Word `.docx` files. Each is stored as one document, next to books in the same database, and searched with
the same command (SPEC.md §18).

## Index documents

```bash
rag index --file notes/design.md --db docs.db         # one document
rag index --dir notes/ --db docs.db                   # every document in a folder, one per file
rag search --db docs.db --query "renewal terms" --kind contract
```

- The format is detected from the content, so names don't need an extension (a `.kt` or `.py` file is read as
  code, see [CODE.md](CODE.md)).
- `--dir` goes through the folder at any depth in path order, skipping hidden files and folders and symbolic
  links. Each file is reported as it is handled, then a summary:

  ```text
  Skipped: logo.png (Unsupported file (not a PDF, EPUB, Word, HTML or text file): …)
  Indexed paper 1: design.md
  Already indexed: copy-of-design.md (paper 1)
  Failed: draft.txt (Empty document: …)
  Folder: 1 indexed, 1 already indexed, 1 skipped, 1 failed
  ```

  A file is identified by the SHA-256 of its bytes, so running it again indexes only new or changed files, and
  two identical files are one document. One unreadable file doesn't stop the others; the command then exits
  with code 3. If the embedding backend can't be reached, nothing is stored and it exits with code 5.
- `--location-size` (characters per location, default 2000) applies to documents as to EPUBs without a page
  list: documents have no pages, so hits are cited by location (`loc 3`).

## Papers and contracts

A document becomes one of two kinds, and each kind names its levels:

| Kind | Document | Section | Passage | Fragment |
| --- | --- | --- | --- | --- |
| paper | paper | section | paragraph | sentence |
| contract | contract | clause | paragraph | sentence |

- **Contract** when it has clause numbering that nests (`4.`, `4.2`, `(a)`, `ARTICLE IV`, `§ 7`, `WHEREAS`)
  **and** reads like an agreement. Numbering alone isn't enough: a paper numbers its sections the same way, and
  stays a paper. Sections are titled with their path: `4. Term › 4.2 Renewal`.
- **Paper** divided by its own headings when it has at least two: Markdown `#` and underlined (Setext)
  headings, HTML `<h1>`–`<h6>`, Word `Heading N` and `Title` styles. Nested headings join into a path title,
  `Introduction › Background`.
- **Paper** divided by topic when it has no headings: TextTiling compares the vocabulary of neighbouring
  paragraphs and cuts where it changes most. It is purely lexical, so a document gets the same structure on
  every device whatever embedding model is used. Each section is titled by the opening words of its first
  paragraph.

What each format contributes:

| Format | Read as |
| --- | --- |
| Markdown, plain text | headings and fenced code blocks; inline marks such as `**bold**` stay as written |
| HTML | the page body, through the same walker as EPUB: blocks, code and tables as in a book |
| Word `.docx` | paragraphs, heading styles, and tables (one line per row), read straight from the zip |

## See what would be stored

`rag structure --file <document>` prints the sections and passages a document would get, without storing
anything; see [CODE.md](CODE.md#see-what-would-be-stored-rag-structure).

## Known limits

- PDFs are always books: a paper or contract saved as PDF is read with the book reader.
- Topic sections (no headings) are only as good as the vocabulary shifts in the text; short documents often
  become one section.
- On Android, the library (`RagEngine`) reads every format here, and the sample app takes documents from
  its picker and books folder. Its viewer opens PDFs and EPUBs; for other documents it shows the passage.
