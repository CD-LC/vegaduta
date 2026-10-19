// Pure helpers for data pinning (no React, no network) so the rules unit-test
// without a DOM. The wire types and fetchers live in lib/api/workflowPins.ts.

import {
  PIN_MAX_CHARS,
  PIN_MAX_COUNT,
  PIN_MAX_TOTAL_CHARS,
  type PinFailureKind,
  type PinSource,
  type WorkflowPin,
} from "../../lib/api/workflowPins";

export type PinMap = Readonly<Record<string, WorkflowPin>>;

/** Trace status the engine writes for a node that returned its pinned output instead of running. */
export const PINNED_STATUS = "PINNED";

export function isPinnedStatus(status: string | null | undefined): boolean {
  return (status ?? "").trim().toUpperCase() === PINNED_STATUS;
}

/** Trace entries (wire shape) whose node did not run because a pin answered for it. */
export function countPinnedTrace(trace: ReadonlyArray<{ status?: string | null } | null | undefined> | null | undefined): number {
  if (!trace) return 0;
  let n = 0;
  for (const e of trace) if (e && isPinnedStatus(e.status)) n++;
  return n;
}

/** Distinct nodes among the pinned trace entries (a loop body can be pinned and visited many times). */
export function pinnedTraceNodeIds(trace: ReadonlyArray<{ nodeId?: string | null; status?: string | null } | null | undefined> | null | undefined): string[] {
  const out = new Set<string>();
  for (const e of trace ?? []) if (e && e.nodeId && isPinnedStatus(e.status)) out.add(e.nodeId);
  return [...out];
}

export type PinTextCheck =
  // Mirror fields: the project has no strictNullChecks, so `ok` alone does not narrow the union.
  | { ok: true; size: number; json: boolean; reason?: undefined; detail?: undefined }
  | { ok: false; size: number; reason: "empty" | "too-large" | "invalid-json"; detail?: string; json?: undefined };

/**
 * Validate pin text before it is sent. Node outputs are usually JSON but can
 * be plain text (an LLM reply), so only text that LOOKS like a JSON object or
 * array is required to parse; anything else is accepted as-is.
 */
export function checkPinText(text: string): PinTextCheck {
  const size = text.length;
  if (text.trim() === "") return { ok: false, size, reason: "empty" };
  if (size > PIN_MAX_CHARS) return { ok: false, size, reason: "too-large" };
  const t = text.trim();
  if (t.startsWith("{") || t.startsWith("[")) {
    try {
      JSON.parse(t);
      return { ok: true, size, json: true };
    } catch (e) {
      return { ok: false, size, reason: "invalid-json", detail: e instanceof Error ? e.message : String(e) };
    }
  }
  try {
    JSON.parse(t);
    return { ok: true, size, json: true };
  } catch {
    return { ok: true, size, json: false };
  }
}

/** Pretty-print JSON text with 2-space indent; anything that does not parse comes back unchanged. */
export function prettyPinText(text: string): string {
  const t = text.trim();
  if (t === "") return text;
  try {
    return JSON.stringify(JSON.parse(t), null, 2);
  } catch {
    return text;
  }
}

/** Compact JSON (what gets stored keeps the size counter honest); non-JSON is left alone. */
export function compactPinText(text: string): string {
  const t = text.trim();
  if (!(t.startsWith("{") || t.startsWith("["))) return text;
  try {
    return JSON.stringify(JSON.parse(t));
  } catch {
    return text;
  }
}

export type PinLimitBreach = "count" | "total" | null;

/** Client-side pre-check of the per-workflow caps; the server stays the authority (it answers 400 with a message). */
export function pinLimitBreach(pins: PinMap, nodeId: string, sizeChars: number): PinLimitBreach {
  let count = 0;
  let total = 0;
  for (const p of Object.values(pins)) {
    if (p.nodeId === nodeId) continue; // replacing this node's pin frees its share
    count++;
    total += p.sizeChars;
  }
  if (count + 1 > PIN_MAX_COUNT) return "count";
  if (total + sizeChars > PIN_MAX_TOTAL_CHARS) return "total";
  return null;
}

/**
 * Trace payloads are redacted (NodeTracePayloads writes "[redacted]" over
 * secret-looking values) and capped, so pinning one is not the same as pinning
 * the real output. Truncation is flagged on the trace entry; redaction is only
 * visible as the marker inside the text.
 */
export function payloadNeedsPinConfirm(text: string | null | undefined, truncated: boolean | null | undefined): boolean {
  if (truncated) return true;
  return !!text && /\[redacted\]/i.test(text);
}

export function payloadHasRedaction(text: string | null | undefined): boolean {
  return !!text && /\[redacted\]/i.test(text);
}

const NUM = new Intl.NumberFormat("en-US");

export function formatPinSize(chars: number): string {
  if (!Number.isFinite(chars) || chars < 0) return "";
  if (chars < 1000) return `${NUM.format(chars)} chars`;
  return `${(chars / 1000).toFixed(chars >= 100_000 ? 0 : 1)}k chars`;
}

export function formatPinTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return "";
  return new Date(ms).toLocaleString();
}

/** Any pin the caller could not read the data of means the caller has no edit-level access (contract: data is null then). */
export function pinsAreMetadataOnly(pins: PinMap): boolean {
  const list = Object.values(pins);
  return list.length > 0 && list.some((p) => p.data == null);
}

export type PinRequest = {
  nodeId: string;
  data: string;
  source: PinSource;
  sourceRunId?: string | null;
};

type Translator = (key: string, values?: Record<string, string | number>) => string;

/** i18n key (workflowCanvas namespace) naming a pin source in banners, tooltips and the editor. */
export function pinSourceKey(source: PinSource): string {
  switch (source) {
    case "test":
      return "pinSourceTest";
    case "run":
      return "pinSourceRun";
    case "trigger_sample":
      return "pinSourceTrigger";
    default:
      return "pinSourceManual";
  }
}

/** Localized text for a failed pin call (the contract's three 400s plus transport failures). */
export function pinFailureText(t: Translator, kind: PinFailureKind, serverMessage: string): string {
  switch (kind) {
    case "too-large":
      return t("pinErrTooLarge", { max: NUM.format(PIN_MAX_CHARS) });
    case "too-many":
      return t("pinErrTooMany", { max: PIN_MAX_COUNT });
    case "total-size":
      return t("pinErrTotalSize", { max: NUM.format(PIN_MAX_TOTAL_CHARS) });
    case "forbidden":
      return t("pinErrForbidden");
    case "not-found":
      return t("pinErrNotFound");
    case "network":
      return t("pinErrNetwork");
    case "invalid":
      return serverMessage ? t("pinErrInvalid", { message: serverMessage }) : t("pinErrGeneric");
    default:
      return t("pinErrGeneric");
  }
}

/** A pin without its payload: what node cards carry in react-flow data (never the pinned text itself). */
export type PinMeta = Omit<WorkflowPin, "data">;

const metaCache = new WeakMap<WorkflowPin, PinMeta>();

/** Same input object -> same PinMeta object, so a memo'd node card re-renders only when its own pin changed. */
export function toPinMeta(pin: WorkflowPin): PinMeta {
  const hit = metaCache.get(pin);
  if (hit) return hit;
  const { data: _data, ...meta } = pin;
  void _data;
  metaCache.set(pin, meta);
  return meta;
}
