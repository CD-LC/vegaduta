// Visitor session token for the PUBLIC agent-share surface (SEC-004/005).
//
// Core no longer derives the share visitor from the caller's IP: it issues a
// random, HMAC-signed token (`sessionToken` in the chat body, `X-Session-Id`
// on the response) and only continues a conversation when that token is sent
// back. This is a LOW-privilege visitor token (it names a conversation on ONE
// share and nothing else), so persisting it in browser storage is fine - it is
// deliberately NOT an auth token. Scoped per shareId so two shared agents
// never swap conversations. Storage may be blocked (private mode, embedded
// third-party iframe): every access is wrapped and falls back to memory for
// the life of the page.

const PREFIX = "vd.share.session.";
const memory = new Map<string, string>();

function key(shareId: string): string {
  return PREFIX + shareId;
}

function store(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function getShareSession(shareId: string): string | null {
  try {
    const v = store()?.getItem(key(shareId));
    if (v) return v;
  } catch {
    /* fall through to memory */
  }
  return memory.get(key(shareId)) ?? null;
}

export function setShareSession(shareId: string, token: string | null | undefined): void {
  if (!token || typeof token !== "string") return;
  memory.set(key(shareId), token);
  try {
    store()?.setItem(key(shareId), token);
  } catch {
    /* memory copy still holds */
  }
}

export function clearShareSession(shareId: string): void {
  memory.delete(key(shareId));
  try {
    store()?.removeItem(key(shareId));
  } catch {
    /* ignore */
  }
}

/** Request headers carrying the stored visitor token (X-Share-Session), or none. */
export function shareSessionHeaders(shareId: string): Record<string, string> {
  const token = getShareSession(shareId);
  return token ? { "X-Share-Session": token } : {};
}

/** Keep the token a response hands back (body `sessionToken` wins over the X-Session-Id header). */
export function adoptShareSession(shareId: string, res: Response | null, bodyToken?: unknown): void {
  const fromBody = typeof bodyToken === "string" && bodyToken ? bodyToken : null;
  let fromHeader: string | null = null;
  try {
    fromHeader = res?.headers?.get("X-Session-Id") ?? null;
  } catch {
    fromHeader = null;
  }
  setShareSession(shareId, fromBody ?? fromHeader);
}
