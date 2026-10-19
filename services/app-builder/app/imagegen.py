"""AI image / logo generation for generated apps.

The user describes a logo, hero picture, icon or illustration; core's
existing image path (ImageGenerationOrchestratorService: tenant BYOK
OpenAI/Vertex, else the platform pool) renders it; the bytes come back here,
are validated (magic bytes, size cap) and committed under assets/ through
the SAME pipeline as an uploaded asset (assets.upload_asset). This module
never talks to an image vendor itself.

If no provider can serve images, core answers 409 code=no_image_provider and
we surface that as a clear GenerateError - nothing is faked.
"""

from __future__ import annotations

import base64
import re
import time

import httpx

from . import assets
from .generate import GenerateError
from .settings import SETTINGS, Settings
from .tenancy import TenantContext

_CORE_GENERATE_IMAGE_PATH = "/api/internal/app-builder/generate-image"

KINDS = ("logo", "hero", "icon", "illustration")
ALLOWED_SIZES = ("1024x1024", "1024x1536", "1536x1024")
_DEFAULT_SIZE = {"logo": "1024x1024", "icon": "1024x1024",
                 "hero": "1536x1024", "illustration": "1024x1024"}
MAX_PROMPT_CHARS = 1000

# Per-kind scaffolding. Keep in sync with web/app/build/imagePrompt.ts.
_SCAFFOLD = {
    "logo": ("A clean flat vector logo mark for: {p}. Simple geometric "
             "shapes, limited palette, centered on a plain white or "
             "transparent-looking background, generous padding. No text, "
             "no letters, no words, no watermark, no mockup, no shadows."),
    "icon": ("A single app icon glyph for: {p}. Flat, bold, minimal, "
             "readable at 32px, centered on a plain solid background. "
             "No text, no letters, no watermark."),
    "hero": ("A wide website hero image for: {p}. Professional, modern, "
             "high quality, composition leaves empty space for headline "
             "text overlay. No text, no letters, no watermark, no UI "
             "mockup."),
    "illustration": ("A friendly modern flat illustration of: {p}. "
                     "Cohesive limited palette, clean shapes, plain "
                     "uncluttered background. No text, no letters, no "
                     "watermark."),
}


def sniff_image(data: bytes) -> str | None:
    """Magic-byte sniffing: content-type claims from upstream are not trusted."""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def build_prompt(kind: str, prompt: str) -> str:
    if kind not in KINDS:
        raise GenerateError(
            f"unknown image kind {kind!r} - allowed: {', '.join(KINDS)}")
    p = " ".join((prompt or "").split())
    if not p:
        raise GenerateError("prompt is required")
    if len(p) > MAX_PROMPT_CHARS:
        raise GenerateError(f"prompt too long (>{MAX_PROMPT_CHARS} chars)")
    return _SCAFFOLD[kind].format(p=p)


def resolve_size(kind: str, size: str | None) -> str:
    if not size:
        return _DEFAULT_SIZE[kind]
    if size not in ALLOWED_SIZES:
        raise GenerateError(
            f"unsupported size {size!r} - allowed: {', '.join(ALLOWED_SIZES)}")
    return size


def _asset_basename(kind: str, prompt: str, ext: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", prompt.lower()).strip("-")[:32].strip("-")
    stamp = time.strftime("%y%m%d-%H%M%S")
    return f"{kind}-{slug + '-' if slug else ''}{stamp}.{ext}"


def _call_core(ctx: TenantContext, prompt: str, size: str,
               settings: Settings) -> dict:
    """POST core's generate-image. Isolated so tests monkeypatch this one
    function instead of the network (same shape as generate._call_core)."""
    url = settings.core_base_url.rstrip("/") + _CORE_GENERATE_IMAGE_PATH
    headers = {"X-Service-Token": settings.core_service_token,
               "X-Tenant-Id": ctx.tenant_id,
               "Content-Type": "application/json"}
    try:
        resp = httpx.request("POST", url, headers=headers,
                             json={"prompt": prompt, "size": size},
                             timeout=settings.core_timeout_seconds)
    except httpx.TimeoutException as e:
        raise GenerateError("image generation timed out") from e
    except httpx.TransportError as e:
        raise GenerateError(f"core unreachable at {url}: {e}") from e
    if resp.status_code >= 400:
        try:
            body = resp.json()
        except ValueError:
            body = {}
        if body.get("code") == "no_image_provider":
            raise GenerateError(
                "no image provider available - add an OpenAI or Google "
                "Vertex key on the Providers page, or use the platform pool")
        raise GenerateError(body.get("error")
                            or f"core returned {resp.status_code}")
    return resp.json() if resp.content else {}


def generate_asset(ctx: TenantContext, project_id: str, prompt: str,
                   kind: str, size: str | None = None,
                   settings: Settings = SETTINGS) -> dict:
    """Generate an image and commit it under assets/. Returns
    {path, bytes, kind, size, model?, provider?, commit_sha?}."""
    full_prompt = build_prompt(kind, prompt)
    size = resolve_size(kind, size)
    data = _call_core(ctx, full_prompt, size, settings)

    b64 = data.get("contentBase64")
    if not isinstance(b64, str) or not b64:
        raise GenerateError("image provider returned no image")
    try:
        raw = base64.b64decode(b64, validate=True)
    except (ValueError, TypeError):
        raise GenerateError("image provider returned invalid image data") from None
    if len(raw) > assets._MAX_ASSET_BYTES:
        raise GenerateError(
            f"generated image too large ({len(raw)} bytes > "
            f"{assets._MAX_ASSET_BYTES})")
    ext = sniff_image(raw)
    if ext is None:
        raise GenerateError(
            "image provider returned an unsupported file (expected PNG, "
            "JPEG or WebP)")

    name = _asset_basename(kind, " ".join(prompt.split()), ext)
    result = assets.upload_asset(ctx, project_id, name, raw, settings)
    result.update({"kind": kind, "size": size,
                   "model": data.get("model"), "provider": data.get("provider")})
    return result
