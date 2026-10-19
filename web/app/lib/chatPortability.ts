// Pure helpers for chat portability: importing conversations from a ChatGPT / Claude
// data export and exporting a chat (core ChatImportController / ChatExportController,
// /api/chat-import and /api/agents/{id}/sessions/{id}/export). No React, no i18n, no
// clock, so everything here is unit-testable on its own (chatPortability.test.ts).

import { filenameFromContentDisposition as parseContentDispositionFilename } from "./contentDisposition";

// ---- shapes of core's responses (ChatPortDtos) ----

/** How many imported chats the list endpoint returns (newest first); mirrors core ChatImportService.LIST_LIMIT. */
export const IMPORTED_CHAT_LIST_LIMIT = 500;

export type ChatImportFeatures = {
  importEnabled: boolean;
  exportEnabled: boolean;
  maxBytes: number;
  maxImportConversations: number;
};

export type ChatPreviewConversation = {
  /** Stable key to send back on import. */
  id: string;
  source: string;
  title: string;
  createdAt?: string | null;
  messageCount: number;
  /** PII-screened start of the first user message; "[hidden]" when it looked sensitive. */
  snippet: string;
  alreadyImported: boolean;
  truncated: boolean;
};

export type ChatImportCapacity = { storedChats: number; maxStoredChats: number; maxPerImport: number };

export type ChatImportPreview = {
  source: string;
  conversations: ChatPreviewConversation[];
  stats: { found: number; listed: number; skipped: number; scanCapReached: boolean };
  capacity: ChatImportCapacity;
  warnings?: string[];
};

export type ChatImportSkipped = { id: string; title: string; reason: string };

export type ChatImportResult = {
  imported: number;
  chats: { id: string; sourceKey: string; title: string; messageCount: number }[];
  skipped: ChatImportSkipped[];
  warnings?: string[];
};

export type ImportedChatSummary = {
  id: string;
  source: string;
  title: string;
  createdAt?: string | null;
  importedAt?: string | null;
  messageCount: number;
  truncated: boolean;
};

export type ImportedChatMessage = {
  role: string;
  content: string;
  timestamp?: string | null;
  attachments?: string[];
};

export type ImportedChatView = ImportedChatSummary & { messages: ImportedChatMessage[] };

export type ExportFormat = "md" | "json" | "txt";

/** Label keys live in the chatPortability i18n namespace. */
export const EXPORT_FORMATS: { id: ExportFormat; labelKey: string }[] = [
  { id: "md", labelKey: "exportMd" },
  { id: "json", labelKey: "exportJson" },
  { id: "txt", labelKey: "exportTxt" },
];

// ---- upload validation ----

export type UploadProblem = "empty" | "tooLarge" | "wrongType";

/** The export is a .zip (ChatGPT / Claude) or the conversations.json inside it. */
export function validateUpload(file: { name: string; size: number } | null | undefined, maxBytes: number): UploadProblem | null {
  if (!file || file.size <= 0) return "empty";
  if (!/\.(zip|json)$/i.test(file.name || "")) return "wrongType";
  if (maxBytes > 0 && file.size > maxBytes) return "tooLarge";
  return null;
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "0 B";
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0)} MB`;
}

// ---- selection ----

export function isSelectable(c: ChatPreviewConversation): boolean {
  return !c.alreadyImported;
}

/** How many more imported chats this user may keep. */
export function roomLeft(capacity: ChatImportCapacity | null | undefined): number {
  if (!capacity) return 0;
  return Math.max(0, capacity.maxStoredChats - capacity.storedChats);
}

/** Most chats one import may take: the per-request cap, and what room is left. */
export function selectionCap(capacity: ChatImportCapacity | null | undefined): number {
  if (!capacity) return 0;
  return Math.max(0, Math.min(capacity.maxPerImport, roomLeft(capacity)));
}

/** The first `cap` conversations that are new, in the order the file lists them. */
export function defaultSelection(conversations: readonly ChatPreviewConversation[] | null | undefined, cap: number): Set<string> {
  const out = new Set<string>();
  for (const c of conversations ?? []) {
    if (out.size >= cap) break;
    if (isSelectable(c)) out.add(c.id);
  }
  return out;
}

export function selectNone(): Set<string> {
  return new Set<string>();
}

/** Ticking beyond the cap is ignored; unticking always works. */
export function toggleSelection(selection: ReadonlySet<string>, id: string, cap: number): Set<string> {
  const next = new Set<string>(selection);
  if (next.has(id)) next.delete(id);
  else if (next.size < cap) next.add(id);
  return next;
}

/** The ids that will be sent: ticked and still importable, in the order shown. */
export function selectedIds(conversations: readonly ChatPreviewConversation[] | null | undefined, selection: ReadonlySet<string>): string[] {
  return (conversations ?? []).filter((c) => isSelectable(c) && selection.has(c.id)).map((c) => c.id);
}

// ---- result ----

const SKIP_REASONS = ["duplicate", "limit_reached", "not_found"] as const;

/** i18n key (inside chatPortability) for a skip reason code; an unknown code reads as the generic one. */
export function skipReasonKey(reason: string | null | undefined): string {
  return (SKIP_REASONS as readonly string[]).includes(reason ?? "") ? `skipReason_${reason}` : "skipReason_other";
}

/** Skipped chats grouped by reason, biggest group first (ties by reason code). */
export function groupSkipped(skipped: readonly ChatImportSkipped[] | null | undefined): { reason: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const s of skipped ?? []) counts.set(s.reason, (counts.get(s.reason) ?? 0) + 1);
  return [...counts.entries()]
    .map(([reason, count]) => ({ reason, count }))
    .sort((a, b) => b.count - a.count || a.reason.localeCompare(b.reason));
}

/** i18n key for a source label; brand names are the values, not translated per locale. */
export function sourceLabelKey(source: string | null | undefined): string {
  if (source === "chatgpt") return "sourceChatgpt";
  if (source === "claude") return "sourceClaude";
  return "sourceOther";
}

// ---- download ----

/**
 * The filename a download should be saved under: from a Content-Disposition header when it has one,
 * with any path part removed (a header is untrusted), else `fallback`.
 */
export function filenameFromContentDisposition(header: string | null | undefined, fallback: string): string {
  // One shared parser for every download (lib/contentDisposition.ts).
  return parseContentDispositionFilename(header, fallback);
}

export function exportFallbackName(format: ExportFormat, base = "chat"): string {
  return `${base}.${format}`;
}

/** A short local date for a list row, or "" when the value is missing or not a date. */
export function shortDate(iso: string | null | undefined, locale?: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleDateString(locale);
}
