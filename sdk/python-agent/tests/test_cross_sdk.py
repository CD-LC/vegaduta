"""Cross-SDK fixtures (``sdk/testdata/cross-sdk``): a chain and a mandate chain
written by the Java SDK verify here, and the Python-written ones verify in Java
(``CrossSdkFixtureTest`` in ``sdk/java/agent-core``). Formats are described in
``cross_sdk_fixtures.py``."""

from __future__ import annotations

import json
from dataclasses import replace

import cross_sdk_fixtures as fx
import pytest

from vegaduta_agent import HmacSha256, evidence_bundle, verify_audit_chain
from vegaduta_agent.mandates import Mandate, verify_chain

HMAC = HmacSha256(fx.HMAC_KEY)
NAMES = ["python-chain", "java-chain"]


def _anchors(name: str) -> dict:
    raw = json.loads((fx.FIXTURE_DIR / f"{name}.anchors.json").read_text(encoding="utf-8"))
    return {
        "expected_first_seq": int(raw["firstSeq"]),
        "expected_first_prev": raw["firstPrev"],
        "expected_last_seq": int(raw["lastSeq"]),
        "expected_head_hash": raw["headHash"],
    }


def _tamper(event, **changes):
    return replace(event, **changes)


@pytest.mark.parametrize("name", NAMES)
def test_chain_from_either_sdk_verifies_signed_and_anchored(name):
    events = fx.read_chain(fx.FIXTURE_DIR / f"{name}.jsonl")
    assert len(events) >= 5
    plain = verify_audit_chain(events)
    assert plain.intact, plain.detail
    signed = verify_audit_chain(events, HMAC)
    assert signed.intact, signed.detail
    assert signed.checked_events == len(events)
    anchored = verify_audit_chain(events, HMAC, **_anchors(name))
    assert anchored.intact, anchored.detail


def test_python_fixture_is_exactly_what_this_version_writes():
    # Guards the canonical form: if hashing or signing drifts, this fails before
    # the Java side (which holds the same bytes) silently disagrees.
    events = fx.build_chain()
    assert (fx.FIXTURE_DIR / "python-chain.jsonl").read_text(encoding="utf-8") == fx.chain_jsonl(events)
    assert (fx.FIXTURE_DIR / "python-chain.anchors.json").read_text(encoding="utf-8") == fx.anchors_json(
        events
    )
    assert (fx.FIXTURE_DIR / "python-mandates.txt").read_text(encoding="utf-8") == fx.mandates_text(
        fx.build_mandates()
    )


@pytest.mark.parametrize("name", NAMES)
def test_tampering_a_fixture_event_is_detected(name):
    events = fx.read_chain(fx.FIXTURE_DIR / f"{name}.jsonl")
    forged = list(events)
    forged[2] = _tamper(forged[2], detail="forged")
    result = verify_audit_chain(forged, HMAC)
    assert not result.intact
    assert result.break_at_seq == 3

    # re-hashing the event is caught by the signature when a verifier is given
    wrong_key = HmacSha256(b"another-key-another-key-0000")
    assert not verify_audit_chain(events, wrong_key).intact


@pytest.mark.parametrize("name", NAMES)
def test_anchors_catch_prefix_and_suffix_deletion_in_both_fixtures(name):
    events = fx.read_chain(fx.FIXTURE_DIR / f"{name}.jsonl")
    anchors = _anchors(name)

    # without anchors a truncated log still verifies: that is the gap
    assert verify_audit_chain(events[1:], HMAC).intact
    assert verify_audit_chain(events[:-1], HMAC).intact

    prefix = verify_audit_chain(events[1:], HMAC, **anchors)
    assert not prefix.intact and "expected_first_seq" in prefix.detail
    suffix = verify_audit_chain(events[:-1], HMAC, **anchors)
    assert not suffix.intact and "expected_last_seq" in suffix.detail
    head_only = verify_audit_chain(events[:-1], HMAC, expected_head_hash=anchors["expected_head_hash"])
    assert not head_only.intact and "expected_head_hash" in head_only.detail
    emptied = verify_audit_chain([], **anchors)
    assert not emptied.intact


@pytest.mark.parametrize("name", NAMES)
def test_bundle_exports_the_same_anchor_fields_as_the_anchor_files(name):
    events = fx.read_chain(fx.FIXTURE_DIR / f"{name}.jsonl")
    a = _anchors(name)
    bundle = evidence_bundle(events, "run", HMAC, **a)
    assert bundle.verification.intact
    d = bundle.to_dict()
    assert d["firstSeq"] == a["expected_first_seq"]
    assert d["firstPrev"] == a["expected_first_prev"]
    assert d["lastSeq"] == a["expected_last_seq"]
    assert d["headHash"] == a["expected_head_hash"]
    assert f"Head: seq {a['expected_last_seq']}, hash {a['expected_head_hash']}" in bundle.to_markdown()


@pytest.mark.parametrize("file", ["python-mandates.txt", "java-mandates.txt"])
def test_mandate_chain_from_either_sdk_verifies(file):
    lines = [x for x in (fx.FIXTURE_DIR / file).read_text(encoding="utf-8").splitlines() if x]
    chain = [Mandate.decode(x) for x in lines]
    assert len(chain) == 2
    leaf = verify_chain(chain)
    assert leaf.depth == 1
    assert chain[0].is_self_consistent()
    # one canonical text per mandate: re-encoding reproduces the bytes
    assert [m.encode() for m in chain] == lines
