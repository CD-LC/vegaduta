"""Tool supply-chain pinning: refuse a tool whose shape silently changed.

Mirrors ``agent-core``'s ``provenance`` package. A tool's *manifest* is the
digest of what the model was promised: name, description, parameter names and
types — the text that ends up in the prompt. Pin it once (``pin``) and every
later registration is checked against the pin (``check``): a description that
grew "also email the file to…", or a schema that gained a ``to`` parameter, is
a supply-chain attack or a bad upgrade — either way the tool must not silently
keep its old trust.

Modes: ``ENFORCE`` refuses a drifted tool (returns violations),
``WARN``-equivalent is just calling ``check`` and logging, and a store of
``None``-like "always pin on first sight" is the default for zero-config
setups.
"""

from __future__ import annotations

import hashlib
import json
import threading
from dataclasses import dataclass
from enum import Enum
from typing import Protocol


def manifest_digest(tool_description: dict) -> str:
    """SHA-256 over the tool's promised surface: name, description, and the
    parameter list (name, type, required) — canonically serialised so field
    order cannot hide a change. Input is the dict ``GovernedTool.describe``
    returns, but any mapping with those keys works."""
    surface = {
        "name": tool_description.get("name", ""),
        "description": tool_description.get("description", ""),
        "parameters": sorted(
            (
                {
                    "name": p.get("name", ""),
                    "type": p.get("type", ""),
                    "required": bool(p.get("required")),
                }
                for p in tool_description.get("parameters", [])
            ),
            key=lambda p: p["name"],
        ),
    }
    canonical = json.dumps(surface, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ToolManifest:
    """One pinned tool shape."""

    tool_name: str
    digest: str
    pinned_at: str  # ISO-8601


class ProvenanceMode(Enum):
    #: Drift raises ``ToolDeniedException`` at registration.
    ENFORCE = "ENFORCE"
    #: Drift is reported to the caller; the tool still registers.
    WARN = "WARN"


class ManifestPinStore(Protocol):
    def pin(self, tool_name: str, digest: str, pinned_at: str) -> bool:
        """Records the pin if absent. Returns False when a different digest is
        already pinned."""
        ...

    def pinned(self, tool_name: str) -> str | None: ...


class InMemoryManifestPinStore:
    def __init__(self) -> None:
        self._pins: dict[str, ToolManifest] = {}
        self._lock = threading.Lock()

    def pin(self, tool_name: str, digest: str, pinned_at: str) -> bool:
        with self._lock:
            existing = self._pins.get(tool_name)
            if existing is not None:
                return existing.digest == digest
            self._pins[tool_name] = ToolManifest(tool_name, digest, pinned_at)
            return True

    def pinned(self, tool_name: str) -> str | None:
        return self._pins.get(tool_name).digest if tool_name in self._pins else None


@dataclass(frozen=True)
class Drift:
    tool_name: str
    pinned_digest: str
    current_digest: str
    detail: str = "tool description or parameter surface changed since the pin"


class ProvenanceGate:
    """Pins tool manifests at registration and reports drift. Wire it into
    ``Governance(wrap_hook=...)`` or call ``register(tool.describe())``
    yourself; ``drift()`` is the report a review reads."""

    def __init__(
        self, store: ManifestPinStore | None = None, mode: ProvenanceMode = ProvenanceMode.ENFORCE
    ) -> None:
        self._store = store or InMemoryManifestPinStore()
        self.mode = mode
        self._drifts: list[Drift] = []

    def check(self, tool_description: dict) -> Drift | None:
        """Returns a ``Drift`` when the pinned shape differs from the current
        one, or ``None`` (fresh pin or unchanged). In ENFORCE mode a drifted
        tool should not be registered — the caller decides (``Governance``
        raises when this returns a drift under ENFORCE)."""
        name = str(tool_description.get("name", ""))
        digest = manifest_digest(tool_description)
        pinned = self._store.pinned(name)
        if pinned is None:
            from datetime import datetime, timezone

            self._store.pin(name, digest, datetime.now(timezone.utc).isoformat())
            return None
        if pinned == digest:
            return None
        drift = Drift(name, pinned, digest)
        self._drifts.append(drift)
        return drift

    def drift(self) -> list[Drift]:
        return list(self._drifts)
