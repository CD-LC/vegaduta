"""Framework-free tests for app/github_credentials.py — the per-tenant
encrypted GitHub PAT store (contract §1).

Covers the three properties that actually matter for a credential:
  1. it is encrypted at rest and the plaintext never appears in the store file
  2. a bad / under-scoped / unreadable-repo token is rejected at connect time
     against GitHub's real API shape, and nothing is written when it is
  3. it is write-only after storage — no route or response hands it back

GitHub is mocked in-process (tests/fake_github.py) because this environment
has no GitHub credentials and no network access, and because a test must never
create branches in a real repository. See that module's docstring.

Run: python3 -m tests.test_github_credentials   (from the app-builder directory)
"""

from __future__ import annotations

import json
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from cryptography.fernet import Fernet  # noqa: E402

from app import github_client, github_credentials  # noqa: E402
from app.github_client import GitHubError  # noqa: E402
from app.github_credentials import (  # noqa: E402
    CredentialDecryptError,
    EncryptionNotConfiguredError,
    GitHubNotConnectedError,
)
from app.settings import Settings  # noqa: E402
from app.tenancy import TenantContext  # noqa: E402
from tests.fake_github import FakeGitHub  # noqa: E402

PASS = 0
FAIL = 0

API = "https://api.github.test"
TOKEN = "github_pat_11ABCDEFG_supersecretvalue"
REPO = "acme/widgets"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="appbuilder-test-ghcreds-")
    key = Fernet.generate_key().decode()
    settings = Settings(data_dir=tmp, app_builder_encryption_key=key,
                        github_api_url=API)
    ctx = TenantContext(tenant_id="tenant_a", subject="admin@acme.test")

    fake = FakeGitHub(api_base=API, token=TOKEN, repo=REPO)
    orig_httpx = github_client.httpx
    github_client.httpx = fake.shim()

    store = os.path.join(tmp, "tenants", "tenant_a", "github.json")

    try:
        print("== not connected yet ==")
        check("status() reports disconnected",
              github_credentials.status(ctx, settings) == {"connected": False})
        check("load() returns None",
              github_credentials.load(ctx, settings) is None)
        try:
            github_credentials.require(ctx, settings)
            check("require() raises GitHubNotConnectedError", False)
        except GitHubNotConnectedError:
            check("require() raises GitHubNotConnectedError", True)
        check("disconnect() on nothing returns False",
              github_credentials.disconnect(ctx, settings) is False)

        print("== a bad token is rejected BEFORE anything is stored ==")
        try:
            github_credentials.connect(ctx, "ghp_wrong", REPO, settings)
            check("wrong token rejected", False)
        except GitHubError as e:
            check("wrong token rejected with 401", e.status == 401, extra=str(e))
        check("nothing was written for a rejected token",
              not os.path.exists(store))

        print("== a token that cannot see the repo is rejected ==")
        try:
            github_credentials.connect(ctx, TOKEN, "someone/else", settings)
            check("unreachable repo rejected", False)
        except GitHubError as e:
            check("unreachable repo rejected with 404", e.status == 404,
                  extra=str(e))
            check("the message tells the user how to fix it",
                  "Repository access" in e.message, extra=e.message)
        check("still nothing written", not os.path.exists(store))

        print("== a read-only token is rejected (needs Contents: write) ==")
        fake.can_push = False
        try:
            github_credentials.connect(ctx, TOKEN, REPO, settings)
            check("read-only token rejected", False)
        except GitHubError as e:
            check("read-only token rejected with 403", e.status == 403,
                  extra=str(e))
        check("still nothing written", not os.path.exists(store))
        fake.can_push = True

        print("== a missing encryption key fails closed, with no store ==")
        no_key = Settings(data_dir=tmp, app_builder_encryption_key="",
                          github_api_url=API)
        before = len(fake.requests)
        try:
            github_credentials.connect(ctx, TOKEN, REPO, no_key)
            check("missing key rejected", False)
        except EncryptionNotConfiguredError as e:
            check("missing key rejected", True)
            check("the error says a token will not be stored unencrypted",
                  "unencrypted" in str(e), extra=str(e))
        check("no GitHub call is even attempted without a key",
              len(fake.requests) == before)
        check("still nothing written", not os.path.exists(store))

        print("== an invalid encryption key never echoes the key back ==")
        bad_key = Settings(data_dir=tmp, github_api_url=API,
                           app_builder_encryption_key="not-a-fernet-key")
        try:
            github_credentials.connect(ctx, TOKEN, REPO, bad_key)
            check("invalid key rejected", False)
        except EncryptionNotConfiguredError as e:
            check("invalid key rejected", True)
            check("the invalid key value is not in the message",
                  "not-a-fernet-key" not in str(e), extra=str(e))

        print("== a good token connects, and is encrypted at rest ==")
        result = github_credentials.connect(ctx, TOKEN, REPO, settings)
        check("connect() reports connected", result.get("connected") is True)
        check("connect() echoes the repo", result.get("repo_full_name") == REPO)
        check("connect() picked up the repo's default branch",
              result.get("default_branch") == "main")
        check("connect() records who did it",
              result.get("connected_by") == "admin@acme.test")
        check("connect() NEVER returns the token",
              TOKEN not in json.dumps(result), extra=json.dumps(result))
        check("connect() does not return the ciphertext either",
              "encrypted_token" not in result)

        check("the store file exists", os.path.isfile(store))
        raw = open(store, "r", encoding="utf-8").read()
        check("the token does NOT appear in plaintext in the store file",
              TOKEN not in raw)
        check("no substring of the token leaks either",
              "supersecretvalue" not in raw)
        stored = json.loads(raw)
        check("the store holds a Fernet ciphertext, not the token",
              stored["encrypted_token"] != TOKEN
              and stored["encrypted_token"].startswith("gAAAA"),
              extra=stored["encrypted_token"][:20])
        check("the ciphertext decrypts back to the exact token",
              Fernet(key.encode()).decrypt(
                  stored["encrypted_token"].encode()).decode() == TOKEN)

        print("== write-only after storage ==")
        st = github_credentials.status(ctx, settings)
        check("status() reports connected", st.get("connected") is True)
        check("status() never contains the token", TOKEN not in json.dumps(st))
        check("status() never contains the ciphertext",
              "encrypted_token" not in st, extra=str(sorted(st)))
        check("status() gives the UI a repo link",
              st.get("repo_url") == f"https://github.com/{REPO}")

        print("== token_for() round-trips just-in-time ==")
        conn = github_credentials.require(ctx, settings)
        check("token_for() returns the original token",
              github_credentials.token_for(conn, settings) == TOKEN)

        print("== a rotated key surfaces as reconnect, not a 500 ==")
        other = Settings(data_dir=tmp, github_api_url=API,
                         app_builder_encryption_key=Fernet.generate_key().decode())
        try:
            github_credentials.token_for(conn, other)
            check("decrypt under a different key raises", False)
        except CredentialDecryptError as e:
            check("decrypt under a different key raises CredentialDecryptError",
                  True)
            check("the error tells the user to reconnect",
                  "reconnect" in str(e).lower(), extra=str(e))
            check("the error carries no ciphertext",
                  stored["encrypted_token"] not in str(e))

        print("== tenant isolation ==")
        ctx_b = TenantContext(tenant_id="tenant_b", subject="other@else.test")
        check("tenant B sees no connection of tenant A's",
              github_credentials.status(ctx_b, settings) == {"connected": False})
        fake_b = FakeGitHub(api_base=API, token="ghp_b_token",
                            repo="other/repo")
        github_client.httpx = fake_b.shim()
        github_credentials.connect(ctx_b, "ghp_b_token", "other/repo", settings)
        github_client.httpx = fake.shim()
        check("tenant A's connection is untouched",
              github_credentials.require(ctx, settings).repo_full_name == REPO)
        check("tenant B's connection is its own",
              github_credentials.require(ctx_b, settings).repo_full_name
              == "other/repo")
        check("tenant B's store is a separate file",
              os.path.isfile(os.path.join(tmp, "tenants", "tenant_b",
                                          "github.json")))

        print("== the token is scrubbed even if GitHub reflects it back ==")
        fake.echo_token_in_error = True
        try:
            github_client.GitHubClient("ghp_leaky_token", settings).get_repo(REPO)
            check("reflected-credential request still fails", False)
        except GitHubError as e:
            check("reflected-credential request fails", e.status == 401)
            check("the reflected token is redacted out of the message",
                  "ghp_leaky_token" not in str(e), extra=str(e))
            check("redaction leaves a visible placeholder", "***" in str(e),
                  extra=str(e))
        fake.echo_token_in_error = False

        print("== malformed repo names are refused before any API call ==")
        for bad in ("not-a-repo", "a/b/c", "../../etc/passwd", "acme/wid gets"):
            try:
                github_client.validate_repo_full_name(bad)
                check(f"rejects {bad!r}", False)
            except github_client.InvalidRepoNameError:
                check(f"rejects {bad!r}", True)

        print("== disconnect forgets the credential ==")
        check("disconnect() returns True",
              github_credentials.disconnect(ctx, settings) is True)
        check("the store file is gone", not os.path.exists(store))
        check("status() reports disconnected again",
              github_credentials.status(ctx, settings) == {"connected": False})

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        github_client.httpx = orig_httpx
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
