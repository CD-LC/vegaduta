"""Figma import - turn a Figma frame URL + the tenant's own personal access
token into design context for generate().

Nothing about the token is stored: it arrives per request over TLS in the
POST body, is sent to api.figma.com in the X-Figma-Token header, and is
dropped when the request ends. It is never logged, audited, echoed, or
included in an error message (all error text below is fixed strings).

SSRF posture: the only host we ever call with the token is
https://api.figma.com. The rendered-PNG URL comes back inside the Figma API
response (data, not instructions); it is fetched WITHOUT the token, only if
it is https on a Figma-image-bucket host, and redirects are not followed.
"""

from __future__ import annotations

import base64
import re
from urllib.parse import parse_qs, unquote, urlparse

import httpx

FIGMA_API = "https://api.figma.com"
TIMEOUT = httpx.Timeout(15.0, connect=5.0)
MAX_IMAGE_BYTES = 3 * 1024 * 1024
MAX_SPEC_CHARS = 6000  # ~1.5k tokens; generate() context cap is 16000
MAX_DEPTH = 8
MAX_TEXT_CHARS = 80

_KEY_RE = re.compile(r"^[A-Za-z0-9]{10,64}$")
_NODE_RE = re.compile(r"^I?\d+:\d+(;\d+:\d+)*$")
_FILE_PATH_KINDS = {"file", "design"}


class FigmaError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


# --------------------------------------------------------------------- #
# URL parsing

def normalize_node_id(raw: str) -> str:
    """'1-2' / '1%3A2' / '1:2' -> '1:2'. Instance ids ('I1-2;3-4') too."""
    s = unquote(raw).strip()
    s = s.replace("-", ":")
    if not _NODE_RE.match(s):
        raise FigmaError(400, "unrecognized Figma node-id in URL")
    return s


def parse_figma_url(url: str) -> tuple[str, str | None]:
    """Return (file_key, node_id or None). Accepts figma.com/file|design/KEY
    (and branch URLs, where the branch key is the file to query)."""
    try:
        u = urlparse(url.strip())
    except ValueError:
        raise FigmaError(400, "not a valid Figma URL")
    host = (u.hostname or "").lower()
    if u.scheme != "https" or host not in ("figma.com", "www.figma.com"):
        raise FigmaError(400, "URL must be an https://www.figma.com file or frame link")
    parts = [p for p in u.path.split("/") if p]
    if len(parts) < 2 or parts[0] not in _FILE_PATH_KINDS:
        raise FigmaError(400, "URL must point to a Figma file or design")
    key = parts[1]
    if len(parts) >= 4 and parts[2] == "branch":
        key = parts[3]
    if not _KEY_RE.match(key):
        raise FigmaError(400, "unrecognized Figma file key in URL")
    node = None
    q = parse_qs(u.query)
    if q.get("node-id"):
        node = normalize_node_id(q["node-id"][0])
    return key, node


# --------------------------------------------------------------------- #
# HTTP (single choke point, monkeypatchable in tests)

def _map_status(status: int) -> FigmaError:
    if status in (401, 403):
        return FigmaError(400, "Figma rejected the access token (or it cannot read this file)")
    if status == 404:
        return FigmaError(404, "Figma file or frame not found")
    if status == 429:
        return FigmaError(429, "Figma rate limit reached, try again shortly")
    return FigmaError(502, f"Figma API error (status {status})")


def _get(url: str, *, token: str | None, params: dict | None = None) -> httpx.Response:
    headers = {"X-Figma-Token": token} if token else {}
    try:
        resp = httpx.get(url, headers=headers, params=params, timeout=TIMEOUT,
                         follow_redirects=False)
    except httpx.TimeoutException:
        raise FigmaError(504, "Figma did not respond in time")
    except httpx.HTTPError:
        raise FigmaError(502, "could not reach Figma")
    return resp


def fetch_nodes(token: str, key: str, node_id: str) -> dict:
    resp = _get(f"{FIGMA_API}/v1/files/{key}/nodes", token=token,
                params={"ids": node_id, "depth": MAX_DEPTH})
    if resp.status_code != 200:
        raise _map_status(resp.status_code)
    try:
        nodes = resp.json().get("nodes") or {}
        entry = nodes.get(node_id) or next(iter(nodes.values()), None)
        doc = (entry or {}).get("document")
    except (ValueError, AttributeError):
        doc = None
    if not isinstance(doc, dict):
        raise FigmaError(404, "Figma frame not found or empty")
    return doc


def _image_host_ok(url: str) -> bool:
    try:
        u = urlparse(url)
    except ValueError:
        return False
    host = (u.hostname or "").lower()
    if u.scheme != "https":
        return False
    return host.endswith(".figma.com") or (
        host.startswith("figma-") and host.endswith(".amazonaws.com"))


def fetch_image(token: str, key: str, node_id: str) -> dict | None:
    """Rendered PNG as an ImageAttachment dict, or None (best effort: the
    spec alone is still useful, so image failures never fail the import)."""
    try:
        resp = _get(f"{FIGMA_API}/v1/images/{key}", token=token,
                    params={"ids": node_id, "format": "png", "scale": 2})
        if resp.status_code != 200:
            return None
        img_url = (resp.json().get("images") or {}).get(node_id)
        if not isinstance(img_url, str) or not _image_host_ok(img_url):
            return None
        raw = _get(img_url, token=None)  # never send the token to the CDN
        if raw.status_code != 200 or len(raw.content) > MAX_IMAGE_BYTES:
            return None
        return {"name": "figma-frame.png", "media_type": "image/png",
                "data_base64": base64.b64encode(raw.content).decode("ascii")}
    except (FigmaError, ValueError, AttributeError):
        return None


# --------------------------------------------------------------------- #
# node tree -> compact spec

def _hex(c: dict, opacity: float = 1.0) -> str:
    def h(v):
        return f"{max(0, min(255, round(float(v) * 255))):02x}"
    s = f"#{h(c.get('r', 0))}{h(c.get('g', 0))}{h(c.get('b', 0))}"
    a = float(c.get("a", 1)) * opacity
    return s if a >= 0.99 else f"{s}@{round(a, 2)}"


def _solid(paints) -> str | None:
    for p in paints or []:
        if isinstance(p, dict) and p.get("type") == "SOLID" and p.get("visible", True):
            return _hex(p.get("color") or {}, float(p.get("opacity", 1)))
    return None


def _num(v) -> str:
    f = float(v)
    return str(int(f)) if f == int(f) else f"{f:.1f}"


def describe_node(n: dict) -> str:
    bits = [f'{n.get("type", "NODE")} "{str(n.get("name", ""))[:40]}"']
    box = n.get("absoluteBoundingBox") or {}
    if box.get("width") and box.get("height"):
        bits.append(f'{_num(box["width"])}x{_num(box["height"])}')
    mode = n.get("layoutMode")
    if mode in ("HORIZONTAL", "VERTICAL"):
        hint = "flex-row" if mode == "HORIZONTAL" else "flex-col"
        if n.get("itemSpacing"):
            hint += f" gap {_num(n['itemSpacing'])}"
        pads = [n.get(k, 0) or 0 for k in
                ("paddingTop", "paddingRight", "paddingBottom", "paddingLeft")]
        if any(pads):
            hint += " pad " + "/".join(_num(p) for p in pads)
        if n.get("primaryAxisAlignItems") in ("CENTER", "MAX", "SPACE_BETWEEN"):
            hint += " justify-" + n["primaryAxisAlignItems"].lower()
        if n.get("counterAxisAlignItems") in ("CENTER", "MAX"):
            hint += " items-" + n["counterAxisAlignItems"].lower()
        bits.append(hint)
    fill = _solid(n.get("fills"))
    if fill and n.get("type") != "TEXT":
        bits.append(f"fill {fill}")
    stroke = _solid(n.get("strokes"))
    if stroke:
        bits.append(f"stroke {stroke}")
    if n.get("cornerRadius"):
        bits.append(f"radius {_num(n['cornerRadius'])}")
    if any(isinstance(e, dict) and e.get("type") == "DROP_SHADOW"
           and e.get("visible", True) for e in n.get("effects") or []):
        bits.append("shadow")
    if n.get("type") == "TEXT":
        st = n.get("style") or {}
        t = [st.get("fontFamily"), _num(st["fontSize"]) + "px" if st.get("fontSize") else None,
             str(int(st["fontWeight"])) if st.get("fontWeight") else None,
             _solid(n.get("fills"))]
        bits.append("text-style " + " ".join(x for x in t if x))
        chars = " ".join(str(n.get("characters", "")).split())[:MAX_TEXT_CHARS]
        if chars:
            bits.append(f'"{chars}"')
    return " | ".join(bits)


def reduce_tree(root: dict, budget: int = MAX_SPEC_CHARS) -> str:
    lines: list[str] = []
    used = 0
    skipped = 0

    def walk(n: dict, depth: int):
        nonlocal used, skipped
        if not isinstance(n, dict) or n.get("visible") is False:
            return
        line = "  " * depth + "- " + describe_node(n)
        if used + len(line) + 1 > budget:
            skipped += 1
            return
        lines.append(line)
        used += len(line) + 1
        if depth >= MAX_DEPTH:
            return
        for ch in n.get("children") or []:
            walk(ch, depth + 1)

    walk(root, 0)
    if skipped:
        lines.append(f"(spec truncated: {skipped}+ more nodes omitted)")
    return "\n".join(lines)


def import_frame(url: str, token: str) -> dict:
    """{context, image?, node_id, file_key}. Raises FigmaError."""
    token = (token or "").strip()
    if not token or len(token) > 512:
        raise FigmaError(400, "a valid Figma personal access token is required")
    if len(url) > 2000:
        raise FigmaError(400, "not a valid Figma URL")
    key, node = parse_figma_url(url)
    if node is None:
        raise FigmaError(400, "select a frame in Figma and copy its link (URL needs node-id)")
    doc = fetch_nodes(token, key, node)
    spec = reduce_tree(doc)
    context = ("FIGMA DESIGN SPEC (layout tree; reproduce structure, spacing, "
               "colors and type faithfully; flex hints derive from auto-layout):\n" + spec)
    out = {"context": context, "file_key": key, "node_id": node,
           "name": str(doc.get("name", ""))[:80]}
    image = fetch_image(token, key, node)
    if image:
        out["image"] = image
    return out


def import_for_project(ctx, project_id: str, url: str, token: str, settings) -> dict:
    """Route body: checks the project exists, imports, audits WITHOUT the
    token (file key + node id only)."""
    from . import audit, projects
    from .tenancy import tenant_root
    projects.get_or_404(settings.data_dir, ctx, project_id)
    result = import_frame(url, token)
    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "figma_imported", "project_id": project_id,
        "actor": ctx.subject, "file_key": result["file_key"],
        "node_id": result["node_id"], "has_image": "image" in result})
    return result
