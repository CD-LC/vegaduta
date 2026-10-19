// Server-side half of passkey, device-PIN and recovery-code sign-in for local auth mode. The
// route handlers app/api/local-auth/unlock/[...path] and app/api/local-auth/device are thin shells
// around the functions here; the rest of local auth (password, refresh, logout) stays in
// localAuthProxy.ts, whose helpers this file reuses.
//
// What the proxy adds on top of forwarding to core's /api/auth/local/unlock/*:
//   - the device cookie. A PIN is only valid together with a 32-byte device secret that core hands
//     out once, at enrolment. The secret goes straight into an HttpOnly, SameSite=Strict cookie
//     (vd_device = "<deviceId>.<deviceSecret>") and is stripped from the body, so page scripts
//     never see it; a PIN sign-in from the browser carries only the PIN and the proxy adds the
//     device from the cookie. Without the cookie there is no PIN sign-in at all.
//   - the refresh cookie, exactly as for password sign-in: any session core returns has its
//     refresh token moved into the HttpOnly cookie before the page sees the response.
//   - X-Vegaduta-Origin. WebAuthn binds a credential to the page's origin and core runs behind
//     this server, so the browser's Origin (or, for a call without one, the origin it reached us
//     on) is passed along for core to derive the relying-party id from.
//
// Core's HTTP paths are the logical ones of its contract; here they live under
// /api/local-auth/unlock/... (same convention as the existing /api/local-auth/<action>).
import { COOKIE_PATH, coreBaseUrl, isSameOrigin, isSecureRequest, refreshCookie } from "./localAuthProxy";

export const DEVICE_COOKIE = "vd_device";
/** Non-secret companion: the name the person gave this device, so the PIN pad can say which one it unlocks. */
export const DEVICE_LABEL_COOKIE = "vd_device_label";
const DEVICE_MAX_AGE_SECONDS = 400 * 24 * 60 * 60; // the longest lifetime browsers honour
const MAX_BODY_BYTES = 64 * 1024; // a passkey registration carries an attestation object
const SEGMENT = /^[A-Za-z0-9_-]{1,128}$/;
const TOKEN = /^[A-Za-z0-9_-]+$/;

type Kind = "plain" | "login" | "setup-pin" | "register" | "enrol" | "delete-device";
type Bearer = "none" | "optional" | "required";
interface UnlockRoute {
  method: "GET" | "POST" | "DELETE";
  /** Path under /unlock; `:id` matches one segment. */
  path: string;
  bearer: Bearer;
  kind: Kind;
}

const ROUTES: readonly UnlockRoute[] = [
  { method: "POST", path: "login", bearer: "none", kind: "login" },
  { method: "POST", path: "passkey/login-options", bearer: "none", kind: "plain" },
  { method: "POST", path: "setup/pin", bearer: "none", kind: "setup-pin" },
  { method: "POST", path: "passkey/register-options", bearer: "optional", kind: "plain" },
  { method: "POST", path: "passkey/register", bearer: "optional", kind: "register" },
  { method: "GET", path: "methods", bearer: "required", kind: "plain" },
  { method: "POST", path: "devices", bearer: "required", kind: "enrol" },
  { method: "DELETE", path: "devices/:id", bearer: "required", kind: "delete-device" },
  { method: "DELETE", path: "passkeys/:id", bearer: "required", kind: "plain" },
  { method: "POST", path: "recovery-codes", bearer: "required", kind: "plain" },
];

function matchRoute(segments: string[]): { routes: UnlockRoute[]; id?: string } | null {
  const routes = ROUTES.filter((r) => {
    const parts = r.path.split("/");
    return parts.length === segments.length && parts.every((p, i) => p === ":id" || p === segments[i]);
  });
  if (!routes.length) return null;
  const idIndex = routes[0].path.split("/").indexOf(":id");
  return { routes, id: idIndex >= 0 ? segments[idIndex] : undefined };
}

// ---- cookies ----------------------------------------------------------------------------------

function readCookie(header: string | null, name: string): string | null {
  if (!header) return null;
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq).trim() === name) {
      const value = part.slice(eq + 1).trim();
      try {
        return value ? decodeURIComponent(value) : null;
      } catch {
        return null;
      }
    }
  }
  return null;
}

function cookie(name: string, value: string, maxAgeSeconds: number, secure: boolean): string {
  const attrs = [
    `${name}=${value ? encodeURIComponent(value) : ""}`,
    `Path=${COOKIE_PATH}`,
    "HttpOnly",
    "SameSite=Strict",
    `Max-Age=${value && maxAgeSeconds > 0 ? Math.floor(maxAgeSeconds) : 0}`,
  ];
  if (secure) attrs.push("Secure");
  return attrs.join("; ");
}

/** Set-Cookie values that store the device binding. */
export function deviceCookies(deviceId: string, deviceSecret: string, label: string, secure: boolean): string[] {
  const out = [cookie(DEVICE_COOKIE, `${deviceId}.${deviceSecret}`, DEVICE_MAX_AGE_SECONDS, secure)];
  if (label) out.push(cookie(DEVICE_LABEL_COOKIE, label, DEVICE_MAX_AGE_SECONDS, secure));
  else out.push(cookie(DEVICE_LABEL_COOKIE, "", 0, secure));
  return out;
}

/** Set-Cookie values that forget the device binding (revoked, removed, or malformed). */
export function clearDeviceCookies(secure: boolean): string[] {
  return [cookie(DEVICE_COOKIE, "", 0, secure), cookie(DEVICE_LABEL_COOKIE, "", 0, secure)];
}

/** The device binding in the request's cookies, or null when absent or not shaped like one. */
export function readDeviceCookie(cookieHeader: string | null): { id: string; secret: string; label?: string } | null {
  const raw = readCookie(cookieHeader, DEVICE_COOKIE);
  if (!raw) return null;
  const dot = raw.indexOf(".");
  const id = dot > 0 ? raw.slice(0, dot) : "";
  const secret = dot > 0 ? raw.slice(dot + 1) : "";
  if (!TOKEN.test(id) || !TOKEN.test(secret)) return null;
  const label = readCookie(cookieHeader, DEVICE_LABEL_COOKIE) ?? undefined;
  return { id, secret, label: label || undefined };
}

// ---- responses --------------------------------------------------------------------------------

function respond(
  body: unknown,
  status: number,
  cookies: string[] = [],
  extra: Record<string, string> = {}
): Response {
  const headers = new Headers({ "Cache-Control": "no-store", ...extra });
  for (const c of cookies) headers.append("Set-Cookie", c);
  if (status === 204) return new Response(null, { status, headers });
  headers.set("Content-Type", "application/json");
  return new Response(JSON.stringify(body), { status, headers });
}

/** Removes the two secrets wherever they sit in a payload; nothing else is touched. */
function stripSecrets(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripSecrets);
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (k === "deviceSecret" || k === "refreshToken") continue;
      out[k] = stripSecrets(v);
    }
    return out;
  }
  return value;
}

/** The origin the browser used, for core to derive the WebAuthn relying-party id from. */
function browserOrigin(request: Request): string {
  const origin = request.headers.get("origin");
  if (origin && origin !== "null") return origin;
  const host = (request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? new URL(request.url).host)
    .split(",")[0]
    .trim();
  return `${isSecureRequest(request) ? "https" : "http"}://${host}`;
}

function stringMap(value: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (value && typeof value === "object" && !Array.isArray(value)) {
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) if (typeof v === "string") out[k] = v;
  }
  return out;
}

// ---- handlers ---------------------------------------------------------------------------------

/** GET /api/local-auth/device: does this browser hold a device binding? Answered from the cookie alone. */
export function handleDeviceStatus(request: Request): Response {
  const secure = isSecureRequest(request);
  const header = request.headers.get("cookie");
  const device = readDeviceCookie(header);
  if (!device) {
    // A cookie that is present but malformed is dead weight: drop it.
    return respond({ known: false }, 200, readCookie(header, DEVICE_COOKIE) ? clearDeviceCookies(secure) : []);
  }
  return respond({ known: true, id: device.id, ...(device.label ? { label: device.label } : {}) }, 200);
}

/** Handle one /api/local-auth/unlock/<segments> call. `fetchImpl` is injectable for tests. */
export async function handleUnlock(
  request: Request,
  segments: string[],
  fetchImpl: typeof fetch = fetch
): Promise<Response> {
  if (!segments.length || !segments.every((s) => SEGMENT.test(s))) {
    return respond({ error: "not_found", message: "Unknown action." }, 404);
  }
  const matched = matchRoute(segments);
  if (!matched) return respond({ error: "not_found", message: "Unknown action." }, 404);
  const route = matched.routes.find((r) => r.method === request.method);
  if (!route) {
    return respond({ error: "method_not_allowed", message: "Method not allowed." }, 405, [], {
      Allow: matched.routes.map((r) => r.method).join(", "),
    });
  }
  if (route.method !== "GET" && !isSameOrigin(request)) {
    return respond({ error: "forbidden", message: "Cross-origin request refused." }, 403);
  }

  const secure = isSecureRequest(request);
  const cookieHeader = request.headers.get("cookie");
  const headers: Record<string, string> = {
    Accept: "application/json",
    "X-Vegaduta-Origin": browserOrigin(request),
  };
  const forwardedFor = request.headers.get("x-forwarded-for");
  if (forwardedFor) headers["X-Forwarded-For"] = forwardedFor.slice(0, 512);

  const auth = request.headers.get("authorization");
  if (route.bearer === "required" && !auth) {
    return respond({ error: "invalid_session", message: "Sign in first." }, 401);
  }
  if (route.bearer !== "none" && auth) headers.Authorization = auth;

  let body: string | undefined;
  let label = "";
  const clearOnSuccess: string[] = [];

  if (route.method === "POST") {
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) {
      return respond({ error: "payload_too_large", message: "Request too large." }, 413);
    }
    let parsed: Record<string, unknown> = {};
    try {
      parsed = raw ? JSON.parse(raw) : {};
    } catch {
      return respond({ error: "bad_request", message: "Malformed JSON." }, 400);
    }
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      return respond({ error: "bad_request", message: "Expected a JSON object." }, 400);
    }
    // Secrets only ever come from cookies, never from the page.
    delete parsed.refreshToken;
    delete parsed.deviceSecret;
    if (typeof parsed.label === "string") {
      label = parsed.label.trim().slice(0, 64);
      parsed.label = label;
    }

    if (route.kind === "login") {
      const type = parsed.type;
      if (type === "pin") {
        const device = readDeviceCookie(cookieHeader);
        if (!device) {
          return respond(
            { error: "device_unknown", message: "This browser is not set up for a PIN." },
            401,
            readCookie(cookieHeader, DEVICE_COOKIE) ? clearDeviceCookies(secure) : []
          );
        }
        parsed = {
          type: "pin",
          identifier: device.id,
          credentials: { pin: typeof parsed.pin === "string" ? parsed.pin.slice(0, 64) : "", deviceSecret: device.secret },
        };
      } else if (type === "passkey" || type === "recovery") {
        parsed = {
          type,
          identifier: typeof parsed.identifier === "string" ? parsed.identifier.slice(0, 256) : type === "recovery" ? "recovery" : "",
          credentials: stringMap(parsed.credentials),
        };
      } else {
        return respond({ error: "bad_request", message: "Unsupported sign-in type." }, 400);
      }
    }
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(parsed);
  } else if (route.kind === "delete-device") {
    // Removing the device THIS browser holds also drops its cookie.
    const device = readDeviceCookie(cookieHeader);
    if (device && device.id === matched.id) clearOnSuccess.push(...clearDeviceCookies(secure));
  }

  const upstreamPath = segments.map(encodeURIComponent).join("/");
  let upstream: Response;
  try {
    upstream = await fetchImpl(`${coreBaseUrl()}/api/auth/local/unlock/${upstreamPath}`, {
      method: route.method,
      headers,
      body,
      cache: "no-store",
    });
  } catch {
    return respond({ error: "unavailable", message: "The server could not be reached." }, 502);
  }

  const text = await upstream.text();
  let payload: Record<string, unknown> | null = null;
  try {
    payload = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  } catch {
    payload = null;
  }

  if (!upstream.ok) {
    const extra: Record<string, string> = {};
    const retry = upstream.headers.get("retry-after");
    if (retry) extra["Retry-After"] = retry;
    const code = payload && typeof payload.error === "string" ? payload.error : "error";
    // Core answers a device it has revoked (too many wrong PINs, or removed elsewhere) with this
    // code: the cookie is useless from now on, so forget it and the PIN pad goes away.
    const cookies = code === "device_revoked" ? clearDeviceCookies(secure) : [];
    return respond(
      {
        error: code,
        message: payload && typeof payload.message === "string" ? payload.message : "Request failed.",
        retryAfterSeconds: payload?.retryAfterSeconds,
      },
      upstream.status,
      cookies,
      extra
    );
  }

  if (upstream.status === 204 || !payload || typeof payload !== "object") {
    return route.method === "DELETE" ? respond(null, 204, clearOnSuccess) : respond({}, 200, clearOnSuccess);
  }

  const cookies: string[] = [...clearOnSuccess];

  // A session: the refresh token goes into its cookie, whether core put the session at the top
  // level (login) or under "session" (setup, passkey registration).
  const holder =
    payload.session && typeof payload.session === "object" ? (payload.session as Record<string, unknown>) : payload;
  if (typeof holder.refreshToken === "string" && holder.refreshToken) {
    const ttl = typeof holder.refreshExpiresIn === "number" ? holder.refreshExpiresIn : 0;
    cookies.push(refreshCookie(holder.refreshToken, ttl, secure));
  }

  // A fresh device binding: the secret goes into its cookie.
  if (
    (route.kind === "setup-pin" || route.kind === "enrol") &&
    typeof payload.deviceId === "string" &&
    typeof payload.deviceSecret === "string" &&
    TOKEN.test(payload.deviceId) &&
    TOKEN.test(payload.deviceSecret)
  ) {
    cookies.push(...deviceCookies(payload.deviceId, payload.deviceSecret, label, secure));
  }

  return respond(stripSecrets(payload), 200, cookies);
}
