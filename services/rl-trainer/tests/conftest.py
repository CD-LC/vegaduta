"""Shared fixtures for the rl-trainer suite.

Puts rl-trainer/ on sys.path so tests can `from app.x import y` regardless of
where pytest is invoked from (same pattern as sidecar/tests/conftest.py).

Every test runs with the service's env vars scrubbed and the module-level
caches (the lazily built Anthropic client, the JWKS client cache) reset, so
the suite is deterministic and never touches the network or a real key.
"""

from __future__ import annotations

import dataclasses
import sys
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import auth, llm
from app.settings import Settings

_ENV_VARS = (
    "RL_TRAINER_AUTH_MODE",
    "RL_TRAINER_INTERNAL_TOKEN",
    "RL_TRAINER_KEYCLOAK_ISSUER",
    "RL_TRAINER_KEYCLOAK_JWKS_URL",
    "RL_TRAINER_KEYCLOAK_AUDIENCE",
    "RL_TRAINER_KEYCLOAK_ALLOWED_AZP",
    "RL_TRAINER_LLM_MODEL",
    "RL_TRAINER_LLM_MAX_TOKENS",
    "RL_TRAINER_DATA_DIR",
    "RL_TRAINER_CORS_ORIGINS",
    "ANTHROPIC_API_KEY",
)


@pytest.fixture(autouse=True)
def _isolated(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    for name in _ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(llm, "_CLIENT", None)
    auth._JWKS_CACHE.clear()
    yield
    auth._JWKS_CACHE.clear()


@pytest.fixture
def make_settings() -> Callable[..., Settings]:
    """Build a Settings from a scrubbed environment plus explicit overrides."""

    def _make(**overrides: Any) -> Settings:
        return dataclasses.replace(Settings(), **overrides)

    return _make
