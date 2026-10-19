"""Consumer-contract helpers (standard library only, Python 3.9+).

Shared by the consumer-side tests of the generated Python SDK (sdk/python/tests/test_contracts.py)
and of the Python Agent SDK (sdk/python-agent/tests/test_contracts.py). The Node twin is
consumer-contract.mjs next to this file; keep the two in step.

A consumer contract is contracts/consumers/<consumer>/<area>.contract.json::

    {"consumer", "provider": "core",
     "interactions": [{"id", "method", "path" (OpenAPI templated), "status",
                       "request":  {"requiredFields": ["$.name", ...]},
                       "response": {"requiredFields": ["$.id", "$.items[*].id", ...]}}]}

The consumer side checks that every interaction exists in docs/api/openapi.json with its fields
declared, and that the client never calls anything that is not an interaction. Whether core really
answers with those fields is the provider verifier's job (core/app).

``validate`` is a deliberately small JSON Schema subset (type, enum, required, properties,
additionalProperties: false, minimum, format uuid/date-time) - enough for contracts/outbound.
"""

from __future__ import annotations

import json
import re
import uuid
from collections.abc import Iterable
from datetime import datetime
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
CONSUMERS_DIR = REPO_ROOT / "contracts" / "consumers"
OUTBOUND_DIR = REPO_ROOT / "contracts" / "outbound"
SPEC_PATH = REPO_ROOT / "docs" / "api" / "openapi.json"

_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}
_FIELD_RE = re.compile(r"^\$(?:\.[A-Za-z_][\w-]*|\[\*\])*$")
_TOKEN_RE = re.compile(r"\.[A-Za-z_][\w-]*|\[\*\]")


def load_contracts(consumer: str) -> list[tuple[Path, dict[str, Any]]]:
    folder = CONSUMERS_DIR / consumer
    if not folder.is_dir():
        return []
    return [(p, json.loads(p.read_text(encoding="utf-8"))) for p in sorted(folder.glob("*.contract.json"))]


def interactions_of(consumer: str) -> list[dict[str, Any]]:
    return [it for _, doc in load_contracts(consumer) for it in doc.get("interactions", [])]


def shape_errors(doc: dict[str, Any], consumer: str) -> list[str]:
    errs: list[str] = []
    if doc.get("consumer") != consumer:
        errs.append(f"consumer is {doc.get('consumer')!r}, expected {consumer}")
    if doc.get("provider") != "core":
        errs.append('provider must be "core"')
    its = doc.get("interactions")
    if not isinstance(its, list) or not its:
        errs.append("interactions must be a non-empty array")
        its = []
    ids = set()
    for it in its:
        where = f"interaction {it.get('id')!r}"
        if not isinstance(it.get("id"), str) or not it.get("id"):
            errs.append(f"{where}: id is required")
        if it.get("id") in ids:
            errs.append(f"{where}: duplicate id")
        ids.add(it.get("id"))
        if it.get("method") not in _METHODS:
            errs.append(f"{where}: method {it.get('method')} is not an HTTP method")
        if not isinstance(it.get("path"), str) or not it["path"].startswith("/"):
            errs.append(f"{where}: path must start with /")
        if not isinstance(it.get("status"), int) or isinstance(it.get("status"), bool):
            errs.append(f"{where}: status must be an integer")
        for side in ("request", "response"):
            fields = (it.get(side) or {}).get("requiredFields")
            if not isinstance(fields, list):
                errs.append(f"{where}: {side}.requiredFields must be an array")
                continue
            errs.extend(
                f"{where}: {side} field {f} is not $.a / $.a[*].b" for f in fields if not _FIELD_RE.match(f)
            )
    return errs


def load_spec() -> dict[str, Any]:
    return json.loads(SPEC_PATH.read_text(encoding="utf-8"))


DRIFT_PATH = CONSUMERS_DIR / "tools" / "spec-status-drift.json"


def load_status_drift() -> list[dict[str, Any]]:
    """Known wrong success statuses in the spec (see spec-status-drift.json)."""
    if not DRIFT_PATH.is_file():
        return []
    return json.loads(DRIFT_PATH.read_text(encoding="utf-8")).get("drift", [])


def status_drift_problems(spec: dict[str, Any], drift: list[dict[str, Any]] | None = None) -> list[str]:
    """Problems with the drift list: an entry the spec has caught up with, or whose proof is gone."""
    problems: list[str] = []
    for d in load_status_drift() if drift is None else drift:
        where = f"spec-status-drift {d.get('method')} {d.get('path')}"
        op = (spec.get("paths", {}).get(d.get("path")) or {}).get(str(d.get("method")).lower())
        if not op:
            problems.append(f"{where}: operation is not in docs/api/openapi.json")
        else:
            responses = op.get("responses") or {}
            if str(d.get("coreStatus")) in responses:
                problems.append(f"{where}: the spec now documents {d.get('coreStatus')}; remove the entry")
            if str(d.get("specStatus")) not in responses:
                problems.append(f"{where}: the spec no longer documents {d.get('specStatus')}")
        src = REPO_ROOT / (d.get("source") or "")
        if not d.get("source") or not src.is_file():
            problems.append(f"{where}: source {d.get('source')} does not exist")
        elif d.get("sourceContains", "\0") not in src.read_text(encoding="utf-8"):
            proof = d.get("coreStatus")
            problems.append(f"{where}: {d['source']} no longer contains the line proving {proof}")
    return problems


def _deref(spec: dict[str, Any], schema: Any) -> dict[str, Any]:
    s = schema or {}
    seen = set()
    while isinstance(s, dict) and "$ref" in s:
        ref = s["$ref"]
        if ref in seen:
            return {}
        seen.add(ref)
        node: Any = spec
        for part in ref.lstrip("#/").split("/"):
            node = node.get(part, {}) if isinstance(node, dict) else {}
        s = node
    return s if isinstance(s, dict) else {}


def _untyped(s: dict[str, Any]) -> bool:
    return not any(k in s for k in ("type", "properties", "items", "allOf", "oneOf", "anyOf", "$ref"))


def resolve_field(spec: dict[str, Any], schema: Any, field: str) -> str:
    """ "ok", "untyped" (the schema is {} so the spec cannot say) or "missing"."""
    candidates = [_deref(spec, schema)]
    for tok in _TOKEN_RE.findall(field[1:]):
        nxt: list[Any] = []
        for raw in candidates:
            s = _deref(spec, raw)
            if _untyped(s):
                return "untyped"
            variants = [s] + [_deref(spec, v) for k in ("allOf", "oneOf", "anyOf") for v in s.get(k, [])]
            for v in variants:
                if tok == "[*]":
                    if v.get("type") == "array" and "items" in v:
                        nxt.append(v["items"])
                else:
                    name = tok[1:]
                    if name in (v.get("properties") or {}):
                        nxt.append(v["properties"][name])
                    elif isinstance(v.get("additionalProperties"), dict):
                        nxt.append(v["additionalProperties"])
        if not nxt:
            return "missing"
        candidates = nxt
    return "ok"


def check_against_spec(
    spec: dict[str, Any], it: dict[str, Any], drift: list[dict[str, Any]] | None = None
) -> tuple[list[str], list[str]]:
    """(problems, untyped) for one interaction against the OpenAPI document."""
    drift = load_status_drift() if drift is None else drift
    problems: list[str] = []
    untyped: list[str] = []
    op = (spec.get("paths", {}).get(it["path"]) or {}).get(it["method"].lower())
    if not op:
        return [f"{it['method']} {it['path']} is not in docs/api/openapi.json"], untyped
    req_schema = (((op.get("requestBody") or {}).get("content") or {}).get("application/json") or {}).get(
        "schema"
    )
    for f in it["request"]["requiredFields"]:
        if req_schema is None:
            problems.append(f"request field {f}: operation has no JSON request body")
            continue
        r = resolve_field(spec, req_schema, f)
        if r == "missing":
            problems.append(f"request field {f} is not declared in the request schema")
        elif r == "untyped":
            untyped.append(f"request {f}")
    responses = op.get("responses") or {}
    resp = responses.get(str(it["status"]))
    if resp is None:
        # core sends it["status"] but the reflection-built spec documents another (spec-status-drift.json)
        d = next(
            (
                x
                for x in drift
                if x.get("method") == it["method"]
                and x.get("path") == it["path"]
                and x.get("coreStatus") == it["status"]
            ),
            None,
        )
        resp = responses.get(str(d["specStatus"])) if d else None
    if resp is None:
        problems.append(f"status {it['status']} is not a documented response")
    resp_schema = ((_deref(spec, resp).get("content") or {}).get("application/json") or {}).get("schema")
    for f in it["response"]["requiredFields"]:
        if resp_schema is None:
            problems.append(f"response field {f}: response {it['status']} has no JSON body")
            continue
        r = resolve_field(spec, resp_schema, f)
        if r == "missing":
            problems.append(f"response field {f} is not declared in the response schema")
        elif r == "untyped":
            untyped.append(f"response {f}")
    return problems, untyped


def path_matches(template: str, concrete: str) -> bool:
    parts = re.split(r"(\{[^}]+\})", template)
    pattern = "".join("[^/]+" if p.startswith("{") else re.escape(p) for p in parts)
    return re.fullmatch(pattern, concrete) is not None


def find_interaction(interactions: Iterable[dict[str, Any]], method: str, path: str) -> dict[str, Any] | None:
    return next(
        (it for it in interactions if it["method"] == method and path_matches(it["path"], path)), None
    )


def has_field(value: Any, field: str) -> bool:
    nodes = [value]
    for tok in _TOKEN_RE.findall(field[1:]):
        nxt: list[Any] = []
        for n in nodes:
            if tok == "[*]":
                if isinstance(n, list):
                    nxt.extend(n)
            elif isinstance(n, dict) and tok[1:] in n:
                nxt.append(n[tok[1:]])
        if not nxt:
            return False
        nodes = nxt
    return True


def code_blocks(markdown: str, langs: Iterable[str]) -> list[str]:
    wanted = set(langs)
    return [
        m.group(2)
        for m in re.finditer(r"^```(\w+)\n(.*?)^```", markdown, re.M | re.S)
        if m.group(1) in wanted
    ]


# --- a small JSON Schema subset ----------------------------------------------------------------

_TYPES = {
    "string": lambda v: isinstance(v, str),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
}


def _format_ok(fmt: str, value: str) -> bool:
    try:
        if fmt == "uuid":
            uuid.UUID(value)
        elif fmt == "date-time":
            datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return True


def validate(schema: dict[str, Any], value: Any, where: str = "$") -> list[str]:
    """Errors of ``value`` against a JSON Schema subset (empty when valid)."""
    errs: list[str] = []
    types = schema.get("type")
    if types is not None:
        allowed = types if isinstance(types, list) else [types]
        if not any(_TYPES[t](value) for t in allowed):
            return [f"{where}: {value!r} is not {'/'.join(allowed)}"]
    if "enum" in schema and value not in schema["enum"]:
        errs.append(f"{where}: {value!r} is not one of {schema['enum']}")
    if isinstance(value, str) and "format" in schema and not _format_ok(schema["format"], value):
        errs.append(f"{where}: {value!r} is not a {schema['format']}")
    if _TYPES["number"](value) and "minimum" in schema and value < schema["minimum"]:
        errs.append(f"{where}: {value} < minimum {schema['minimum']}")
    if isinstance(value, dict):
        props = schema.get("properties") or {}
        errs.extend(f"{where}: missing required {k}" for k in schema.get("required", []) if k not in value)
        for k, v in value.items():
            if k in props:
                errs.extend(validate(props[k], v, f"{where}.{k}"))
            elif schema.get("additionalProperties") is False:
                errs.append(f"{where}: unexpected property {k}")
    return errs


def load_outbound(name: str) -> dict[str, Any]:
    return json.loads((OUTBOUND_DIR / name).read_text(encoding="utf-8"))
