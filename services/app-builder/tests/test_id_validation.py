"""Id allowlists must reject a trailing newline.

``re.match(r"^...$", s)`` also accepts ``s`` with ONE trailing "\\n" (``$``
matches before a final newline), so "abc\\n" used to pass the tenant/project id
checks. The validators now use ``fullmatch``.

Run: python3 -m tests.test_id_validation   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.tenancy import (  # noqa: E402
    TenantValidationError,
    validate_project_id,
    validate_tenant_id,
)

PASS = 0
FAIL = 0


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def rejects(fn, value: str) -> bool:
    try:
        fn(value)
    except TenantValidationError:
        return True
    return False


def main() -> int:
    print("== tenant id ==")
    check("plain id accepted", validate_tenant_id("tenant_a-1") == "tenant_a-1")
    check("trailing newline rejected", rejects(validate_tenant_id, "tenant_a\n"))
    check("embedded newline rejected", rejects(validate_tenant_id, "tenant\n_a"))

    print("== project id ==")
    check("plain id accepted", validate_project_id("My.app-1") == "My.app-1")
    check("trailing newline rejected", rejects(validate_project_id, "myapp\n"))
    check("embedded newline rejected", rejects(validate_project_id, "my\napp"))

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
