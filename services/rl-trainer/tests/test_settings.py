"""Settings are read from env vars at construction time, with local-dev defaults."""

from __future__ import annotations

import dataclasses

import pytest

from app.settings import Settings


def test_defaults_with_empty_environment() -> None:
    s = Settings()
    assert s.auth_mode == "internal"  # fail-closed default, not "none"
    assert s.internal_token == ""
    assert s.keycloak_issuer == ""  # never a localhost default (AGENTS.md rule 4)
    assert s.keycloak_additional_issuers == ""
    assert s.keycloak_issuers() == []
    assert s.keycloak_jwks_url == ""
    assert s.keycloak_audience is None
    assert s.llm_model == "claude-sonnet-5"
    assert s.llm_api_key_present is False
    assert s.llm_max_tokens == 8000
    assert s.data_dir == "./data"
    assert s.cors_origins == ""


def test_env_overrides(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("RL_TRAINER_AUTH_MODE", "keycloak")
    monkeypatch.setenv("RL_TRAINER_INTERNAL_TOKEN", "s3cret")
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_ISSUER", "https://auth.example.test/realms/r")
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_JWKS_URL", "http://keycloak:8080/certs")
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_AUDIENCE", "rl-trainer")
    monkeypatch.setenv("RL_TRAINER_LLM_MODEL", "claude-test")
    monkeypatch.setenv("RL_TRAINER_LLM_MAX_TOKENS", "1234")
    monkeypatch.setenv("RL_TRAINER_DATA_DIR", "/tmp/rl")
    monkeypatch.setenv("RL_TRAINER_CORS_ORIGINS", "https://a.test,https://b.test")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")

    s = Settings()
    assert s.auth_mode == "keycloak"
    assert s.internal_token == "s3cret"
    assert s.keycloak_issuer == "https://auth.example.test/realms/r"
    assert s.keycloak_issuers() == ["https://auth.example.test/realms/r"]
    assert s.keycloak_jwks_url == "http://keycloak:8080/certs"
    assert s.keycloak_audience == "rl-trainer"
    assert s.llm_model == "claude-test"
    assert s.llm_max_tokens == 1234
    assert s.llm_api_key_present is True
    assert s.data_dir == "/tmp/rl"
    assert s.cors_origins == "https://a.test,https://b.test"


def test_api_key_is_never_stored_only_its_presence(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-very-secret")
    s = Settings()
    assert "sk-very-secret" not in repr(s)
    assert all(v != "sk-very-secret" for v in dataclasses.asdict(s).values())


@pytest.mark.parametrize("value", ["", "   ", "\t\n"])
def test_blank_api_key_counts_as_absent(monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", value)
    assert Settings().llm_api_key_present is False


@pytest.mark.parametrize("value", ["abc", "", "12.5"])
def test_invalid_max_tokens_fails_at_construction(
    monkeypatch: pytest.MonkeyPatch, value: str
) -> None:
    monkeypatch.setenv("RL_TRAINER_LLM_MAX_TOKENS", value)
    with pytest.raises(ValueError):
        Settings()


def test_settings_are_frozen() -> None:
    s = Settings()
    with pytest.raises(dataclasses.FrozenInstanceError):
        s.auth_mode = "none"  # type: ignore[misc]


def test_additional_issuers_shared_name_and_service_name(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_ISSUER", "https://auth.new.test/realms/r")
    monkeypatch.setenv("KEYCLOAK_ADDITIONAL_ISSUERS", " https://auth.old.test/realms/r ,")
    assert Settings().keycloak_issuers() == [
        "https://auth.new.test/realms/r",
        "https://auth.old.test/realms/r",
    ]
    # The service-specific name wins over the shared compose name.
    monkeypatch.setenv("RL_TRAINER_KEYCLOAK_ADDITIONAL_ISSUERS", "https://auth.alt.test/realms/r")
    assert Settings().keycloak_issuers()[1:] == ["https://auth.alt.test/realms/r"]
