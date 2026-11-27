# Code projects

Kotlin-RAG indexes source code the way it indexes books: one project becomes one document, stored in the
same SQLite file and searched with the same command, so a question can be answered from a book and from the
code that implements it. Nothing runs or compiles the code; the structure comes from a scanner (SPEC.md §18.4).

## Index a project

```bash
rag index --project path/to/project --db code.db
rag index --file src/main/kotlin/rag/Main.kt --db code.db   # a single file is a project of one
rag search --db code.db --query "where are search results fused" --kind code
```

- `--project` takes a folder; `--file` still takes any single file, and a file with a source extension is read
  as code. The two are exclusive. `--toc-pages` and `--location-size` are for PDFs and EPUBs and are refused
  with `--project`.
- The output adds a files line: `Files: 52 read, 1 skipped (build/: build folder)`.
- **Re-index detection:** a project is identified by a fingerprint over its indexed files (each relative path
  with the SHA-256 of its bytes). Indexing an unchanged project again does nothing; files that are skipped
  don't count. A changed project is stored as a new document; the old one stays until you delete it.
- Keeping code apart from books is just another database file (`--db code.db`).

## The four levels

| Level | For code | Built from |
| --- | --- | --- |
| Document (`b`) | the project | the folder name |
| Section (`c`) | a file, a class, a nested class | the file path and the class names, as a path title |
| Passage (`g`) | a function, with its doc comment and annotations | what search returns and embeds |
| Fragment (`p`) | a line | what a hit points at |

Each file is a page, labelled with its relative path, so a hit is cited by file:
`app/src/main/kotlin/shop/cart/Cart.kt`. Section titles drop the source-set folders that every file repeats:
`core/src/main/kotlin/rag/search/Searcher.kt` becomes `core › rag/search › Searcher.kt`, and a class in it
`core › rag/search › Searcher.kt › Searcher`. Other source sets keep their name: `core (test) › rag › SearcherTest.kt`.

A function is one passage even with blank lines inside it; one over the 400-token cap is split between lines.
Code outside functions (imports, properties, the code after a nested class) is split at blank lines, inside
the section it belongs to.

Languages: Kotlin, Java, C#, JavaScript and TypeScript, Go, Rust, Swift, C and C++, and Python (by
indentation). Any other text file in a project is indexed as plain text, one section per file.

## See what would be stored: `rag structure`

```bash
rag structure --project path/to/project [--depth 0|1|2] [--json]
rag structure --file path/to/File.kt
```

It reads through the same reader as `rag index` and writes nothing. For each section it lists its passages
with their lines, size and how many pieces a long function became; then what was skipped, the totals and the
scanner's warnings. `--depth 1` shows sections only, `--depth 0` just the totals, and `--json` gives the same
report for diffing two versions of the heuristics. It works for documents too (pages instead of lines).

An excerpt, from the test fixture `cli/src/test/resources/code/mini-project`:

```text
  app › shop/cart › Cart.kt  [app/src/main/kotlin/shop/cart/Cart.kt]
    L1  17 chars  3 tok  package shop.cart
    L3  21 chars  3 tok  import shop.pay.Money
  app › shop/cart › Cart.kt › Cart  [app/src/main/kotlin/shop/cart/Cart.kt]
    L5–L7  144 chars  27 tok  /** A shopping cart: lines of products with quantities. */
  app › shop/cart › Cart.kt › Cart › Line  [app/src/main/kotlin/shop/cart/Cart.kt]
    L9–L10  111 chars  25 tok  /** One product in the cart. */
    L11  44 chars  9 tok  fun subtotal(): Money = unitPrice * quantity
    L12  1 chars  1 tok  }
  app › shop/cart › Cart.kt › Cart  [app/src/main/kotlin/shop/cart/Cart.kt]
    L14–L24  476 chars  82 tok  /** Adds [quantity] of [sku]; adding a product again rais...
    L26  82 chars  17 tok  fun total(): Money = lines.fold(Money.ZERO) { sum, line -...
    L27  1 chars  1 tok  }
  app › shop/cart › Discounts.kt  [app/src/main/kotlin/shop/cart/Discounts.kt]
    L1  17 chars  3 tok  package shop.cart
    L3  21 chars  3 tok  import shop.pay.Money
  app › shop/cart › Discounts.kt › Discounts  [app/src/main/kotlin/shop/cart/Discounts.kt]
    L5–L6  61 chars  13 tok  /** Discount rules applied at checkout. */
    L7–L9  157 chars  33 tok  /** Ten percent off orders of 100 or more. */
    L10  1 chars  1 tok  }
  app › shop/cart › Discounts.kt  [app/src/main/kotlin/shop/cart/Discounts.kt]
    L12–L13  151 chars  30 tok  /** [percent] percent of [amount], rounded down to the ce...
  app › shop/pay › Payment.kt  [app/src/main/kotlin/shop/pay/Payment.kt]
    L1  16 chars  3 tok  package shop.pay
  app › shop/pay › Payment.kt › Money  [app/src/main/kotlin/shop/pay/Payment.kt]
    L3–L5  83 chars  20 tok  /** An amount of money in cents. */
    L6  60 chars  10 tok  operator fun plus(other: Money) = Money(cents + other.cents)
    L7  61 chars  10 tok  operator fun minus(other: Money) = Money(cents - other.ce...
```

## What is skipped

| Reason | What |
| --- | --- |
| `build folder` | folders named `build`, `out`, `target`, `dist`, `node_modules`, `__pycache__`, `venv` |
| `hidden` | anything whose name starts with `.` (including `.git` and the `.gitignore` files themselves) |
| `gitignore` | what the project's `.gitignore` files exclude: the root one and nested ones, with `**`, `/`-anchored and folder-only rules and `!` |
| `lockfile` | `package-lock.json`, `yarn.lock`, `Cargo.lock`, `go.sum` and other lockfiles, and any `*.lock` |
| `too large` | files over 1 MB |
| `binary` | a NUL byte, or text that is neither UTF-8 nor mostly printable, in the first KB |
| `minified` | `*.min.*`, or more than 300 characters per line on average |
| `symlink` | symbolic links, which are never followed |

## Known limits

- The structure is heuristic, not a compiler front end: macros, generated code, unusual formatting and
  template-heavy C++ can mis-split. `rag structure` is there to make that visible.
- Deeper levels are titles, not units: `Searcher.kt › Searcher` can be searched and cited, but "all functions
  in class X" is not a query.
- A changed project is indexed as a new document rather than updated in place.
