// Thin fetch wrapper around the core Spring Boot API - trimmed to exactly the
// endpoints this standalone platform-admin app uses (the platform-wide
// /api/platform/** surface plus /api/system/status for the shared
// SystemStatusPanel). Ported from web/app/lib/api.js's apiFetch/toJson
// pattern verbatim (see that file's own comments for the reasoning behind
// the Authorization header, the timeoutMs escape hatch, and the toJson
// error/toast handling) - every other domain (agents, workflows, knowledge,
// channels, etc.) intentionally has no wrapper here, since this app never
// calls those endpoints.
import { auth } from "./auth";
import { toast } from "./toastBus";
import { reportClientError } from "./clientLogger";
import { publicApiBase } from "./env";

export const API_BASE = publicApiBase();

// Wire types for the `api` object's responses. `JsonObject` is used where a
// screen only needs loose property access; named shapes where a method's
// comment documents the contract or a call site iterates/inspects specific
// fields (typing the returns keeps `.map`/`.filter`/`.catch` callbacks
// properly inferred under noImplicitAny + strictBindCallApply instead of
// forcing every consumer to re-annotate `any`).
export type JsonObject = Record<string, any>;

export interface IssuesOpenCount {
  open: number;
  failedTenants: number;
}

// PlatformKillSwitchStatus - see getKillSwitchStatus's comment.
export interface KillSwitchStatus {
  stopped: boolean;
  reason: string | null;
  stoppedAt: string | null;
  updatedBy: string | null;
}

export interface McpRegistrySearchResult {
  results?: any[];
  unavailable?: boolean;
  nextCursor?: string | null;
}

// { pluginId, version, artifact: { fileName, sizeBytes, sha256 } } - the
// fragment plugins/page.tsx merges into the catalog entry before saving.
export interface PluginArtifactUploadResult {
  pluginId: string;
  version: string;
  artifact: { fileName: string; sizeBytes: number; sha256: string };
}

// WebSearchProvidersController's read-only provider picture.
export interface WebSearchProvidersStatus {
  multiProviderActive?: boolean;
  primary?: string;
  fallback?: string[];
  activeNow?: string | null;
  providers?: any[];
}

// discoverHostedLlmModels resolves to { models: [] } on any upstream
// failure - an empty list means "couldn't discover, fall back to manual".
export interface HostedLlmModelDiscovery {
  models?: any[];
}

// PlatformUserController's paged user list.
export interface PlatformUsersPage {
  users?: any[];
  total?: number;
}

// SiteWidePromoResult - see grantSiteWidePromo's comment.
export interface SiteWidePromoResult {
  grantedTenants: { id: string; name: string }[];
  skippedAlreadyUnlimitedTenantIds: string[];
  grantedAt: string;
  expiresAt: string;
  reason: string;
}

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
// Names the actual failure instead of a flat "service unavailable"
// (mirrors web/app/lib/api/core.ts): which request failed, to which host, and
// the likely cause - timeout, a build baked with localhost, mixed content, or a
// dropped/blocked connection.
export function describeNetworkFailure(rawError: any, path: string, method?: string, timedOut = false): string {
  const where = `${String(method || "GET").toUpperCase()} ${path.split("?")[0]}`;
  const raw = String(rawError?.message || rawError || "unknown error");
  let host = API_BASE;
  let apiHost = "";
  try {
    const u = new URL(API_BASE);
    host = u.host;
    apiHost = u.hostname;
  } catch {
    // relative/odd base - keep the raw string
  }
  const pageHost = typeof location !== "undefined" ? location.hostname : "";
  const isLocal = (h: string) => h === "localhost" || h === "127.0.0.1" || h === "[::1]" || h === "::1";
  if (timedOut) {
    return `No response from ${host} for ${where} - the server is busy or not answering in time. Retry in a moment.`;
  }
  if (apiHost && isLocal(apiHost) && pageHost && !isLocal(pageHost)) {
    return `This build is configured to call ${host} (${where}), which is only reachable from the machine itself. NEXT_PUBLIC_API_BASE_URL was baked as localhost at build time - web-admin needs a rebuild with the public API URL. [${raw}]`;
  }
  if (typeof location !== "undefined" && location.protocol === "https:" && API_BASE.startsWith("http://")) {
    return `The browser blocked ${where} because this page is https but the API (${host}) is plain http (mixed content). The API URL must be https. [${raw}]`;
  }
  return `Couldn't complete ${where} to ${host}: the browser reports "${raw}". Your internet is up, so the likely causes are the server restarting, the request being blocked by CORS, or a DNS/TLS problem reaching ${host}. Retry shortly; if it persists, report this message.`;
}

const OFFLINE_MESSAGE =
  "You appear to be offline - check your internet connection. We'll pick up where we left off when you're back.";

// Outage coalescing (mirrors web/app/lib/api/core.ts): during a real outage
// every request rejects at the network layer and toastBus's 4s window means
// the same toast re-pops every 4s for the whole outage. One popup per
// minute max, plus a single "reconnected" when a fetch resolves again.
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

// A read that fails at the network layer is retried once before it is called an
// outage: a revision swap during a deploy, a dropped connection or a Cloudflare
// edge blip all clear within a second, and a single one used to raise the
// "service unavailable" popup. Only idempotent GETs are retried, never a
// deliberate timeout abort, and never a write.
const NETWORK_RETRY_DELAY_MS = 700;

async function doFetch(
  path: string,
  rest: any,
  headers: any,
  signal?: AbortSignal,
  quiet = false,
  retried = false
): Promise<Response> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, { ...rest, headers, ...(signal ? { signal } : {}) });
  } catch (rawError: any) {
    const isGet = (rest?.method || "GET").toUpperCase() === "GET";
    if (isGet && !retried && !signal?.aborted && rawError?.name !== "AbortError") {
      await new Promise((resolve) => setTimeout(resolve, NETWORK_RETRY_DELAY_MS));
      return doFetch(path, rest, headers, signal, quiet, true);
    }
    // A deliberate client-side timeoutMs abort (see below) looks identical
    // to the user as "didn't get a response in time" - same message either
    // way is the right call, not worth a second copy just to say "slow"
    // instead of "down".
    const offline = typeof navigator !== "undefined" && navigator.onLine === false;
    const message = offline
      ? OFFLINE_MESSAGE
      : describeNetworkFailure(rawError, path, rest?.method, Boolean(signal?.aborted));
    const err: any = new Error(message);
    err.isBackendUnreachable = true;
    err.offline = offline;
    err.cause = rawError;
    // Speculative background reads (session prefetch) must not raise the outage popup
    // on their own: whatever page the person opens next makes the real call and
    // surfaces its own error.
    if (!quiet) noteBackendUnreachable(message);
    reportClientError("api_backend_unreachable", String(rawError?.message || rawError), { url: `${API_BASE}${path}` });
    throw err;
  }
  // Outside the try on purpose: a fault in the "reconnected" popup must never turn a
  // request that succeeded into a reported outage.
  noteBackendReachable();
  return res;
}

// Two layers of "don't ask the network twice", the out-of-the-box speed work
// every modern console ships (SWR/React-Query style, kept dependency-free):
//
// 1. IN-FLIGHT DEDUPE - identical CONCURRENT GETs share one request. The
//    admin overview mounts THREE getPlatformHealth() callers at once (the
//    page's Promise.all, the AdminShell health pill, SystemStatusSection).
// 2. FRESH TTL + STALE-WHILE-REVALIDATE - a completed GET is remembered and
//    served for GET_CACHE_TTL_MS with zero network. Past that, for up to
//    GET_CACHE_STALE_MS, the stale copy is STILL served instantly while a
//    deduped background refresh repopulates the entry for the next read -
//    the standard SWR contract (React Query's staleTime+refetch, minus the
//    dependency). This is what kills the "every page loads" feel: a revisit
//    renders last-known data immediately instead of a spinner, and the data
//    self-heals behind the scenes. Own-writes stay exact - every mutation
//    through this same apiFetch flushes the whole map (non-GET branch), so
//    a page that writes then re-reads always sees its own write. The one
//    honest bound: data another admin changes can be up to ~STALE_MS old on
//    screen, converging on each navigation.
//
// Safety posture: only PLAIN GETs participate - any caller-specific option
// (timeout, signal, headers, body) or non-GET method bypasses sharing
// entirely, so nothing with side effects or per-caller semantics can ever
// be conflated. Error responses are shared in-flight (all waiters get the
// same failure, same as today) but never cached - a recovered backend shows
// up on the very next call. A terminal 401 flushes the cache too: the
// session that populated it is over, and no later identity may inherit it.
const GET_CACHE_TTL_MS = 15_000;
const GET_CACHE_STALE_MS = 300_000;
const GET_CACHE_MAX_ENTRIES = 200;

// A GET response buffered into a reusable form - a Response body is
// single-use, so sharing/caching requires keeping the bytes and handing
// each consumer a fresh Response. `res` survives for the (test-stub) case
// where the response has no text() to buffer; those pass through unshared.
type SharedGet = { res?: Response; ok: boolean; status?: number; headers?: Headers; body?: string };

const inflightGets = new Map<string, Promise<SharedGet>>();
const getCache = new Map<string, { data: SharedGet; fetchedAt: number }>();

/**
 * Flush the whole GET cache. Called by tests between cases (a module-level
 * cache survives across a suite's fetch stubs otherwise) and legitimately by
 * sign-out/user-switch flows - a different identity must never inherit a
 * previous session's cached reads.
 */
export function clearApiGetCache(): void {
  getCache.clear();
}

/**
 * Session boot warm-up: fire the handful of GETs the console needs the
 * moment auth resolves, so the FIRST navigation renders from the SWR cache
 * instead of paying a cold round trip per read. Fire-and-forget through
 * apiFetch itself - responses land in the same dedupe+cache a real page
 * call uses (a page mounting mid-prefetch shares the in-flight request
 * rather than doubling it). Errors are swallowed by design: prefetch is
 * speculative, the real call site surfaces its own errors when it runs.
 */
export function prefetchApiGets(paths: string[]): void {
  for (const path of paths) {
    apiFetch(path, { quiet: true }).then(
      () => {},
      () => {}
    );
  }
}

function isDedupableGet(options: any): boolean {
  const method = (options?.method || "GET").toUpperCase();
  return (
    method === "GET" &&
    options?.timeoutMs == null &&
    // `quiet` only changes whether an outage popup is raised, never the request.
    options?.signal == null &&
    options?.headers == null &&
    options?.body == null
  );
}

function responseFromShared(s: SharedGet): Response {
  if (s.res) return s.res;
  // Null-body statuses (204 etc.) reject a non-null body in the Response
  // constructor - an empty buffered body is passed as null.
  return new Response(s.body === "" ? null : s.body, { status: s.status, headers: s.headers });
}

async function shareableGet(path: string, options: any): Promise<SharedGet> {
  const res = await performFetch(path, options);
  if (typeof res?.text !== "function") {
    return { res, ok: !!res?.ok };
  }
  return { status: res.status, headers: res.headers, body: await res.text(), ok: res.ok };
}

export async function apiFetch(path: string, options: any = {}): Promise<Response> {
  const method = (options?.method || "GET").toUpperCase();
  if (method !== "GET") {
    // Any write can change what every GET returns, and the cache has no way
    // to know which paths a mutation touched - the whole (small, ~15s) map
    // is dropped. This is what keeps the cache honest: a page that writes
    // then re-reads always sees its own write.
    getCache.clear();
    return performFetch(path, options);
  }
  if (isDedupableGet(options)) {
    const cached = getCache.get(path);
    if (cached) {
      const age = Date.now() - cached.fetchedAt;
      if (age <= GET_CACHE_TTL_MS) {
        return responseFromShared(cached.data);
      }
      if (age <= GET_CACHE_STALE_MS) {
        // Stale-while-revalidate: the caller renders last-known data with
        // zero wait; the deduped refresh lands in the cache for the next
        // read. Explicit settle handler - nobody awaits this refresh, so a
        // rejection must be swallowed here, not surface as unhandled.
        refreshGet(path, options).then(
          () => {},
          () => {}
        );
        return responseFromShared(cached.data);
      }
      getCache.delete(path);
    }
    return refreshGet(path, options).then(responseFromShared);
  }
  return performFetch(path, options);
}

// One network fetch behind the in-flight dedupe: concurrent callers share
// the same promise (each still gets its own Response via responseFromShared
// at the call site), a success lands in the cache, a 401 flushes it.
function refreshGet(path: string, options: any): Promise<SharedGet> {
  const inflight = inflightGets.get(path);
  if (inflight != null) {
    return inflight;
  }
  const promise = shareableGet(path, options);
  inflightGets.set(path, promise);
  // .then(clear, clear) rather than .finally: a discarded finally() chain
  // would surface an unhandled rejection on top of the caller's own.
  const clear = () => inflightGets.delete(path);
  promise.then(clear, clear);
  promise.then(
    (s) => {
      if (s.ok) {
        if (getCache.size >= GET_CACHE_MAX_ENTRIES) {
          getCache.delete(getCache.keys().next().value);
        }
        getCache.set(path, { data: s, fetchedAt: Date.now() });
      }
      if (s.status === 401) {
        getCache.clear();
      }
      // Explicit rejection handler on this discarded branch - a failed
      // shareableGet must not surface as a second, unhandled rejection on
      // top of the one the real caller already gets.
    },
    () => {}
  );
  return promise;
}

async function performFetch(path: string, options: any = {}): Promise<Response> {
  const token = await auth.getToken();
  const headers = { ...(options.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  const { timeoutMs, quiet, ...rest } = options;
  if (!timeoutMs) {
    return doFetch(path, rest, headers, undefined, !!quiet);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await doFetch(path, rest, headers, controller.signal, !!quiet);
  } finally {
    clearTimeout(timer);
  }
}

// Direct feedback ("proper meaningful messages to consumer, not raw http
// responses") - ported from web/app/lib/api.js's identical helper. Only
// fires when there's no JSON body at all (a raw infra-level failure that
// never reached GlobalExceptionHandler, e.g. a route that doesn't exist or a
// reverse-proxy 502) - the normal case (any 4xx/5xx GlobalExceptionHandler
// itself maps) always has body.error already set to a clean message.
function friendlyStatusFallback(status: number): string {
  if (status === 401) return "Your session has expired. Please sign in again.";
  if (status === 403) return "You don't have permission to do that.";
  if (status === 404) return "That couldn't be found.";
  if (status === 409)
    return "Someone else changed this while you were editing it, or it conflicts with its current state - reload and try again.";
  if (status === 429) return "Too many requests - please wait a moment and try again.";
  if (status >= 500) return "The service is temporarily unavailable. Please try again shortly.";
  return "Something went wrong. Please try again.";
}

// Background reads (nav badges, the notification poll) must never pop an error
// toast: the person did not ask for them, and a role/tenant-scope refusal on
// one of them would repeat on every page load. Same parsing and same thrown
// Error as toJson, minus the toast and the client-error beacon.
export async function toJsonQuiet(res: Response): Promise<any> {
  return toJson(res, true);
}

export async function toJson(res: Response, quiet = false): Promise<any> {
  if (!res.ok) {
    let body: any = {};
    try {
      body = await res.json();
    } catch {
      // Non-JSON error body - fall through.
    }
    let endpoint = "";
    try {
      endpoint = res.url ? new URL(res.url).pathname : "";
    } catch {
      // keep empty
    }
    const message =
      body.error ||
      `${friendlyStatusFallback(res.status)} (HTTP ${res.status}${res.statusText ? " " + res.statusText : ""}${endpoint ? " on " + endpoint : ""})`;
    const err: any = new Error(message);
    err.status = res.status;
    err.upstreamStatus = body.upstreamStatus;
    err.errors = body.errors;
    // Same "skip the popup on 401" posture as web/'s toJson - a 401 here
    // means the token expired mid-session; login-required mode (auth.js)
    // sends the person straight back to Keycloak rather than showing a
    // stale app screen, so an alarm popup on the way out would be noise.
    if (res.status !== 401 && !quiet) {
      toast.error(
        err.errors?.length ? `${message} (${err.errors.length} issue${err.errors.length === 1 ? "" : "s"})` : message
      );
      // Same "skip 401" condition as the toast above - see web/'s api.ts for
      // the reasoning. See app/lib/clientLogger.ts for what this beacon does.
      reportClientError("api_call_failed", message, { status: res.status, url: res.url });
    }
    throw err;
  }
  if (res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export function jsonBody(payload: any) {
  return {
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  };
}

