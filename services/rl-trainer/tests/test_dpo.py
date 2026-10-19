"""app/dpo.py - the DPO endpoint is a VALIDATING STUB: it checks the
preference JSONL, estimates volume, and queues a job. It never trains, and
these tests pin that honesty (status stays 'queued', no torch import)."""

from __future__ import annotations

import json
import re
import sys

import pytest

from app.dpo import DpoValidationError, queue_job, validate_and_estimate


def _line(prompt: str = "p", chosen: str = "c", rejected: str = "r") -> str:
    return json.dumps({"prompt": prompt, "chosen": chosen, "rejected": rejected})


def test_valid_pairs_counted_and_tokens_estimated() -> None:
    body = "\n".join([_line("abcd", "efgh", "ijkl"), "", "   ", _line("a" * 40, "b", "c")])
    est = validate_and_estimate(body)
    assert est == {
        "valid_pairs": 2,
        "invalid_lines": 0,
        "estimated_tokens": (12 + 42) // 4,
        "errors_sample": [],
    }


def test_crlf_and_extra_keys_tolerated() -> None:
    obj = {"prompt": "p", "chosen": "c", "rejected": "r", "meta": {"agent": "x"}}
    body = json.dumps(obj) + "\r\n" + _line() + "\r\n"
    assert validate_and_estimate(body)["valid_pairs"] == 2


def test_invalid_lines_reported_but_valid_ones_kept() -> None:
    body = "\n".join(
        [
            _line(),
            "{not json",
            "[1, 2]",
            json.dumps({"prompt": "p", "chosen": "c"}),
            json.dumps({"prompt": "", "chosen": "c", "rejected": "r"}),
            json.dumps({"prompt": "p", "chosen": 1, "rejected": None}),
        ]
    )
    est = validate_and_estimate(body)
    assert est["valid_pairs"] == 1
    assert est["invalid_lines"] == 5
    errs = est["errors_sample"]
    assert errs[0].startswith("line 2: invalid JSON")
    assert errs[1] == "line 3: expected a JSON object"
    assert errs[2] == "line 4: missing/invalid field(s): rejected"
    assert errs[3] == "line 5: missing/invalid field(s): prompt"
    assert errs[4] == "line 6: missing/invalid field(s): chosen, rejected"


def test_errors_sample_capped_at_five() -> None:
    body = "\n".join([_line()] + ["garbage"] * 9)
    est = validate_and_estimate(body)
    assert est["invalid_lines"] == 9
    assert len(est["errors_sample"]) == 5


@pytest.mark.parametrize("body", ["", "\n\n  \n"])
def test_empty_body_rejected(body: str) -> None:
    with pytest.raises(DpoValidationError, match="empty"):
        validate_and_estimate(body)


def test_zero_valid_pairs_rejected_with_first_errors() -> None:
    with pytest.raises(DpoValidationError) as ei:
        validate_and_estimate("nope\n{}")
    msg = str(ei.value)
    assert "no valid preference pairs" in msg
    assert "line 1: invalid JSON" in msg


def test_queue_job_smoke_on_tiny_dataset_never_trains() -> None:
    torch_loaded_before = "torch" in sys.modules
    job = queue_job("\n".join([_line("q1", "good", "bad"), _line("q2", "yes", "no"), "junk"]))

    assert re.fullmatch(r"dpo-[0-9a-f]{12}", job["jobId"])
    assert job["status"] == "queued"
    assert job["pairs"] == 2
    assert job["invalidLines"] == 1
    assert job["estimatedTokens"] == (9 + 7) // 4
    assert len(job["errorsSample"]) == 1
    assert "NO GPU training worker" in job["note"]
    # The stub must not pull in a training stack behind our back.
    assert ("torch" in sys.modules) == torch_loaded_before


def test_job_ids_are_unique() -> None:
    assert len({queue_job(_line())["jobId"] for _ in range(20)}) == 20


def test_queue_job_propagates_validation_error() -> None:
    with pytest.raises(DpoValidationError):
        queue_job("{}")
