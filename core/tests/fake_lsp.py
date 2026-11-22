"""A tiny language server for the end-to-end tests. It keeps documents in memory,
reports a warning for every TODO, and completes words seen in the document."""

import json
import re
import sys

stdin, stdout = sys.stdin.buffer, sys.stdout.buffer
docs = {}
next_id = 1000
pending = {}


def send(msg):
    body = json.dumps(msg).encode()
    stdout.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    stdout.flush()


def read():
    length = None
    while True:
        line = stdin.readline()
        if not line:
            return None
        line = line.strip()
        if not line:
            break
        k, _, v = line.decode().partition(":")
        if k.lower() == "content-length":
            length = int(v)
    return json.loads(stdin.read(length))


def apply(text, change):
    if "range" not in change:
        return change["text"]
    lines = text.split("\n")

    def offset(p):
        return sum(len(l) + 1 for l in lines[: p["line"]]) + p["character"]

    r = change["range"]
    return text[: offset(r["start"])] + change["text"] + text[offset(r["end"]) :]


def publish(uri):
    diags = []
    for n, line in enumerate(docs[uri].split("\n")):
        c = line.find("TODO")
        if c >= 0:
            diags.append({"range": {"start": {"line": n, "character": c}, "end": {"line": n, "character": c + 4}},
                          "severity": 2, "message": "Unfinished work", "source": "fake"})
    send({"jsonrpc": "2.0", "method": "textDocument/publishDiagnostics", "params": {"uri": uri, "diagnostics": diags}})


def word_at(uri, pos):
    line = docs[uri].split("\n")[pos["line"]]
    start = pos["character"]
    while start > 0 and (line[start - 1].isalnum() or line[start - 1] == "_"):
        start -= 1
    return line[start : pos["character"]], start


sys.stdout.write("motd: this line is not part of the protocol\n")
sys.stdout.flush()

while True:
    msg = read()
    if msg is None:
        break
    method, params = msg.get("method"), msg.get("params") or {}
    if method is None:
        if msg.get("id") == 1:
            send({"jsonrpc": "2.0", "method": "window/showMessage",
                  "params": {"type": 3, "message": "config " + json.dumps(msg.get("result"))}})
        continue
    rid = msg.get("id")
    if method == "initialize":
        sys.stderr.write("fake server starting in " + str(params.get("rootPath")) + "\n")
        send({"jsonrpc": "2.0", "id": rid, "result": {
            "serverInfo": {"name": "fake-lsp", "version": "1.0"},
            "capabilities": {"textDocumentSync": {"change": 2, "save": {"includeText": False}},
                             "completionProvider": {"triggerCharacters": ["."]},
                             "hoverProvider": True, "definitionProvider": True,
                             "signatureHelpProvider": {"triggerCharacters": ["("]}}}})
    elif method == "initialized":
        send({"jsonrpc": "2.0", "id": 1, "method": "workspace/configuration",
              "params": {"items": [{"section": "fake.option"}]}})
        send({"jsonrpc": "2.0", "method": "$/progress",
              "params": {"token": "t", "value": {"kind": "begin", "title": "Indexing", "percentage": 0}}})
        send({"jsonrpc": "2.0", "method": "$/progress", "params": {"token": "t", "value": {"kind": "end"}}})
    elif method == "textDocument/didOpen":
        d = params["textDocument"]
        docs[d["uri"]] = d["text"]
        publish(d["uri"])
    elif method == "textDocument/didChange":
        uri = params["textDocument"]["uri"]
        for ch in params["contentChanges"]:
            docs[uri] = apply(docs[uri], ch)
        publish(uri)
    elif method == "textDocument/completion":
        uri, pos = params["textDocument"]["uri"], params["position"]
        prefix, start = word_at(uri, pos)
        words = sorted(set(re.findall(r"[A-Za-z_]\w+", docs[uri])))
        items = [{"label": w, "kind": 6, "detail": "word",
                  "textEdit": {"range": {"start": {"line": pos["line"], "character": start}, "end": pos}, "newText": w}}
                 for w in words if w.startswith(prefix) and w != prefix]
        items.append({"label": "snippet", "insertText": "snippet(${1:arg})$0", "insertTextFormat": 2})
        send({"jsonrpc": "2.0", "id": rid, "result": {"isIncomplete": False, "items": items}})
    elif method == "textDocument/hover":
        uri, pos = params["textDocument"]["uri"], params["position"]
        line = docs[uri].split("\n")[pos["line"]]
        send({"jsonrpc": "2.0", "id": rid, "result": {"contents": {"kind": "markdown", "value": "Line: `" + line + "`"}}})
    elif method == "textDocument/definition":
        uri = params["textDocument"]["uri"]
        send({"jsonrpc": "2.0", "id": rid, "result": [{"uri": uri, "range": {
            "start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 1}}}]})
    elif method == "textDocument/signatureHelp":
        send({"jsonrpc": "2.0", "id": rid, "result": {"signatures": [
            {"label": "greet(name, times)", "parameters": [{"label": "name"}, {"label": "times"}]}], "activeParameter": 1}})
    elif method == "shutdown":
        send({"jsonrpc": "2.0", "id": rid, "result": None})
    elif method == "exit":
        break
    elif rid is not None:
        send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "unknown " + method}})
