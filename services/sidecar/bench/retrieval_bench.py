"""
L8 benchmark harness: single-vector (current) vs late-interaction (multi-vector).

Offline research tool. NOT imported by the service, NOT a production switch,
adds NO dependency to requirements.txt. Run from sidecar/:

    python -m bench.retrieval_bench
    python -m bench.retrieval_bench --dense minilm-l6-v2 --late <colbert-checkpoint-repo>
    python -m bench.retrieval_bench --eval my_set.json --k 1,3,5 --repeat 5 --json out.json

Systems compared
  dense:<key>   the CURRENT path: an app.embeddings MODEL_REGISTRY model, one L2-normalised
                vector per text, zero-padded to 768 exactly as embed_texts() does, cosine score.
  late:<repo>   late interaction: one vector per token, score = sum over query tokens of the
                max cosine against document tokens (MaxSim). Loaded with sentence-transformers
                using output_value="token_embeddings". For a genuine ColBERT checkpoint that
                exposes token vectors this is the real thing; for a plain bi-encoder (the
                default here) it is only a "MaxSim over its token vectors" control, which is
                usually WORSE than pooling - itself a useful data point. Note: a ColBERT
                checkpoint's projection/linear head and query-marker conventions are not
                applied by a bare SentenceTransformer load; use a checkpoint packaged for
                sentence-transformers (or PyLate) and check the dims it reports.

Metrics: recall@k, MRR, query latency (p50/p95 over --repeat, scoring only after the query
is encoded is NOT separated: the number is encode + score), index time, and stored floats per
document (the real cost of multi-vector). The metric/MaxSim helpers are pure numpy so
tests/test_retrieval_bench.py needs no model.
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

import numpy as np

DEFAULT_EVAL = Path(__file__).with_name("eval_set.json")
# Mirrors app.embeddings.STORAGE_DIMENSIONS; kept literal so pure helpers import no app code.
STORAGE_DIMENSIONS = 768


# ---------------------------------------------------------------- pure scoring / metrics

def l2_normalize(m: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(m, axis=-1, keepdims=True)
    return m / np.clip(n, 1e-12, None)


def zero_pad(vecs: np.ndarray, width: int = STORAGE_DIMENSIONS) -> np.ndarray:
    if vecs.shape[-1] > width:
        raise ValueError(f"{vecs.shape[-1]} dims exceed storage width {width}")
    return np.pad(vecs, [(0, 0)] * (vecs.ndim - 1) + [(0, width - vecs.shape[-1])])


def maxsim(query_tokens: np.ndarray, doc_tokens: np.ndarray) -> float:
    """ColBERT score: sum over query tokens of the best cosine against any doc token."""
    q, d = l2_normalize(query_tokens), l2_normalize(doc_tokens)
    return float((q @ d.T).max(axis=1).sum())


def rank_by_score(scores: dict[str, float]) -> list[str]:
    return [k for k, _ in sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))]


def recall_at_k(ranked: list[str], relevant: set[str], k: int) -> float:
    return len(set(ranked[:k]) & relevant) / len(relevant) if relevant else 0.0


def reciprocal_rank(ranked: list[str], relevant: set[str]) -> float:
    for i, doc in enumerate(ranked, 1):
        if doc in relevant:
            return 1.0 / i
    return 0.0


def evaluate(rankings: dict[str, list[str]], queries: list[dict], ks: list[int]) -> dict:
    out = {f"recall@{k}": 0.0 for k in ks}
    out["mrr"] = 0.0
    for item in queries:
        rel, ranked = set(item["relevant"]), rankings[item["q"]]
        for k in ks:
            out[f"recall@{k}"] += recall_at_k(ranked, rel, k)
        out["mrr"] += reciprocal_rank(ranked, rel)
    n = max(len(queries), 1)
    return {m: round(v / n, 4) for m, v in out.items()}


def percentile(xs: list[float], p: float) -> float:
    s = sorted(xs)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))] if s else 0.0


# ---------------------------------------------------------------- systems (lazy model imports)

class DenseSystem:
    def __init__(self, key: str):
        from app.embeddings import get_model  # lazy: heavy import
        self.name = f"dense:{key}"
        self.model = get_model(key)

    def _enc(self, texts):
        return zero_pad(self.model.encode(texts, normalize_embeddings=True, convert_to_numpy=True))

    def index(self, docs: dict[str, str]):
        self.ids = list(docs)
        self.mat = self._enc([docs[i] for i in self.ids])
        self.floats_per_doc = STORAGE_DIMENSIONS  # one padded vector per chunk

    def search(self, query: str) -> dict[str, float]:
        return dict(zip(self.ids, (self.mat @ self._enc([query])[0]).tolist()))


class LateSystem:
    def __init__(self, repo: str):
        from sentence_transformers import SentenceTransformer  # lazy
        self.name = f"late:{repo}"
        self.model = SentenceTransformer(repo)

    def _tokens(self, text: str) -> np.ndarray:
        t = self.model.encode(text, output_value="token_embeddings", convert_to_numpy=False)
        return t.detach().cpu().numpy() if hasattr(t, "detach") else np.asarray(t)

    def index(self, docs: dict[str, str]):
        self.ids = list(docs)
        self.tokens = {i: l2_normalize(self._tokens(docs[i])) for i in self.ids}
        self.floats_per_doc = int(statistics.mean(v.size for v in self.tokens.values()))

    def search(self, query: str) -> dict[str, float]:
        q = self._tokens(query)
        return {i: maxsim(q, self.tokens[i]) for i in self.ids}


def run_system(system, data: dict, ks: list[int], repeat: int) -> dict:
    t0 = time.perf_counter()
    system.index(data["docs"])
    index_s = time.perf_counter() - t0
    rankings, lat = {}, []
    for item in data["queries"]:
        for _ in range(max(repeat, 1)):
            t = time.perf_counter()
            scores = system.search(item["q"])
            lat.append((time.perf_counter() - t) * 1000)
        rankings[item["q"]] = rank_by_score(scores)
    res = {"system": system.name}
    res.update(evaluate(rankings, data["queries"], ks))
    res.update(index_seconds=round(index_s, 3), query_ms_p50=round(percentile(lat, 50), 2),
               query_ms_p95=round(percentile(lat, 95), 2), floats_per_doc=system.floats_per_doc)
    return res


def format_table(rows: list[dict], ks: list[int]) -> str:
    cols = ["system"] + [f"recall@{k}" for k in ks] + ["mrr", "query_ms_p50", "query_ms_p95", "index_seconds", "floats_per_doc"]
    return "\n".join(["\t".join(cols)] + ["\t".join(str(r[c]) for c in cols) for r in rows])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--eval", type=Path, default=DEFAULT_EVAL)
    ap.add_argument("--dense", default="minilm-l6-v2,multilingual-minilm", help="comma list of MODEL_REGISTRY keys")
    ap.add_argument("--late", default="sentence-transformers/all-MiniLM-L6-v2",
                    help="comma list of token-level encoder repos (use a ColBERT checkpoint for the real test)")
    ap.add_argument("--k", default="1,3,5")
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--json", type=Path)
    a = ap.parse_args(argv)
    ks = [int(x) for x in a.k.split(",")]
    data = json.loads(a.eval.read_text(encoding="utf-8"))
    systems = [DenseSystem(k) for k in a.dense.split(",") if k] + [LateSystem(r) for r in a.late.split(",") if r]
    rows = [run_system(s, data, ks, a.repeat) for s in systems]
    print(format_table(rows, ks))
    if a.json:
        a.json.write_text(json.dumps(rows, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
