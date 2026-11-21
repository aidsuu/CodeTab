#!/usr/bin/env python3
"""Downloads the TextMate grammars bundled with CodeTab into app/src/main/assets/textmate."""

import json
import plistlib
import re
import shutil
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
OUT = ASSETS / "textmate"
KEEP = {"jangkau-dark.json"}

VSCODE = "https://raw.githubusercontent.com/microsoft/vscode/1.95.0/"
VSCODE_EXTENSIONS = """
    bat clojure coffeescript cpp csharp css dart diff docker fsharp git-base go groovy handlebars html ini
    java javascript json julia latex less log lua make markdown-basics objective-c perl php powershell pug
    python r restructuredtext ruby rust scss shellscript sql swift typescript-basics vb xml yaml
""".split()

WANTED = set("""
    bat bibtex c clojure coffeescript cpp csharp css cuda-cpp dart diff dockerfile fsharp git-commit
    git-rebase go groovy handlebars html ignore ini jade java javascript javascriptreact json jsonc jsonl
    julia latex less log lua makefile markdown objective-c objective-cpp perl php powershell properties
    python r restructuredtext ruby rust scss shellscript sql swift tex typescript typescriptreact vb xml
    xsl yaml
""".split())

EXTRA = {
    "kotlin": {
        "base": "https://raw.githubusercontent.com/fwcd/vscode-kotlin/4a7c1538754828c1d22a8bee8ff3400045b4352a/",
        "grammar": "syntaxes/kotlin.tmLanguage.json",
        "config": "language-configuration.json",
        "license": "LICENSE",
        "scope": "source.kotlin",
    },
    "toml": {
        "base": "https://raw.githubusercontent.com/tamasfe/taplo/08f343be02ce1b20296470396a42f0fa47820449/editors/vscode/",
        "grammar": "toml.tmLanguage.json",
        "config": "language-configuration.json",
        "license": "LICENSE.md",
        "scope": "source.toml",
    },
}


def fetch(url: str) -> bytes:
    scheme, rest = url.split("://", 1)
    host, path = rest.split("/", 1)
    with urllib.request.urlopen(f"{scheme}://{host}/{urllib.parse.quote(path)}", timeout=60) as r:
        return r.read()


def grammar_json(data: bytes, name: str) -> dict:
    if name.endswith(".json"):
        return json.loads(data)
    return plistlib.loads(data)


def strip_jsonc(text: str) -> str:
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i : j + 1])
            i = j + 1
        elif text.startswith("//", i):
            i = text.find("\n", i)
            i = n if i < 0 else i
        elif text.startswith("/*", i):
            i = text.find("*/", i) + 2
        else:
            out.append(c)
            i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


# Joni (the Oniguruma port used on Android) rejects variable-length look-behind.
REGEX_FIXES = {
    r"(?<!\.\s*)": r"(?<!\.|\.\s|\.\s\s)",
    r"^await\s+using|[^\._$[:alnum:]]await\s+using)": r"^await\susing|[^\._$[:alnum:]]await\susing)",
    r"(?<=\S\s+)": r"(?<=\S\s|\S\s\s|\S\s\s\s|\S\s\s\s\s)",
    r"(?<=^[\s\d\p]*)": r"(?<=^|\s)",
    r"(?<=(^|\s))": r"(?<=^|\s)",
    r"(?<=^\s*)": r"(?<=^|^\s|^\s\s|^\s\s\s|^\s\s\s\s|^\s\s\s\s\s\s\s\s)",
}


def sanitize(node):
    if isinstance(node, dict):
        for key in ("match", "begin", "end", "while"):
            if isinstance(node.get(key), str):
                for old, new in REGEX_FIXES.items():
                    node[key] = node[key].replace(old, new)
        for key in ("captures", "beginCaptures", "endCaptures", "whileCaptures"):
            caps = node.get(key)
            if isinstance(caps, dict):
                node[key] = {k: v for k, v in caps.items() if isinstance(v, dict)}
        for v in node.values():
            sanitize(v)
    elif isinstance(node, list):
        for v in node:
            sanitize(v)
    return node


def includes(node, found: set):
    if isinstance(node, dict):
        inc = node.get("include")
        if isinstance(inc, str) and not inc.startswith(("#", "$")):
            found.add(inc.split("#", 1)[0])
        for v in node.values():
            includes(v, found)
    elif isinstance(node, list):
        for v in node:
            includes(v, found)


def main():
    grammars, configs = {}, {}
    for ext in VSCODE_EXTENSIONS:
        base = f"{VSCODE}extensions/{ext}/"
        pkg = json.loads(fetch(base + "package.json"))
        contributes = pkg.get("contributes", {})
        for lang in contributes.get("languages", []):
            if lang["id"] in WANTED and lang.get("configuration") and lang["id"] not in configs:
                configs[lang["id"]] = base + lang["configuration"].lstrip("./")
        for g in contributes.get("grammars", []):
            if g.get("injectTo"):
                continue
            entry = {"url": base + g["path"].lstrip("./"), "language": g.get("language"), "embedded": g.get("embeddedLanguages")}
            current = grammars.get(g["scopeName"])
            if current is None or (current["language"] not in WANTED and entry["language"] in WANTED):
                grammars[g["scopeName"]] = entry
        print(f"read {ext}", file=sys.stderr)

    for lang, spec in EXTRA.items():
        grammars[spec["scope"]] = {"url": spec["base"] + spec["grammar"], "language": lang, "embedded": None}
        configs[lang] = spec["base"] + spec["config"]

    primary = {"php": "text.html.php"}
    for scope, g in grammars.items():
        lang = g["language"]
        if lang in WANTED | set(EXTRA) and lang != "php" and (lang not in primary or "embedded" in primary[lang]):
            primary[lang] = scope

    selected, queue, loaded = set(), list(primary.values()), {}
    while queue:
        scope = queue.pop()
        if scope in selected or scope not in grammars:
            continue
        selected.add(scope)
        data = fetch(grammars[scope]["url"])
        loaded[scope] = sanitize(grammar_json(data, grammars[scope]["url"]))
        found = set()
        includes(loaded[scope], found)
        queue.extend(found - selected)

    for child in OUT.iterdir():
        if child.name in KEEP:
            continue
        shutil.rmtree(child) if child.is_dir() else child.unlink()
    (OUT / "grammars").mkdir()
    (OUT / "config").mkdir()

    entries = []
    names = {scope: lang for lang, scope in primary.items()}
    for scope in sorted(selected):
        file = f"grammars/{scope}.json"
        (OUT / file).write_text(json.dumps(loaded[scope], separators=(",", ":")))
        entry = {"name": names.get(scope, scope), "scopeName": scope, "grammar": f"textmate/{file}"}
        lang = names.get(scope)
        if lang in configs:
            cfg = f"config/{lang}.json"
            text = strip_jsonc(fetch(configs[lang]).decode("utf-8"))
            (OUT / cfg).write_text(json.dumps(json.loads(text), separators=(",", ":")))
            entry["languageConfiguration"] = f"textmate/{cfg}"
        embedded = {k: v for k, v in (grammars[scope]["embedded"] or {}).items() if v in primary}
        if embedded:
            entry["embeddedLanguages"] = embedded
        entries.append(entry)
    (OUT / "languages.json").write_text(json.dumps({"languages": entries}, indent=1))

    licenses = ASSETS / "licenses"
    (licenses / "VSCode-grammars-MIT.txt").write_bytes(fetch(VSCODE + "LICENSE.txt"))
    for lang, spec in EXTRA.items():
        (licenses / f"{lang}-grammar-MIT.txt").write_bytes(fetch(spec["base"] + spec["license"]))

    missing = sorted((WANTED | set(EXTRA)) - set(primary))
    print(f"{len(selected)} grammars, {len(entries)} entries, missing: {missing}", file=sys.stderr)


if __name__ == "__main__":
    main()
