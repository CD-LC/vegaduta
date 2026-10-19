"""Per-tenant GitHub credential storage, encrypted at rest.

Part of the bring-your-own-repo pivot
(`docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md` §1). A tenant admin
pastes a fine-grained Personal Access Token scoped to exactly one repository
with Contents: read/write + Pull requests: read/write; from then on
app-builder pushes generated code to a branch in THAT repo and opens a PR
there, instead of writing to a checkout on our host and running a preview
container we pay for.

## Why a PAT and not a GitHub App

Recorded in the contract, restated here because it is the first question
anyone reading this file will have: a GitHub App is the more polished
long-term answer but needs an interactive one-time registration on
github.com (create the app, generate a private key, set a webhook secret)
that cannot be done autonomously, and it adds a platform-level secret (the
App's private key) to operate and rotate. Phase 1 ships per-tenant PATs:
zero new platform-level secrets beyond the encryption key below, and it
fully replaces the Docker preview infra, which is the actual goal. A GitHub
App is a valid Phase 2 if the PAT UX proves to be real friction.

## Storage and key

`data/tenants/<tenant_id>/github.json` — one file per tenant, the same
file-per-tenant shape `projects.py` already uses, so a tenant's credential
can never be read through another tenant's path (every path is built from
the authenticated `TenantContext`, never from request input).

Encrypted with Fernet under `APP_BUILDER_ENCRYPTION_KEY` — a NEW key, never
core's `APP_ENCRYPTION_KEY`. See `settings.py` for the trust-boundary
reasoning.

## Write-only posture

The raw token is:
  * never logged (this module has no logging calls, and every GitHub error
    message is passed through `github_client.redact()`),
  * never returned by any route after the initial connect — `status()` and
    `public_dict()` deliberately omit both the plaintext and the ciphertext,
  * decrypted just-in-time by `token_for()` inside the request that needs
    it, held in a local variable, and dropped when that request ends. Nothing
    caches it: not the `Project` record, not the audit log, not a module
    global.
"""

from __future__ import annotations

import json
import os
import stat
import threading
import time
from dataclasses import asdict, dataclass, field

from . import github_client
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, tenant_root

_LOCK = threading.Lock()

_STORE_FILENAME = "github.json"


class GitHubNotConnectedError(RuntimeError):
    """No GitHub repo is connected for this tenant. Surfaced as 409 — the
    caller's next step is to connect one, not to retry."""


class EncryptionNotConfiguredError(RuntimeError):
    """APP_BUILDER_ENCRYPTION_KEY is missing or not a valid Fernet key.
    Surfaced as 503: this is a deployment gap, not the caller's fault, and we
    refuse to store a token in the clear as a fallback."""


class CredentialDecryptError(RuntimeError):
    """A stored token could not be decrypted — almost always means the
    encryption key was rotated or replaced. Surfaced as 409 with a
    reconnect instruction rather than a 500, because reconnecting is the
    actual fix."""


@dataclass
class GitHubConnection:
    tenant_id: str
    repo_full_name: str
    encrypted_token: str
    connected_by: str | None = None
    connected_at: float = field(default_factory=time.time)
    default_branch: str = "main"

    def public_dict(self) -> dict:
        """Everything a caller may see. Deliberately omits
        `encrypted_token` as well as the plaintext — a ciphertext is still
        credential material and has no business in an API response."""
        return {
            "connected": True,
            "repo_full_name": self.repo_full_name,
            "default_branch": self.default_branch,
            "connected_by": self.connected_by,
            "connected_at": self.connected_at,
            "repo_url": f"https://github.com/{self.repo_full_name}",
        }


# --------------------------------------------------------------------- #
# encryption

def _fernet(settings: Settings):
    key = (settings.app_builder_encryption_key or "").strip()
    if not key:
        raise EncryptionNotConfiguredError(
            "GitHub connection is not configured on this deployment: "
            "APP_BUILDER_ENCRYPTION_KEY is unset, and a token will not be "
            "stored unencrypted.")
    try:
        from cryptography.fernet import Fernet  # type: ignore
    except ImportError as e:  # pragma: no cover - cryptography is a dep
        raise EncryptionNotConfiguredError(
            "GitHub connection requires the `cryptography` package.") from e
    try:
        return Fernet(key.encode("utf-8"))
    except Exception as e:
        # str(e) here is Fernet's own "must be 32 url-safe base64-encoded
        # bytes" wording — it never echoes the key back.
        raise EncryptionNotConfiguredError(
            "APP_BUILDER_ENCRYPTION_KEY is not a valid Fernet key "
            f"({e}). Generate one with: python -c \"from "
            "cryptography.fernet import Fernet; "
            "print(Fernet.generate_key().decode())\"") from None


def encrypt_token(token: str, settings: Settings = SETTINGS) -> str:
    return _fernet(settings).encrypt(token.encode("utf-8")).decode("ascii")


def token_for(conn: GitHubConnection, settings: Settings = SETTINGS) -> str:
    """Decrypt a tenant's PAT for the duration of ONE request.

    Callers must keep the return value in a local variable and never persist
    it, log it, put it on a `Project` record, or include it in a response.
    Every caller in this service does exactly that — see
    `scaffold._create_project_byo()` and `generate._promote_to_branch()`.
    """
    try:
        return _fernet(settings).decrypt(
            conn.encrypted_token.encode("utf-8")).decode("utf-8")
    except EncryptionNotConfiguredError:
        raise
    except Exception:
        # Never include the underlying exception: an InvalidToken repr can
        # carry the ciphertext.
        raise CredentialDecryptError(
            f"the stored GitHub token for {conn.repo_full_name} could not be "
            "decrypted (the encryption key has most likely changed). "
            "Disconnect and reconnect GitHub to fix this.") from None


# --------------------------------------------------------------------- #
# persistence

def _store_path(ctx: TenantContext, settings: Settings) -> str:
    return os.path.join(tenant_root(settings.data_dir, ctx), _STORE_FILENAME)


def load(ctx: TenantContext,
         settings: Settings = SETTINGS) -> GitHubConnection | None:
    path = _store_path(ctx, settings)
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
    except (json.JSONDecodeError, OSError):
        return None
    if not isinstance(raw, dict) or not raw.get("encrypted_token"):
        return None
    known = {f for f in GitHubConnection.__dataclass_fields__}
    return GitHubConnection(**{k: v for k, v in raw.items() if k in known})


def require(ctx: TenantContext,
            settings: Settings = SETTINGS) -> GitHubConnection:
    conn = load(ctx, settings)
    if conn is None:
        raise GitHubNotConnectedError(
            "no GitHub repository is connected for this tenant — connect one "
            "with POST /projects/github/connect first")
    return conn


def _save(ctx: TenantContext, conn: GitHubConnection,
          settings: Settings) -> GitHubConnection:
    path = _store_path(ctx, settings)
    tmp = path + ".tmp"
    with _LOCK:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(asdict(conn), f, indent=2)
        try:
            # Owner-only, best effort: a no-op on Windows dev boxes, real on
            # the Linux container this runs in.
            os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
        except OSError:
            pass
        os.replace(tmp, path)
    return conn


# --------------------------------------------------------------------- #
# the three operations behind the routes

def connect(ctx: TenantContext, token: str, repo_full_name: str,
            settings: Settings = SETTINGS) -> dict:
    """Validate the token against GitHub's real API, then store it encrypted.

    Order matters: the key is checked FIRST (so a misconfigured deployment
    fails without a pointless network round trip), then the token is proven
    to actually reach the repo with write access, and only then is anything
    written. A bad or under-scoped token is therefore rejected immediately
    rather than silently failing on the tenant's first real build.
    """
    token = (token or "").strip()
    if not token:
        raise ValueError("token is required")
    name = github_client.validate_repo_full_name(repo_full_name)

    _fernet(settings)  # fail fast on a missing/invalid key

    info = github_client.validate_repo_access(token, name, settings)

    conn = GitHubConnection(
        tenant_id=ctx.tenant_id,
        repo_full_name=info["repo_full_name"],
        encrypted_token=encrypt_token(token, settings),
        connected_by=ctx.subject,
        default_branch=info["default_branch"],
    )
    _save(ctx, conn, settings)
    return conn.public_dict()


def status(ctx: TenantContext, settings: Settings = SETTINGS) -> dict:
    conn = load(ctx, settings)
    if conn is None:
        return {"connected": False}
    return conn.public_dict()


def disconnect(ctx: TenantContext, settings: Settings = SETTINGS) -> bool:
    """Forget the stored credential. Deliberately does NOT touch anything in
    the tenant's repository — branches and PRs we opened are theirs and stay
    exactly where they are."""
    path = _store_path(ctx, settings)
    if not os.path.exists(path):
        return False
    with _LOCK:
        try:
            os.remove(path)
        except OSError:
            return False
    return True
