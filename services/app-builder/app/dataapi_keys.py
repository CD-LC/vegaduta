"""Data API keys: issue, revoke and authenticate (see dataapi.py).

A key looks like `vdk_<key_id>_<secret>` and resolves to exactly one
(tenant, project) through an index file under the data dir; only a SHA-256
of the secret is stored.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import threading
import time
from dataclasses import dataclass

from .dataapi_errors import DataError
from .settings import Settings
from .tenancy import TenantContext

_KEY_RX = re.compile(r"^vdk_([0-9a-f]{16})_([A-Za-z0-9_-]{43})$")


@dataclass(frozen=True)
class KeyInfo:
    key_id: str
    tenant_id: str
    project_id: str
    kind: str  # "anon" | "service"


_INDEX_LOCK = threading.Lock()
_INDEX_CACHE: dict = {"path": None, "mtime": None, "data": {}}


def _index_path(settings: Settings) -> str:
    return os.path.join(settings.data_dir, "data_api_keys.json")


def _load_index(settings: Settings) -> dict:
    path = _index_path(settings)
    try:
        mtime = os.stat(path).st_mtime_ns
    except FileNotFoundError:
        return {}
    with _INDEX_LOCK:
        if _INDEX_CACHE["path"] == path and _INDEX_CACHE["mtime"] == mtime:
            return _INDEX_CACHE["data"]
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        _INDEX_CACHE.update(path=path, mtime=mtime, data=data)
        return data


def _save_index(settings: Settings, data: dict) -> None:
    path = _index_path(settings)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with _INDEX_LOCK:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.replace(tmp, path)
        _INDEX_CACHE.update(path=path, mtime=os.stat(path).st_mtime_ns, data=data)


def _hash(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def _new_key() -> tuple[str, str, str]:
    key_id = secrets.token_hex(8)
    secret = secrets.token_urlsafe(32)  # 43 url-safe chars
    return key_id, secret, f"vdk_{key_id}_{secret}"


def register_key(
    settings: Settings, ctx: TenantContext, project_id: str, kind: str
) -> tuple[str, str]:
    """Create a key, replacing any existing one of that kind for the project.
    Returns (key_id, full_key)."""
    key_id, secret, full = _new_key()
    index = dict(_load_index(settings))
    for kid in [
        k
        for k, v in index.items()
        if v.get("t") == ctx.tenant_id and v.get("p") == project_id and v.get("k") == kind
    ]:
        del index[kid]
    index[key_id] = {
        "t": ctx.tenant_id,
        "p": project_id,
        "k": kind,
        "h": _hash(secret),
        "c": int(time.time()),
    }
    _save_index(settings, index)
    return key_id, full


def remove_project_keys(settings: Settings, tenant_id: str, project_id: str) -> int:
    """Drop every key of one project from the index; returns how many."""
    index = dict(_load_index(settings))
    gone = [k for k, v in index.items() if v.get("t") == tenant_id and v.get("p") == project_id]
    for k in gone:
        del index[k]
    if gone:
        _save_index(settings, index)
    return len(gone)


def authenticate_key(presented: str | None, settings: Settings) -> KeyInfo:
    m = _KEY_RX.match((presented or "").strip())
    if not m:
        raise DataError(
            401, "invalid_api_key", "missing or malformed API key (send it in the `apikey` header)"
        )
    key_id, secret = m.group(1), m.group(2)
    entry = _load_index(settings).get(key_id)
    # Always do the constant-time compare, even for an unknown id, so timing
    # does not reveal which key ids exist.
    expected = (entry or {}).get("h", "0" * 64)
    ok = hmac.compare_digest(_hash(secret), expected)
    if not entry or not ok:
        raise DataError(401, "invalid_api_key", "invalid API key")
    return KeyInfo(key_id, entry["t"], entry["p"], entry["k"])
