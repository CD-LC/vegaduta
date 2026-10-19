"""coderag CLI: init | index | search | status."""

from __future__ import annotations

import argparse
import json
import sys
import time

from .config import load_config, write_template
from .embedders import make_embedder
from .pipeline import sync
from .store import Store


def _cmd_init(args) -> int:
    path = write_template(args.path)
    print(f"Wrote {path}")
    print("Tip: add '.coderag/' to your .gitignore (the index database "
          "lives there).")
    return 0


def _open(args):
    overrides = {}
    if getattr(args, "embedder", None):
        overrides["embedder"] = args.embedder
    if getattr(args, "embed_model", None):
        overrides["embed_model"] = args.embed_model
    if getattr(args, "llm_context", None):
        overrides["llm_context"] = True
    cfg = load_config(args.path, overrides)
    return cfg, Store(cfg.db_path)


def _cmd_index(args) -> int:
    cfg, store = _open(args)
    try:
        embedder = make_embedder(cfg)
        t0 = time.time()
        stats = sync(cfg, store, embedder, full=args.full, quiet=args.quiet)
        dt = time.time() - t0
        print(json.dumps({
            "files_scanned": stats.files_scanned,
            "files_changed": stats.files_changed,
            "files_deleted": stats.files_deleted,
            "chunks_total_in_changed_files": stats.chunks_total,
            "chunks_embedded": stats.chunks_embedded,
            "chunks_reused": stats.chunks_reused,
            "llm_summaries_generated": stats.llm_summaries_generated,
            "llm_summaries_cached": stats.llm_summaries_cached,
            "chunker_engines": stats.engines,
            "seconds": round(dt, 2),
        }, indent=2))
        return 0
    finally:
        store.close()


def _cmd_search(args) -> int:
    cfg, store = _open(args)
    try:
        embedder = make_embedder(cfg)
        stored = store.get_meta("embedder_identity")
        if stored and stored != embedder.identity:
            print(f"warning: index was built with {stored}, "
                  f"searching with {embedder.identity}", file=sys.stderr)
        qvec = embedder.embed([args.query])[0]
        hits = store.hybrid_search(qvec, args.query, top_k=args.top_k,
                                   path_prefix=args.path_filter)
        if args.json:
            print(json.dumps([hit.__dict__ for hit in hits], indent=2))
            return 0
        for rank, hit in enumerate(hits, 1):
            header = f"#{rank}  {hit.path}:{hit.start_line}-{hit.end_line}"
            if hit.symbol:
                header += f"  [{hit.symbol}]"
            header += f"  score={hit.score:.3f}"
            print(header)
            if args.show_context and hit.context:
                for line in hit.context.split("\n"):
                    print(f"    | {line}")
            snippet = hit.content.split("\n")
            for line in snippet[: args.lines]:
                print(f"    {line}")
            if len(snippet) > args.lines:
                print(f"    ... ({len(snippet) - args.lines} more lines)")
            print()
        return 0
    finally:
        store.close()


def _cmd_graph(args) -> int:
    from . import graph as g
    cfg = load_config(args.path)
    action = args.graph_command
    if action == "build":
        stats = g.build_graph(cfg, quiet=args.quiet)
        print(json.dumps(stats, indent=2))
    elif action == "callers":
        print(json.dumps(g.callers_of(cfg, args.symbol), indent=2))
    elif action == "callees":
        print(json.dumps(g.callees_of(cfg, args.symbol), indent=2))
    elif action == "impact":
        print(json.dumps(
            g.impact_of(cfg, args.symbol, depth=args.depth), indent=2))
    elif action == "stats":
        print(json.dumps(g.graph_stats(cfg), indent=2))
    elif action == "export":
        print(g.export_graph(cfg, fmt=args.format))
    return 0


def _cmd_status(args) -> int:
    cfg, store = _open(args)
    try:
        s = store.stats()
        print(f"root:        {cfg.root}")
        print(f"database:    {cfg.db_path}")
        print(f"files:       {s['files']}")
        print(f"chunks:      {s['chunks']}")
        print(f"embedder:    {s['embedder'] or '(never indexed)'}")
        print(f"last commit: {s['last_commit'] or '(none)'}")
        if s["last_indexed"]:
            age = time.time() - float(s["last_indexed"])
            print(f"last index:  {age/60:.1f} min ago")
        if s["languages"]:
            langs = ", ".join(f"{l}={n}" for l, n in s["languages"][:12])  # noqa: E741
            print(f"languages:   {langs}")
        return 0
    finally:
        store.close()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="coderag",
        description="Incremental, contextualized RAG ingestion for codebases.")
    sub = p.add_subparsers(dest="command", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--path", default=".", help="repo root (default: .)")
    common.add_argument("--embedder", help="override embedder provider")
    common.add_argument("--embed-model", dest="embed_model",
                        help="override embedding model")

    sp = sub.add_parser("init", help="write a .coderag.yml template")
    sp.add_argument("--path", default=".")
    sp.set_defaults(func=_cmd_init)

    sp = sub.add_parser("index", parents=[common],
                        help="index or incrementally update the repo")
    sp.add_argument("--full", action="store_true",
                    help="rescan every file (still reuses unchanged chunks)")
    sp.add_argument("--llm-context", action="store_true",
                    help="enable LLM contextual summaries for this run")
    sp.add_argument("-q", "--quiet", action="store_true")
    sp.set_defaults(func=_cmd_index)

    sp = sub.add_parser("search", parents=[common],
                        help="semantic search over the index")
    sp.add_argument("query")
    sp.add_argument("-k", "--top-k", type=int, default=8)
    sp.add_argument("--path-filter", help="restrict to paths with this prefix")
    sp.add_argument("--lines", type=int, default=8,
                    help="snippet lines to print per hit")
    sp.add_argument("--show-context", action="store_true",
                    help="print each hit's context header")
    sp.add_argument("--json", action="store_true")
    sp.set_defaults(func=_cmd_search)

    sp = sub.add_parser("status", parents=[common], help="index statistics")
    sp.set_defaults(func=_cmd_status)

    sp = sub.add_parser(
        "graph",
        help="deterministic AST-derived code graph (symbols, calls, "
             "imports, inheritance)")
    gsub = sp.add_subparsers(dest="graph_command", required=True)
    gp = gsub.add_parser("build", help="build/update the graph (incremental)")
    gp.add_argument("--path", default=".")
    gp.add_argument("-q", "--quiet", action="store_true")
    gp.set_defaults(func=_cmd_graph)
    for name, hlp in (("callers", "who calls this symbol"),
                      ("callees", "what this symbol calls")):
        gp2 = gsub.add_parser(name, help=hlp)
        gp2.add_argument("symbol")
        gp2.add_argument("--path", default=".")
        gp2.set_defaults(func=_cmd_graph)
    gp = gsub.add_parser("impact",
                         help="transitive blast radius of a symbol or file")
    gp.add_argument("symbol")
    gp.add_argument("--depth", type=int, default=3)
    gp.add_argument("--path", default=".")
    gp.set_defaults(func=_cmd_graph)
    gp = gsub.add_parser("stats", help="graph statistics")
    gp.add_argument("--path", default=".")
    gp.set_defaults(func=_cmd_graph)
    gp = gsub.add_parser("export", help="export graph as json or dot")
    gp.add_argument("--format", choices=("json", "dot"), default="json")
    gp.add_argument("--path", default=".")
    gp.set_defaults(func=_cmd_graph)

    args = p.parse_args(argv)
    try:
        return args.func(args)
    except BrokenPipeError:  # e.g. `coderag graph export | head`
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
