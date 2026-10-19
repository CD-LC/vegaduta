"""
The render completion callback (see render.py).

A finished render POSTs its job snapshot at an allow-listed host so a
workflow can resume from a webhook trigger. The callback URL and headers
both come from a tenant-authored request, so this module is the SSRF and
header-injection boundary for that push.
"""

from __future__ import annotations

import logging
import os
from typing import TYPE_CHECKING
from urllib.parse import urlparse

import requests

from .render_errors import RenderError

if TYPE_CHECKING:
    from .render import RenderJob

log = logging.getLogger("sidecar.render")

# Completion callback. This exists because of a hard limit in the workflow
# engine, not as a convenience: `WorkflowNodeType` has no WAIT or DELAY value
# (checked - there is no such enum constant and no such executor case), and
# the `http` node's own `timeoutSeconds` is capped at 30. So a workflow
# literally cannot sit and poll a multi-minute render: there is no sleep
# primitive to poll WITH, and no single HTTP call may run long enough to wait
# it out. The supported shape is therefore push, not poll - this module POSTs
# the finished job snapshot at a per-node webhook trigger on the same
# workflow graph, which resumes it from a second trigger node.
#
# The allowlist is not optional. A callback URL arrives inside a
# tenant-authored workflow's render request, so an unrestricted callback is
# a POST-capable SSRF primitive aimed at whatever this container can reach -
# and this container is on agent-io alongside core and code-intel. Hostnames
# only, matched exactly, defaulting to the one host that is ever a legitimate
# target. Per CLAUDE.md RULE 0 this is an internal-only service-to-service
# URL, which is the documented exception that correctly stays off the public
# domain - the same relationship core -> sidecar already has.
CALLBACK_ALLOWED_HOSTS = {
    h.strip().lower()
    for h in os.environ.get("RENDER_CALLBACK_ALLOWED_HOSTS", "core").split(",")
    if h.strip()
}


def validate_callback(url: str) -> None:
    """Refuse a callback target that is not on the allowlist. Called at submit
    time, not at completion, so a bad URL is a 400 on the request that set it
    rather than a silent failure forty minutes later."""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise RenderError(f"callback_url scheme must be http or https, got {parsed.scheme!r}")
    host = (parsed.hostname or "").lower()
    if host not in CALLBACK_ALLOWED_HOSTS:
        raise RenderError(
            f"callback_url host {host!r} is not allowed. Allowed: "
            f"{sorted(CALLBACK_ALLOWED_HOSTS)} (set RENDER_CALLBACK_ALLOWED_HOSTS to change)"
        )


# Security audit 2026-09-18. `callback_headers` comes from the same
# tenant-authored render request as `callback_url`, and was merged into the
# outgoing POST unfiltered. The callback target is core, on the internal
# network, so the headers that matter are the ones core (or a proxy in front
# of it) would TRUST: Host (virtual-host routing), X-Forwarded-* (client-IP /
# scheme spoofing past an allowlist), Cookie, Proxy-*, and the framing headers
# (Content-Length / Transfer-Encoding / Connection) that enable request
# smuggling. Content-Type is dropped too - the body is always this module's
# own JSON, and a caller relabelling it is never legitimate.
#
# Authorization is deliberately KEPT. The callback lands on core's workflow
# webhook, and WorkflowWebhookController has a per-node OAUTH2 auth mode that
# REQUIRES an `Authorization: Bearer` header (SHARED_SECRET mode carries the
# token in the path, and scripts/video-pipeline sends X-Webhook-Token). Letting
# the caller authenticate the callback is the whole reason this field exists;
# stripping Authorization would make OAUTH2-mode trigger nodes unreachable.
# The host allowlist above is what bounds where that credential can be sent.
_CALLBACK_HEADER_DENY = frozenset(
    {
        "host",
        "content-length",
        "content-type",
        "transfer-encoding",
        "connection",
        "cookie",
    }
)
_CALLBACK_HEADER_DENY_PREFIXES = ("proxy-", "x-forwarded-")
_CALLBACK_MAX_HEADERS = 20
_CALLBACK_MAX_VALUE_BYTES = 2048
_CALLBACK_MAX_NAME_CHARS = 128
# RFC 7230 `token` - the only characters a header field-name may contain.
_HEADER_NAME_CHARS = frozenset(
    "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
)


def _filter_callback_headers(supplied: object) -> dict[str, str]:
    """Reduce caller-supplied callback headers to the ones safe to forward.

    Drops rather than raises: this runs at completion time, on the render
    thread, and a bad header must not cost the caller a finished render. Every
    drop is logged by NAME only - a value may be a credential.
    """
    if not isinstance(supplied, dict):
        return {}
    kept: dict[str, str] = {}
    for raw_name, raw_value in supplied.items():
        name = str(raw_name).strip()
        value = "" if raw_value is None else str(raw_value)
        lowered = name.lower()
        if not name or len(name) > _CALLBACK_MAX_NAME_CHARS or not set(name) <= _HEADER_NAME_CHARS:
            # Covers CR/LF (and NUL, spaces, colons) in a NAME: none are
            # token characters, so a header-injection name never survives.
            log.warning("dropping callback header with an invalid name: %r", name[:64])
            continue
        if lowered in _CALLBACK_HEADER_DENY or lowered.startswith(_CALLBACK_HEADER_DENY_PREFIXES):
            log.warning("dropping disallowed callback header %s", name)
            continue
        if any(ch in value for ch in ("\r", "\n", "\x00")):
            log.warning("dropping callback header %s - value contains CR/LF/NUL", name)
            continue
        if len(value.encode("utf-8", "replace")) > _CALLBACK_MAX_VALUE_BYTES:
            log.warning(
                "dropping callback header %s - value exceeds %d bytes",
                name,
                _CALLBACK_MAX_VALUE_BYTES,
            )
            continue
        if len(kept) >= _CALLBACK_MAX_HEADERS:
            log.warning(
                "dropping callback header %s - more than %d headers supplied",
                name,
                _CALLBACK_MAX_HEADERS,
            )
            continue
        kept[name] = value
    return kept


def notify(job: RenderJob, request: dict) -> None:
    """POST the finished job's snapshot to its callback_url, if it set one."""
    url = (request.get("callback_url") or "").strip()
    if not url:
        return
    headers = {"Content-Type": "application/json"}
    headers.update(_filter_callback_headers(request.get("callback_headers")))
    payload = job.snapshot()
    # The callback carries the outcome, never the video: a 20MB base64 body
    # posted at a workflow webhook is not a payload the engine should have to
    # absorb. The resumed workflow reads the artifact by job id.
    if payload.get("result"):
        payload["result"] = {k: v for k, v in payload["result"].items() if k != "video_base64"}
    try:
        # allow_redirects=False (security audit 2026-09-18): the host allowlist
        # is checked against callback_url only, so a followed redirect would
        # carry the caller's Authorization header to a host nobody validated.
        response = requests.post(
            url, json=payload, headers=headers, timeout=30, allow_redirects=False
        )
        log.info("render %s callback -> %s HTTP %s", job.job_id, url, response.status_code)
    except requests.RequestException as e:
        # A failed callback must not flip a successful render to failed. The
        # job stays readable at GET /render/{job_id}, so the outcome is never
        # lost - only the push is.
        log.error("render %s callback to %s failed: %s", job.job_id, url, e)
