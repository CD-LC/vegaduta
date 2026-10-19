// A reply that failed (provider rejected the key, rate limit, upstream error...) is part of the conversation:
// it must look the same live and after a reload, and offer "Try again". Prod re-verification 2026-10-05, bug 13:
// the error showed live (a transient banner) and was gone after a reload, leaving the user message with nothing
// under it.
//
// Core records the failed turn as an assistant message and marks it. The wire contract is not final, so a
// message counts as failed if ANY of these hold - the integrator can reconcile with core's DTO later:
//   - `status === "failed"`
//   - `error === true`
//   - its text starts with the literal prefix "[reply failed] " (stripped for display)
// Pure and total: nothing here throws on odd input.

export const FAILED_REPLY_PREFIX = "[reply failed] ";

export interface MaybeFailedMessage {
  status?: unknown;
  error?: unknown;
  content?: unknown;
  text?: unknown;
}

function textOf(m: MaybeFailedMessage | null | undefined): string {
  if (!m) return "";
  if (typeof m.content === "string") return m.content;
  if (typeof m.text === "string") return m.text;
  return "";
}

export function isFailedMessage(m: MaybeFailedMessage | null | undefined): boolean {
  if (!m || typeof m !== "object") return false;
  if (m.status === "failed" || m.error === true) return true;
  return textOf(m).startsWith(FAILED_REPLY_PREFIX);
}

/** The text to show for a failed message: the marker prefix removed. */
export function failedDisplayText(m: MaybeFailedMessage | null | undefined): string {
  const text = textOf(m);
  return text.startsWith(FAILED_REPLY_PREFIX) ? text.slice(FAILED_REPLY_PREFIX.length) : text;
}

/** The previous user message of the turn at `index`, when it can simply be sent again (text only). */
export function retryableUserText(
  turns: ReadonlyArray<{ role?: string; content?: string; attachment?: unknown; attachments?: unknown[]; frames?: unknown[] }>,
  index: number,
): string | null {
  const prev = turns[index - 1];
  if (!prev || prev.role !== "user" || typeof prev.content !== "string" || !prev.content.trim()) return null;
  if (prev.attachment) return null;
  if (Array.isArray(prev.attachments) && prev.attachments.length > 0) return null;
  if (Array.isArray(prev.frames) && prev.frames.length > 0) return null;
  return prev.content;
}

/** The person (or the page) stopped the request: not a failure, so no error bubble. */
export function isCancelledError(err: unknown): boolean {
  const e = err as { name?: unknown; failureClass?: unknown } | null | undefined;
  return !!e && (e.name === "AbortError" || e.failureClass === "aborted");
}
