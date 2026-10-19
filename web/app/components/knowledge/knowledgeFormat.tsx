import { formatBytes as formatSharedBytes } from "../../lib/format";

// Synchronous formats (Stage 6 + F3a/F3b) - go through POST /upload and
// come back with a chunk count in one request. Mirrors
// KnowledgeController.SUPPORTED_EXTENSIONS.
export const SYNC_EXTENSIONS = [".md", ".pdf", ".docx", ".html", ".htm", ".png", ".jpg", ".jpeg", ".webp"];

// Async formats (Stage F3c/F3d) - go through POST /upload-audio, return a
// job id immediately, and need polling. Mirrors
// KnowledgeController.TRANSCRIBABLE_EXTENSIONS.
export const ASYNC_EXTENSIONS = [".mp3", ".wav", ".m4a", ".ogg", ".mp4", ".mov", ".webm"];

export const ALL_EXTENSIONS = [...SYNC_EXTENSIONS, ...ASYNC_EXTENSIONS];

// "Build the backend to make it real" - KnowledgeDocumentStatus's non-terminal
// values (see that enum's javadoc). While any document for the selected
// collection is in one of these, DOCUMENT_POLL_INTERVAL_MS keeps refetching
// the real per-file list so EXTRACTING/EMBEDDING transitions actually show up
// live instead of only on next manual refresh - most visible for async
// (audio/video) uploads, which genuinely sit QUEUED for a few seconds waiting
// on KnowledgeIngestionJobRunner's 15s poll.
export const NON_TERMINAL_STATUSES = ["UPLOADED", "QUEUED", "EXTRACTING", "EMBEDDING"];
export const DOCUMENT_POLL_INTERVAL_MS = 4000;

// Minimal shapes for this panel's loosely-typed (api.ts is `any`-typed
// throughout) collection/agent/document/search-result data - only the
// fields this file actually reads.
export interface KnowledgeCollectionSummary {
  id: string;
  name: string;
  description?: string | null;
  isDefault?: boolean;
  fileCount?: number;
  chunkCount?: number;
  embeddingModel?: string;
  updatedAt?: string;
}

export interface KnowledgeDocumentItem {
  id: string;
  filename: string;
  fileExtension?: string | null;
  fileSizeBytes?: number;
  status: string;
  chunkCount?: number | null;
  errorMessage?: string | null;
  hasOriginalFile?: boolean;
}

export interface FaceEnrollmentItem {
  id: string;
  personName: string;
  createdAt: string;
}

export interface SearchResultItem {
  id: string;
  collectionId: string;
  chunkIndex: number;
  content: string;
  distance: number;
}

export function extensionOf(filename: string) {
  const lower = filename.toLowerCase();
  return ALL_EXTENSIONS.find((ext) => lower.endsWith(ext)) || null;
}

// File-type badge styling - purely presentational (icon color/label by
// extension), same visual idea as the mockup's PDF/DOC/MD/IMG/VID/AUD chips.
// Theme-aware token pairs (audit: the previous fixed dark-theme hex pairs
// composited to ~1.4-2:1 on a light card, well under AA) - each badge now
// reads var(--ax-*) directly instead of a literal hex, so it re-tints with
// the active theme the same way the MD/FILE badges already did.
export function fileBadge(ext: string) {
  if (ext === ".pdf")
    return { label: "PDF", color: "var(--ax-red)", bg: "color-mix(in srgb, var(--ax-red) 15%, transparent)" };
  if (ext === ".docx")
    return { label: "DOC", color: "var(--ax-cyan)", bg: "color-mix(in srgb, var(--ax-cyan) 15%, transparent)" };
  if (ext === ".md" || ext === ".html" || ext === ".htm")
    return { label: "MD", color: "var(--ax-mid)", bg: "var(--ax-panel)" };
  if ([".png", ".jpg", ".jpeg", ".webp"].includes(ext))
    return { label: "IMG", color: "var(--ax-amber)", bg: "color-mix(in srgb, var(--ax-amber) 15%, transparent)" };
  if ([".mp4", ".mov", ".webm"].includes(ext))
    return { label: "VID", color: "var(--ax-violet)", bg: "color-mix(in srgb, var(--ax-violet) 15%, transparent)" };
  if ([".mp3", ".wav", ".m4a", ".ogg"].includes(ext))
    return { label: "AUD", color: "var(--ax-green)", bg: "color-mix(in srgb, var(--ax-green) 15%, transparent)" };
  return { label: "FILE", color: "var(--ax-mid)", bg: "var(--ax-panel)" };
}

// Real, not fabricated - just KB/MB formatting of the real fileSizeBytes
// sum below. No percentage anywhere in this file: KnowledgeDocumentStatus
// has no progress-fraction field (see KnowledgeIngestionService - the
// sidecar's /embed call chunks+embeds in one request, so there's no
// intermediate "68% embedded" moment to report even if we wanted one).
// Upload totals: binary KB/MB, never below "1 KB", "0 KB" when empty (FE-016 preset).
export const formatBytes = (bytes: number): string =>
  formatSharedBytes(bytes, { minUnit: "KB", maxUnit: "MB", decimals: { MB: 1 }, zero: "0 KB", invalid: "0 KB" });

// Face enrollment (task #15) - reads a File back as a data URL for the
// on-device face pipeline (web/app/lib/edge/face.ts), rather than
// re-fetching the just-uploaded file from the server.
export function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    // readAsDataURL always yields a string result (never ArrayBuffer) - the
    // wider FileReader#result type is just the DOM lib's shared shape with
    // readAsArrayBuffer/readAsText.
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error || new Error("Could not read file"));
    reader.readAsDataURL(file);
  });
}

export function initialsOf(name: string | null | undefined) {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] || "") + (parts[1]?.[0] || "")).toUpperCase() || name.slice(0, 2).toUpperCase();
}

// Wraps every case-insensitive occurrence of `term` in `text` with <mark> -
// real substring highlighting against the real chunk text, not a fabricated
// annotation. Used only for the semantic-search result preview.
export function highlight(text: string, term: string) {
  if (!term.trim()) return text;
  const escaped = term.trim().replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp(`(${escaped})`, "ig");
  const parts = text.split(re);
  return parts.map((part, i) =>
    re.test(part) && part.toLowerCase() === term.trim().toLowerCase() ? <mark key={i}>{part}</mark> : part
  );
}
