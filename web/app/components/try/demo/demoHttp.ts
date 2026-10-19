// Shared plumbing for the demo-sandbox client (see ../demoClient.ts for why this module exists
// separately from app/lib/api.ts): the API base, the stored sandbox token, and the never-throwing
// JSON / timeout / URL helpers every call uses.

import { publicApiBase } from "../../../lib/env";

export const API_BASE = publicApiBase();

// Same key the /try page reads on mount to resume an existing sandbox. The
// raw token is only ever returned once by the server (only its SHA-256 is
// stored), so losing this means starting a new sandbox - which is a fine
// outcome for a 5-day throwaway and much better than a recoverable secret.
export const DEMO_TOKEN_KEY = "vegaduta.demoToken";

export function readStoredToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(DEMO_TOKEN_KEY);
  } catch {
    // Private-browsing / storage-disabled. The sandbox still works for this
    // page load, it just will not survive a refresh - degrade, never break.
    return null;
  }
}

export function storeToken(token: string): void {
  try {
    window.localStorage.setItem(DEMO_TOKEN_KEY, token);
  } catch {
    /* see readStoredToken */
  }
}

export function clearStoredToken(): void {
  try {
    window.localStorage.removeItem(DEMO_TOKEN_KEY);
  } catch {
    /* see readStoredToken */
  }
}

export async function readJson(res: Response): Promise<any> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

/**
 * A fetch that cannot hang forever. Without this, one stalled connection
 * (proxy black-hole, mobile network dropping mid-handshake) leaves the /try
 * boot screen spinning indefinitely — an unresolvable promise is the one
 * failure mode "resolve everything into content" can't absorb on its own.
 * AbortSignal.timeout is missing on a few older browsers; there we degrade
 * to a plain un-timed fetch rather than throwing at module scope.
 */
export function timeoutSignal(ms: number): AbortSignal | undefined {
  try {
    return AbortSignal.timeout(ms);
  } catch {
    return undefined;
  }
}

/**
 * The server returns an API-RELATIVE path (`/api/demo/image/<id>`) because web
 * and core live on different subdomains and core has no business guessing its
 * own public origin. Joining it here — against the same base every other call
 * in this module uses — is the one place that knowledge belongs.
 */
export function absoluteImageUrl(raw: unknown): string | null {
  if (typeof raw !== "string" || !raw) return null;
  if (raw.startsWith("http://") || raw.startsWith("https://")) return raw;
  return `${API_BASE}${raw.startsWith("/") ? "" : "/"}${raw}`;
}
