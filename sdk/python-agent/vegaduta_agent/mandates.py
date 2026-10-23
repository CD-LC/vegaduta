"""Delegated mandates: authority that can only narrow.

Mirrors ``io.vegaduta.agent.api.mandate``. One ``Mandate`` is a link of a
delegation chain: the authority under which an agent acts, handed to it by the
previous hop. A mandate can only be *attenuated*, never widened — a derived
mandate names a subset of the parent's tools, a risk ceiling at or below the
parent's, an expiry at or before the parent's. Every mandate carries a
SHA-256 digest over its canonical fields plus the parent's digest, so a chain
can be re-verified by anyone holding the whole chain (``verify_chain``).

Mandates are in-process capabilities, not tokens: they carry no signature and
prove nothing to a remote party. They answer "under whose mandate did hop 4
act, and did it exceed hop 2's authority" inside one trust domain, where JWTs
cannot be attenuated offline. The wire encoding and digest use the same
canonical form as the Java SDK, so a chain verified in one language verifies
in the other.
"""

from __future__ import annotations

import hashlib
import uuid
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timezone
from urllib.parse import quote_plus, unquote_plus

from .context import RunContext
from .gate import Verdict
from .risk import RiskLabel, ToolClassification
from .tool_call import ToolCall

ATTRIBUTE = "vegaduta.mandate"

MAX_TEXT = 512
MAX_TOOLS = 256

_KEYS = {"id", "iss", "sub", "prn", "tools", "risk", "depth", "maxDepth", "exp", "parent", "digest"}


def _glob_matches(pattern: str, value: str) -> bool:
    """``*`` matches any run of characters. Linear two-pointer with
    backtracking to the last star only, so a hostile pattern cannot stall the
    gate."""
    pi = vi = 0
    star_p = star_v = -1
    while vi < len(value):
        if pi < len(pattern) and pattern[pi] == "*":
            star_p = pi
            pi += 1
            star_v = vi
        elif pi < len(pattern) and pattern[pi] == value[vi]:
            pi += 1
            vi += 1
        elif star_p >= 0:
            pi = star_p + 1
            star_v += 1
            vi = star_v
        else:
            return False
    while pi < len(pattern) and pattern[pi] == "*":
        pi += 1
    return pi == len(pattern)


def _url_encode(value: str) -> str:
    """Java ``URLEncoder`` equivalence: unreserved ``[a-zA-Z0-9.*_-]`` pass
    through, space becomes ``+``, everything else percent-encoded."""
    return quote_plus(value, safe="*-._")


def _url_decode(value: str) -> str:
    return unquote_plus(value)


def _instant_str(at: datetime) -> str:
    """``Instant.toString()`` equivalence: UTC, ``Z`` suffix, fractional
    seconds only when nonzero (3 or 6 digits, matching Java's 3/6/9 grouping)."""
    at = at.astimezone(timezone.utc)
    base = at.strftime("%Y-%m-%dT%H:%M:%S")
    micros = at.microsecond
    if micros == 0:
        return base + "Z"
    if micros % 1000 == 0:
        return f"{base}.{micros // 1000:03d}Z"
    return f"{base}.{micros:06d}Z"


def _parse_instant(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _require_text(value: str | None, name: str) -> str:
    if not value or not value.strip():
        raise ValueError(f"{name} must not be blank")
    if len(value) > MAX_TEXT:
        raise ValueError(f"{name} is longer than {MAX_TEXT} characters")
    if any(ord(c) < 0x20 or ord(c) == 0x7F for c in value):
        raise ValueError(f"{name} must not contain control characters")
    return value


@dataclass(frozen=True)
class Mandate:
    id: str
    issuer: str
    subject: str
    principal: str | None
    allowed_tools: frozenset[str]
    max_risk: RiskLabel
    depth: int
    max_depth: int
    expires_at: datetime
    parent_id: str | None
    digest: str

    # -- construction ------------------------------------------------------

    @classmethod
    def root(
        cls,
        issuer: str,
        subject: str,
        *,
        allowed_tools: Iterable[str],
        max_risk: RiskLabel = RiskLabel.MEDIUM,
        max_depth: int = 8,
        expires_at: datetime,
        principal: str | None = None,
        id: str | None = None,
    ) -> Mandate:
        """Builds a root mandate (depth 0, no parent)."""
        return cls._build(
            id or str(uuid.uuid4()),
            issuer,
            subject,
            principal,
            frozenset(allowed_tools),
            max_risk,
            0,
            max_depth,
            expires_at,
            None,
            None,
        )

    @classmethod
    def _build(
        cls,
        id: str,
        issuer: str,
        subject: str,
        principal: str | None,
        allowed_tools: frozenset[str],
        max_risk: RiskLabel,
        depth: int,
        max_depth: int,
        expires_at: datetime,
        parent_id: str | None,
        parent_digest: str | None,
    ) -> Mandate:
        _require_text(id, "id")
        _require_text(issuer, "issuer")
        _require_text(subject, "subject")
        if principal is not None:
            _require_text(principal, "principal")
        if len(allowed_tools) > MAX_TOOLS:
            raise ValueError(f"a mandate may carry at most {MAX_TOOLS} tool patterns")
        for tool in allowed_tools:
            _require_text(tool, "allowed tool pattern")
        if depth < 0 or max_depth < 0 or depth > max_depth:
            raise ValueError(f"depth {depth} exceeds max_depth {max_depth}")
        partial = cls(
            id=id,
            issuer=issuer,
            subject=subject,
            principal=principal,
            allowed_tools=allowed_tools,
            max_risk=max_risk,
            depth=depth,
            max_depth=max_depth,
            expires_at=expires_at,
            parent_id=parent_id,
            digest="",
        )
        return cls(
            id=partial.id,
            issuer=partial.issuer,
            subject=partial.subject,
            principal=partial.principal,
            allowed_tools=partial.allowed_tools,
            max_risk=partial.max_risk,
            depth=partial.depth,
            max_depth=partial.max_depth,
            expires_at=partial.expires_at,
            parent_id=partial.parent_id,
            digest=_sha256(partial._canonical(parent_digest)),
        )

    def derive(
        self,
        new_subject: str,
        narrower_tools: Iterable[str],
        lower_or_equal_risk: RiskLabel,
        earlier_or_equal_expiry: datetime,
    ) -> Mandate:
        """Hands a narrower mandate to the next agent. Issued by this
        mandate's subject, keeps principal and max_depth, only attenuates."""
        narrower = frozenset(narrower_tools)
        if self.depth + 1 > self.max_depth:
            raise ValueError(f"mandate {self.id} is at depth {self.depth} of {self.max_depth}; cannot derive")
        self._check_attenuation(narrower, lower_or_equal_risk, earlier_or_equal_expiry)
        return Mandate._build(
            str(uuid.uuid4()),
            self.subject,
            new_subject,
            self.principal,
            narrower,
            lower_or_equal_risk,
            self.depth + 1,
            self.max_depth,
            earlier_or_equal_expiry,
            self.id,
            self.digest,
        )

    def _check_attenuation(self, tools: frozenset[str], risk: RiskLabel, expiry: datetime) -> None:
        for tool in tools:
            if not self._covers(tool):
                raise ValueError(f"derived tool {tool!r} is not covered by mandate {self.id}'s allowed tools")
        if risk > self.max_risk:
            raise ValueError(
                f"derived maxRisk {risk.name} exceeds parent {self.max_risk.name} of mandate {self.id}"
            )
        if expiry > self.expires_at:
            raise ValueError(f"derived expiry exceeds the expiry of mandate {self.id}")

    # -- checks ------------------------------------------------------------

    def permits(self, tool_name: str) -> bool:
        """True when ``tool_name`` matches an allowed pattern (exact or glob)."""
        return any(_glob_matches(pattern, tool_name) for pattern in self.allowed_tools)

    def _covers(self, pattern: str) -> bool:
        """True when every tool name ``pattern`` could match is also permitted
        here: an exact name must be permitted; a child glob is covered when it
        matches a parent pattern taken literally."""
        return self.permits(pattern)

    def is_expired(self, now: datetime | None = None) -> bool:
        return (now or datetime.now(timezone.utc)) > self.expires_at

    def is_self_consistent(self) -> bool:
        """True for a root mandate whose digest matches its own fields."""
        return self.depth == 0 and self.digest == _sha256(self._canonical(None))

    def _canonical(self, parent_digest: str | None) -> str:
        """The digested form, byte-identical to the Java ``Mandate.canonical``:
        every free-text value is URL-encoded so no value can contain the field
        separator (``\\n``) or tool separator (``,``)."""
        tools = ",".join(_url_encode(t) for t in sorted(self.allowed_tools))
        return (
            f"id={_url_encode(self.id)}\n"
            f"issuer={_url_encode(self.issuer)}\n"
            f"subject={_url_encode(self.subject)}\n"
            f"principal={'' if self.principal is None else _url_encode(self.principal)}\n"
            f"tools={tools}\n"
            f"maxRisk={self.max_risk.name}\n"
            f"depth={self.depth}\n"
            f"maxDepth={self.max_depth}\n"
            f"expiresAt={_instant_str(self.expires_at)}\n"
            f"parentId={'' if self.parent_id is None else _url_encode(self.parent_id)}\n"
            f"parentDigest={'' if parent_digest is None else parent_digest}\n"
        )

    # -- wire form -----------------------------------------------------------

    def encode(self) -> str:
        """The ``key=value;`` form, URL-encoded values — the same layout the
        Java SDK uses, so encodings interop."""
        parts = [
            f"id={_url_encode(self.id)}",
            f"iss={_url_encode(self.issuer)}",
            f"sub={_url_encode(self.subject)}",
        ]
        if self.principal is not None:
            parts.append(f"prn={_url_encode(self.principal)}")
        tools = ",".join(_url_encode(t) for t in sorted(self.allowed_tools))
        parts.append(f"tools={tools}")
        parts.append(f"risk={self.max_risk.name}")
        parts.append(f"depth={self.depth}")
        parts.append(f"maxDepth={self.max_depth}")
        parts.append(f"exp={_instant_str(self.expires_at)}")
        if self.parent_id is not None:
            parts.append(f"parent={_url_encode(self.parent_id)}")
        parts.append(f"digest={_url_encode(self.digest)}")
        return ";".join(parts) + ";"

    @classmethod
    def decode(cls, encoded: str) -> Mandate:
        """Reverses ``encode``. Strict: a duplicate or unknown key is rejected.
        Verifies the digest against the encoded fields only; a parent link needs
        the chain (``verify_chain``)."""
        fields: dict[str, str] = {}
        for pair in encoded.split(";"):
            if not pair:
                continue
            key, sep, value = pair.partition("=")
            if not sep:
                raise ValueError("mandate encoding has a pair without '='")
            if key not in _KEYS:
                raise ValueError(f"unknown mandate key {key!r}")
            if key in fields:
                raise ValueError(f"duplicate mandate key {key!r}")
            fields[key] = _url_decode(value)
        for required in ("id", "iss", "sub", "tools", "risk", "depth", "maxDepth", "exp", "digest"):
            if required not in fields:
                raise ValueError(f"mandate encoding lacks '{required}'")
        tools = frozenset(t for t in fields["tools"].split(",") if t)
        return cls(
            id=fields["id"],
            issuer=fields["iss"],
            subject=fields["sub"],
            principal=fields.get("prn"),
            allowed_tools=tools,
            max_risk=RiskLabel[fields["risk"]],
            depth=int(fields["depth"]),
            max_depth=int(fields["maxDepth"]),
            expires_at=_parse_instant(fields["exp"]),
            parent_id=fields.get("parent"),
            digest=fields["digest"],
        )


def attach(context: RunContext, mandate: Mandate) -> RunContext:
    """A copy of ``context`` carrying the encoded leaf mandate."""
    attrs = dict(context.attributes)
    attrs[ATTRIBUTE] = mandate.encode()
    return context.with_attrs(attributes=attrs)


def from_context(context: RunContext) -> Mandate | None:
    """The mandate carried by ``context``, if any. Raises ``ValueError`` when
    the attribute is present but malformed."""
    encoded = context.attributes.get(ATTRIBUTE)
    return None if encoded is None else Mandate.decode(encoded)


def verify_chain(root_to_leaf: list[Mandate]) -> Mandate:
    """Re-verifies a chain ordered root to leaf: digests recomputed from the
    canonical fields and each parent's digest, contiguous parent links and
    depths, attenuation at every hop, fixed principal and max_depth. Returns
    the leaf for convenience; raises ``ValueError`` naming the first
    violation."""
    if not root_to_leaf:
        raise ValueError("mandate chain is empty")
    root = root_to_leaf[0]
    if root.depth != 0 or root.parent_id is not None:
        raise ValueError(f"chain does not start at a root mandate: {root.id}")
    if not root.is_self_consistent():
        raise ValueError(f"mandate {root.id} digest does not match its fields")
    parent = root
    for child in root_to_leaf[1:]:
        if child.parent_id != parent.id:
            raise ValueError(f"mandate {child.id} names parent {child.parent_id} but follows {parent.id}")
        if child.depth != parent.depth + 1:
            raise ValueError(f"mandate {child.id} has depth {child.depth} after {parent.depth}")
        if child.digest != _sha256(child._canonical(parent.digest)):
            raise ValueError(f"mandate {child.id} digest does not match its fields")
        if child.principal != parent.principal or child.max_depth != parent.max_depth:
            raise ValueError(f"mandate {child.id} changed principal or maxDepth")
        parent._check_attenuation(child.allowed_tools, child.max_risk, child.expires_at)
        parent = child
    return root_to_leaf[-1]


class MandateGate:
    """A ``ToolDispatchGate`` enforcing the mandate carried in the run context.
    Missing mandates deny only when ``require_mandate``; otherwise the check
    defers to the delegate. A malformed mandate always denies."""

    REASON_MISSING = "mandate:missing"
    REASON_MALFORMED = "mandate:malformed"
    REASON_EXPIRED = "mandate:expired"
    REASON_TOOL_NOT_PERMITTED = "mandate:tool-not-permitted"
    REASON_RISK_EXCEEDED = "mandate:risk-exceeded"
    REASON_DIGEST_MISMATCH = "mandate:digest-mismatch"
    REASON_SUBJECT_MISMATCH = "mandate:subject-mismatch"

    def __init__(self, require_mandate: bool = False, delegate=None) -> None:
        self.require_mandate = require_mandate
        self._delegate = delegate

    def evaluate(self, call: ToolCall, classification: ToolClassification, context: RunContext) -> Verdict:
        try:
            mandate = from_context(context)
        except ValueError:
            return Verdict.deny(self.REASON_MALFORMED)
        if mandate is None:
            if self.require_mandate:
                return Verdict.deny(self.REASON_MISSING)
            return self._next(call, classification, context)
        if mandate.depth == 0 and not mandate.is_self_consistent():
            return Verdict.deny(f"{self.REASON_DIGEST_MISMATCH}:{mandate.id[:64]}")
        if context.agent_id and context.agent_id != mandate.subject:
            return Verdict.deny(f"{self.REASON_SUBJECT_MISMATCH}:{mandate.id[:64]}")
        if mandate.is_expired():
            return Verdict.deny(f"{self.REASON_EXPIRED}:{mandate.id[:64]}")
        if not mandate.permits(call.tool_name):
            return Verdict.deny(f"{self.REASON_TOOL_NOT_PERMITTED}:{call.tool_name}")
        if classification.risk > mandate.max_risk:
            return Verdict.deny(
                f"{self.REASON_RISK_EXCEEDED}:{classification.risk.name}>{mandate.max_risk.name}"
            )
        return self._next(call, classification, context)

    def _next(self, call, classification, context) -> Verdict:
        if self._delegate is None:
            return Verdict.allow()
        return self._delegate.evaluate(call, classification, context)
