// Response decoding for apiFetch callers (split out of core.ts, which re-exports
// it): friendly status fallbacks, toJson / toJsonQuietOn error mapping (toast,
// maintenance, spend-cap guards, client-error beacon) and jsonBody.
import { toast } from "../toastBus";
import { reportClientError } from "../clientLogger";
import { isMaintenanceResponse, signalMaintenance } from "../platformStatus";
import { knownGuardError } from "../spendCap";
import type { ApiError } from "./transport";

// Shape of the JSON error body GlobalExceptionHandler returns - every field
// optional, since a raw infra 4xx/5xx may carry no JSON at all.
type ApiErrorBody = {
  error?: string;
  /** RFC 9457 problem+json human sentence, used when there is no legacy `error`. */
  detail?: unknown;
  code?: unknown;
  max_age?: unknown;
  upstreamStatus?: unknown;
  errors?: unknown[];
  gate?: unknown;
  report?: unknown;
};

async function readErrorBody(res: Response): Promise<ApiErrorBody> {
  try {
    const body: unknown = await res.json();
    return body && typeof body === "object" ? (body as ApiErrorBody) : {};
  } catch {
    // Non-JSON error body (e.g. a raw 404 from a route that doesn't exist) - fall through.
    return {};
  }
}

// Direct feedback ("proper meaningful messages to consumer, not raw http
// responses") - every 4xx/5xx GlobalExceptionHandler maps already comes back
// with a clean JSON { error: "..." } body (see that class's javadoc), so
// body.error below covers the normal case. This fallback only fires when
// there's NO JSON body at all - a raw infra-level failure that never reached
// Spring's exception handling (a route that doesn't exist, a reverse proxy
// 502, the backend process itself being down) - so `HTTP 404 Not Found`
// used to be exactly what a user saw on screen. Classified by status range
// instead, same "no raw HTTP detail reaches the consumer" posture as the
// backend's own ProviderErrorTranslator#classify.
export function friendlyStatusFallback(status: number): string {
  if (status === 400) return "That request wasn't accepted - please check the details and try again.";
  if (status === 401) return "Your session has expired. Please sign in again.";
  if (status === 403) return "You don't have permission to do that - ask an admin if you think you should.";
  if (status === 404) return "That couldn't be found - it may have been removed or moved.";
  if (status === 408 || status === 504) return "The service took too long to respond - please try again.";
  if (status === 409)
    return "Someone else changed this while you were editing it, or it conflicts with its current state - reload and try again.";
  if (status === 422) return "Some of the details weren't valid - check the fields and try again.";
  if (status === 429) return "Too many requests - please wait a moment and try again.";
  // 502/503 is almost always a restart or overload in front of a healthy
  // app (a Container Apps revision swap is the classic trigger) - naming
  // the likely cause is more honest and more actionable than a flat "sorry".
  if (status === 502 || status === 503)
    return "The service is restarting or briefly overloaded - it usually clears within a minute. Please retry.";
  if (status >= 500) return "Something went wrong on our side - the error was logged and our team is looking into it.";
  return "Something went wrong. Please try again.";
}

// Generic over the caller's expected payload type. T is an assertion about the
// server contract, not a runtime check (see ./schemas.ts for the boundaries
// that ARE validated); the default `unknown` forces a caller that has no
// type to narrow before reading fields.
export async function toJson<T = unknown>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await readErrorBody(res);
    let endpoint = "";
    try {
      endpoint = res.url ? new URL(res.url).pathname : "";
    } catch {
      // keep empty
    }
    const message =
      body.error ||
      (typeof body.detail === "string" && body.detail) ||
      `${friendlyStatusFallback(res.status)} (HTTP ${res.status}${res.statusText ? " " + res.statusText : ""}${endpoint ? " on " + endpoint : ""})`;
    const err: ApiError = new Error(message);
    err.status = res.status;
    if (typeof body.code === "string") err.code = body.code;
    if (typeof body.max_age === "number" && Number.isFinite(body.max_age)) err.maxAge = body.max_age;
    err.upstreamStatus = body.upstreamStatus;
    // Workflow governance rejections (OrchestratorService#validate, surfaced
    // via GlobalExceptionHandler#handleWorkflowValidation) carry the full
    // list of problems here, not just the first - see WorkflowCanvas.js.
    err.errors = body.errors;
    // Agent-version promote rejections (AgentVersionController's 412, V260
    // eval gate) carry the full gate report under `gate` - which eval run
    // was consulted, its verdict/score, and why it did not qualify - so the
    // Versions card can render "what do I need to fix" straight off the
    // failure without a second call. Same opt-in convention as err.errors:
    // undefined for every response that doesn't carry one.
    err.gate = body.gate;
    // Template submit rejections (TemplateNotReadyException, 422 TEMPLATE_NOT_READY) carry the whole
    // readiness report - every blocker and advisory at once - so the submit screen can list them all.
    err.report = body.report;
    // Platform maintenance (web-admin /maintenance): core refuses every
    // non-platform-admin call with 503 {error:"maintenance"}. AppShell swaps
    // to the maintenance screen on this signal, so no per-call toast.
    if (isMaintenanceResponse(res.status, body)) {
      err.maintenance = true;
      signalMaintenance();
      throw err;
    }
    // Spend cap reached (402) / view-as read-only refusal (403): a specific,
    // expected state with its own clear wording - never the generic error toast.
    const guard: any = knownGuardError(res.status, body);
    if (guard) {
      if (guard.readOnly) toast.info(guard.message);
      else toast.warn(guard.message);
      throw guard;
    }
    // Global HUD popup (ToastHost.js) - fires here, once, for every failed
    // API call across the whole console, so individual panels don't each
    // need their own wiring to satisfy "all errors should be popup." A 401
    // is deliberately skipped: that's the normal shape of "your session
    // expired, about to redirect to Keycloak," not a problem worth an alarm
    // popup for. Everything else (validation, 404s, 5xxs, upstream provider
    // failures) raises one.
    if (res.status !== 401) {
      toast.error(
        err.errors?.length ? `${message} (${err.errors.length} issue${err.errors.length === 1 ? "" : "s"})` : message
      );
      // Same "skip 401" condition as the toast above - a 401 is the normal
      // shape of "session expired," not a failure worth keeping server-side.
      // See app/lib/clientLogger.ts for what this beacon does and doesn't do.
      reportClientError("api_call_failed", message, { status: res.status, url: res.url });
    }
    throw err;
  }
  if (res.status === 204) return null as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

// Same contract as toJson, except the listed statuses raise NO global toast
// and NO client-error beacon. For endpoints where a specific status is an
// expected STATE rather than a failure — e.g. the LLM-usage endpoints return
// 403 on FREE plans ("Premium feature"), and the maintenance screen POLLS
// them, so routing that through toJson meant an ERROR toast popping over the
// admin console every poll cycle for something that isn't wrong (audit
// screenshot, 2026-07-28). The error still throws with .status intact, so
// panels render their own inline entitlement notice.
export function toJsonQuietOn<T = unknown>(quietStatuses: number[]) {
  return async (res: Response): Promise<T> => {
    if (!res.ok && quietStatuses.includes(res.status)) {
      const body = await readErrorBody(res);
      // Quiet means no toast, not a vaguer message: a spend-cap / read-only
      // refusal still carries its own wording for the inline notice.
      const guard = knownGuardError(res.status, body);
      if (guard) throw guard;
      const err: ApiError = new Error(body.error || friendlyStatusFallback(res.status));
      err.status = res.status;
      if (isMaintenanceResponse(res.status, body)) signalMaintenance();
      throw err;
    }
    return toJson<T>(res);
  };
}

export function jsonBody(payload: unknown) {
  return {
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  };
}
