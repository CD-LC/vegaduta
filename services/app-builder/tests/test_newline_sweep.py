"""Every ``^...$`` allowlist in app-builder, driven with newline-bearing input.

``$`` also matches before ONE trailing newline, so ``re.match(r"^...$", s)``
accepts ``s + "\\n"``. Each validator below is exercised through the caller that
actually reaches it, not by reading the code:

* github_client.validate_repo_full_name / validate_branch: used ``.match``;
  the value ``"owner/repo\\n/"`` survived ``strip().strip("/")`` as
  ``"owner/repo\\n"``. Now ``fullmatch``.
* The public routes already refuse such a repo name (pydantic's pattern does not
  accept a trailing newline), and a branch is stripped first; both stay pinned.
* figma (``urlparse`` deletes CR/LF; node ids are stripped), mobile_store
  (config values are stripped) and dataapi_keys (the key is stripped) cannot
  see a trailing newline by construction; pinned here so a refactor that drops
  the strip fails loudly.

Run: python -m tests.test_newline_sweep   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import dataapi_keys, figma, github_client, mobile_store  # noqa: E402
from app.github_client import InvalidRepoNameError  # noqa: E402
from app.settings import Settings  # noqa: E402
from tests.test_repo_sync import HDRS, REACT_SEED, TOKEN, Scenario  # noqa: E402

PASS = 0
FAIL = 0
KEY = "ABCDEFGHIJ"


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def raises(fn, *args, exc=Exception) -> bool:
    try:
        fn(*args)
    except exc:
        return True
    return False


def test_github_client_validators():
    print("== github_client.validate_repo_full_name / validate_branch ==")
    for name in ("acme/widgets\n/", "acme/widgets\n/\n", "acme\n/widgets", "acme/widgets\r\n/"):
        check(
            f"validate_repo_full_name refuses {name!r}",
            raises(github_client.validate_repo_full_name, name, exc=InvalidRepoNameError),
        )
    for branch in ("feat/x\n/", "feat\n/x", "feat/x\r\n/", "main\n/"):
        check(
            f"validate_branch refuses {branch!r}",
            raises(github_client.validate_branch, branch, exc=InvalidRepoNameError),
        )
    check(
        "a plain repo name and its trimmed form are accepted",
        github_client.validate_repo_full_name(" acme/widgets/ ") == "acme/widgets"
        and github_client.validate_repo_full_name("acme/widgets\n") == "acme/widgets",
    )
    check(
        "a plain branch and its trimmed form are accepted",
        github_client.validate_branch("feat/x") == "feat/x"
        and github_client.validate_branch("feat/x\n") == "feat/x",
    )


def test_routes():
    print("== routes that take a repo name or a branch ==")
    sc = Scenario(REACT_SEED)
    for name in ("acme/widgets\n", "acme/widgets\n/"):
        r = sc.client.post(
            "/projects/github/connect",
            headers=HDRS,
            json={"token": TOKEN, "repo_full_name": name},
        )
        check(f"connect refuses repo_full_name={name!r}", r.status_code == 422, str(r.status_code))
        r = sc.client.post("/projects/import", headers=HDRS, json={"repo_full_name": name})
        check(f"import refuses repo_full_name={name!r}", r.status_code == 422, str(r.status_code))
    sc.connect()
    r = sc.imp(project_id="q1", base_branch="main\n")
    gh = r.json().get("github") or {} if r.status_code == 201 else {}
    check(
        "a trailing newline on base_branch is trimmed, never stored",
        r.status_code == 201 and gh.get("base_branch") == "main",
        f"{r.status_code} {gh.get('base_branch')!r}",
    )
    r = sc.imp(project_id="q2", base_branch="main\n/")
    check(
        "an embedded newline in base_branch is refused",
        r.status_code in (400, 422),
        str(r.status_code),
    )


def test_callers_that_strip_first():
    print("== figma / mobile_store / dataapi_keys never see a newline ==")
    for url in (
        f"https://www.figma.com/design/{KEY}\n/x",
        f"https://www.figma.com/design/{KEY}/x?node-id=1-2\n",
    ):
        key, node = figma.parse_figma_url(url)
        check(
            f"figma key and node are clean for {url!r}",
            key == KEY and node in (None, "1:2"),
            f"{key!r} {node!r}",
        )
    check(
        "an encoded newline in the figma key is refused",
        raises(
            figma.parse_figma_url, f"https://www.figma.com/design/{KEY}%0A/x", exc=figma.FigmaError
        ),
    )
    for raw in ("1-2\n", "1-2%0A", "I1-2;3-4\n"):
        check(
            f"normalize_node_id({raw!r}) carries no newline",
            "\n" not in figma.normalize_node_id(raw),
        )
    check(
        "an embedded newline in a node id is refused",
        raises(figma.normalize_node_id, "1-\n2", exc=figma.FigmaError),
    )

    cfg = {
        "app_name": "A",
        "short_description": "s",
        "full_description": "f",
        "package_id": "com.acme.app\n",
        "version_name": "1.0.0\n",
    }
    out = mobile_store.validate_store_config(cfg)
    check(
        "store config values are trimmed, never stored with a newline",
        out["package_id"] == "com.acme.app" and out["version_name"] == "1.0.0",
        str(out),
    )
    for field, value in (("package_id", "com.acme\n.app"), ("version_name", "1.0.0\n-x")):
        bad = dict(cfg, **{field: value})
        check(
            f"an embedded newline in {field} is refused",
            raises(mobile_store.validate_store_config, bad, exc=mobile_store.GenerateError),
        )

    key_id, secret = "0" * 16, "A" * 43
    key = f"vdk_{key_id}_{secret}"
    entry = {"h": dataapi_keys._hash(secret), "t": "tenant_a", "p": "p1", "k": "service"}
    real_load = dataapi_keys._load_index
    dataapi_keys._load_index = lambda _settings: {key_id: entry}
    try:
        info = dataapi_keys.authenticate_key(key + "\n", Settings(data_dir="/nonexistent"))
        check(
            "a key presented with a trailing newline is trimmed, then matched in full",
            info.key_id == key_id,
        )
        malformed = None
        try:
            dataapi_keys.authenticate_key(
                key[:30] + "\n" + key[30:], Settings(data_dir="/nonexistent")
            )
        except dataapi_keys.DataError as e:
            malformed = e
        check(
            "a key with an embedded newline is refused as malformed",
            malformed is not None and "malformed" in str(malformed.args),
            str(malformed),
        )
    finally:
        dataapi_keys._load_index = real_load


def main() -> int:
    test_github_client_validators()
    test_routes()
    test_callers_that_strip_first()
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
