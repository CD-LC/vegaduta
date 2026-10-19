// The local-auth counterpart of the keycloak-js instance (see authClient.ts). When
// NEXT_PUBLIC_AUTH_MODE=local, getKeycloak() returns one of these instead of a Keycloak, and it
// exposes the SAME surface the rest of the console already reads - `authenticated`, `token`,
// `tokenParsed`, `realmAccess`, `refreshTokenParsed`, `timeSkew`, `init()`, `login()`,
// `updateToken()`, the on* hooks - so authClaims.ts, authReauth.ts, AuthProvider and every page
// keep working without knowing which mode they are in.
//
// Where the secrets live (same rule as FE-001 in auth.ts): the ACCESS token is held in memory
// only. The REFRESH token never reaches this code at all - the same-origin proxy
// (app/api/local-auth) keeps it in an HttpOnly cookie, so no script on the page can read or
// exfiltrate it. A reload restores the session by asking the proxy to refresh with that cookie,
// which is the local analogue of Keycloak's check-sso.
import { LOCAL_AUTH_BASE } from "./authMode";

/** What the proxy returns on sign-in and refresh (never the refresh token). */
export interface LocalSessionPayload {
  accessToken: string;
  expiresIn: number;
  refreshExpiresIn: number;
  passwordChangeRequired?: boolean;
  /** Present on first-run setup only; the one-time recovery codes to show before continuing. */
  recoveryCodes?: string[];
  user?: { id?: string; email?: string; firstName?: string; lastName?: string; tenantId?: string; roles?: string[] };
}

/** An error from the proxy with core's stable `error` code (invalid_credentials, too_many_attempts, ...). */
export class LocalAuthError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfterSeconds?: number
  ) {
    super(message);
    this.name = "LocalAuthError";
  }
}

/** POST JSON to the proxy; resolves the parsed body or throws {@link LocalAuthError}. */
export function localAuthPost<T = unknown>(
  action: string,
  body?: unknown,
  headers?: Record<string, string>
): Promise<T> {
  return localAuthRequest<T>("POST", action, body ?? {}, headers);
}

/**
 * Any-method call to the proxy (GET and DELETE for the passkey / device endpoints). A POST always
 * carries a JSON body; GET and DELETE carry none. Resolves the parsed body (null for a 204) or
 * throws {@link LocalAuthError}.
 */
export async function localAuthRequest<T = unknown>(
  method: "GET" | "POST" | "DELETE",
  action: string,
  body?: unknown,
  headers?: Record<string, string>
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${LOCAL_AUTH_BASE}/${action}`, {
      method,
      credentials: "same-origin",
      headers: method === "POST" ? { "Content-Type": "application/json", ...headers } : { ...headers },
      ...(method === "POST" ? { body: JSON.stringify(body ?? {}) } : {}),
    });
  } catch {
    // Network failure: status 0 is the "transient, keep the session" signal updateToken keys on.
    throw new LocalAuthError(0, "network", "Could not reach the server.");
  }
  const text = await res.text();
  let parsed: Record<string, unknown> | null = null;
  try {
    parsed = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  } catch {
    parsed = null;
  }
  if (!res.ok) {
    throw new LocalAuthError(
      res.status,
      typeof parsed?.error === "string" ? parsed.error : "error",
      typeof parsed?.message === "string" ? parsed.message : `Request failed (${res.status})`,
      typeof parsed?.retryAfterSeconds === "number" ? parsed.retryAfterSeconds : undefined
    );
  }
  return parsed as T;
}

/** Decode a JWT payload without verifying it - display/expiry use only; core validates every request. */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const part = token.split(".")[1];
    if (!part) return null;
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

// A second tab can rotate the refresh cookie between our request being built and sent; core
// refuses the stale one inside a short grace window. One delayed retry picks up the new cookie.
const REFRESH_RETRY_DELAY_MS = 400;
/** Web Locks name shared by every tab of this origin (see LocalAuthClient#refresh). */
const REFRESH_LOCK_NAME = "vegaduta-local-auth-refresh";

export class LocalAuthClient {
  authenticated = false;
  token: string | undefined;
  tokenParsed: Record<string, unknown> | undefined;
  refreshTokenParsed: { exp: number } | undefined;
  realmAccess: { roles: string[] } | undefined;
  // Kept for surface parity; there is no id token or clock skew to track in local mode.
  idToken: string | undefined;
  timeSkew = 0;
  passwordChangeRequired = false;

  onTokenExpired?: () => void;
  onAuthSuccess?: () => void;
  onAuthRefreshSuccess?: () => void;
  onAuthRefreshError?: () => void;

  private inflight: Promise<boolean> | null = null;
  private expiryTimer: ReturnType<typeof setTimeout> | null = null;

  /** Restores a session from the refresh cookie; resolves whether one exists. */
  async init(_options?: Record<string, unknown>): Promise<boolean> {
    void _options;
    try {
      await this.refresh();
      return true;
    } catch (err) {
      // 401: no (or an ended) session - a normal "not signed in". Anything else (network, 5xx)
      // is a real failure the caller's retry policy should see.
      if (err instanceof LocalAuthError && err.status === 401) {
        this.clear();
        return false;
      }
      throw err;
    }
  }

  /** Sends the person to the local sign-in page, remembering where they were. */
  login(opts?: Record<string, unknown>): void {
    if (typeof window === "undefined") return;
    const here = window.location.pathname + window.location.search;
    const target = typeof opts?.redirectUri === "string" ? safeNext(opts.redirectUri) : safeNext(here);
    const next = target && target !== "/login" ? `?next=${encodeURIComponent(target)}` : "";
    window.location.assign(`/login${next}`);
  }

  /**
   * keycloak-js semantics: resolves true when a refresh happened, false when the token still has
   * `minValiditySeconds` left; rejects when the refresh failed. A rejection with the session ended
   * (401) clears `refreshTokenParsed`, which authReauth.ts reads as "session gone"; a network or
   * server failure leaves it intact so a one-off blip stays silent.
   */
  updateToken(minValiditySeconds = 5): Promise<boolean> {
    const exp = typeof this.tokenParsed?.exp === "number" ? this.tokenParsed.exp : 0;
    const stillValid = this.token && minValiditySeconds >= 0 && exp * 1000 - Date.now() > minValiditySeconds * 1000;
    if (stillValid) return Promise.resolve(false);
    if (this.inflight !== null) return this.inflight;
    this.inflight = this.refresh()
      .then(() => true)
      .catch((err: unknown) => {
        if (err instanceof LocalAuthError && err.status === 401) {
          this.clear();
        }
        this.onAuthRefreshError?.();
        throw err;
      })
      .finally(() => {
        this.inflight = null;
      });
    return this.inflight;
  }

  /** Adopts a session returned by sign-in / setup / change-password. */
  adopt(payload: LocalSessionPayload): void {
    const first = !this.authenticated;
    this.apply(payload);
    if (first) this.onAuthSuccess?.();
  }

  /** There is no hosted account console in local mode; the sign-in page owns password change. */
  createAccountUrl(): string | null {
    return null;
  }

  /** Forget the session in memory (the proxy clears the cookie on logout). */
  clear(): void {
    this.authenticated = false;
    this.token = undefined;
    this.tokenParsed = undefined;
    this.refreshTokenParsed = undefined;
    this.realmAccess = undefined;
    this.passwordChangeRequired = false;
    if (this.expiryTimer) {
      clearTimeout(this.expiryTimer);
      this.expiryTimer = null;
    }
  }

  /**
   * One refresh at a time per origin, not just per tab: core rotates the refresh token on every
   * use, so two tabs refreshing together make one of them present a token the other has just
   * retired. The Web Locks API serialises them (the second tab then refreshes with the rotated
   * cookie and succeeds); where it is missing, the one delayed retry below is the fallback.
   */
  private refresh(): Promise<void> {
    const locks = typeof navigator !== "undefined" ? (navigator as Navigator & { locks?: LockManager }).locks : undefined;
    if (locks && typeof locks.request === "function") {
      return locks.request(REFRESH_LOCK_NAME, () => this.refreshOnce());
    }
    return this.refreshOnce();
  }

  private async refreshOnce(): Promise<void> {
    let payload: LocalSessionPayload;
    try {
      payload = await localAuthPost<LocalSessionPayload>("refresh");
    } catch (err) {
      if (err instanceof LocalAuthError && err.status === 401) {
        await new Promise((resolve) => setTimeout(resolve, REFRESH_RETRY_DELAY_MS));
        payload = await localAuthPost<LocalSessionPayload>("refresh");
      } else {
        throw err;
      }
    }
    const wasAuthenticated = this.authenticated;
    this.apply(payload);
    if (wasAuthenticated) this.onAuthRefreshSuccess?.();
    else this.onAuthSuccess?.();
  }

  private apply(payload: LocalSessionPayload): void {
    const claims = decodeJwtPayload(payload.accessToken);
    this.token = payload.accessToken;
    this.tokenParsed = claims ?? {};
    const roles = (claims?.realm_access as { roles?: unknown } | undefined)?.roles;
    this.realmAccess = { roles: Array.isArray(roles) ? roles.filter((r): r is string => typeof r === "string") : [] };
    this.refreshTokenParsed = { exp: Math.floor(Date.now() / 1000) + Math.max(0, payload.refreshExpiresIn) };
    this.passwordChangeRequired = !!payload.passwordChangeRequired;
    this.authenticated = true;
    this.armExpiryTimer(payload.expiresIn);
  }

  // keycloak-js arms onTokenExpired off the token's exp; do the same so the token is renewed on
  // its own schedule even in a backgrounded tab (authClient.ts wires the handler).
  private armExpiryTimer(expiresInSeconds: number): void {
    if (this.expiryTimer) clearTimeout(this.expiryTimer);
    if (typeof window === "undefined") return;
    const ms = Math.max(1000, (expiresInSeconds - 10) * 1000);
    this.expiryTimer = setTimeout(() => this.onTokenExpired?.(), ms);
  }
}

/** Only same-origin absolute paths may be used as a post-login destination. */
export function safeNext(value: string | null | undefined): string {
  if (!value) return "/";
  let path = value;
  try {
    if (/^[a-z][a-z0-9+.-]*:/i.test(path) && typeof window !== "undefined") {
      const url = new URL(path);
      if (url.origin !== window.location.origin) return "/";
      path = url.pathname + url.search + url.hash;
    }
  } catch {
    return "/";
  }
  // "//host" and "/\host" are protocol-relative / browser-normalised cross-origin targets.
  if (!path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) return "/";
  return path;
}
