// Read-only "view as" (platform-admin impersonation).
//
// Flow: a platform admin starts a session in web-admin, which opens
// `https://app.<domain>/view-as#grant=<grant>` in a NEW tab. The grant rides in
// the URL fragment (never the query string) so it never reaches a server log,
// a proxy or a Referer header. ViewAsBanner (root layout) lifts it out of the
// hash on first paint into a "pending" slot and strips the hash; /view-as then
// validates it (GET /api/view-as/session) once the admin's own Keycloak session
// is ready and promotes it to active.
//
// While active, every request this app makes to core carries
// `X-View-As: <grant>` next to the admin's OWN bearer token; core serves data as
// the viewed tenant/user and refuses every write with 403
// {error:"impersonation_read_only"}.
//
// Storage is sessionStorage on purpose: tab-scoped (other tabs of the same
// browser stay the admin's own console), gone when the tab closes. Never
// localStorage. Every access is try/catch - a blocked/private storage simply
// means the grant lives in memory for this page life.
//
// Framework-free so core.ts (and every other fetch helper) can import it; the
// React hook lives in useViewAs.ts.

export const VIEW_AS_HEADER = "X-View-As";
export const READ_ONLY_ERROR = "impersonation_read_only";
export const READ_ONLY_MESSAGE = "Read-only view: changes are disabled";

const GRANT_KEY = "vd.viewAs.grant";
const SESSION_KEY = "vd.viewAs.session";
const PENDING_KEY = "vd.viewAs.pending";

export interface ViewAsSession {
  id: string | null;
  tenantName: string | null;
  userEmail: string | null;
  expiresAt: string | null;
  readOnly: boolean;
}

export interface ViewAsState {
  grant: string;
  session: ViewAsSession | null;
}

// Opaque server token: allow the URL-safe/base64/JWT alphabets, bounded length.
// Anything else (spaces, quotes, newlines) is refused before it can ever be put
// in a request header.
const GRANT_RE = /^[A-Za-z0-9._~+/=-]{8,4096}$/;

export function isPlausibleGrant(v: unknown): v is string {
  return typeof v === "string" && GRANT_RE.test(v);
}

/** `#grant=abc` (or `grant=abc&x=y`) -> "abc"; null when absent or malformed. */
export function parseGrantFromHash(hash: string | null | undefined): string | null {
  if (!hash) return null;
  const raw = hash.startsWith("#") ? hash.slice(1) : hash;
  if (!raw) return null;
  let value: string | null = null;
  try {
    value = new URLSearchParams(raw).get("grant");
  } catch {
    return null;
  }
  // URLSearchParams turns a literal "+" into a space; a base64 grant may carry one.
  if (value != null) value = value.replace(/ /g, "+");
  return isPlausibleGrant(value) ? value : null;
}

/** Shape-check GET /api/view-as/session's body. Null when it is not a session. */
export function normalizeSession(raw: any): ViewAsSession | null {
  if (!raw || typeof raw !== "object") return null;
  const str = (v: unknown) => (typeof v === "string" && v.trim() ? v : null);
  return {
    id: str(raw.id),
    tenantName: str(raw.tenantName),
    userEmail: str(raw.userEmail),
    expiresAt: str(raw.expiresAt),
    readOnly: raw.readOnly !== false,
  };
}

/** Milliseconds until expiresAt (negative once past); null when unknown/unparseable. */
export function msUntil(expiresAt: string | null | undefined, now: number = Date.now()): number | null {
  if (!expiresAt) return null;
  const t = new Date(expiresAt).getTime();
  return Number.isNaN(t) ? null : t - now;
}

export function sessionExpired(session: ViewAsSession | null | undefined, now: number = Date.now()): boolean {
  const left = msUntil(session?.expiresAt, now);
  return left != null && left <= 0;
}

/** "29:05", "00:09", "00:00" once expired; "--:--" when the expiry is unknown. */
export function formatCountdown(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return "--:--";
  const total = Math.max(0, Math.ceil(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/** True for core's read-only refusal body. */
export function isReadOnlyBody(status: number, body: any): boolean {
  return status === 403 && !!body && body.error === READ_ONLY_ERROR;
}

// ---- storage + store -------------------------------------------------------

function storage(): Storage | null {
  try {
    return typeof window !== "undefined" ? window.sessionStorage : null;
  } catch {
    return null;
  }
}

function safeGet(key: string): string | null {
  try {
    return storage()?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

function safeSet(key: string, value: string): void {
  try {
    storage()?.setItem(key, value);
  } catch {
    // blocked storage: the in-memory copy still works for this page life
  }
}

function safeRemove(key: string): void {
  try {
    storage()?.removeItem(key);
  } catch {
    // ignore
  }
}

let state: ViewAsState | null = null;
let loaded = false;
const listeners = new Set<() => void>();

function load(): ViewAsState | null {
  if (loaded) return state;
  if (typeof window === "undefined") return null;
  loaded = true;
  const grant = safeGet(GRANT_KEY);
  if (!isPlausibleGrant(grant)) {
    state = null;
    return state;
  }
  let session: ViewAsSession | null = null;
  try {
    const raw = safeGet(SESSION_KEY);
    session = raw ? normalizeSession(JSON.parse(raw)) : null;
  } catch {
    session = null;
  }
  state = { grant, session };
  return state;
}

function emit(): void {
  for (const l of listeners) {
    try {
      l();
    } catch {
      // a listener must never break the store
    }
  }
}

export function getViewAsState(): ViewAsState | null {
  return load();
}

export function getViewAsGrant(): string | null {
  return load()?.grant ?? null;
}

export function isViewAsActive(): boolean {
  return getViewAsGrant() != null;
}

/** `{ "X-View-As": grant }` while a view-as session is active in this tab, else `{}`. */
export function viewAsHeaders(): Record<string, string> {
  const grant = getViewAsGrant();
  return grant ? { [VIEW_AS_HEADER]: grant } : {};
}

/** Adds X-View-As to a mutable headers record in place (no-op when inactive). Returns it. */
export function applyViewAsHeader<T extends Record<string, string>>(headers: T): T {
  const grant = getViewAsGrant();
  if (grant) (headers as Record<string, string>)[VIEW_AS_HEADER] = grant;
  return headers;
}

// Chat state that would otherwise outlive a view-as session in this tab: the
// in-flight turn record (resumableStream.ts, sessionStorage). Drafts/threads in
// localStorage (build/chatStore.ts) are shared with the admin's other tabs, so
// they are not purged; instead chatStore and resumableStream neither read nor
// write while view-as is active.
const VIEW_AS_PURGED_SESSION_PREFIXES = ["vegaduta.chat.pendingTurn.v1:"];

/** Drops this tab's chat drafts / in-flight turn records (called on enter and exit). */
export function purgeTabChatState(): void {
  const s = storage();
  if (!s) return;
  try {
    const doomed: string[] = [];
    for (let i = 0; i < s.length; i++) {
      const k = s.key(i);
      if (k && VIEW_AS_PURGED_SESSION_PREFIXES.some((p) => k.startsWith(p))) doomed.push(k);
    }
    doomed.forEach((k) => s.removeItem(k));
  } catch {
    // blocked storage: nothing was persisted there either
  }
}

export function activateViewAs(grant: string, session: ViewAsSession | null): void {
  if (!isPlausibleGrant(grant)) return;
  purgeTabChatState();
  loaded = true;
  state = { grant, session };
  safeSet(GRANT_KEY, grant);
  if (session) safeSet(SESSION_KEY, JSON.stringify(session));
  else safeRemove(SESSION_KEY);
  emit();
}

export function updateViewAsSession(session: ViewAsSession): void {
  const cur = load();
  if (!cur) return;
  state = { grant: cur.grant, session };
  safeSet(SESSION_KEY, JSON.stringify(session));
  emit();
}

/** Ends view-as in this tab only (the server-side session is ended from web-admin or by expiry). */
export function exitViewAs(): void {
  const had = load() != null;
  loaded = true;
  state = null;
  safeRemove(GRANT_KEY);
  safeRemove(SESSION_KEY);
  safeRemove(PENDING_KEY);
  if (had) purgeTabChatState();
  if (had) emit();
}

export function stashPendingGrant(grant: string): void {
  if (isPlausibleGrant(grant)) safeSet(PENDING_KEY, grant);
}

/** Reads (without clearing) the grant /view-as is about to validate. */
export function peekPendingGrant(): string | null {
  const g = safeGet(PENDING_KEY);
  return isPlausibleGrant(g) ? g : null;
}

export function clearPendingGrant(): void {
  safeRemove(PENDING_KEY);
}

export function subscribeViewAs(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Test hook: forget the in-memory copy so the next read re-loads from storage (subscribers are kept). */
export function __resetViewAsForTests(): void {
  state = null;
  loaded = false;
}

/** GET /api/view-as/session status -> what to do: 401/404/410 mean the grant is dead. */
export function classifyViewAsStatus(status: number): "ok" | "invalid" | "error" {
  if (status >= 200 && status < 300) return "ok";
  if (status === 401 || status === 404 || status === 410) return "invalid";
  return "error";
}
