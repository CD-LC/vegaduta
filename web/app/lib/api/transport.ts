// The network layer under apiFetch (split out of core.ts): the API base URL,
// the unreachable/offline messages and outage-toast coalescing, the in-flight
// activity counter, and the single authenticated fetch (doFetch/apiFetchOnce).
// core.ts re-exports everything here, so callers keep importing from "./core".
import { auth } from "../auth";
import { toast } from "../toastBus";
import { reportClientError } from "../clientLogger";
import { VIEW_AS_HEADER, viewAsHeaders } from "../viewAs";
import { publicApiBase } from "../env";
import { errorMessage, errorName } from "../errors";

export const API_BASE = publicApiBase();

// Every call in this file goes through here instead of bare fetch() so the
// Keycloak-issued bearer token (Phase 0 Stage 3) rides on every request.
// Harmless against a `core` running without the "security" profile (the
// header is just ignored by NoSecurityConfig's permitAll() chain), so this
// works unmodified whether the backend has auth on or off.
// `timeoutMs` (opt-in, not a default on every call) - direct feedback: the
// per-agent tool picker's "Loading available tools…" could sit forever
// because a bare fetch() has no client-side ceiling at all, only whatever
// the backend's own MCP-resolution timeout happens to be (up to ~60s, and
// possibly longer if the hang is actually in spawning a subprocess rather
// than in a call the backend's timeout wraps). Not applied unconditionally
// to every apiFetch call because some (the SSE chat stream in particular)
// are SUPPOSED to stay open far longer than any reasonable fixed ceiling.
// 2026-07-20 direct feedback: "if backend is not reachable it should show
// sorry we will be back, our team is looking into issue" - a real gap this
// closes. Everything below (toJson's friendlyStatusFallback etc.) only
// runs once fetch() has actually resolved to a Response - a 4xx/5xx from a
// backend that IS running. When the backend process itself is down (or
// unreachable - DNS/connection-refused/CORS-preflight failure), fetch()
// itself REJECTS with a raw TypeError ("Failed to fetch") that used to
// propagate straight past every one of this file's friendly-message layers
// and surface verbatim in whatever panel's own catch block happened to
// display it. Caught here, once, for every call in this file, same "one
// place fixes every caller" posture as toJson's own toast wiring below.
export const BACKEND_UNREACHABLE_MESSAGE =
  "We're sorry, our service is temporarily unavailable. Our team has been notified and is looking into it - please try again shortly.";

// Names the actual failure instead of the flat BACKEND_UNREACHABLE_MESSAGE
// (direct feedback 2026-10-01: "even internet connected it says something went
// wrong ... there should be specific message on what the error is"). A browser
// hides WHY a fetch rejected, but the page can still tell the likely causes
// apart from what it does know: a client-side timeout, a build pointed at
// localhost, an https page calling an http API (mixed content), or a plain
// dropped/blocked connection - and it always says which endpoint failed.
export function describeNetworkFailure(rawError: any, path: string, method?: string, timedOut = false): string {
  const verb = String(method || "GET").toUpperCase();
  const where = `${verb} ${path.split("?")[0]}`;
  const raw = String(rawError?.message || rawError || "unknown error");
  let host = API_BASE;
  let apiHost = "";
  try {
    const u = new URL(API_BASE);
    host = u.host;
    apiHost = u.hostname;
  } catch {
    // relative/odd base - fall through with the raw string
  }
  const pageHost = typeof location !== "undefined" ? location.hostname : "";
  const isLocal = (h: string) => h === "localhost" || h === "127.0.0.1" || h === "[::1]" || h === "::1";
  if (timedOut) {
    return `No response from ${host} for ${where} - the server accepted the connection or is busy but did not answer in time. Retry in a moment.`;
  }
  if (apiHost && isLocal(apiHost) && pageHost && !isLocal(pageHost)) {
    return `This build is configured to call ${host} (${where}), which is only reachable from the machine itself. NEXT_PUBLIC_API_BASE_URL was baked as localhost at build time - the web app needs a rebuild with the public API URL. [${raw}]`;
  }
  if (typeof location !== "undefined" && location.protocol === "https:" && API_BASE.startsWith("http://")) {
    return `The browser blocked ${where} because this page is https but the API (${host}) is plain http (mixed content). The API URL must be https. [${raw}]`;
  }
  return `Couldn't complete ${where} to ${host}: the browser reports "${raw}". Your internet is up, so the likely causes are the server restarting, the request being blocked by CORS, or a DNS/TLS problem reaching ${host}. Retry shortly; if it persists, report this message.`;
}

// The browser is offline / has no network route at all - a DIFFERENT root
// cause than the backend being down, and the person can fix this one
// themselves. navigator.onLine is a hint not a verdict (it only asserts a
// network interface exists), so it only ever upgrades the message toward
// "your side", never the other way.
export const OFFLINE_MESSAGE =
  "You appear to be offline - check your internet connection. We'll pick up where we left off when you're back.";

// Outage coalescing (2026-09-25, "so many 'service unavailable' popups"):
// during a real outage - a revision swap, a cold restart - EVERY in-flight
// and newly-fired request rejects at the network layer, and toastBus's 4s
// identical-message window means the same toast re-pops every 4 seconds for
// the entire outage: one problem, N popups. This module-level state turns a
// reachability outage into ONE popup per minute max, plus a single green
// "reconnected" when the next fetch resolves - the information "it broke /
// it's back" without the alarm storm.
let backendDownAt: number | null = null;
let lastUnreachableToastAt = 0;
const UNREACHABLE_TOAST_COOLDOWN_MS = 60_000;

function noteBackendUnreachable(message: string): void {
  if (backendDownAt == null) backendDownAt = Date.now();
  const now = Date.now();
  if (now - lastUnreachableToastAt < UNREACHABLE_TOAST_COOLDOWN_MS) return;
  lastUnreachableToastAt = now;
  toast.error(message);
}

function noteBackendReachable(): void {
  if (backendDownAt == null) return;
  backendDownAt = null;
  toast.success("Connection restored - the service is reachable again.");
}

// In-flight request activity (prod latency pass, 2026-09-24): an observable
// counter of backend calls whose response headers have not arrived yet.
// NavigationProgress subscribes so the top bar shows for ANY page still
// waiting on data. Counted until headers, so a long chat stream does not pin it.
type ActivityListener = (inflight: number) => void;
const activityListeners = new Set<ActivityListener>();
let inflightCount = 0;
export const apiActivity = {
  get inflight(): number {
    return inflightCount;
  },
  subscribe(listener: ActivityListener): () => void {
    activityListeners.add(listener);
    return () => {
      activityListeners.delete(listener);
    };
  },
};
function activityChange(delta: number): void {
  inflightCount = Math.max(0, inflightCount + delta);
  for (const l of activityListeners) {
    try {
      l(inflightCount);
    } catch {
      // a listener must never break a request
    }
  }
}

// Options every apiFetch* entry point accepts: a plain RequestInit plus the
// two apiFetch-layer directives (stripped before fetch() sees the init).
// `headers` is narrowed to a plain record because apiFetchOnce spreads it and
// adds Authorization - a Headers instance or tuple list would not survive
// that spread, and no caller passes one.
export type ApiFetchOptions = Omit<RequestInit, "headers"> & {
  headers?: Record<string, string>;
  /** Client-side ceiling in ms; aborts the request when exceeded. */
  timeoutMs?: number;
  /** Opt out of the GET dedupe/SWR cache for volatile, polled reads. */
  skipGetCache?: boolean;
};

// The Error toJson / doFetch throw - the extra fields are opt-in (undefined
// unless the failure carried them). Read them via app/lib/errors.ts helpers.
export type ApiError = Error & {
  status?: number;
  upstreamStatus?: unknown;
  errors?: unknown[];
  gate?: unknown;
  /** Template-submit readiness report (422 TEMPLATE_NOT_READY). */
  report?: unknown;
  /** Set when core refused the call because the platform is in maintenance (503). */
  maintenance?: boolean;
  isBackendUnreachable?: boolean;
  offline?: boolean;
  cause?: unknown;
  /** Stable machine-readable code from the error body (e.g. "reauth_required"). */
  code?: string;
  /** `max_age` hint in seconds (reauth_required), when the body carried one. */
  maxAge?: number;
};

export async function doFetch(
  path: string,
  rest: Omit<ApiFetchOptions, "timeoutMs" | "skipGetCache">,
  headers: Record<string, string>,
  signal?: AbortSignal
): Promise<Response> {
  activityChange(1);
  try {
    const res = await fetch(`${API_BASE}${path}`, { ...rest, headers, ...(signal ? { signal } : {}) });
    // ANY resolved response - even a 4xx/5xx - proves the network path is
    // back, so an outage flagged by a previous rejection ends here. The
    // status itself is still the caller's problem to classify.
    noteBackendReachable();
    return res;
  } catch (rawError: unknown) {
    // A caller-supplied external signal aborting (this function's own
    // `signal` param is unset - that's reserved for the internal timeoutMs
    // controller below, whose aborts keep the existing generic-message
    // behavior unchanged) is a DELIBERATE, already-classified event, not an
    // outage. Today the only caller that passes options.signal is
    // api.streamChat's Universal Request Lifecycle wiring - its own
    // timers/cancel() already know exactly why the abort fired
    // (connect-timeout / stream-idle-timeout / absolute-timeout / user
    // cancel) and build their own distinct, user-legible message for it.
    // Wrapping it here in "our service is unavailable" and firing the
    // global outage toast would be actively wrong (hitting Cancel does not
    // mean the backend is down) and would stomp the more specific message.
    if (!signal && errorName(rawError) === "AbortError" && rest?.signal?.aborted) {
      throw rawError;
    }
    // A deliberate client-side timeoutMs abort (see below) looks identical
    // to the user as "didn't get a response in time" - same message either
    // way is the right call, not worth a second copy just to say "slow"
    // instead of "down".
    const offline = typeof navigator !== "undefined" && navigator.onLine === false;
    const message = offline
      ? OFFLINE_MESSAGE
      : describeNetworkFailure(rawError, path, rest?.method, Boolean(signal?.aborted));
    const err: ApiError = new Error(message);
    err.isBackendUnreachable = true;
    err.offline = offline;
    err.cause = rawError;
    // Coalesced, not raw: the first rejection of an outage pops once, repeats
    // are silent for a minute, and noteBackendReachable announces recovery.
    // The toast still fires - a real connectivity break is exactly what the
    // popup is for - it just can't flood.
    noteBackendUnreachable(message);
    reportClientError("api_backend_unreachable", errorMessage(rawError, String(rawError)), {
      url: `${API_BASE}${path}`,
    });
    throw err;
  } finally {
    activityChange(-1);
  }
}

export async function apiFetchOnce(path: string, options: ApiFetchOptions = {}): Promise<Response> {
  const token = await auth.getToken();
  const headers: Record<string, string> = { ...(options.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  // View-as: the admin's own token plus the grant; core serves the viewed
  // tenant/user read-only. Absent outside a view-as tab. A caller that names
  // the header itself (/view-as validating a NEW grant) wins.
  if (!(VIEW_AS_HEADER in headers)) Object.assign(headers, viewAsHeaders());
  // timeoutMs and skipGetCache are apiFetch-layer directives, not RequestInit
  // fields - strip both so neither leaks into the fetch() init below.
  const { timeoutMs, skipGetCache, ...rest } = options;
  if (!timeoutMs) {
    return doFetch(path, rest, headers);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await doFetch(path, rest, headers, controller.signal);
  } finally {
    clearTimeout(timer);
  }
}

// A request body can only be read once. A string / FormData / URLSearchParams /
// Blob body can be handed to fetch() again unchanged; a ReadableStream (or
// anything else exotic) cannot, so those are never retried rather than being
// replayed as an empty body.
export function isReplayableBody(body: unknown): boolean {
  if (body == null) return true;
  if (typeof body === "string") return true;
  if (typeof FormData !== "undefined" && body instanceof FormData) return true;
  if (typeof URLSearchParams !== "undefined" && body instanceof URLSearchParams) return true;
  if (typeof Blob !== "undefined" && body instanceof Blob) return true;
  if (typeof ArrayBuffer !== "undefined" && body instanceof ArrayBuffer) return true;
  return false;
}
