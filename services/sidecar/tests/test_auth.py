"""
Internal-auth tests (2026-08-07) - the sidecar was the last unauthenticated
service in the stack, so these lock down the require_internal_token
dependency's three configurations: fail-closed (required, no secret ->
503), enforcing (required, secret set -> 401 without / 200 with the token),
and the bare-local-dev escape hatch (SIDECAR_AUTH_REQUIRED=false).

app.main reads SIDECAR_AUTH_REQUIRED/SIDECAR_SHARED_SECRET once at import
(deliberately - see its comment), so each test sets the env and reloads the
module to get an app wired for that configuration. The module-scoped
cleanup fixture reloads it one final time under the original environment so
no later suite inherits this file's last configuration.

The authed round-trip uses /rerank with app.main's `rerank` symbol
monkeypatched out - same "never load a multi-hundred-MB model in a unit
test" rule test_pipeline.py's module docstring states; /rerank is the only
endpoint whose happy path needs no base64 fixture bytes.
"""
import importlib

import pytest
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient

import app.main


def _reload_main(monkeypatch, *, secret: str | None, required: str | None):
    if secret is None:
        monkeypatch.delenv("SIDECAR_SHARED_SECRET", raising=False)
    else:
        monkeypatch.setenv("SIDECAR_SHARED_SECRET", secret)
    if required is None:
        monkeypatch.delenv("SIDECAR_AUTH_REQUIRED", raising=False)
    else:
        monkeypatch.setenv("SIDECAR_AUTH_REQUIRED", required)
    return importlib.reload(app.main)


@pytest.fixture(scope="module", autouse=True)
def restore_main_after_module():
    # monkeypatch restores the env per test, but the module object keeps
    # whatever configuration the last reload baked in - put it back.
    yield
    importlib.reload(app.main)


class TestFailClosed:
    def test_no_secret_and_auth_required_rejects_everything_with_503(self, monkeypatch):
        main = _reload_main(monkeypatch, secret=None, required=None)

        response = TestClient(main.app).post("/rerank", json={"query": "q", "documents": []})

        assert response.status_code == 503
        assert response.json()["detail"] == "sidecar auth is not configured"

    def test_health_stays_open_even_when_auth_is_unconfigured(self, monkeypatch):
        # The compose healthcheck and SystemStatusController's probe carry no
        # credentials - a misconfigured secret must not read as "sidecar down".
        main = _reload_main(monkeypatch, secret=None, required=None)

        response = TestClient(main.app).get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "ok", "service": "sidecar"}


class TestEnforcing:
    def test_missing_token_is_401(self, monkeypatch):
        main = _reload_main(monkeypatch, secret="s3cret", required=None)

        response = TestClient(main.app).post("/rerank", json={"query": "q", "documents": []})

        assert response.status_code == 401

    def test_wrong_token_is_401(self, monkeypatch):
        main = _reload_main(monkeypatch, secret="s3cret", required=None)

        response = TestClient(main.app).post(
            "/rerank",
            json={"query": "q", "documents": []},
            headers={"X-Internal-Token": "wrong"},
        )

        assert response.status_code == 401

    def test_correct_token_reaches_the_endpoint(self, monkeypatch):
        main = _reload_main(monkeypatch, secret="s3cret", required=None)
        monkeypatch.setattr(main, "rerank", lambda query, documents: [0.5] * len(documents))

        response = TestClient(main.app).post(
            "/rerank",
            json={"query": "q", "documents": ["doc a", "doc b"]},
            headers={"X-Internal-Token": "s3cret"},
        )

        assert response.status_code == 200
        assert response.json() == {"scores": [0.5, 0.5]}

    def test_every_non_health_route_carries_the_auth_dependency(self, monkeypatch):
        # Structural guard: a future endpoint added without
        # dependencies=[Depends(require_internal_token)] shows up here as a
        # named failure rather than as a silently open route.
        main = _reload_main(monkeypatch, secret="s3cret", required=None)
        client = TestClient(main.app)

        open_routes = []
        for route in main.app.routes:
            # APIRoute only: FastAPI's built-in /docs//openapi.json routes
            # carry no tenant data and are deliberately out of scope here.
            if not isinstance(route, APIRoute) or route.path == "/health":
                continue
            for method in route.methods - {"HEAD", "OPTIONS"}:
                response = client.request(method, route.path, json={})
                if response.status_code != 401:
                    open_routes.append((method, route.path, response.status_code))
        assert open_routes == []


class TestEscapeHatch:
    def test_auth_required_false_allows_tokenless_requests(self, monkeypatch):
        main = _reload_main(monkeypatch, secret=None, required="false")
        monkeypatch.setattr(main, "rerank", lambda query, documents: [])

        response = TestClient(main.app).post("/rerank", json={"query": "q", "documents": []})

        assert response.status_code == 200

    def test_anything_other_than_false_still_requires_auth(self, monkeypatch):
        # "0"/"no"/typos must NOT silently disable auth - only the literal
        # (case-insensitive) "false" is the escape hatch.
        main = _reload_main(monkeypatch, secret="s3cret", required="0")

        response = TestClient(main.app).post("/rerank", json={"query": "q", "documents": []})

        assert response.status_code == 401
