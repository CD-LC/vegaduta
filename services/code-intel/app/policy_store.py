"""Per-tenant policy persistence + service defaults.

A tenant's guardrail policy is stored in its own subtree (policy.json). If
none is set, a conservative default is used: execution off, writes on but
approval-gated (test_gated). Operators raise autonomy deliberately, per
tenant — never a global flag flip.
"""

from __future__ import annotations

import json
import os

from .policy import Policy
from .settings import Settings


def _policy_path(tenant_dir: str) -> str:
    return os.path.join(tenant_dir, "policy.json")


def load_policy(tenant_dir: str, settings: Settings) -> Policy:
    path = _policy_path(tenant_dir)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            p = Policy.from_dict(json.load(f))
    else:
        p = _default_policy(settings)
    # The service-level exec kill-switch always wins: even an autopilot
    # tenant policy cannot enable execution if the operator disabled it
    # for the whole service (Codex-style managed layer overriding project).
    if not settings.allow_exec:
        p.exec_enabled = False
    return p


def save_policy(tenant_dir: str, policy: Policy) -> Policy:
    path = _policy_path(tenant_dir)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(policy.to_dict(), f, indent=2)
    os.replace(tmp, path)
    return policy


def _default_policy(settings: Settings) -> Policy:
    return Policy(
        exec_enabled=settings.allow_exec,   # follows the service kill-switch
        apply_enabled=True,
        mode="test_gated",                  # writes need a human by default
    )
