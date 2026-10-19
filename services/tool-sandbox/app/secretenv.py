"""Turning resolved vault secrets into environment variables for the harness.

The contract, in one line: **secrets go to the trusted harness through the
environment, and the harness is the only thing in the process that can read
them.**

Why the environment and not a file in the work directory: a file would put
plaintext at rest, however briefly, on a volume that outlives the process if
the container is killed mid-run. The environment of a process that is about to
be replaced by execve is the narrower carrier, and `proc.run` already takes an
``extra_env`` for exactly this shape of thing.

Why this is safe from the tenant program, which shares that environment:

* **Python** - the builtin allow-list (``policy.PYTHON_ALLOWED_BUILTINS``) has
  no ``open``, ``eval``, ``exec``, ``compile``, ``globals``, ``vars`` or
  ``getattr``, and ``_guarded_import`` refuses every module outside
  ``PYTHON_ALLOWED_MODULES``, which excludes ``os`` and ``sys``. There is no
  expressible way to reach ``os.environ``. The harness additionally *deletes*
  these variables from ``os.environ`` once it has read them, so even a future
  hole in that list finds nothing left.
* **Java** - ``policy.JAVA_DENIED_CLASSES`` denies ``java.lang.System`` (the
  comment there already calls it "the door to getenv"), plus ``Runtime``,
  ``ProcessBuilder``, ``ProcessHandle``, ``Class`` and ``ClassLoader``, so
  neither ``System.getenv`` nor a reflective route to it verifies.

If either of those allow-lists is ever widened, this file is the thing that
becomes unsafe - which is why the reasoning lives here rather than in a commit
message.
"""

from __future__ import annotations

# Prefix chosen so the harness can find these without being told which names to
# expect, and so they are obviously ours in a `ps e` dump during debugging.
SECRET_ENV_PREFIX = "VEGA_SECRET_"

# Mirrors SecretVaultService.VALID_NAME in core. A name that does not match is
# dropped rather than sanitised: core is the only writer of this field, so a
# mismatch means the two sides have drifted and silently repairing it would
# hide that.
_VALID_NAME_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")


def _is_valid_name(name: str) -> bool:
    return (
        bool(name)
        and len(name) <= 64
        and name[0].isalpha()
        and name[0].isupper()
        and all(c in _VALID_NAME_CHARS for c in name)
    )


def secret_env(secrets: dict[str, str] | None, settings) -> dict[str, str]:
    """Builds the ``extra_env`` map for one execution.

    Returns an empty dict for the overwhelmingly common no-secrets case, so
    ``proc.run`` is called exactly as it was before this feature existed.

    Enforces two budgets from ``settings`` so a caller cannot blow out the
    process environment: a count cap and a per-value byte cap. Exceeding either
    drops the offending entry rather than failing the run - a tool that is
    missing one credential fails with a clear upstream error, whereas a run
    that never starts gives the tenant nothing to act on.
    """
    if not secrets:
        return {}

    max_count = getattr(settings, "max_secrets", 32)
    max_bytes = getattr(settings, "max_secret_bytes", 8192)

    env: dict[str, str] = {}
    for name, value in secrets.items():
        if len(env) >= max_count:
            break
        if not isinstance(name, str) or not isinstance(value, str):
            continue
        if not _is_valid_name(name):
            continue
        if len(value.encode("utf-8")) > max_bytes:
            continue
        env[SECRET_ENV_PREFIX + name] = value
    return env
