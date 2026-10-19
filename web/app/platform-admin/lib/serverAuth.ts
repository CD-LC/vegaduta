import { publicApiBase } from "./env";
// Server-side auth guard for privileged API route handlers (and this app's
// proxy.ts) that hold a secret and proxy to a privileged upstream --
// today that's app/api/academy/**, which forwards to the Academy admin API
// using SCHOLARSHIP_ADMIN_KEY (see app/lib/academyProxy.ts; the env name
// predates the scholarship program's retirement — it's the Academy admin
// API's shared key).
//
// THE FLAW THIS CLOSES: this app's auth (app/lib/auth.ts, Keycloak) runs
// entirely in the browser. Next.js route handlers execute server-side and
// are reachable directly over HTTP regardless of what the React UI does --
// before this fix, the Academy proxy routes had NO authentication at all.
// Anyone who could reach the public admin host (the whole internet, per
// Caddy's public routing) could drive the Academy admin API, with the
// server-side SCHOLARSHIP_ADMIN_KEY doing the privileged work on their
// behalf. Classic confused deputy.
//
// THE FIX delegates to core's own, already-correct Keycloak resource-server
// verification instead of re-implementing JWT signature/issuer checks here:
// no JWT library (jose/jsonwebtoken) is present in this app's node_modules,
// and installing one is out of scope for this fix (see task constraints --
// no npm install). Every other screen in this app already trusts core's
// enforcement of `/api/platform/**` -> `ROLE_platform-admin`:
//   core/security/src/main/java/.../SecurityConfig.java
//     .requestMatchers("/api/platform/**").hasAuthority("ROLE_platform-admin")
// and `GET /api/platform/health` (core/app/.../PlatformHealthController.java)
// is a real, cheap, side-effect-free read behind that same gate. This
// reuses that gate as a verification oracle: forward the caller's own
// Authorization header to that endpoint and read back whether core accepted
// it.
//   200          -> valid token AND platform-admin role (checked atomically,
//                   by the exact same code path every other admin screen
//                   already depends on)
//   401          -> passed straight through (no/expired/invalid token)
//   403          -> passed straight through (authenticated, wrong role)
//   unreachable/
//   anything else -> FAIL CLOSED as 503. "Couldn't verify" must never be
//                   treated as "allowed".
//
// Used from both proxy.ts (Edge runtime) and the route handlers (Node
// runtime) -- deliberately has no logger/pino import (Edge-incompatible,
// see app/lib/logger.ts's own comment) and no other app-only import so it
// runs unmodified in both.

const API_BASE = publicApiBase();

// NOTE: deliberately NOT a discriminated union ({ ok: true } | { ok: false;
// ...}). This repo's tsconfig.json sets "strict": false without
// strictNullChecks, and under that setting this TS toolchain does not
// narrow a boolean-literal discriminant (`if (!r.ok) r.error` fails to
// typecheck even though it's semantically sound) -- confirmed by isolated
// repro against this exact tsconfig. `status`/`error` are always present
// instead, so callers never depend on narrowing.
export interface AuthCheckResult {
  ok: boolean;
  status: number;
  error: string;
}

const VERIFIED_OK: AuthCheckResult = { ok: true, status: 200, error: "" };

export async function verifyPlatformAdmin(request: Request): Promise<AuthCheckResult> {
  const authHeader = request.headers.get("authorization") || request.headers.get("Authorization");
  if (!authHeader || !/^Bearer\s+\S+/i.test(authHeader)) {
    return { ok: false, status: 401, error: "Authentication required." };
  }

  let upstream: Response;
  try {
    upstream = await fetch(`${API_BASE}/api/platform/health`, {
      method: "GET",
      headers: { Authorization: authHeader },
      cache: "no-store",
    });
  } catch {
    // Fail CLOSED -- an unreachable verification oracle is never "allowed".
    return {
      ok: false,
      status: 503,
      error: "Could not verify your admin session right now. Please try again shortly.",
    };
  }

  if (upstream.status === 200) return VERIFIED_OK;
  if (upstream.status === 401) {
    return { ok: false, status: 401, error: "Your session has expired. Please sign in again." };
  }
  if (upstream.status === 403) {
    return {
      ok: false,
      status: 403,
      error: "You don't have the platform-admin role. Ask an existing platform admin to grant it to you in Keycloak.",
    };
  }
  // Any other upstream status (5xx, unexpected 3xx, etc.) also fails closed
  // rather than guessing.
  return {
    ok: false,
    status: 503,
    error: "Could not verify your admin session right now. Please try again shortly.",
  };
}
