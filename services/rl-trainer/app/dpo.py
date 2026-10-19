"""DPO (Direct Preference Optimization) preference-training - DOCUMENTED STUB.

This endpoint intentionally does NOT train weights. DPO/GRPO weight training
needs a GPU worker (TRL's DPOTrainer, VERL, or a hosted fine-tune API), which
is deliberately out of scope for this default CPU/LLM-only image (no torch, no
CUDA - see requirements.txt and the README's "GPU-enablement path"). Faking a
training run would be dishonest, so instead we:

  * validate the uploaded DPO preference JSONL (schema per line:
    {"prompt": ..., "chosen": ..., "rejected": ...}),
  * estimate the number of valid pairs and an approximate token volume, and
  * return a queued job descriptor with an honest note explaining that the
    actual weight-training step requires the optional [gpu] worker.

The README documents exactly how to enable real training (the [gpu] extra, a
TRL DPOTrainer sketch, or routing to a hosted fine-tune API).
"""

from __future__ import annotations

import json
import uuid

# Rough chars-per-token heuristic - deliberately approximate. We do NOT pull
# in a tokenizer dependency for what is a capacity estimate on a stub endpoint.
_CHARS_PER_TOKEN = 4


class DpoValidationError(ValueError):
    """Raised (=> HTTP 400) when the JSONL is malformed or has no valid pairs."""


def validate_and_estimate(jsonl_text: str) -> dict:
    """Parse and validate a DPO preference JSONL body, returning pair/token
    estimates. Raises DpoValidationError on the first structural problem or if
    zero valid pairs are found."""
    lines = [ln for ln in jsonl_text.splitlines() if ln.strip()]
    if not lines:
        raise DpoValidationError("empty JSONL body")

    valid_pairs = 0
    total_chars = 0
    errors: list[str] = []

    for lineno, ln in enumerate(lines, 1):
        try:
            obj = json.loads(ln)
        except json.JSONDecodeError as e:
            errors.append(f"line {lineno}: invalid JSON ({e.msg})")
            continue
        if not isinstance(obj, dict):
            errors.append(f"line {lineno}: expected a JSON object")
            continue
        missing = [k for k in ("prompt", "chosen", "rejected")
                   if not isinstance(obj.get(k), str) or not obj.get(k)]
        if missing:
            errors.append(
                f"line {lineno}: missing/invalid field(s): {', '.join(missing)}")
            continue
        valid_pairs += 1
        total_chars += len(obj["prompt"]) + len(obj["chosen"]) + len(obj["rejected"])

    if valid_pairs == 0:
        raise DpoValidationError(
            "no valid preference pairs found. Each line must be a JSON object "
            "with non-empty string fields 'prompt', 'chosen', 'rejected'. "
            + (f"First errors: {errors[:5]}" if errors else ""))

    return {
        "valid_pairs": valid_pairs,
        "invalid_lines": len(errors),
        "estimated_tokens": (total_chars // _CHARS_PER_TOKEN),
        "errors_sample": errors[:5],
    }


def queue_job(jsonl_text: str) -> dict:
    """Validate the JSONL and return a queued-job descriptor. Honest by design:
    status is 'queued' and the note explains that no GPU worker is attached in
    this image, so no weights will actually be produced until one is enabled."""
    est = validate_and_estimate(jsonl_text)
    job_id = f"dpo-{uuid.uuid4().hex[:12]}"
    return {
        "jobId": job_id,
        "status": "queued",
        "pairs": est["valid_pairs"],
        "invalidLines": est["invalid_lines"],
        "estimatedTokens": est["estimated_tokens"],
        "errorsSample": est["errors_sample"],
        "note": (
            "Preference data validated and a job was queued, but this default "
            "CPU/LLM-only image has NO GPU training worker attached, so no "
            "model weights will be produced. DPO/GRPO weight training requires "
            "the optional [gpu] extra (TRL DPOTrainer / VERL) or routing to a "
            "hosted fine-tune API - see rl-trainer/README.md 'GPU-enablement "
            "path'. This endpoint does not fake training: the job will remain "
            "'queued' until such a worker is enabled. For prompt-level "
            "improvement that needs no GPU, use POST /optimize/prompt instead."
        ),
    }
