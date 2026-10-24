#!/usr/bin/env bash
# Offline check of relative links and #anchors in README.md, CONTRIBUTING.md and docs/**/*.md.
# External (http/https/mailto) links are not fetched. Fenced code blocks are ignored.
# Default file set: tracked plus new untracked (not ignored) files, so a doc you have not
# added yet is still checked. Files named on the command line are resolved against the
# current directory, from any directory inside the repository.
# Also fails (default file set, or any .html file named on the command line) when a hand-written
# HTML page under docs/ links to a .md file: mkdocs publishes the HTML verbatim and serves each
# Markdown page as /<name>/, so such a link works on disk and on GitHub but is a 404 on the site.
# Also fails on a leftover `TODO(maintainers)` identity placeholder in a Markdown file or CODEOWNERS.
# Usage: bash scripts/ci/docs-link-check.sh [files...]
set -uo pipefail
args=()
for a in "$@"; do args+=("$(realpath "$a")"); done
cd "$(git rev-parse --show-toplevel)" || exit 1
exec python3 - "${args[@]+"${args[@]}"}" <<'PY'
import os, re, sys, subprocess, urllib.parse

args = sys.argv[1:]
if args:
    files = [os.path.relpath(a) for a in args]
else:
    out = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "README.md", "CONTRIBUTING.md", "docs/*.md", "docs/**/*.md"],
                         capture_output=True, text=True).stdout.split()
    files = sorted(f for f in set(out) if os.path.exists(f) and "/archive/" not in f)

def strip_blocks(text):
    text = re.sub(r"(?s)<!--.*?-->", "", text)
    return re.sub(r"(?ms)^[ \t]*(```|~~~).*?^[ \t]*\1[^\n]*$", "", text)

def strip_code(text):
    return re.sub(r"`[^`\n]*`", "", strip_blocks(text))

def slug(h):
    h = re.sub(r"<[^>]+>", "", h)
    h = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", h)
    h = h.strip().lower()
    h = re.sub(r"[^\w\- ]", "", h, flags=re.UNICODE)
    return h.replace(" ", "-")

anchor_cache = {}
def anchors(path):
    if path in anchor_cache:
        return anchor_cache[path]
    seen, res = {}, set()
    try:
        text = open(path, encoding="utf-8").read()
    except Exception:
        anchor_cache[path] = res
        return res
    for line in strip_blocks(text).splitlines():
        m = re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if m:
            s = slug(m.group(1))
            n = seen.get(s, 0)
            seen[s] = n + 1
            res.add(s if n == 0 else f"{s}-{n}")
    for m in re.finditer(r'<a\s+(?:name|id)="([^"]+)"', text):
        res.add(m.group(1))
    anchor_cache[path] = res
    return res

link_re = re.compile(r"(?<!\!)\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)|!\[[^\]]*\]\(\s*<?([^)\s>]+)|^[ ]{0,3}\[[^\]]+\]:\s*<?([^\s>]+)", re.M)
bad = 0
for f in files:
    text = strip_code(open(f, encoding="utf-8").read())
    for m in link_re.finditer(text):
        target = m.group(1) or m.group(2) or m.group(3)
        if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith("//"):
            continue
        path, _, frag = target.partition("#")
        path = urllib.parse.unquote(path)
        dest = f if path == "" else os.path.normpath(os.path.join(os.path.dirname(f), path))
        if path.startswith("/"):
            dest = os.path.normpath(path.lstrip("/"))
        if not os.path.exists(dest):
            print(f"{f}: missing target {target}"); bad += 1; continue
        if frag and dest.endswith(".md") and os.path.isfile(dest):
            if urllib.parse.unquote(frag).lower() not in anchors(dest):
                print(f"{f}: missing anchor {target}"); bad += 1

html_md = re.compile(r'href="([^":#?]*\.md)(?:#[^"]*)?"')
if args:
    html_files = [f for f in files if f.endswith(".html")]
else:
    out = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "docs/*.html", "docs/**/*.html"],
                         capture_output=True, text=True).stdout.split()
    html_files = sorted(f for f in set(out) if os.path.exists(f) and "/archive/" not in f)
for f in html_files:
    for n, line in enumerate(open(f, encoding="utf-8").read().splitlines(), 1):
        for m in html_md.finditer(line):
            print(f"{f}:{n}: href to a .md file ({m.group(1)}) is a 404 on the published site; link the site URL (.../name/)")
            bad += 1

if not args:
    out = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "*.md", ".github/CODEOWNERS"],
                         capture_output=True, text=True).stdout.split()
    for f in sorted(set(out)):
        if not os.path.isfile(f) or "/archive/" in f:
            continue
        for n, line in enumerate(open(f, encoding="utf-8", errors="replace").read().splitlines(), 1):
            if "TODO(maintainers)" in line:
                print(f"{f}:{n}: leftover TODO(maintainers) placeholder; the repository identity is settled")
                bad += 1

print(f"checked {len(files)} files and {len(html_files)} html pages, {bad} broken link(s) or placeholder(s)")
sys.exit(1 if bad else 0)
PY
