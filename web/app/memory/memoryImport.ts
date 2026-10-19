// Pure helpers for "Import from another assistant" on the Memory page: the prompt
// the user copies into ChatGPT / Claude / Gemini, and the selection / request /
// result logic around core's /api/memory/import endpoints (MemoryImportController).
// No React, no i18n, no clock, so everything here is unit-testable on its own
// (memoryImport.test.ts).
import type { MemoryFact } from "./memoryTypes";

export type ImportSource = "chatgpt" | "claude" | "gemini";

// Brand names are not translated.
export const IMPORT_SOURCES: { id: ImportSource; label: string }[] = [
  { id: "chatgpt", label: "ChatGPT" },
  { id: "claude", label: "Claude" },
  { id: "gemini", label: "Gemini" },
];

export function isImportSource(value: string): value is ImportSource {
  return IMPORT_SOURCES.some((s) => s.id === value);
}

export function importSourceLabel(source: ImportSource): string {
  return IMPORT_SOURCES.find((s) => s.id === source)?.label ?? source;
}

const SOURCE_LINE: Record<ImportSource, string> = {
  chatgpt: "Use your saved memories and anything you know about me from our past chats.",
  claude: "Use your memory of me and anything you know from our past conversations.",
  gemini: "Use the information you have saved about me and anything you know from our past chats.",
};

/**
 * The exact prompt the user pastes into the other assistant. It asks for one short fact
 * per line and nothing else, and for nothing sensitive: the server also screens every
 * line, but the cheapest protection is not asking for secrets in the first place.
 */
export function buildImportPrompt(source: ImportSource): string {
  return [
    "List everything you remember about me, one fact per line.",
    SOURCE_LINE[source] ?? SOURCE_LINE.chatgpt,
    "Cover my name, where I live, my work and projects, the tools and languages I use, my interests, and how I like answers to be written.",
    "Rules:",
    '- Plain text only: one short fact per line, each starting with "- ".',
    "- No headings and no commentary before or after the list.",
    "- Do not include passwords, API keys, bank or card numbers, government ID numbers, health details or anything else sensitive.",
    "- If you remember nothing about me, reply with the single word: None",
  ].join("\n");
}

// ---- shapes of core's responses (MemoryImportDtos) ----

export type ImportPreviewFact = {
  /** Client-local index, stable only within one preview response. */
  id: number;
  text: string;
  /** Suggested scope; the user picks the real one. */
  scope?: string | null;
  /** Text of the memory the user already has that this line repeats. */
  duplicateOf?: string | null;
  /** Stable reason code when the line was refused; see IMPORT_REASON_CODES. */
  rejectedReason?: string | null;
};

export type ImportPreview = {
  facts: ImportPreviewFact[];
  stats: { lines: number; kept: number; duplicates: number; rejected: number };
  /** Room left: shared facts, and the per-assistant limit. Absent on an older core. */
  capacity?: { sharedRemaining: number; perAgentLimit: number } | null;
  source?: string;
};

export type ImportSkipped = { text: string; reason: string };
export type ImportResult = { saved: number; skipped: ImportSkipped[]; source?: string };

export type ImportRequestFact = { text: string; scope: "shared" | "agent"; agentId?: string };

// ---- selection ----

/** Only a new, accepted line can be imported; repeats and refused lines are shown but locked. */
export function isSelectable(fact: ImportPreviewFact): boolean {
  return !fact.rejectedReason && !fact.duplicateOf;
}

export function defaultSelection(facts: readonly ImportPreviewFact[] | null | undefined): Set<number> {
  return new Set((facts ?? []).filter(isSelectable).map((f) => f.id));
}

export function selectAll(facts: readonly ImportPreviewFact[] | null | undefined): Set<number> {
  return defaultSelection(facts);
}

export function selectNone(): Set<number> {
  return new Set<number>();
}

export function toggleSelection(selection: ReadonlySet<number>, id: number): Set<number> {
  const next = new Set<number>(selection);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

/** The facts that will actually be sent: selectable and ticked, in the order shown. */
export function selectedFacts(
  facts: readonly ImportPreviewFact[] | null | undefined,
  selection: ReadonlySet<number>
): ImportPreviewFact[] {
  return (facts ?? []).filter((f) => isSelectable(f) && selection.has(f.id));
}

// ---- request ----

/** Sentinel for "shared across assistants" in the who-remembers picker (same one AddFactForm uses). */
export const IMPORT_SHARED_TARGET = "__shared__";

export function buildImportRequest(
  facts: readonly ImportPreviewFact[] | null | undefined,
  selection: ReadonlySet<number>,
  target: string,
  source: ImportSource
): { facts: ImportRequestFact[]; source: ImportSource } {
  const chosen = selectedFacts(facts, selection);
  const shared = !target || target === IMPORT_SHARED_TARGET;
  return {
    source,
    facts: chosen.map(
      (f): ImportRequestFact => (shared ? { text: f.text, scope: "shared" } : { text: f.text, scope: "agent", agentId: target })
    ),
  };
}

/**
 * How many more memories fit in the chosen place, or null when the server did not say.
 * Shared: the server counts it. One assistant: its per-assistant limit minus what it already holds.
 */
export function roomFor(
  target: string,
  capacity: ImportPreview["capacity"],
  existing: readonly MemoryFact[] | null | undefined
): number | null {
  if (!capacity) return null;
  if (!target || target === IMPORT_SHARED_TARGET) return Math.max(0, capacity.sharedRemaining);
  const held = (existing ?? []).filter((f) => f.scope === "agent" && f.agentId === target).length;
  return Math.max(0, capacity.perAgentLimit - held);
}

/** How many of the selected facts will not fit (0 when there is room or the room is unknown). */
export function overflow(selectedCount: number, room: number | null): number {
  if (room === null) return 0;
  return Math.max(0, selectedCount - room);
}

// ---- result ----

/** Skipped facts grouped by reason, biggest group first (ties by reason code). */
export function groupSkipped(skipped: readonly ImportSkipped[] | null | undefined): { reason: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const s of skipped ?? []) counts.set(s.reason, (counts.get(s.reason) ?? 0) + 1);
  return [...counts.entries()]
    .map(([reason, count]) => ({ reason, count }))
    .sort((a, b) => b.count - a.count || a.reason.localeCompare(b.reason));
}

/**
 * Every reason code core can return, from the parser (MemoryImportParser.R_*) and the
 * import service (MemoryImportService.S_*). Each has an `importReason_<code>` string.
 */
export const IMPORT_REASON_CODES = [
  "too_short",
  "no_content",
  "too_long",
  "prompt_injection",
  "instruction",
  "unsafe_content",
  "credentials",
  "secret",
  "card_number",
  "id_number",
  "over_limit",
  "empty",
  "invalid_scope",
  "agent_required",
  "duplicate",
  "limit_reached",
  "agent_unavailable",
  "unavailable",
  "invalid",
  "error",
] as const;

/** i18n key (inside memoryPage) for a reason code; an unknown code reads as the generic one. */
export function importReasonKey(reason: string | null | undefined): string {
  return (IMPORT_REASON_CODES as readonly string[]).includes(reason ?? "") ? `importReason_${reason}` : "importReason_other";
}
