"""Pure-numpy tests for bench/retrieval_bench.py (no model download).

Run from sidecar/: pytest tests/
"""

import json
from pathlib import Path

import numpy as np
import pytest

from bench import retrieval_bench as rb


def test_maxsim_identical_tokens_scores_query_length():
    q = np.array([[1.0, 0.0], [0.0, 1.0]])
    assert rb.maxsim(q, q) == pytest.approx(2.0)


def test_maxsim_takes_best_doc_token_per_query_token():
    q = np.array([[1.0, 0.0]])
    d = np.array([[0.0, 1.0], [1.0, 0.0], [-1.0, 0.0]])
    assert rb.maxsim(q, d) == pytest.approx(1.0)


def test_zero_padding_preserves_cosine_and_rejects_overwide():
    a, b = np.array([[0.6, 0.8]]), np.array([[1.0, 0.0]])
    pa, pb = rb.zero_pad(a), rb.zero_pad(b)
    assert pa.shape == (1, 768)
    assert (pa @ pb.T)[0, 0] == pytest.approx((a @ b.T)[0, 0])
    with pytest.raises(ValueError):
        rb.zero_pad(np.zeros((1, 1024)))


def test_recall_mrr_and_evaluate():
    ranked = ["x", "a", "b"]
    assert rb.recall_at_k(ranked, {"a", "z"}, 2) == 0.5
    assert rb.reciprocal_rank(ranked, {"b"}) == pytest.approx(1 / 3)
    res = rb.evaluate({"q": ranked}, [{"q": "q", "relevant": ["a"]}], [1, 2])
    assert res == {"recall@1": 0.0, "recall@2": 1.0, "mrr": 0.5}


def test_rank_by_score_is_deterministic_on_ties():
    assert rb.rank_by_score({"b": 1.0, "a": 1.0, "c": 2.0}) == ["c", "a", "b"]


class _FakeSystem:
    name = "fake"
    floats_per_doc = 3

    def index(self, docs):
        self.ids = list(docs)

    def search(self, query):
        return {i: (1.0 if i == "d1" else 0.0) for i in self.ids}


def test_run_system_end_to_end_with_fake():
    data = {"docs": {"d1": "a", "d2": "b"}, "queries": [{"q": "x", "relevant": ["d1"]}]}
    row = rb.run_system(_FakeSystem(), data, [1], 2)
    assert row["recall@1"] == 1.0 and row["mrr"] == 1.0 and row["system"] == "fake"
    assert "recall@1" in rb.format_table([row], [1])


def test_eval_set_is_well_formed():
    data = json.loads(Path(rb.DEFAULT_EVAL).read_text(encoding="utf-8"))
    assert len(data["docs"]) >= 20 and len(data["queries"]) >= 10
    for item in data["queries"]:
        assert item["relevant"] and all(d in data["docs"] for d in item["relevant"])
