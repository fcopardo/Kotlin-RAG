"""
A fully local agent: a model in Ollama answers questions about your books, calling `rag mcp` for
passages. Standard library only (Python 3.9+).

    python3 examples/python/local_agent.py "What is the slot table in Jetpack Compose?"

Environment (all optional):
    RAG         path to the rag launcher   (default: cli/build/install/rag/bin/rag)
    RAG_DB      the books database          (default: library.db)
    OLLAMA_URL  Ollama server               (default: http://localhost:11434)
    MODEL       a chat model with tool use  (default: llama3.2:3b)

The same loop works with any model that does tool calling: list the MCP tools, hand them to the
model in its tool format, run the calls it asks for, and send the results back until it answers.
Here it also searches once before the model's first turn, because small local models often answer
from memory instead of calling the tool; larger models can skip that and rely on the tools alone.
"""
import json
import os
import subprocess
import sys
import urllib.request

RAG = os.environ.get("RAG", "cli/build/install/rag/bin/rag")
RAG_DB = os.environ.get("RAG_DB", "library.db")
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
MODEL = os.environ.get("MODEL", "llama3.2:3b")

SYSTEM = (
    "You answer questions about the user's books, using only the passages you are given or find with "
    "search_books, and cite book, chapter and pages for each claim. If the passages don't answer the "
    "question, say so instead of answering from memory."
)


class McpClient:
    """A minimal MCP client over stdio: JSON-RPC 2.0, one message per line."""

    def __init__(self, command):
        # stderr is inherited, so the server's warnings show in the terminal.
        self.proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.next_id = 0
        self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                    "clientInfo": {"name": "local-agent", "version": "1"}})
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def send(self, message):
        self.proc.stdin.write(json.dumps(message) + "\n")
        self.proc.stdin.flush()

    def request(self, method, params=None):
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params or {}})
        reply = json.loads(self.proc.stdout.readline())
        if "error" in reply:
            raise RuntimeError(reply["error"]["message"])
        return reply["result"]

    def tools(self):
        return self.request("tools/list")["tools"]

    def call(self, name, arguments):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        # Errors (unknown book, bad argument) go back to the model as text it can act on.
        return result["content"][0]["text"]

    def close(self):
        self.proc.stdin.close()
        self.proc.wait()


def ollama_chat(messages, tools):
    body = json.dumps({"model": MODEL, "messages": messages, "tools": tools, "stream": False,
                       "options": {"temperature": 0}}).encode()
    request = urllib.request.Request(OLLAMA_URL + "/api/chat", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.load(response)["message"]


def passages_for(question, mcp, k=5):
    """Searches before the model's first turn: small models often answer from memory instead of searching."""
    found = json.loads(mcp.call("search_books", {"query": question, "k": k}))["passages"]
    return "\n\n".join(
        f'[{i}] {p["book"]} · {p["chapter"]} · {p["pages"]}\n{p["text"]}' for i, p in enumerate(found, 1)
    ) or "(no passages found)"


def answer(question, mcp, max_rounds=5):
    # MCP tool definitions map one to one onto Ollama's (and OpenAI's) function tools.
    tools = [{"type": "function", "function": {"name": t["name"], "description": t["description"],
                                               "parameters": t["inputSchema"]}} for t in mcp.tools()]
    context = passages_for(question, mcp)
    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"Passages from my books:\n\n{context}\n\nQuestion: {question}"},
    ]
    for _ in range(max_rounds):
        reply = ollama_chat(messages, tools)
        messages.append(reply)
        calls = reply.get("tool_calls") or []
        if not calls:
            return reply["content"]
        for call in calls:
            name, arguments = call["function"]["name"], call["function"].get("arguments") or {}
            print(f"  → {name}({json.dumps(arguments)})", file=sys.stderr)
            messages.append({"role": "tool", "tool_name": name, "content": mcp.call(name, arguments)})
    return "(no answer after %d tool rounds)" % max_rounds


if __name__ == "__main__":
    question = " ".join(sys.argv[1:]) or "What is the slot table in Jetpack Compose?"
    client = McpClient([RAG, "mcp", "--db", RAG_DB])
    try:
        print(answer(question, client))
    finally:
        client.close()
