import type { DetectedImportSource, ImportEnvelope, ImportSource } from "../../lib/api/agentImport";

// Pure helpers for the "Import agent" page: parse the pasted/uploaded export,
// guess which platform it came from (so the source selector can pre-select),
// size-check it, and build the request envelope. No React, no network, and the
// payload is never logged or stored here.
//
// Detection is a CLIENT-SIDE HINT only. The server re-detects (and refuses a
// source/shape mismatch), so a wrong or missing guess here costs a 400 at worst
// and "auto" is always safe. When the shape is ambiguous this returns null
// rather than guessing, which leaves the selector on Auto-detect.

/** Client-side ceiling for the pasted/uploaded export (matches the server default, 1 MiB). */
export const MAX_IMPORT_BYTES = 1024 * 1024;

/** Agent names are sanitised to 100 chars server-side; stop the input there too. */
export const MAX_NAME_LENGTH = 100;

export const IMPORT_SOURCES: readonly ImportSource[] = ["auto", "openai-assistant", "openai-agents", "flowise"];

export type ParseFailure = "empty" | "invalid-json" | "not-object";

// One flat object (not a boolean-discriminated union): web/tsconfig has
// strict:false, where `if (!r.ok)` does not narrow a union. Callers check
// `payload` (non-null on success) and read `reason` (non-null on failure).
export type ParseOutcome = { payload: Record<string, unknown> | null; reason: ParseFailure | null };

/** UTF-8 byte length without relying on TextEncoder being present in every runtime. */
export function utf8ByteLength(text: string): number {
  let bytes = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.length) {
      const next = text.charCodeAt(i + 1);
      if (next >= 0xdc00 && next <= 0xdfff) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    } else bytes += 3;
  }
  return bytes;
}

export function exceedsImportLimit(text: string, maxBytes: number = MAX_IMPORT_BYTES): boolean {
  // Cheap pre-checks: a UTF-8 string is at least text.length bytes and at most 3x.
  if (text.length > maxBytes) return true;
  if (text.length * 3 <= maxBytes) return false;
  return utf8ByteLength(text) > maxBytes;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Parses the pasted text as a JSON object. Never throws. */
export function parseExport(text: string): ParseOutcome {
  const trimmed = (text || "").replace(/^﻿/, "").trim();
  if (!trimmed) return { payload: null, reason: "empty" };
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { payload: null, reason: "invalid-json" };
  }
  if (!isPlainObject(parsed)) return { payload: null, reason: "not-object" };
  return { payload: parsed, reason: null };
}

const AGENTS_SDK_KEYS = [
  "handoffs",
  "model_settings",
  "modelSettings",
  "mcp_servers",
  "mcpServers",
  "input_guardrails",
  "output_guardrails",
  "handoff_description",
  "output_type",
];

function flowiseNodes(payload: Record<string, unknown>): unknown[] | null {
  if (Array.isArray(payload.nodes)) return payload.nodes;
  // A stored chatflow record: { id?, name, description?, flowData: "<json string>" }.
  if (typeof payload.flowData === "string") {
    try {
      const inner = JSON.parse(payload.flowData);
      if (isPlainObject(inner) && Array.isArray(inner.nodes)) return inner.nodes;
    } catch {
      return null;
    }
  }
  return null;
}

function looksLikeFlowise(payload: Record<string, unknown>): boolean {
  const nodes = flowiseNodes(payload);
  if (!nodes || nodes.length === 0) return false;
  // Flowise nodes carry a `data` object (and usually a React Flow `type`/`position`).
  return nodes.some((n) => isPlainObject(n) && (isPlainObject(n.data) || typeof n.type === "string"));
}

function looksLikeAssistant(payload: Record<string, unknown>): boolean {
  if (payload.object === "assistant") return true;
  if (typeof payload.id === "string" && payload.id.startsWith("asst_")) return true;
  if ("tool_resources" in payload) return true;
  // Assistants API function tools nest the definition under `function`; the Agents SDK does not.
  if (Array.isArray(payload.tools)) {
    return payload.tools.some((t) => isPlainObject(t) && t.type === "function" && isPlainObject(t.function));
  }
  return false;
}

function looksLikeAgentsSdk(payload: Record<string, unknown>): boolean {
  return AGENTS_SDK_KEYS.some((k) => k in payload);
}

/** Best-guess source for an already-parsed export; null when it is not clearly one of the three. */
export function detectSource(payload: Record<string, unknown>): DetectedImportSource | null {
  if (!isPlainObject(payload)) return null;
  if (looksLikeFlowise(payload)) return "flowise";
  if (looksLikeAssistant(payload)) return "openai-assistant";
  if (looksLikeAgentsSdk(payload)) return "openai-agents";
  return null;
}

/** Convenience: detect straight from the textarea contents; null on anything unparsable or unclear. */
export function detectSourceFromText(text: string): DetectedImportSource | null {
  const parsed = parseExport(text);
  return parsed.payload ? detectSource(parsed.payload) : null;
}

/**
 * Builds the request envelope both routes accept. `name` is the edited agent
 * name; it is sent as overrides.name only when non-blank (the server only reads
 * that one override). The payload goes as an object, which the server prefers.
 */
export function buildEnvelope(source: ImportSource, payload: Record<string, unknown>, name?: string): ImportEnvelope {
  const envelope: ImportEnvelope = { source: IMPORT_SOURCES.includes(source) ? source : "auto", payload };
  const trimmed = (name || "").trim().slice(0, MAX_NAME_LENGTH);
  if (trimmed) envelope.overrides = { name: trimmed };
  return envelope;
}
