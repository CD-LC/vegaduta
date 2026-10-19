#!/usr/bin/env python3
"""Offline check of every bundle in this folder against the rules the server enforces on import
(TemplateBundleService): format, required fields, size, autonomy tier, tool list, and that no
secret-shaped value or knowledge-base id is carried.

Usage: python3 examples/agent-bundles/validate.py [FILE...]   (default: every *.json here)
"""
import json
import re
import sys
from pathlib import Path

FORMAT = "vegaduta.agent-bundle/1"
MAX_AGENT_CHARS = 64_000
MAX_TOOLS = 100
TIERS = {"SUPERVISED", "SEMI_AUTONOMOUS", "FULLY_AUTONOMOUS"}
# Tools a bundle may list. Keep in step with the built-in tool catalog.
KNOWN_TOOLS = {
    "search_knowledge_base", "list_knowledge_collections", "request_human", "remember_fact",
    "recall_related_facts", "remember_shared_fact", "recall_shared_graph_facts", "generate_document",
    "fetch_web_page", "web_search", "run_code", "google_gmail", "google_calendar", "google_sheets",
    "google_drive",
}
SECRET = re.compile(r"(sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|xox[bp]-|-----BEGIN [A-Z ]*PRIVATE KEY)")


def check(path: Path) -> list[str]:
    errors = []
    text = path.read_text()
    try:
        b = json.loads(text)
    except json.JSONDecodeError as e:
        return [f"not valid JSON: {e}"]
    if b.get("format") != FORMAT:
        errors.append(f"format must be {FORMAT}")
    for k in ("title", "agent"):
        if not b.get(k):
            errors.append(f"missing {k}")
    if len(b.get("title", "")) > 200:
        errors.append("title over 200 characters")
    agent = b.get("agent") or {}
    if len(json.dumps(agent)) > MAX_AGENT_CHARS:
        errors.append("agent design over the size cap")
    if not str(agent.get("systemPrompt", "")).strip():
        errors.append("agent has no systemPrompt")
    tier = agent.get("autonomyTier")
    if tier not in TIERS:
        errors.append(f"autonomyTier must be one of {sorted(TIERS)}")
    elif tier == "FULLY_AUTONOMOUS":
        errors.append("FULLY_AUTONOMOUS is lowered to SUPERVISED on import; do not ship it")
    tools = agent.get("allowedToolNames") or []
    if not tools:
        errors.append("allowedToolNames is empty: an empty list means ALL tools, list the ones you need")
    if len(tools) > MAX_TOOLS:
        errors.append("too many tools")
    for t in tools:
        if t not in KNOWN_TOOLS:
            errors.append(f"unknown tool '{t}' (add it to KNOWN_TOOLS if it is real)")
    if agent.get("allowedKnowledgeCollectionIds"):
        errors.append("allowedKnowledgeCollectionIds must be empty (ids belong to one workspace)")
    if SECRET.search(text):
        errors.append("looks like it contains a secret")
    return errors


def main(argv):
    files = [Path(a) for a in argv] or sorted(Path(__file__).parent.glob("*.json"))
    bad = 0
    for f in files:
        errs = check(f)
        print(("FAIL " if errs else "ok   ") + f.name)
        for e in errs:
            print("     - " + e)
        bad += bool(errs)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
