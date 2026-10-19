"""app/main.py through FastAPI's TestClient: auth wiring, request validation,
error-body shape and the three routes, with the LLM faked at the SDK-client
seam so app/llm.py runs for real."""

from __future__ import annotations

import json
from collections.abc import Callable, Iterator

import pytest
from fastapi.testclient import TestClient

from app import llm, main
from app.settings import Settings

from .fakes import FakeAnthropic

SECRET = "api-test-secret"
AUTH = {"X-Internal-Token": SECRET, "X-Tenant-Id": "acme"}

MakeSettings = Callable[..., Settings]

OPTIMIZE_BODY = {
    "agentId": "agent-1",
    "currentSystemPrompt": "You are helpful.",
    "failingCases": [{"userMessage": "refund?", "response": "no", "reward": 0.0}],
    "rubric": "Be accurate.",
}
JUDGE_BODY = {"response": "The window is 30 days.", "principles": ["Never invent policy."]}
DPO_LINE = json.dumps({"prompt": "p", "chosen": "c", "rejected": "r"})


@pytest.fixture
def use_settings(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> Callable[..., Settings]:
    def _use(**overrides: object) -> Settings:
        base: dict[str, object] = {"auth_mode": "internal", "internal_token": SECRET}
        base.update(overrides)
        s = make_settings(**base)
        monkeypatch.setattr(main, "SETTINGS", s)
        return s

    _use()
    return _use


@pytest.fixture
def client(use_settings: Callable[..., Settings]) -> Iterator[TestClient]:
    with TestClient(main.app) as c:
        yield c


def _assert_error_shape(body: dict, status: int) -> None:
    assert set(body) == {"timestamp", "status", "error"}
    assert body["status"] == status
    assert body["timestamp"].endswith("Z")


def test_health_needs_no_auth_and_reports_mode(
    client: TestClient, use_settings: Callable[..., Settings]
) -> None:
    assert client.get("/health").json() == {"status": "ok", "mode": "stub-only"}
    use_settings(llm_api_key_present=True)
    assert client.get("/health").json() == {"status": "ok", "mode": "llm"}


@pytest.mark.parametrize(
    ("path", "body"),
    [
        ("/optimize/prompt", OPTIMIZE_BODY),
        ("/judge/constitutional", JUDGE_BODY),
        ("/train/dpo", {"jsonl": DPO_LINE}),
    ],
)
def test_every_post_route_requires_auth(client: TestClient, path: str, body: dict) -> None:
    for headers in ({}, {"X-Internal-Token": "wrong", "X-Tenant-Id": "acme"}):
        r = client.post(path, json=body, headers=headers)
        assert r.status_code == 401
        _assert_error_shape(r.json(), 401)
        assert SECRET not in r.text


def test_unconfigured_secret_fails_closed(
    client: TestClient, use_settings: Callable[..., Settings]
) -> None:
    use_settings(internal_token="")
    r = client.post("/train/dpo", json={"jsonl": DPO_LINE}, headers=AUTH)
    assert r.status_code == 500
    _assert_error_shape(r.json(), 500)


def test_invalid_tenant_is_400(client: TestClient) -> None:
    r = client.post(
        "/train/dpo",
        json={"jsonl": DPO_LINE},
        headers={"X-Internal-Token": SECRET, "X-Tenant-Id": "../../etc"},
    )
    assert r.status_code == 400
    _assert_error_shape(r.json(), 400)


# ------------------------------------------------------------------ /train/dpo


def test_dpo_happy_path(client: TestClient) -> None:
    r = client.post("/train/dpo", json={"jsonl": DPO_LINE + "\n" + DPO_LINE}, headers=AUTH)
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "queued"
    assert body["pairs"] == 2
    assert body["jobId"].startswith("dpo-")


def test_dpo_invalid_dataset_is_400(client: TestClient) -> None:
    r = client.post("/train/dpo", json={"jsonl": "not json"}, headers=AUTH)
    assert r.status_code == 400
    _assert_error_shape(r.json(), 400)
    assert "no valid preference pairs" in r.json()["error"]


@pytest.mark.parametrize("body", [{}, {"jsonl": ""}, {"jsonl": 5}])
def test_dpo_request_validation_is_422(client: TestClient, body: dict) -> None:
    assert client.post("/train/dpo", json=body, headers=AUTH).status_code == 422


# ------------------------------------------------------------- LLM endpoints


@pytest.mark.parametrize(
    ("path", "body"), [("/optimize/prompt", OPTIMIZE_BODY), ("/judge/constitutional", JUDGE_BODY)]
)
def test_llm_routes_503_without_api_key(client: TestClient, path: str, body: dict) -> None:
    r = client.post(path, json=body, headers=AUTH)
    assert r.status_code == 503
    _assert_error_shape(r.json(), 503)
    assert "ANTHROPIC_API_KEY" in r.json()["error"]


@pytest.mark.parametrize(
    ("path", "body"),
    [
        ("/optimize/prompt", {**OPTIMIZE_BODY, "failingCases": []}),
        ("/optimize/prompt", {k: v for k, v in OPTIMIZE_BODY.items() if k != "agentId"}),
        ("/optimize/prompt", {**OPTIMIZE_BODY, "failingCases": [{"userMessage": "x"}]}),
        (
            "/optimize/prompt",
            {
                **OPTIMIZE_BODY,
                "failingCases": [{"userMessage": "x", "response": "y", "reward": "high"}],
            },
        ),
        ("/judge/constitutional", {**JUDGE_BODY, "principles": []}),
        ("/judge/constitutional", {"principles": ["p"]}),
        ("/judge/constitutional", {**JUDGE_BODY, "principles": "be nice"}),
    ],
)
def test_llm_route_request_validation_is_422(client: TestClient, path: str, body: dict) -> None:
    assert client.post(path, json=body, headers=AUTH).status_code == 422


def test_optimize_prompt_happy_path(
    client: TestClient, use_settings: Callable[..., Settings], monkeypatch: pytest.MonkeyPatch
) -> None:
    use_settings(llm_api_key_present=True, llm_model="claude-test")
    reply = {"candidates": [{"prompt": "Better", "rationale": "r", "expectedImprovement": "large"}]}
    fake = FakeAnthropic(json.dumps(reply))
    monkeypatch.setattr(llm, "_CLIENT", fake)

    r = client.post("/optimize/prompt", json=OPTIMIZE_BODY, headers=AUTH)
    assert r.status_code == 200
    assert r.json() == reply
    assert fake.calls[0]["model"] == "claude-test"
    assert "refund?" in fake.calls[0]["messages"][0]["content"]


def test_judge_happy_path_clamps(
    client: TestClient, use_settings: Callable[..., Settings], monkeypatch: pytest.MonkeyPatch
) -> None:
    use_settings(llm_api_key_present=True)
    reply = {
        "score": 3.5,
        "violations": [{"principle": "Never invent policy.", "explanation": "x"}],
    }
    monkeypatch.setattr(llm, "_CLIENT", FakeAnthropic(json.dumps(reply)))

    r = client.post("/judge/constitutional", json=JUDGE_BODY, headers=AUTH)
    assert r.status_code == 200
    assert r.json() == {"score": 1.0, "violations": reply["violations"]}


@pytest.mark.parametrize("reply", [RuntimeError("rate limited"), "not json at all"])
def test_llm_failure_is_502(
    client: TestClient,
    use_settings: Callable[..., Settings],
    monkeypatch: pytest.MonkeyPatch,
    reply: object,
) -> None:
    use_settings(llm_api_key_present=True)
    monkeypatch.setattr(llm, "_CLIENT", FakeAnthropic(reply))  # type: ignore[arg-type]
    r = client.post("/judge/constitutional", json=JUDGE_BODY, headers=AUTH)
    assert r.status_code == 502
    _assert_error_shape(r.json(), 502)
