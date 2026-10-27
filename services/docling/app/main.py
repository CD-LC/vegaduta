"""
VegaDūta - Docling document-conversion service.

A thin FastAPI adapter around IBM's Docling parser: one endpoint, POST
/extract, speaking the sidecar's exact request/response contract so core's
DoclingConversionService routes uploads through the same SidecarConversionClient
wire code unchanged - only the base URL and shared secret differ
(app.knowledge.docling.*, off by default until the `docling` compose profile
runs). See app/conversion.py for why a separate service exists at all.

Internal auth is the sidecar's shape too: every route except /health requires
X-Internal-Token to match DOCLING_SHARED_SECRET - this service is published on
host port 8002. See require_internal_token below for the fail-closed /
escape-hatch semantics.
"""

import base64
import hmac
import logging
import os

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .conversion import convert_to_markdown

logging.basicConfig(
    level=os.environ.get("DOCLING_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("docling")

# Read once at import, like the sidecar's settings - a rotated secret requires
# a container restart either way (the Java caller resolves its copy at bean
# construction), so per-request env reads would only pretend to support live
# rotation.
_AUTH_REQUIRED = os.environ.get("DOCLING_AUTH_REQUIRED", "true").strip().lower() != "false"
_SHARED_SECRET = os.environ.get("DOCLING_SHARED_SECRET", "")

if not _AUTH_REQUIRED:
    # The ONE unauthenticated mode, for bare local dev (uvicorn on a laptop,
    # no compose, no secret distribution). Never set this on a deployment -
    # the compose default keeps it true.
    log.warning(
        "DOCLING_AUTH_REQUIRED=false - every endpoint is open to any caller "
        "that can reach this port. Bare-local-dev escape hatch only; never "
        "run a deployment like this."
    )
elif not _SHARED_SECRET:
    # Fail closed, matching the sidecar: a deployment that forgot the secret
    # serves 503s (visible, diagnosable) rather than silently accepting
    # anonymous traffic.
    log.error(
        "DOCLING_SHARED_SECRET is not set while DOCLING_AUTH_REQUIRED=true - "
        "every request except /health will be rejected with 503. Set "
        "DOCLING_SHARED_SECRET in deploy/compose/.env (openssl rand -hex 32) or, for "
        "bare local dev only, set DOCLING_AUTH_REQUIRED=false."
    )


def require_internal_token(x_internal_token: str = Header(default="")) -> None:
    """Shared-secret auth, compared in constant time.

    Mirrors the sidecar's require_internal_token exactly: honors
    DOCLING_AUTH_REQUIRED=false as the documented bare-local-dev escape hatch
    (warned loudly at startup above), and with auth required but no secret
    configured, everything fails closed with a 503.
    """
    if not _AUTH_REQUIRED:
        return
    if not _SHARED_SECRET:
        raise HTTPException(status_code=503, detail="docling auth is not configured")
    if not hmac.compare_digest(x_internal_token or "", _SHARED_SECRET):
        raise HTTPException(status_code=401, detail="invalid internal token")


app = FastAPI(
    title="VegaDūta - Docling conversion service",
    version="0.1.0",
    docs_url=None,  # no interactive docs on a service published on a host port
    redoc_url=None,  # (8002) - same posture as the sidecar; the schema would
    openapi_url=None,  # map this service's whole surface for free
)


@app.get("/health")
def health() -> dict:
    """Unauthenticated liveness only - it reveals no tenant data, and the
    compose healthcheck calls it without credentials."""
    return {"status": "ok", "service": "docling"}


class ExtractRequest(BaseModel):
    filename: str = Field(min_length=1)
    # Base64 over JSON rather than multipart - identical to the sidecar's
    # ExtractRequest because the Java caller (SidecarConversionClient) sends
    # the exact same body to both services.
    content_base64: str = Field(min_length=1)
    # Accepted but deliberately ignored: kept for contract compatibility with
    # the sidecar's ExtractRequest. Docling runs its own OCR pipeline
    # internally, so a tenant's cloud-OCR selection on the Java side has no
    # meaning here - the Java caller sends them unset anyway.
    ocr_provider: str | None = Field(default=None)
    ocr_api_key: str | None = Field(default=None)


class ExtractResponse(BaseModel):
    text: str


@app.post(
    "/extract",
    response_model=ExtractResponse,
    dependencies=[Depends(require_internal_token)],
)
def extract(request: ExtractRequest) -> ExtractResponse:
    try:
        content = base64.b64decode(request.content_base64, validate=True)
    except Exception as e:
        raise HTTPException(
            status_code=400, detail="content_base64 is not valid base64"
        ) from e

    try:
        text = convert_to_markdown(request.filename, content)
    except ValueError as e:
        # A ValueError from conversion.py means the file itself was the
        # problem (unsupported extension, unparseable content, empty result) -
        # a 400, not a 500. Any OTHER exception type is left to propagate as
        # FastAPI's default 500 - deliberately not caught here, so an
        # unexpected parser bug is visible as a real error instead of being
        # silently relabeled as a client mistake.
        raise HTTPException(status_code=400, detail=str(e))  # noqa: B904

    if not text.strip():
        raise HTTPException(status_code=400, detail="Extraction produced no readable text content")

    return ExtractResponse(text=text)
