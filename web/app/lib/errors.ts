/**
 * Helpers for reading a `catch (e)` value, which TypeScript types as `unknown`
 * (`useUnknownInCatchVariables`). Kept separate from `lib/api*` on purpose: the
 * API error format is owned there, while this only reads the generic shape.
 *
 * `errorMessage(e, fallback)` is the typed replacement for the old
 * `e.message || fallback` idiom and returns the same value for every input that
 * idiom handled: an `Error` (or any object with a string `message`) gives its
 * message, and an empty or missing message gives the fallback.
 */

/** The string `message` of an Error or Error-like object, or undefined. */
function messageField(e: unknown): string | undefined {
  if (typeof e !== "object" || e === null) return undefined;
  const message = (e as { message?: unknown }).message;
  return typeof message === "string" ? message : undefined;
}

/**
 * Human-readable message for a caught value.
 *
 * - With a `fallback`: the value's non-empty `message`, else `fallback`
 *   (same result as `e.message || fallback`, but never throws on null).
 * - Without one: the value's `message` (even if empty), else the value itself
 *   when it is a string, else `String(e)` ("Unknown error" for null/undefined).
 */
export function errorMessage(e: unknown, fallback?: string): string {
  const message = messageField(e);
  if (fallback !== undefined) return message ? message : fallback;
  if (message !== undefined) return message;
  if (typeof e === "string") return e;
  if (e === null || e === undefined) return "Unknown error";
  return String(e);
}

/**
 * A named field of an Error-like caught value, or undefined. lib/api's toJson
 * throws a plain Error with extra fields attached (`status`, `errors`, `gate`,
 * ...), so there is no error class to narrow with `instanceof`; read those
 * fields through here instead of casting the catch variable.
 */
export function errorField(e: unknown, key: string): unknown {
  return typeof e === "object" && e !== null ? (e as Record<string, unknown>)[key] : undefined;
}

/** The string `name` of an Error/DOMException-like value (e.g. "AbortError"), or undefined. */
export function errorName(e: unknown): string | undefined {
  const name = errorField(e, "name");
  return typeof name === "string" ? name : undefined;
}

/** The numeric HTTP `status` lib/api attaches to a failed call, or undefined. */
export function errorStatus(e: unknown): number | undefined {
  const status = errorField(e, "status");
  return typeof status === "number" ? status : undefined;
}

/** Shown when an edit lost an optimistic-lock race and core sent no message of its own. */
export const EDIT_CONFLICT_MESSAGE =
  "Someone else changed this while you were editing it. Reload to see their changes, then try again.";

/** True for an HTTP 409, which core answers for a lost optimistic-lock race (tenants, agents, workflows, studio apps). */
export function isConflictError(e: unknown): boolean {
  return errorStatus(e) === 409;
}

/**
 * Message for a failed save: on a 409 the "someone else changed this - reload"
 * copy (core's own sentence when it sent one, else EDIT_CONFLICT_MESSAGE),
 * otherwise errorMessage(e, fallback). Use in the catch of an edit/save of a
 * tenant, agent, workflow or studio app.
 */
export function saveErrorMessage(e: unknown, fallback: string): string {
  if (isConflictError(e)) return errorMessage(e, EDIT_CONFLICT_MESSAGE) || EDIT_CONFLICT_MESSAGE;
  return errorMessage(e, fallback);
}

/** Stable `code` core answers with (HTTP 401) when a sensitive action needs a fresh sign-in. */
export const REAUTH_REQUIRED_CODE = "reauth_required";

/**
 * True for core's 401 `reauth_required`: the session is valid but too old for
 * this action (DELETE /api/account). The right response is a `prompt=login`
 * re-authentication, not the generic "session expired" path.
 */
export function isReauthRequired(e: unknown): boolean {
  return errorStatus(e) === 401 && errorField(e, "code") === REAUTH_REQUIRED_CODE;
}

/** The `max_age` hint (seconds) a reauth_required error carried, or undefined. */
export function reauthMaxAgeSeconds(e: unknown): number | undefined {
  const v = errorField(e, "maxAge");
  return typeof v === "number" && Number.isFinite(v) && v >= 0 ? v : undefined;
}
