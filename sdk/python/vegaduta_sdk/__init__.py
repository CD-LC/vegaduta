"""VegaDuta Python SDK.

generated/ is produced by ``node sdk/generate.mjs``; runtime.py is hand-written.
"""

from .runtime import (
    DEFAULT_BASE_URL,
    STREAM_ERROR_MARKER,
    ApiError,
    HttpClient,
    SseDecoder,
    StreamInterruptedError,
    iter_sse,
)

try:
    from .generated import models
    from .generated.client import Client, VegadutaClient
except ModuleNotFoundError as exc:  # pragma: no cover - only before the first generation
    if not (exc.name or "").startswith(f"{__name__}.generated"):
        raise

    def _not_generated(*_args, **_kwargs):
        raise ImportError("vegaduta_sdk.generated has not been generated yet. Run: node sdk/generate.mjs")

    models = None  # type: ignore[assignment]
    Client = VegadutaClient = _not_generated  # type: ignore[assignment,misc]

__version__ = "0.1.0"

__all__ = [
    "ApiError",
    "Client",
    "DEFAULT_BASE_URL",
    "HttpClient",
    "STREAM_ERROR_MARKER",
    "SseDecoder",
    "StreamInterruptedError",
    "VegadutaClient",
    "iter_sse",
    "models",
]
