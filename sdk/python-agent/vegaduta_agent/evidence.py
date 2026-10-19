"""Tamper-evident audit: hash-chained, optionally signed events and the
evidence bundle an auditor receives.

Mirrors ``agent-core``'s ``evidence`` package. ``HashChainedAuditSink`` wraps
any ``AuditSink`` and adds three attributes to every event — ``chain.seq``,
``chain.prev`` (the previous event's hash; ``GENESIS`` for the first) and
``chain.hash`` = sha256(prev + canonical(event)) — so deleting, reordering or
rewriting a stored event breaks the chain. When a ``Signer`` is configured,
``chain.sig`` signs the hash so the writer is provable too. ``verify`` re-walks
a stored chain; ``evidence_bundle`` packages a run's events, the verification
result and OWASP ASI coverage as JSON or Markdown for a reviewer.

The canonical form is byte-compatible with the Java SDK's
``HashChainedAuditSink.canonical``: a flat sorted JSON object where event
attributes appear as ``attr.<key>`` and chain fields are excluded. Chains
written by either SDK verify in the other.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import threading
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

from .audit import AuditEvent, AuditSink
from .risk import AsiRisk

GENESIS = "0" * 64
CHAIN_SEQ = "chain.seq"
CHAIN_PREV = "chain.prev"
CHAIN_HASH = "chain.hash"
CHAIN_SIG = "chain.sig"
CHAIN_ALG = "chain.alg"
CHAIN_SIG_ERROR = "chain.sigError"
CHAIN_KEYS = frozenset({CHAIN_SEQ, CHAIN_PREV, CHAIN_HASH, CHAIN_SIG, CHAIN_ALG, CHAIN_SIG_ERROR})
_ATTR_PREFIX = "attr."


def _escape(s: str) -> str:
    """The same string escaping the Java ``JsonLite`` writer uses."""
    out = []
    for c in s:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c == "\n":
            out.append("\\n")
        elif c == "\r":
            out.append("\\r")
        elif c == "\t":
            out.append("\\t")
        elif c == "\b":
            out.append("\\b")
        elif c == "\f":
            out.append("\\f")
        elif ord(c) < 0x20:
            out.append(f"\\u{ord(c):04x}")
        else:
            out.append(c)
    return "".join(out)


def _instant_str(at: datetime) -> str:
    at = at.astimezone(timezone.utc)
    base = at.strftime("%Y-%m-%dT%H:%M:%S")
    micros = at.microsecond
    if micros == 0:
        return base + "Z"
    if micros % 1000 == 0:
        return f"{base}.{micros // 1000:03d}Z"
    return f"{base}.{micros:06d}Z"


def canonical(event: AuditEvent) -> str:
    """The canonical serialization that is hashed: a flat JSON object in
    sorted key order — every top-level field under its own name, every
    attribute under ``attr.<key>`` (chain fields excluded), values escaped as
    the Java writer escapes them. Byte-compatible across the two SDKs."""
    flat: dict[str, str] = {
        "id": str(event.id),
        "createdAt": _instant_str(event.created_at),
        "actorId": event.actor_id,
        "actionType": event.action_type,
        "targetType": event.target_type,
        "targetId": event.target_id,
        "outcome": event.outcome.name,
        "runId": event.context.run_id,
    }
    if event.detail is not None:
        flat["detail"] = event.detail
    for key, value in (
        ("tenantId", event.context.tenant_id),
        ("agentId", event.context.agent_id),
        ("sessionId", event.context.session_id),
        ("correlationId", event.context.correlation_id),
    ):
        if value is not None:
            flat[key] = value
    if event.risks:
        flat["risks"] = ",".join(sorted(r.id for r in event.risks))
    for key, value in event.attributes.items():
        if key not in CHAIN_KEYS:
            flat[_ATTR_PREFIX + key] = str(value)
    parts = ",".join(f'"{_escape(k)}":"{_escape(v)}"' for k, v in sorted(flat.items()))
    return "{" + parts + "}"


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class Signer(Protocol):
    """Signs the UTF-8 bytes of a chain hash. HMAC ships in the box; plug in
    Ed25519 via your crypto library for a non-repudiable trail."""

    @property
    def algorithm(self) -> str: ...

    def sign(self, data: bytes) -> bytes: ...


class Verifier(Protocol):
    def verify(self, data: bytes, signature: bytes) -> bool: ...


class HmacSha256:
    """HMAC-SHA256 signer/verifier — the symmetric option from the Java SDK.
    Anyone holding the key can also forge events; prefer an asymmetric signer
    where the audit consumer is untrusted."""

    algorithm = "HmacSHA256"

    def __init__(self, key: bytes) -> None:
        if not key:
            raise ValueError("key must not be empty")
        self._key = key

    def sign(self, data: bytes) -> bytes:
        return hmac.new(self._key, data, hashlib.sha256).digest()

    def verify(self, data: bytes, signature: bytes) -> bool:
        return hmac.compare_digest(self.sign(data), signature)


@dataclass(frozen=True)
class ChainVerification:
    """The result of ``verify``: ``intact`` means every event's hash and
    sequence recomputed cleanly; otherwise ``break_at_seq`` and ``detail``
    name where the chain stopped verifying."""

    intact: bool
    checked_events: int
    break_at_seq: int | None = None
    detail: str = ""


class HashChainedAuditSink:
    """Wraps a sink: every recorded event is sequenced, chained to the
    previous event's hash, optionally signed, then forwarded. Sequencing,
    hashing and signing happen under one lock so interleaved calls cannot
    fork the chain. Use ``resume`` to continue a chain a previous process
    started."""

    def __init__(
        self,
        delegate: AuditSink,
        signer: Signer | None = None,
        *,
        last_seq: int = 0,
        last_hash: str = GENESIS,
    ) -> None:
        if last_seq < 0:
            raise ValueError("last_seq must not be negative")
        if (last_seq == 0) != (last_hash == GENESIS):
            raise ValueError("seq 0 must pair with the GENESIS hash and vice versa")
        self._delegate = delegate
        self._signer = signer
        self._seq = last_seq
        self._head = last_hash
        self._lock = threading.Lock()

    @classmethod
    def resume(cls, delegate: AuditSink, signer: Signer | None, last_seq: int, last_hash: str):
        """Continues a chain a previous process wrote: seq and hash must come
        from storage you trust — otherwise the new process silently starts a
        fork."""
        return cls(delegate, signer, last_seq=last_seq, last_hash=last_hash)

    @property
    def head(self) -> str:
        return self._head

    @property
    def seq(self) -> int:
        return self._seq

    def record(self, event: AuditEvent) -> None:
        with self._lock:
            nxt = self._seq + 1
            prev = self._head
            digest = _sha256(prev + canonical(event))
            attributes = {k: v for k, v in event.attributes.items() if k not in CHAIN_KEYS}
            attributes[CHAIN_SEQ] = str(nxt)
            attributes[CHAIN_PREV] = prev
            attributes[CHAIN_HASH] = digest
            if self._signer is not None:
                try:
                    attributes[CHAIN_ALG] = self._signer.algorithm
                    sig = self._signer.sign(digest.encode("utf-8"))
                    attributes[CHAIN_SIG] = base64.b64encode(sig).decode("ascii")
                except Exception as exc:
                    # Keep the chain contiguous: the event is recorded unsigned
                    # and says so, rather than leaving a gap.
                    attributes[CHAIN_SIG_ERROR] = f"{type(exc).__name__}: {exc}"
            self._seq = nxt
            self._head = digest
        chained = AuditEvent(
            action_type=event.action_type,
            target_type=event.target_type,
            target_id=event.target_id,
            context=event.context,
            outcome=event.outcome,
            actor_id=event.actor_id,
            detail=event.detail,
            risks=event.risks,
            attributes=attributes,
            id=event.id,
            created_at=event.created_at,
        )
        self._delegate.record(chained)


def _broken(seq: int | None, checked: int, detail: str) -> ChainVerification:
    return ChainVerification(False, checked, break_at_seq=seq, detail=detail)


def verify(events: Sequence[AuditEvent], verifier: Verifier | None = None) -> ChainVerification:
    """Re-walks a stored chain: sequence numbers contiguous, each ``chain.prev``
    the previous hash, each ``chain.hash`` recomputed over the canonical form,
    and — when a verifier is given — every ``chain.sig`` valid. The first
    event is trusted only to start at seq 1 with the GENESIS prev."""
    expected_seq: int | None = None
    expected_prev = GENESIS
    checked = 0
    for event in events:
        attrs = event.attributes
        seq_text = attrs.get(CHAIN_SEQ)
        prev = attrs.get(CHAIN_PREV)
        hash_ = attrs.get(CHAIN_HASH)
        seq_for_report = expected_seq if expected_seq is not None else _parse_seq(seq_text, 1)
        if seq_text is None or prev is None or hash_ is None:
            return _broken(seq_for_report, checked, "missing chain attributes")
        seq = _parse_seq(seq_text, -1)
        if seq < 1:
            return _broken(seq_for_report, checked, f"invalid chain.seq {seq_text[:32]!r}")
        if expected_seq is None:
            expected_seq = seq
            expected_prev = GENESIS if seq == 1 else prev
        if seq != expected_seq:
            return _broken(expected_seq, checked, f"expected seq {expected_seq} but found {seq}")
        if prev != expected_prev:
            return _broken(seq, checked, "chain.prev does not match previous hash")
        if _sha256(prev + canonical(event)) != hash_:
            return _broken(seq, checked, "chain.hash does not match event content")
        if verifier is not None:
            sig = attrs.get(CHAIN_SIG)
            if sig is None:
                why = "signer failed when writing" if CHAIN_SIG_ERROR in attrs else "missing"
                return _broken(seq, checked, f"missing chain.sig ({why})")
            try:
                raw = base64.b64decode(sig, validate=True)
            except (binascii.Error, ValueError):
                return _broken(seq, checked, "chain.sig is not Base64")
            if not verifier.verify(hash_.encode("utf-8"), raw):
                return _broken(seq, checked, "signature invalid")
        expected_prev = hash_
        expected_seq = seq + 1
        checked += 1
    return ChainVerification(True, checked)


def _parse_seq(text: str | None, fallback: int) -> int:
    try:
        return int(text) if text is not None else fallback
    except ValueError:
        return -1


@dataclass(frozen=True)
class EvidenceBundle:
    """A run's chained audit events plus the verification result and the OWASP
    ASI items they exercised — the artifact a security review or an auditor
    opens first."""

    run_id: str
    generated_at: datetime
    event_count: int
    verification: ChainVerification
    asi_coverage: tuple[tuple[str, str, int], ...]  # (ASI id, title, event count)
    events_json: tuple[str, ...]  # each event's canonical form

    def to_dict(self) -> dict:
        return {
            "runId": self.run_id,
            "generatedAt": _instant_str(self.generated_at),
            "eventCount": self.event_count,
            "chainVerification": {
                "intact": self.verification.intact,
                "checkedEvents": self.verification.checked_events,
                "breakAtSeq": self.verification.break_at_seq,
                "detail": self.verification.detail,
            },
            "asiCoverage": [
                {"id": i, "title": t, "events": n} for i, t, n in self.asi_coverage
            ],
            "events": list(self.events_json),
        }

    def to_json(self) -> str:
        import json

        return json.dumps(self.to_dict(), indent=2)

    def to_markdown(self) -> str:
        v = self.verification
        lines = [
            f"# Evidence bundle — run {self.run_id}",
            "",
            f"- Generated: {_instant_str(self.generated_at)}",
            f"- Events: {self.event_count}",
            f"- Chain: {'intact' if v.intact else 'BROKEN'} ({v.checked_events} checked"
            + (f", break at seq {v.break_at_seq}: {v.detail}" if not v.intact else "")
            + ")",
            "",
            "## OWASP ASI coverage",
            "",
        ]
        if self.asi_coverage:
            lines += ["| Item | Title | Events |", "|---|---|---|"]
            lines += [f"| {i} | {t} | {n} |" for i, t, n in self.asi_coverage]
        else:
            lines.append("No ASI-tagged events.")
        return "\n".join(lines) + "\n"


def evidence_bundle(
    events: Sequence[AuditEvent],
    run_id: str,
    verifier: Verifier | None = None,
) -> EvidenceBundle:
    """Verifies the events' chain and folds them into a bundle for export."""
    verification = verify(events, verifier)
    counts: dict[AsiRisk, int] = {}
    for event in events:
        for risk in event.risks:
            counts[risk] = counts.get(risk, 0) + 1
    coverage = tuple(
        (risk.id, risk.title, counts.get(risk, 0)) for risk in AsiRisk if risk in counts
    )
    return EvidenceBundle(
        run_id=run_id,
        generated_at=datetime.now(timezone.utc),
        event_count=len(events),
        verification=verification,
        asi_coverage=coverage,
        events_json=tuple(canonical(e) for e in events),
    )
