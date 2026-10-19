#!/usr/bin/env node
// Fixes "Offline tokens not allowed for the user or client" for every IDE /
// browser plugin sign-in, at the source.
//
// WHAT BREAKS: all plugins (VS Code, JetBrains, Chrome, Eclipse) sign in with
// scope `openid offline_access` against the `agentic-ai-ide` client - offline
// tokens idle out at 30 days, where a normal session dies after the realm's
// 4-hour SSO idle. Keycloak only issues one when the USER holds the
// `offline_access` realm role. A user who lacks it cannot sign in to any
// plugin at all: the device approval succeeds, then the token exchange returns
//   {"error":"not_allowed","error_description":"Offline tokens not allowed for the user or client"}
//
// WHY USERS LACK IT (corrected 2026-09-18): Keycloak maps the realm's
// default-role composite `default-roles-agentic-ai` - which carries
// offline_access, uma_authorization and the account roles - DIRECTLY on each
// user. Core's KeycloakAdminClient.replaceUserRealmRoles removed every
// directly-mapped realm role not in the requested set, and every signup /
// onboarding path requests exactly {tenant-admin}, so it deleted the default
// role on every signup: 12 of 14 production users. Fixed in core by 0e660ccd,
// which now never removes a `default-roles-*` mapping. (This header first
// blamed the 2026-09-09 partialImport migration; that was wrong.)
//
// WHY THIS RE-ATTACHES THE DEFAULT ROLE, NOT BARE offline_access: the fixed
// core code protects ONLY `default-roles-*`. A directly-granted offline_access
// is still stripped the next time a user's roles are replaced, so granting it
// directly - what the first version of this script did - is a stopgap that
// erodes. Re-attaching the composite is the mapping core now preserves, and it
// also restores uma_authorization and the account roles the same bug removed.
// deploy/keycloak/restore-default-role.py does the same job in Python; either
// is correct.
//
// WHAT THIS DOES (idempotent, safe to re-run):
//   1. checks the `offline_access` realm role exists;
//   2. ensures the default-role composite includes it, so every FUTURE user
//      gets it;
//   3. re-attaches `default-roles-<realm>` to every EXISTING user whose
//      effective roles lack offline_access.
// It never removes a role, never touches any other mapping, and never
// changes a client.
//
// DRY RUN BY DEFAULT. Nothing is written without --apply.
//
//   KEYCLOAK_URL=https://auth.example.com \
//   KEYCLOAK_ADMIN_USER=admin KEYCLOAK_ADMIN_PASSWORD=... \
//     node scripts/keycloak/grant-offline-access.mjs            # report only
//     node scripts/keycloak/grant-offline-access.mjs --apply    # fix
//
// Credentials come from the environment only - never an argument (shell
// history, `ps`). The admin password authenticates to the MASTER realm's
// admin-cli, exactly like kcadm.sh. Nothing sensitive is printed.
//
// There is no default URL: pass your environment's public auth host,
// e.g. https://auth.example.com (production) or https://auth.staging.example.com.

const apply = process.argv.includes("--apply");
const realm = process.env.KEYCLOAK_REALM || "agentic-ai";
const url = (process.env.KEYCLOAK_URL || "").replace(/\/+$/, "");
const adminUser = process.env.KEYCLOAK_ADMIN_USER || "admin";
const adminPassword = process.env.KEYCLOAK_ADMIN_PASSWORD;

class DieError extends Error {}

/** Print and unwind. NOT process.exit(): exiting while fetch's keep-alive
 * sockets are still open crashes Node on Windows with
 * "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)" - which is what the
 * first real run printed right after a (correct) 401. Throwing lets main()'s
 * catch set the exit code and Node shut its handles down normally. */
function die(msg) {
  console.error(`[offline-access] ${msg}`);
  throw new DieError(msg);
}

/** A Cloudflare edge in front of the auth host may 403 non-browser default user
 * agents on the Keycloak Admin API (it has blocked
 * urllib's). Node's fetch sends "node". Identify honestly, in a shape the
 * edge accepts. */
const UA = "Mozilla/5.0 (compatible; vegaduta-grant-offline-access/1.0)";

async function main() {
if (!url) die("Set KEYCLOAK_URL (e.g. https://auth.example.com). There is no default.");
if (!/^https?:\/\//.test(url)) die(`KEYCLOAK_URL must be an http(s) origin, got ${url}`);
if (!adminPassword) die("Set KEYCLOAK_ADMIN_PASSWORD (the master-realm admin). Read from the environment only.");

async function adminToken() {
  const res = await fetch(`${url}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", "User-Agent": UA },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: "admin-cli",
      username: adminUser,
      password: adminPassword,
    }),
  });
  if (res.status === 403) die("admin sign-in blocked (403) - that is the Cloudflare edge, not Keycloak. Retry from a normal network; the credentials were never checked.");
  if (!res.ok) die(`admin sign-in failed (${res.status}). The username is "${adminUser}" and the password is Key Vault secret keycloak-admin-password.`);
  return (await res.json()).access_token;
}

let token = await adminToken();
let tokenAt = Date.now();

async function api(method, path, body) {
  // Master-realm admin tokens live ~60s; a large user list outlives one.
  if (Date.now() - tokenAt > 45_000) {
    token = await adminToken();
    tokenAt = Date.now();
  }
  const res = await fetch(`${url}/admin/realms/${realm}${path}`, {
    method,
    headers: {
      "User-Agent": UA,
      Authorization: `Bearer ${token}`,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${(await res.text()).slice(0, 200)}`);
  const text = await res.text();
  return text ? JSON.parse(text) : {};
}

console.log(`[offline-access] ${url}  realm=${realm}  mode=${apply ? "APPLY" : "dry-run"}`);

// 1. The role itself.
const offline = await api("GET", "/roles/offline_access");
if (!offline) {
  die(
    "The realm has no `offline_access` role. That is unusual (Keycloak creates it with every realm) and " +
      "this script will not invent one - check the realm, then re-run."
  );
}

// 2. Future users: the default-role composite.
const defaultRoleName = `default-roles-${realm}`;
const defaultComposites = await api("GET", `/roles/${encodeURIComponent(defaultRoleName)}/composites/realm`);
if (defaultComposites === null) {
  console.log(`[offline-access] WARN: no ${defaultRoleName} role - future users will not get offline_access automatically.`);
} else if (defaultComposites.some((r) => r.name === "offline_access")) {
  console.log(`[offline-access] ok   ${defaultRoleName} already includes offline_access (future users covered)`);
} else if (apply) {
  await api("POST", `/roles/${encodeURIComponent(defaultRoleName)}/composites`, [offline]);
  console.log(`[offline-access] FIX  added offline_access to ${defaultRoleName}`);
} else {
  console.log(`[offline-access] WOULD add offline_access to ${defaultRoleName}`);
}

// 3. Existing users, paged. The test is whether the default role is DIRECTLY
// mapped - deliberately NOT "does the user effectively have offline_access".
// Users fixed by this script's first version hold offline_access as a direct
// grant, which passes an effective-roles check while still being exactly the
// fragile mapping core strips; they must get the composite too.
const defaultRole = await api("GET", `/roles/${encodeURIComponent(defaultRoleName)}`);
if (!defaultRole) {
  die(`No ${defaultRoleName} role to re-attach. Check the realm before re-running.`);
}
const PAGE = 100;
let first = 0;
let scanned = 0;
const missing = [];
for (;;) {
  const users = await api("GET", `/users?first=${first}&max=${PAGE}&briefRepresentation=true`);
  if (!users || users.length === 0) break;
  for (const u of users) {
    scanned += 1;
    const direct = (await api("GET", `/users/${u.id}/role-mappings/realm`)) || [];
    if (!direct.some((r) => r.name === defaultRoleName)) missing.push(u);
  }
  if (users.length < PAGE) break;
  first += PAGE;
}

console.log(`[offline-access] scanned ${scanned} user(s); ${missing.length} lack ${defaultRoleName}`);
for (const u of missing) {
  // Username only, never email or ids - enough to recognise, not a data dump.
  if (apply) {
    await api("POST", `/users/${u.id}/role-mappings/realm`, [defaultRole]);
    console.log(`[offline-access] FIX  re-attached ${defaultRoleName} to ${u.username}`);
  } else {
    console.log(`[offline-access] WOULD re-attach ${defaultRoleName} to ${u.username}`);
  }
}

if (!apply && missing.length > 0) {
  console.log("\n[offline-access] dry run - nothing changed. Re-run with --apply.");
} else {
  console.log(`\n[offline-access] done. Affected users must sign in to the plugin AGAIN - a session that already failed does not retry itself.`);
}
}

main().catch((err) => {
  if (!(err instanceof DieError)) console.error(`[offline-access] ${err instanceof Error ? err.message : err}`);
  process.exitCode = 1;
});
