// Server-side half of local auth mode (NEXT_PUBLIC_AUTH_MODE=local): the same-origin proxy in
// app/api/local-auth/[action]/route.ts is a thin shell around the functions here.
//
// Why a proxy at all. Core's refresh token must not be readable by page scripts (FE-001: a
// stolen refresh token works from another machine until the session ends). Core itself is a
// different origin from the console and its CORS policy does not allow credentials, so it cannot
// set a cookie the console can use. This proxy sits on the console's own origin: it forwards the
// sign-in / refresh / logout calls to core, moves the refresh token between the response body and
// an HttpOnly, SameSite=Strict cookie scoped to this path, and hands the browser only the
// short-lived access token. Core's own API stays token-in-body so any other client (CLI, SDK)
// uses it directly.
import { getPublicEnv, publicApiBase } from "./env";

export const REFRESH_COOKIE = "vd_local_rt";
export const COOKIE_PATH = "/api/local-auth";

/** Actions the proxy forwards, with the HTTP method core expects for each. */
export const ACTIONS: Record<string, "GET" | "POST"> = {
  config: "GET",
  login: "POST",
  setup: "POST",
  refresh: "POST",
  logout: "POST",
  "change-password": "POST",
  "reset-request": "POST",
};

const MAX_BODY_BYTES = 8 * 1024;

/** Core's base URL as seen from THIS server process (an internal address wins over the public one). */
export function coreBaseUrl(): string {
  const internal = process.env.CORE_INTERNAL_URL;
  return (internal && internal.trim() ? internal.trim() : publicApiBase()).replace(/\/+$/, "");
}

/**
 * Same-origin guard for state-changing calls. The refresh cookie is SameSite=Strict, which already
 * stops a cross-site page from riding it; this additionally refuses a request whose Origin or
 * Fetch-Metadata says it came from somewhere else, so the proxy is not a confused deputy for
 * non-browser-cookie paths either.
 */
export function isSameOrigin(request: Request): boolean {
  const site = request.headers.get("sec-fetch-site");
  if (site && site !== "same-origin" && site !== "none") return false;
  const origin = request.headers.get("origin");
  if (!origin) return true; // non-browser client (curl, tests): no ambient cookie to abuse
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host");
  try {
    return !!host && new URL(origin).host === host;
  } catch {
    return false;
  }
}

/** The refresh cookie's value from a Cookie header, or null. */
export function readRefreshCookie(cookieHeader: string | null): string | null {
  if (!cookieHeader) return null;
  for (const part of cookieHeader.split(";")) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq).trim() === REFRESH_COOKIE) {
      const value = part.slice(eq + 1).trim();
      return value ? decodeURIComponent(value) : null;
    }
  }
  return null;
}

/** Set-Cookie value that stores (or, with maxAgeSeconds <= 0, clears) the refresh token. */
export function refreshCookie(value: string, maxAgeSeconds: number, secure: boolean): string {
  const attrs = [
    `${REFRESH_COOKIE}=${value ? encodeURIComponent(value) : ""}`,
    `Path=${COOKIE_PATH}`,
    "HttpOnly",
    "SameSite=Strict",
    `Max-Age=${value && maxAgeSeconds > 0 ? Math.floor(maxAgeSeconds) : 0}`,
  ];
  if (secure) attrs.push("Secure");
  return attrs.join("; ");
}

/**
 * True when the browser reached us over HTTPS (directly or through a TLS-terminating proxy).
 * When the deployment's canonical site URL is https the answer is always true: the refresh
 * cookie's Secure flag must not be removable by a client-settable X-Forwarded-Proto: http -
 * a caller that reaches the origin directly can set that header itself.
 */
export function isSecureRequest(request: Request): boolean {
  const canonical = (getPublicEnv("NEXT_PUBLIC_SITE_URL") || "").toLowerCase();
  if (canonical.startsWith("https:")) return true;
  const proto = request.headers.get("x-forwarded-proto");
  if (proto) return proto.split(",")[0].trim() === "https";
  return new URL(request.url).protocol === "https:";
}

function json(body: unknown, status: number, extra?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra },
  });
}

/** Handle one proxied call. `fetchImpl` is injectable for tests. */
export async function handleLocalAuth(
  request: Request,
  action: string,
  fetchImpl: typeof fetch = fetch
): Promise<Response> {
  // own-property lookup: "constructor" / "__proto__" must be an unknown action, not an inherited member
  const method = Object.prototype.hasOwnProperty.call(ACTIONS, action) ? ACTIONS[action] : undefined;
  if (!method) return json({ error: "not_found", message: "Unknown action." }, 404);
  if (request.method !== method) {
    return json({ error: "method_not_allowed", message: "Method not allowed." }, 405, { Allow: method });
  }
  if (method === "POST" && !isSameOrigin(request)) {
    return json({ error: "forbidden", message: "Cross-origin request refused." }, 403);
  }

  const cookieRefresh = readRefreshCookie(request.headers.get("cookie"));
  const secure = isSecureRequest(request);
  const headers: Record<string, string> = { Accept: "application/json" };
  // Core throttles failed sign-ins per client address. Seen from core every call comes from this
  // server, so hand over the client address the edge proxy (Caddy) already recorded; core only
  // honours it when this server is a trusted proxy (server.tomcat.remoteip.trusted-proxies). A
  // client that reaches this server directly can forge it, which only dodges the per-address
  // bucket: the per-account bucket still applies.
  const forwardedFor = request.headers.get("x-forwarded-for");
  if (forwardedFor) headers["X-Forwarded-For"] = forwardedFor.slice(0, 512);
  let body: string | undefined;

  if (method === "POST") {
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) {
      return json({ error: "payload_too_large", message: "Request too large." }, 413);
    }
    let parsed: Record<string, unknown> = {};
    try {
      parsed = raw ? JSON.parse(raw) : {};
    } catch {
      return json({ error: "bad_request", message: "Malformed JSON." }, 400);
    }
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      return json({ error: "bad_request", message: "Expected a JSON object." }, 400);
    }
    // The refresh token only ever comes from the cookie, never from the page.
    delete parsed.refreshToken;
    if (action === "refresh" || action === "logout") {
      if (!cookieRefresh) {
        return action === "logout"
          ? json({}, 200, { "Set-Cookie": refreshCookie("", 0, secure) })
          : json({ error: "invalid_session", message: "No session." }, 401, {
              "Set-Cookie": refreshCookie("", 0, secure),
            });
      }
      parsed.refreshToken = cookieRefresh;
    }
    if (action === "change-password") {
      const auth = request.headers.get("authorization");
      if (!auth) return json({ error: "invalid_session", message: "Sign in first." }, 401);
      headers.Authorization = auth;
    }
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(parsed);
  }

  let upstream: Response;
  try {
    upstream = await fetchImpl(`${coreBaseUrl()}/api/auth/local/${action}`, {
      method,
      headers,
      body,
      cache: "no-store",
    });
  } catch {
    // Logout must always forget the token in this browser, even if core cannot be told right now
    // (the token then simply expires on its own).
    if (action === "logout") return json({}, 200, { "Set-Cookie": refreshCookie("", 0, secure) });
    return json({ error: "unavailable", message: "The server could not be reached." }, 502);
  }

  const text = await upstream.text();
  let payload: Record<string, unknown> | null = null;
  try {
    payload = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  } catch {
    payload = null;
  }

  if (action === "logout") {
    return json({}, 200, { "Set-Cookie": refreshCookie("", 0, secure) });
  }

  if (!upstream.ok) {
    const extra: Record<string, string> = {};
    const retry = upstream.headers.get("retry-after");
    if (retry) extra["Retry-After"] = retry;
    // A refused refresh does NOT clear the cookie. Core rotates the refresh token on every use, so
    // two concurrent refreshes (a second tab, or a page that remounts its auth client) present the
    // same token and the loser gets a 401 while the winner's Set-Cookie has already stored the new
    // one. Clearing here used to wipe that fresh cookie, so the client's one delayed retry
    // (localAuthClient.ts) found "No session" and the person was signed out for no reason. A cookie
    // that really is dead is harmless: every later refresh answers 401 until the next sign-in
    // (which replaces it) or logout (which clears it).
    return json(
      payload && typeof payload === "object"
        ? {
            error: payload.error ?? "error",
            message: payload.message ?? "Request failed.",
            retryAfterSeconds: payload.retryAfterSeconds,
          }
        : { error: "error", message: "Request failed." },
      upstream.status,
      extra
    );
  }

  if (action === "config" || !payload || typeof payload !== "object") {
    return json(payload ?? {}, 200);
  }

  // A session: keep the refresh token in the cookie, hand the page everything else.
  const { refreshToken, ...pageSafe } = payload as Record<string, unknown>;
  const extra: Record<string, string> = {};
  if (typeof refreshToken === "string" && refreshToken) {
    const ttl = typeof pageSafe.refreshExpiresIn === "number" ? pageSafe.refreshExpiresIn : 0;
    extra["Set-Cookie"] = refreshCookie(refreshToken, ttl, secure);
  }
  return json(pageSafe, 200, extra);
}
