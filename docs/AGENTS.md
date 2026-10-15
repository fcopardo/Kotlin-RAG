# Using the library from an agent

Two long-running servers make your indexed books available to a local agent. Both run over the same
warm database connection, so searches take tens of milliseconds instead of the seconds a fresh
`rag search` process needs.

| Server | Command | Use it for |
|---|---|---|
| **MCP** | `rag mcp --db books.db` | Agents that speak the Model Context Protocol: Claude Code, Claude Desktop, the Python `mcp` package, most agent frameworks. |
| **HTTP** | `rag serve --db books.db` | Any program: Python with `urllib` or `requests`, scripts, other languages. |

Both are read-only. Index and embed books with the CLI (see the [README](../README.md)), even while a
server runs: the server notices the change on its next request.

**Measured** from Python on an Apple M4 Pro, with a 4-book library (*Jetpack Compose internals*,
*Kotlin for Android Developers*, *Cracking the Coding Interview*, *The Art of War*; ~17,000
paragraphs) and mxbai through Ollama:

| | Time |
|---|---|
| First search (loads the caches and warms Ollama's model) | 0.6–1.2 s |
| Later searches | 49–97 ms |
| `rag search --json` as a subprocess, each time | ~1.4 s, plus the embedding |

## Requirements

- **The `rag` command:** build it with `./gradlew :cli:installDist`; it's at `cli/build/install/rag/bin/rag`.
- **Ollama** running, with the books' embedding model pulled, for searches by meaning. Without it,
  search falls back to keywords and warns on stderr.
- **Optional, `--onnx-dir <dir>`:** for books embedded with an ONNX build such as
  `nomic-embed-text@onnx-int8`. On Apple silicon, run `rag` with an arm64 JDK (`JAVA_HOME`).

## MCP

```bash
rag mcp --db books.db [--embed-model …] [--ollama-url …] [--onnx-dir …]
```

The server speaks JSON-RPC 2.0 over stdin and stdout, one message per line, following MCP protocol
versions 2024-11-05, 2025-03-26 and 2025-06-18. stdout carries only protocol messages; logs and
warnings go to stderr.

### Tools

| Tool | Arguments | Returns |
|---|---|---|
| `search_books` | `query` (required), `k` (1–20, default 5), `subject`, `book_id`, `mode` (`hybrid`, `lexical`, `vector`) | `passages`: each with `book`, `bookId`, `chapter`, `pages`, `match`, `text`, `bestSentence`, and `summary` for summary hits |
| `list_books` | none | `books`: each with `id`, `title`, `format`, `chapters`, `models`, `subjects` (each with `subject` and `weight`: main, tangential or broad) |
| `list_chapters` | `book_id` | `chapters`: each with `number`, `title`, `pages` |
| `read_chapter` | `book_id`, `chapter`, `offset` | Up to 12,000 characters of the chapter. When `nextOffset` is present, call again with it for the rest. |

- **Result format:** results come as `structuredContent`, and as the same JSON in a text block for
  older clients.
- **Fields that are null** (a passage's `summary`, the last page's `nextOffset`) are left out to save
  the model tokens.
- **Errors** (an unknown book or chapter, a missing argument) are tool results with `isError: true`
  and a message the model can act on.

### Claude Code and Claude Desktop

Claude Code:

```bash
claude mcp add books -- /path/to/rag mcp --db /path/to/books.db
```

Claude Desktop, in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "books": { "command": "/path/to/rag", "args": ["mcp", "--db", "/path/to/books.db"] }
  }
}
```

### A plain local agent

[examples/python/local_agent.py](../examples/python/local_agent.py) is a complete agent with no
dependencies: llama3.2 in Ollama answers, and calls the tools through `rag mcp`. It shows the whole
loop (initialize, convert `tools/list` to the model's tool format, run `tools/call`, repeat). It
also shows the retrieve-first step that small models need: one search with the user's question
before the model's first turn.

### Python, with the MCP SDK

`pip install mcp` (Python 3.10 or later):

```python
import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

server = StdioServerParameters(command="/path/to/rag", args=["mcp", "--db", "/path/to/books.db"])

async def main():
    async with stdio_client(server) as (read, write), ClientSession(read, write) as session:
        await session.initialize()
        result = await session.call_tool("search_books", {"query": "what is the slot table", "k": 3})
        for p in result.structuredContent["passages"]:
            print(p["book"], "·", p["chapter"], "·", p["pages"])
            print("  ", p["bestSentence"])

asyncio.run(main())
```

To give the tools to your own model loop, pass `(await session.list_tools()).tools`, which carry
names, descriptions and JSON schemas, as the model's tool definitions.

### Python, with no dependencies

MCP over stdio is plain JSON lines, so the standard library is enough:

```python
import json, subprocess

proc = subprocess.Popen(["/path/to/rag", "mcp", "--db", "/path/to/books.db"],
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
ids = iter(range(1, 1_000_000))

def rpc(method, params=None):
    msg = {"jsonrpc": "2.0", "id": next(ids), "method": method, "params": params or {}}
    proc.stdin.write(json.dumps(msg) + "\n"); proc.stdin.flush()
    reply = json.loads(proc.stdout.readline())
    if "error" in reply:
        raise RuntimeError(reply["error"]["message"])
    return reply["result"]

rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "me", "version": "1"}})
proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n"); proc.stdin.flush()

result = rpc("tools/call", {"name": "search_books", "arguments": {"query": "what is the slot table"}})
passages = result["structuredContent"]["passages"]
```

## HTTP

```bash
rag serve --db books.db [--port 8765] [--host 127.0.0.1] [--embed-model …] [--ollama-url …] [--onnx-dir …]
```

The server listens on `127.0.0.1` only, unless `--host` says otherwise. **Anything else exposes your
books to the network, with no authentication.**

| Endpoint | Returns |
|---|---|
| `GET /health` | `{"status": "ok", "books": 4}` |
| `GET /books` | The same fields as `list_books` |
| `GET /books/{id}/chapters` | The same fields as `list_chapters` |
| `GET /books/{id}/chapters/{n}?offset=` | The same as `read_chapter` |
| `GET /search?q=…&k=&book=&subject=&mode=&compact=` | Full hits, or passages with `compact=true` |
| `POST /search`, JSON body `{"query", "k", "book", "subject", "mode", "compact"}` | The same |

- **Full hits** are the same JSON as `rag search --json` and Android's `Hit`: `score`, `matchType`,
  `phrase`, `paragraph`, `chapter`, `bookId`, `bookTitle`, `pageKind`, with unit keys and page labels.
- **Passages** (`compact`) are the MCP tool's smaller format, which suits a model's context better.
- **Errors** are `{"error": "…"}`:
  - 400: bad arguments;
  - 404: an unknown book, chapter or endpoint;
  - 503: the embedding backend is unavailable;
  - 500: anything else.

```python
import json, urllib.request

def search(query, k=5, subject=None):
    body = json.dumps({"query": query, "k": k, "subject": subject, "compact": True}).encode()
    req = urllib.request.Request("http://127.0.0.1:8765/search", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

for p in search("all warfare is based on deception", k=3):
    print(f'{p["book"]} · {p["chapter"]} · {p["pages"]}: {p["bestSentence"]}')
```

## Giving passages to a model

- Put each passage's `text` in the model's context with its citation (`book`, `chapter`, `pages`),
  and ask it to answer only from them and to cite pages. `bestSentence` is the part to quote.
- Summary hits (`match: "summary"`) answer "what is this chapter or book about" questions. Their
  `summary` is generated text, so cite the passage, not the summary.
- **No passages, or only weak ones,** means the books likely don't cover the question. Tell the model
  so instead of letting it guess.
- Use `subject` (from `list_books`) or `book_id` when the user names a field or a book. A search over
  the whole library is fine otherwise: other books didn't lower recall in the 4-book tests
  (SPEC.md §8.4).
