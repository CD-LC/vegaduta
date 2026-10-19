"""SEC-044: the worker must not start without real LiveKit credentials.

config.py used to default LIVEKIT_API_KEY/LIVEKIT_API_SECRET to the published
"devkey"/"devsecret-change-me-32-bytes-min" pair. These tests pin the
fail-closed replacement: no defaults, a clear startup error, and the old
placeholders refused even when set explicitly.
"""

import importlib

import pytest

from app import config

REAL_KEY = "APIk3yForTests"
REAL_SECRET = "a-real-looking-secret-that-is-long-enough-000"


@pytest.fixture
def reloaded_config(monkeypatch):
    """Reload config.py under a controlled environment, then restore it."""

    def _load(**env):
        for name in ("LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"):
            monkeypatch.delenv(name, raising=False)
        for name, value in env.items():
            monkeypatch.setenv(name, value)
        return importlib.reload(config)

    yield _load
    monkeypatch.undo()
    importlib.reload(config)


def test_no_default_credentials_when_env_unset(reloaded_config):
    cfg = reloaded_config()
    assert cfg.LIVEKIT_API_KEY == ""
    assert cfg.LIVEKIT_API_SECRET == ""


def test_startup_check_fails_when_env_unset(reloaded_config):
    cfg = reloaded_config()
    with pytest.raises(cfg.ConfigError) as exc:
        cfg.require_livekit_credentials()
    msg = str(exc.value)
    assert "LIVEKIT_API_KEY and LIVEKIT_API_SECRET must be set" in msg


def test_startup_check_passes_with_real_env(reloaded_config):
    cfg = reloaded_config(LIVEKIT_API_KEY=REAL_KEY, LIVEKIT_API_SECRET=REAL_SECRET)
    assert cfg.LIVEKIT_API_KEY == REAL_KEY
    cfg.require_livekit_credentials()  # does not raise


def test_whitespace_only_env_counts_as_unset(reloaded_config):
    cfg = reloaded_config(LIVEKIT_API_KEY="   ", LIVEKIT_API_SECRET=REAL_SECRET)
    with pytest.raises(cfg.ConfigError, match="LIVEKIT_API_KEY must be set"):
        cfg.require_livekit_credentials()


@pytest.mark.parametrize(
    ("key", "secret", "missing"),
    [
        ("", REAL_SECRET, "LIVEKIT_API_KEY must be set"),
        (REAL_KEY, "", "LIVEKIT_API_SECRET must be set"),
        ("", "", "LIVEKIT_API_KEY and LIVEKIT_API_SECRET must be set"),
    ],
)
def test_each_missing_value_is_named(key, secret, missing):
    with pytest.raises(config.ConfigError, match=missing):
        config.require_livekit_credentials(key, secret)


@pytest.mark.parametrize(
    ("key", "secret"),
    [
        ("devkey", REAL_SECRET),
        (REAL_KEY, "devsecret-change-me-32-bytes-min"),
        ("devkey", "devsecret-change-me-32-bytes-min"),
    ],
)
def test_old_published_placeholders_are_refused(key, secret):
    with pytest.raises(config.ConfigError, match="placeholder"):
        config.require_livekit_credentials(key, secret)


def test_error_never_echoes_the_secret():
    with pytest.raises(config.ConfigError) as exc:
        config.require_livekit_credentials("devkey", REAL_SECRET)
    assert REAL_SECRET not in str(exc.value)


def test_startup_gate(monkeypatch):
    """The gate main.py's __main__ runs: creds required except download-files."""
    monkeypatch.setattr(config, "LIVEKIT_API_KEY", "")
    monkeypatch.setattr(config, "LIVEKIT_API_SECRET", "")
    for argv in (["app.main", "start"], ["app.main", "dev"], ["app.main"]):
        with pytest.raises(config.ConfigError):
            config.check_startup_config(argv)
    config.check_startup_config(["app.main", "download-files"])  # no creds needed

    monkeypatch.setattr(config, "LIVEKIT_API_KEY", REAL_KEY)
    monkeypatch.setattr(config, "LIVEKIT_API_SECRET", REAL_SECRET)
    config.check_startup_config(["app.main", "start"])


def test_main_calls_the_gate_before_run_app():
    """main.py is not imported here (it pulls in native livekit plugins), so pin
    the wiring textually: the gate must run before cli.run_app."""
    from pathlib import Path

    src = (Path(__file__).resolve().parents[1] / "app" / "main.py").read_text(encoding="utf-8")
    main_block = src[src.index('if __name__ == "__main__":') :]
    assert "config.check_startup_config(sys.argv)" in main_block
    assert main_block.index("check_startup_config") < main_block.index("cli.run_app(")
    assert "sys.exit(" in main_block
