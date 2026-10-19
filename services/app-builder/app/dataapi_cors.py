"""CORS for the public data API routes (see dataapi.py)."""

from __future__ import annotations


class DataCorsMiddleware:
    """Pure-ASGI CORS for /data/v1/*. The console's own CORSMiddleware only
    admits the console origins (and would 400 every other preflight), but a
    deployed app calls this API from an arbitrary origin. Preflights carry no
    `apikey`, so they are answered generically; the per-project allowed-origins
    rule is enforced on the real request (see `dataapi._check_origin`)."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not scope["path"].startswith("/data/v1"):
            return await self.app(scope, receive, send)
        if scope["method"] == "OPTIONS":
            await send(
                {
                    "type": "http.response.start",
                    "status": 204,
                    "headers": [
                        (b"access-control-allow-origin", b"*"),
                        (b"access-control-allow-methods", b"GET, POST, PATCH, DELETE, OPTIONS"),
                        (
                            b"access-control-allow-headers",
                            b"apikey, authorization, content-type, prefer",
                        ),
                        (b"access-control-max-age", b"600"),
                        (b"content-length", b"0"),
                    ],
                }
            )
            await send({"type": "http.response.body", "body": b""})
            return

        async def send_with_cors(message):
            if message["type"] == "http.response.start":
                hs = list(message.get("headers", []))
                if not any(h[0].lower() == b"access-control-allow-origin" for h in hs):
                    hs.append((b"access-control-allow-origin", b"*"))
                message["headers"] = hs
            await send(message)

        await self.app(scope, receive, send_with_cors)
