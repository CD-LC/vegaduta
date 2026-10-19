// load-test/lib/auth.js
//
// Keycloak Resource Owner Password Credentials (ROPC) grant helper, shared
// by every script in load-test/scripts/.
//
// Client id / realm confirmed 2026-08-05 by READING the platform's own
// source (not guessed) - see web/app/lib/auth.ts:20-22:
//   KEYCLOAK_REALM      default "agentic-ai"
//   KEYCLOAK_CLIENT_ID  default "agentic-ai-web"
// That is the platform's real public web client (no secret, standard-flow
// with PKCE, per deploy/keycloak/agentic-ai-realm.json) - exactly the shape
// ROPC needs: grant_type=password with client_id and no client_secret.
//
// USAGE PATTERN - call getAuthToken() from a script's setup() function,
// NEVER from the default (per-iteration) function:
//   export function setup() {
//     const token = getAuthToken();
//     return { token, ... };
//   }
//   export default function (data) {
//     // use data.token here on every iteration
//   }
// setup() runs exactly ONCE for the whole test run, regardless of VU count,
// and its return value is handed to every VU on every iteration via the
// `data` parameter. That means a single Keycloak login happens once and is
// reused by every VU/iteration for the life of the run, instead of
// re-authenticating on every single request. This is k6's own documented
// pattern for "authenticate once, reuse everywhere" (there is no true
// per-VU-singleton concept in k6's cooperative-multitasking model; setup()
// is the closest and correct equivalent, and is what all three scripts in
// this harness use).
//
// KNOWN LIMITATION (documented, not hidden): Keycloak access tokens are
// typically short-lived (the realm's accessTokenLifespan - commonly around
// 5 minutes unless configured otherwise). This harness's default staged
// ramp is 1m ramp-up + 3m hold + 30s ramp-down = ~4.5 minutes total, so a
// token minted at test start should comfortably outlive one default run.
// If you push DURATION/stages well past that combined window, the cached
// token can expire mid-run and every subsequent request will start failing
// with 401 - watch for a sudden spike in http_req_failed if you extend the
// stages beyond the defaults. Re-authenticating mid-run is intentionally
// NOT implemented here to keep this helper simple and auditable; a longer
//-running scenario would need to call getAuthToken() again periodically
// from inside default() (e.g. every N iterations) rather than relying
// solely on the one setup()-time token.
import http from "k6/http";
import { check } from "k6";

function requireEnv(name) {
  const val = __ENV[name];
  if (!val) {
    throw new Error(
      `[load-test/auth] Required environment variable ${name} is not set. ` +
        `Pass it with -e ${name}=... - refusing to run against an empty/undefined value.`
    );
  }
  return val;
}

// Performs the ROPC grant and returns the bearer access token as a string.
// Throws loudly (via k6 check() + an explicit Error) on any non-200
// response, a non-JSON body, or a JSON body missing access_token - rather
// than silently returning undefined and letting every subsequent request in
// the run fail with a confusing, hard-to-diagnose 401.
export function getAuthToken() {
  const keycloakUrl = requireEnv("KEYCLOAK_URL");
  const username = requireEnv("LOAD_TEST_USERNAME");
  const password = requireEnv("LOAD_TEST_PASSWORD");
  // Realm and client id are NOT required env vars - they default to this
  // platform's real, confirmed values (see header comment above) so a
  // normal run doesn't need to restate them. Override only if a session
  // truly needs a different realm/client (e.g. a dedicated test realm).
  const realm = __ENV.KEYCLOAK_REALM || "agentic-ai";
  const clientId = __ENV.KEYCLOAK_CLIENT_ID || "agentic-ai-web";

  const tokenUrl = `${keycloakUrl}/realms/${realm}/protocol/openid-connect/token`;

  const res = http.post(
    tokenUrl,
    {
      grant_type: "password",
      client_id: clientId,
      username: username,
      password: password,
    },
    {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      tags: { name: "KeycloakROPCGrant" },
    }
  );

  const ok = check(res, {
    "keycloak token grant succeeded (200)": (r) => r.status === 200,
  });

  if (!ok) {
    throw new Error(
      `[load-test/auth] Keycloak ROPC grant failed: status=${res.status} body=${res.body}. ` +
        `Check LOAD_TEST_USERNAME/LOAD_TEST_PASSWORD, that ${tokenUrl} is reachable, and that ` +
        `the "${clientId}" client has Direct Access Grants enabled in this realm.`
    );
  }

  let parsed;
  try {
    parsed = res.json();
  } catch (e) {
    throw new Error(
      `[load-test/auth] Keycloak token response was not valid JSON: ${res.body}`
    );
  }

  if (!parsed || !parsed.access_token) {
    throw new Error(
      `[load-test/auth] Keycloak token response had no access_token field: ${res.body}`
    );
  }

  return parsed.access_token;
}
