#!/usr/bin/env python3
"""Re-attach Keycloak's realm default role to every user that lost it.

Symptom (all clients, 2026-09-18): "Offline tokens are not allowed for this
user" at sign-in on Android, iOS, VS Code, Chrome, JetBrains - every client
that asks for `offline_access`. Cause: `KeycloakAdminClient.replaceUserRealmRoles`
deleted the direct mapping of `default-roles-<realm>` (the composite that
carries offline_access / uma_authorization / account roles) on every signup,
because signup passes exactly {tenant-admin}. The code fix stops it recurring;
this script repairs users that were already stripped. Additive only: it POSTs
one role mapping and removes nothing.

    python deploy/keycloak/restore-default-role.py --base https://auth.example.com \
        --password "$(az keyvault secret show --vault-name <your-key-vault> \
                       --name keycloak-admin-password --query value -o tsv)"
    python deploy/keycloak/restore-default-role.py --base http://localhost:8180 --password admin   # staging compose

Add --dry-run to only list who would be fixed.
"""
import argparse
import json
import urllib.parse
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 vegaduta-ops"}  # Cloudflare 403s urllib's default UA


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="Keycloak base URL, e.g. https://auth.example.com")
    ap.add_argument("--realm", default="agentic-ai")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--password", required=True, help="master-realm admin password")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    def req(url, form=None, token=None, method=None, body=None):
        headers = dict(UA)
        data = None
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        elif form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        resp = urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=60)
        raw = resp.read()
        return json.loads(raw) if raw else None

    token = req(
        f"{args.base}/realms/master/protocol/openid-connect/token",
        form={"grant_type": "password", "client_id": "admin-cli", "username": args.admin_user, "password": args.password},
    )["access_token"]
    admin = f"{args.base}/admin/realms/{args.realm}"
    default_role = req(f"{admin}/roles/default-roles-{args.realm}", token=token)

    users = []
    first = 0
    while True:
        page = req(f"{admin}/users?first={first}&max=100&briefRepresentation=true", token=token)
        if not page:
            break
        users.extend(page)
        first += len(page)

    fixed, already = [], 0
    for user in users:
        effective = {r["name"] for r in req(f"{admin}/users/{user['id']}/role-mappings/realm/composite", token=token)}
        if "offline_access" in effective:
            already += 1
            continue
        if args.dry_run:
            fixed.append((user.get("username"), "would fix"))
            continue
        req(f"{admin}/users/{user['id']}/role-mappings/realm", token=token, method="POST",
            body=[{"id": default_role["id"], "name": default_role["name"]}])
        after = {r["name"] for r in req(f"{admin}/users/{user['id']}/role-mappings/realm/composite", token=token)}
        fixed.append((user.get("username"), "ok" if "offline_access" in after else "STILL MISSING"))

    print(f"{len(users)} users; {already} already had offline_access; {len(fixed)} {'would be ' if args.dry_run else ''}repaired")
    for username, status in fixed:
        print(f"  {username}: {status}")


if __name__ == "__main__":
    main()
