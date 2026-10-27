"""
Docling service tests - contract, internal auth and error semantics.

app.main reads DOCLING_AUTH_REQUIRED/DOCLING_SHARED_SECRET once at import
(deliberately - see its comment), so each test sets the env and reloads the
module to get an app wired for that configuration, the same pattern
services/sidecar/tests/test_auth.py established. The module-scoped cleanup
fixture reloads it one final time under the original environment so no later
suite inherits this file's last configuration.

The one real conversion test uses a tiny inline HTML fixture: the HTML backend
parses locally (no layout/OCR model artifacts needed), unlike PDF, so it runs
without the several-GB model download the Dockerfile bakes. It is still skipped
when docling itself is not installed - importing DocumentConverter pulls a
torch-scale dependency tree that a fast auth/contract pass should not pay.
"""

import base64
import importlib
import importlib.util

import pytest
from fastapi.testclient import TestClient

import app.main

_DOCLING_AVAILABLE = importlib.util.find_spec("docling") is not None


def _reload_main(monkeypatch, *, secret: str | None, required: str | None):
    if secret is None:
        monkeypatch.delenv("DOCLING_SHARED_SECRET", raising=False)
    else:
        monkeypatch.setenv("DOCLING_SHARED_SECRET", secret)
    if required is None:
        monkeypatch.delenv("DOCLING_AUTH_REQUIRED", raising=False)
    else:
        monkeypatch.setenv("DOCLING_AUTH_REQUIRED", required)
    return importlib.reload(app.main)


@pytest.fixture(scope="module", autouse=True)
def restore_main_after_module():
    # monkeypatch restores the env per test, but the module object keeps
    # whatever configuration the last reload baked in - put it back.
    yield
    importlib.reload(app.main)


def _extract_payload(filename: str, content: bytes) -> dict:
    return {
        "filename": filename,
        "content_base64": base64.b64encode(content).decode("ascii"),
        "ocr_provider": None,
        "ocr_api_key": None,
    }


class TestFailClosed:
    def test_no_secret_and_auth_required_rejects_everything_with_503(self, monkeypatch):
        main = _reload_main(monkeypatch, secret=None, required=None)

        response = TestClient(main.app).post("/extract", json=_extract_payload("a.html", b"x"))

        assert response.status_code == 503
        assert response.json()["detail"] == "docling auth is not configured"

    def test_health_stays_open_even_when_auth_is_unconfigured(self, monkeypatch):
        # The compose healthcheck carries no credentials - a misconfigured
        # secret must not read as "docling down".
        main = _reload_main(monkeypatch, secret=None, required=None)

        response = TestClient(main.app).get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "ok", "service": "docling"}


class TestEnforcing:
    def test_missing_token_is_401(self, monkeypatch):
        main = _reload_main(monkeypatch, secret="s3cret", required=None)

        response = TestClient(main.app).post("/extract", json=_extract_payload("a.html", b"x"))

        assert response.status_code == 401

    def test_wrong_token_is_401(self, monkeypatch):
        main = _reload_main(monkeypatch, secret="s3cret", required=None)

        response = TestClient(main.app).post(
            "/extract",
            json=_extract_payload("a.html", b"x"),
            headers={"X-Internal-Token": "wrong"},
        )

        assert response.status_code == 401

    def test_correct_token_reaches_the_endpoint(self, monkeypatch):
        # A 400 (not 401/503) proves the request passed auth and died inside
        # the handler - here on deliberately invalid base64, which needs no
        # docling import.
        main = _reload_main(monkeypatch, secret="s3cret", required=None)

        response = TestClient(main.app).post(
            "/extract",
            json={"filename": "a.html", "content_base64": "!!!", "ocr_provider": None},
            headers={"X-Internal-Token": "s3cret"},
        )

        assert response.status_code == 400
        assert response.json()["detail"] == "content_base64 is not valid base64"


class TestEscapeHatch:
    def test_auth_required_false_allows_tokenless_requests(self, monkeypatch):
        main = _reload_main(monkeypatch, secret=None, required="false")

        # 400 on the unsupported extension, not 401: auth is off.
        response = TestClient(main.app).post("/extract", json=_extract_payload("a.xyz", b"x"))

        assert response.status_code == 400

    def test_anything_other_than_false_still_requires_auth(self, monkeypatch):
        # "0"/"no"/typos must NOT silently disable auth - only the literal
        # (case-insensitive) "false" is the escape hatch.
        main = _reload_main(monkeypatch, secret="s3cret", required="0")

        response = TestClient(main.app).post("/extract", json=_extract_payload("a.html", b"x"))

        assert response.status_code == 401


class TestExtractErrors:
    """File-side 400s, exercised with auth disabled so each test isolates the
    handler's error mapping rather than the token check (covered above)."""

    def _client(self, monkeypatch):
        main = _reload_main(monkeypatch, secret=None, required="false")
        return TestClient(main.app)

    def test_invalid_base64_is_400(self, monkeypatch):
        response = self._client(monkeypatch).post(
            "/extract", json={"filename": "a.html", "content_base64": "not-base64!"}
        )

        assert response.status_code == 400
        assert response.json()["detail"] == "content_base64 is not valid base64"

    def test_unsupported_extension_is_400(self, monkeypatch):
        # .txt is NOT in SUPPORTED_EXTENSIONS (mirrored by the Java allowlist).
        response = self._client(monkeypatch).post(
            "/extract", json=_extract_payload("notes.txt", b"hello")
        )

        assert response.status_code == 400
        assert "Unsupported file extension" in response.json()["detail"]


@pytest.mark.skipif(
    not _DOCLING_AVAILABLE,
    reason="docling package not installed - pip install -r requirements.txt to run the "
    "real conversion path (importing it pulls torch-scale dependencies)",
)
class TestRealConversion:
    def test_html_extracts_markdown_text(self, monkeypatch):
        # Inline HTML deliberately: the HTML backend parses locally, so this
        # exercises the real DocumentConverter path without the model download
        # a PDF would trigger.
        main = _reload_main(monkeypatch, secret=None, required="false")
        html = b"<html><body><h1>Quarterly results</h1><p>Revenue grew 12%.</p></body></html>"

        response = TestClient(main.app).post("/extract", json=_extract_payload("q3.html", html))

        assert response.status_code == 200
        text = response.json()["text"]
        assert "Quarterly results" in text
        assert "Revenue grew" in text
