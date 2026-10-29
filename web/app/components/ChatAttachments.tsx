"use client";

/**
 * Multimodal chat attachments for the Playground composer (ChatPanel.tsx).
 *
 * Everything here is gated on the agent-detail `modalities` block:
 *   { image, audio, video, document, maxAttachmentBytes, maxAttachments }
 * An agent payload WITHOUT a modalities field (older core builds) - or with
 * every flag false - yields null from readModalities(), and the caller hides
 * the entire attach surface: no button, no dead space, byte-identical send
 * payloads to the pre-attachment world.
 *
 * Files are base64-encoded client-side (FileReader.readAsDataURL) and ride
 * inline on the chat POST as
 *   attachments: [{ fileName, mimeType, dataBase64 }]
 * - see api.streamChat's `attachments` param. Validation (per-file size,
 * total size, count, mime vs enabled modalities) happens here BEFORE send so
 * the user gets a plain sentence instead of a dead send button or a server
 * 413/415.
 *
 * Copy is deliberately inline English (no new i18n keys) - the playground
 * page already carries inline copy (its PageHeader subtitle), and en.json is
 * mid-migration and fragile; see MIGRATION-NOTES.md.
 */

import { formatBytes } from "../lib/format";
import { useRef } from "react";
import type { ChangeEvent } from "react";
import { FileAudio, FileSpreadsheet, FileText, FileVideo, File as FileIcon, Paperclip, X } from "lucide-react";
import { Button } from "./ui/button";
import { cn } from "../lib/utils";
import { readSandboxDataUnavailableReason } from "../lib/sandboxDataHint";
import { useTranslations } from "../lib/i18n";

export type AgentModalities = {
  image: boolean;
  audio: boolean;
  video: boolean;
  document: boolean;
  /**
   * Data-analysis sandbox (server: AgentModalitiesView.sandboxData): CSV / XLSX /
   * JSON / PDF / TXT are accepted because they are mounted in the agent's
   * run_code working directory, whatever the model itself can read. Optional
   * so an older core's payload (no such field) still type-checks as "off".
   */
  sandboxData?: boolean;
  /**
   * Server sentence saying why sandboxData is false (Code execution off for
   * the agent / E2B not connected). Only ever present when core's
   * data-analysis flag is on and the sandbox is unavailable; shown in the
   * paperclip tooltip and by SandboxDataHint under the composer.
   */
  sandboxDataUnavailableReason?: string | null;
  maxAttachmentBytes: number;
  maxAttachments: number;
};

/** One picked-but-not-yet-sent file, held in the composer's chip row. */
export type PendingAttachment = {
  /** Client-side key for React lists + removal - never sent to the server. */
  id: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  /** Raw base64 payload (no data: prefix) - what the chat POST carries. */
  dataBase64: string;
  /** Full data: URL, images only - used for thumbnail previews. */
  previewUrl: string | null;
  /**
   * Set for a sandbox data file that was stored through
   * POST /api/agents/{id}/chat/attachments instead of being base64-encoded:
   * the turn then carries this key (ChatRequest.attachmentKey) and
   * dataBase64 stays empty. At most one per message - the chat request has a
   * single attachmentKey slot.
   */
  uploaded?: { attachmentKey: string; mimeType: string };
  /**
   * Set for a file the /assistant surface saved in the projects folder (POST /api/assistant/workspace/files)
   * instead of sending its bytes with the message: the Assistant is told the path and reads or searches it
   * with its file tools. dataBase64 stays empty. `workspaceTextPath` is a PDF's extracted text.
   */
  workspacePath?: string;
  workspaceTextPath?: string | null;
};

/** What a sent user turn keeps for history rendering - no base64 kept around. */
export type SentAttachment = {
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  previewUrl: string | null;
};

// Backend defaults from the contract - used only if a modalities block is
// present but omits the numeric caps.
const DEFAULT_MAX_ATTACHMENT_BYTES = 10485760; // 10 MB
const DEFAULT_MAX_ATTACHMENTS = 3;

// Document types offered/accepted when the `document` modality is on.
const DOCUMENT_MIME_TYPES = ["application/pdf", "text/plain", "text/csv", "application/json"];

// Sandbox-only input (ChatAttachmentCategory.SPREADSHEET on the backend): no
// chat model takes a workbook natively, so it is never sent inline.
export const XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

// What Windows with Excel installed reports for a .csv (and sometimes for an
// .xlsx): the legacy .xls type. Never accepted as itself - only as a cue to
// look at the extension.
const LEGACY_EXCEL_MIME_TYPE = "application/vnd.ms-excel";

/**
 * The type a picked file should be treated (and sent) as. The browser's own
 * answer wins whenever it is specific; the extension is consulted only when
 * the OS said nothing useful - empty, application/octet-stream, or the legacy
 * Excel type - and then only for .xlsx and .csv.
 */
export function effectiveMimeType(file: { name?: string; type?: string }): string {
  const type = (file.type || "").toLowerCase();
  if (type && type !== "application/octet-stream" && type !== LEGACY_EXCEL_MIME_TYPE) return type;
  const name = (file.name || "").toLowerCase();
  if (name.endsWith(".xlsx")) return XLSX_MIME_TYPE;
  if (name.endsWith(".csv") && type === LEGACY_EXCEL_MIME_TYPE) return "text/csv";
  return type;
}

function isXlsx(file: { name?: string; type?: string }): boolean {
  return effectiveMimeType(file) === XLSX_MIME_TYPE;
}

/** The same file under its effective type, so the server (and the inline payload) see what it really is. */
function withEffectiveType(file: File): File {
  const type = effectiveMimeType(file);
  return type && type !== (file.type || "").toLowerCase() ? new File([file], file.name, { type }) : file;
}

/**
 * True when a picked file must travel through the upload endpoint (stored,
 * then referenced by attachmentKey) rather than inline base64: a workbook
 * always, and a PDF/TXT/CSV/JSON when the model has no document input of its
 * own (the inline path would reject it with a 415). Always false unless the
 * agent advertises the data-analysis sandbox.
 */
export function isSandboxUploadFile(file: { name?: string; type?: string }, modalities: AgentModalities): boolean {
  if (!modalities.sandboxData) return false;
  if (isXlsx(file)) return true;
  return !modalities.document && DOCUMENT_MIME_TYPES.includes(effectiveMimeType(file));
}

/**
 * Normalize an agent's modalities block. Returns null when the agent can't
 * take any attachment at all - either an older core with no `modalities`
 * field, or one with every flag false - which is the caller's signal to
 * render no attach UI whatsoever.
 */
export function readModalities(agent: any): AgentModalities | null {
  const m = agent?.modalities;
  if (!m) return null;
  const normalized: AgentModalities = {
    image: !!m.image,
    audio: !!m.audio,
    video: !!m.video,
    document: !!m.document,
    sandboxData: !!m.sandboxData,
    maxAttachmentBytes:
      typeof m.maxAttachmentBytes === "number" && m.maxAttachmentBytes > 0
        ? m.maxAttachmentBytes
        : DEFAULT_MAX_ATTACHMENT_BYTES,
    maxAttachments:
      typeof m.maxAttachments === "number" && m.maxAttachments > 0 ? m.maxAttachments : DEFAULT_MAX_ATTACHMENTS,
  };
  if (!normalized.image && !normalized.audio && !normalized.video && !normalized.document && !normalized.sandboxData) {
    return null;
  }
  // Key added only when there is a reason, so a default payload normalizes to
  // exactly the object it did before this field existed.
  const unavailableReason = readSandboxDataUnavailableReason(agent);
  if (unavailableReason) normalized.sandboxDataUnavailableReason = unavailableReason;
  return normalized;
}

/** The file picker's `accept` attribute, built from the enabled flags only. */
export function buildAccept(modalities: AgentModalities): string {
  const parts: string[] = [];
  if (modalities.image) parts.push("image/*");
  if (modalities.audio) parts.push("audio/*");
  if (modalities.video) parts.push("video/*");
  if (modalities.document || modalities.sandboxData) parts.push(...DOCUMENT_MIME_TYPES);
  if (modalities.sandboxData) parts.push(".xlsx", XLSX_MIME_TYPE);
  return parts.join(",");
}

/** "images, audio, video and documents (PDF, TXT, CSV, JSON)" - for the paperclip tooltip. */
export function describeModalities(modalities: AgentModalities): string {
  const kinds: string[] = [];
  if (modalities.image) kinds.push("images");
  if (modalities.audio) kinds.push("audio");
  if (modalities.video) kinds.push("video");
  if (modalities.document) kinds.push("documents (PDF, TXT, CSV, JSON)");
  if (modalities.sandboxData) {
    kinds.push(
      modalities.document
        ? "spreadsheets (XLSX) for code analysis"
        : "data files for code analysis (XLSX, CSV, JSON, PDF, TXT)"
    );
  }
  const list = kinds.length > 1 ? `${kinds.slice(0, -1).join(", ")} and ${kinds[kinds.length - 1]}` : kinds[0] || "";
  return `This model accepts ${list}. Up to ${modalities.maxAttachments} file${
    modalities.maxAttachments === 1 ? "" : "s"
  }, ${formatFileSize(modalities.maxAttachmentBytes)} total.`;
}

/**
 * The paperclip's tooltip / aria-label: what can be attached, plus - when the
 * server sent one - why data files for code analysis are not among them.
 */
export function attachHint(modalities: AgentModalities): string {
  const base = describeModalities(modalities);
  const reason = modalities.sandboxData ? "" : (modalities.sandboxDataUnavailableReason || "").trim();
  return reason ? `${base} ${reason}` : base;
}

/** Attachment / file sizes: binary units up to MB, one decimal under 10 KB and in MB (FE-016 preset). */
export function formatFileSize(bytes: number): string {
  return formatBytes(bytes, { maxUnit: "MB", decimals: { KB: (kb) => (kb < 10 ? 1 : 0), MB: 1 } });
}

function mimeAllowed(mimeType: string, modalities: AgentModalities): boolean {
  const type = (mimeType || "").toLowerCase();
  if (modalities.image && type.startsWith("image/")) return true;
  if (modalities.audio && type.startsWith("audio/")) return true;
  if (modalities.video && type.startsWith("video/")) return true;
  if (modalities.document && DOCUMENT_MIME_TYPES.includes(type)) return true;
  if (modalities.sandboxData && (type === XLSX_MIME_TYPE || DOCUMENT_MIME_TYPES.includes(type))) return true;
  return false;
}

function fileToBase64(file: File): Promise<{ dataUrl: string; base64: string }> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || "");
      const comma = dataUrl.indexOf(",");
      resolve({ dataUrl, base64: comma >= 0 ? dataUrl.slice(comma + 1) : "" });
    };
    reader.onerror = () => reject(reader.error || new Error(`Could not read "${file.name}".`));
    reader.readAsDataURL(file);
  });
}

let attachmentSeq = 0;

/**
 * Validate newly picked files against the modality flags + caps (counting
 * what's already attached), then base64-encode the ones that pass. Returns
 * either the full new attachment list or a single friendly error sentence -
 * never both, so a bad pick changes nothing.
 */
export async function addFiles(
  existing: PendingAttachment[],
  picked: File[],
  modalities: AgentModalities
): Promise<{ attachments: PendingAttachment[] } | { error: string }> {
  if (picked.length === 0) return { attachments: existing };

  if (existing.length + picked.length > modalities.maxAttachments) {
    return {
      error: `You can attach up to ${modalities.maxAttachments} file${
        modalities.maxAttachments === 1 ? "" : "s"
      } per message.`,
    };
  }

  for (const file of picked) {
    if (!mimeAllowed(effectiveMimeType(file), modalities)) {
      return {
        error: `"${file.name}" (${file.type || "unknown type"}) isn't a kind of file this model accepts. ${describeModalities(
          modalities
        )}`,
      };
    }
    if (file.size > modalities.maxAttachmentBytes) {
      return {
        error: `"${file.name}" is ${formatFileSize(file.size)} - the limit is ${formatFileSize(
          modalities.maxAttachmentBytes
        )} per file.`,
      };
    }
  }

  // The total cap is on the inline (base64) payload. A stored data file
  // travels as a key, not in that payload, so it does not count here.
  const existingTotal = existing.reduce((sum, a) => (a.uploaded ? sum : sum + a.sizeBytes), 0);
  const pickedTotal = picked.reduce((sum, f) => sum + f.size, 0);
  if (existingTotal + pickedTotal > modalities.maxAttachmentBytes) {
    return {
      error: `Attachments add up to ${formatFileSize(existingTotal + pickedTotal)} - the limit is ${formatFileSize(
        modalities.maxAttachmentBytes
      )} total per message.`,
    };
  }

  const encoded: PendingAttachment[] = [];
  for (const file of picked) {
    const { dataUrl, base64 } = await fileToBase64(file);
    // Effective type: a .csv the OS called application/vnd.ms-excel is sent
    // as text/csv. dataBase64 is the raw bytes, so it is unaffected.
    const mimeType = effectiveMimeType(file) || "application/octet-stream";
    encoded.push({
      id: `att-${Date.now()}-${attachmentSeq++}`,
      fileName: file.name,
      mimeType,
      sizeBytes: file.size,
      dataBase64: base64,
      previewUrl: mimeType.startsWith("image/") ? dataUrl : null,
    });
  }
  return { attachments: [...existing, ...encoded] };
}

/**
 * The composer's pick handler: sandbox data files (see isSandboxUploadFile)
 * are stored through `upload` (api.uploadChatAttachment bound to the agent)
 * and kept as a key; everything else goes through addFiles unchanged. Same
 * all-or-nothing contract as addFiles - a rejected pick changes nothing, and
 * the server's own rejection sentence (Code execution off, E2B not connected,
 * too large) is returned verbatim as the error.
 */
export async function addPickedFiles(
  existing: PendingAttachment[],
  picked: File[],
  modalities: AgentModalities,
  upload: (file: File) => Promise<{ attachmentKey?: string; mimeType?: string } | null | undefined>
): Promise<{ attachments: PendingAttachment[] } | { error: string }> {
  const sandboxFiles = picked.filter((f) => isSandboxUploadFile(f, modalities));
  if (sandboxFiles.length === 0) return addFiles(existing, picked, modalities);

  if (existing.length + picked.length > modalities.maxAttachments) {
    return {
      error: `You can attach up to ${modalities.maxAttachments} file${
        modalities.maxAttachments === 1 ? "" : "s"
      } per message.`,
    };
  }
  // ChatRequest carries one attachmentKey, so one stored file per message.
  if (sandboxFiles.length + existing.filter((a) => a.uploaded).length > 1) {
    return {
      error: "You can attach one data file for analysis per message. Send this one first, then attach the next.",
    };
  }
  const file = sandboxFiles[0];
  if (file.size > modalities.maxAttachmentBytes) {
    return {
      error: `"${file.name}" is ${formatBytes(file.size)} - the limit is ${formatBytes(
        modalities.maxAttachmentBytes
      )} per file.`,
    };
  }

  // Inline files first: they are validated locally, so a bad one is refused
  // before anything is stored on the server.
  const inlinePicked = picked.filter((f) => !isSandboxUploadFile(f, modalities));
  const inlineResult = await addFiles(existing, inlinePicked, modalities);
  if ("error" in inlineResult) return inlineResult;

  // A workbook the OS reported without a type (or a .csv/.xlsx it called
  // application/vnd.ms-excel) would be refused as "unsupported type" by the
  // server - name the type it actually is.
  const toSend = withEffectiveType(file);
  let stored: { attachmentKey?: string; mimeType?: string } | null | undefined;
  try {
    stored = await upload(toSend);
  } catch (err: any) {
    return { error: err?.message || `Could not upload "${file.name}". Please try again.` };
  }
  if (!stored?.attachmentKey) {
    return { error: `Could not upload "${file.name}". Please try again.` };
  }
  const mimeType = stored.mimeType || toSend.type || "application/octet-stream";
  return {
    attachments: [
      ...inlineResult.attachments,
      {
        id: `att-${Date.now()}-${attachmentSeq++}`,
        fileName: file.name,
        mimeType,
        sizeBytes: file.size,
        dataBase64: "",
        previewUrl: null,
        uploaded: { attachmentKey: stored.attachmentKey, mimeType },
      },
    ],
  };
}

/**
 * Splits a message's attachments into streamChat's two channels: the single
 * stored file (5th argument, `{ attachmentKey, mimeType }`, or null) and the
 * inline base64 list (7th argument).
 */
export function splitForSend(attachments: PendingAttachment[]): {
  stored: { attachmentKey: string; mimeType: string } | null;
  inline: { fileName: string; mimeType: string; dataBase64: string }[];
} {
  const stored = attachments.find((a) => a.uploaded)?.uploaded ?? null;
  const inline = attachments
    .filter((a) => !a.uploaded && (!a.workspacePath || a.dataBase64))
    .map(({ fileName, mimeType, dataBase64 }) => ({ fileName, mimeType, dataBase64 }));
  return { stored, inline };
}

function iconForMime(mimeType: string) {
  const type = (mimeType || "").toLowerCase();
  if (type === XLSX_MIME_TYPE || type === "text/csv") return FileSpreadsheet;
  if (type.startsWith("audio/")) return FileAudio;
  if (type.startsWith("video/")) return FileVideo;
  if (DOCUMENT_MIME_TYPES.includes(type)) return FileText;
  return FileIcon;
}

/**
 * Paperclip button + hidden file input. Render ONLY when readModalities()
 * returned non-null - the caller owns that gate.
 */
export function AttachButton({
  modalities,
  disabled,
  onFiles,
}: {
  modalities: AgentModalities;
  disabled?: boolean;
  onFiles: (files: File[]) => void;
}) {
    const tHC = useTranslations("hcComponentsChatAttachments");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const hint = attachHint(modalities);

  function handleChange(e: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files || []);
    e.target.value = ""; // allow re-picking the same file later
    if (files.length > 0) onFiles(files);
  }

  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        multiple={modalities.maxAttachments > 1}
        accept={buildAccept(modalities)}
        onChange={handleChange}
        className="sr-only"
        aria-label={tHC("hcComponentsChatAttachments.s2")}
        tabIndex={-1}
      />
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="shrink-0"
        onClick={() => fileInputRef.current?.click()}
        disabled={disabled}
        aria-label={`Attach a file. ${hint}`}
        title={hint}
      >
        <Paperclip aria-hidden="true" size={14} />
      </Button>
    </>
  );
}

/**
 * The picked-files chip row shown above the composer input. Image files get
 * a small thumbnail, everything else an icon + name + human size. Each chip's
 * remove control is a real button (focusable, aria-labelled), so chips are
 * keyboard-removable.
 */
export function AttachmentChips({
  attachments,
  onRemove,
  disabled,
}: {
  attachments: PendingAttachment[];
  onRemove: (id: string) => void;
  disabled?: boolean;
}) {
    const tHC = useTranslations("hcComponentsChatAttachments");
  if (attachments.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-2" aria-label={tHC("hcComponentsChatAttachments.s1")}>
      {attachments.map((att) => {
        const Icon = iconForMime(att.mimeType);
        return (
          <li
            key={att.id}
            className="flex items-center gap-2 rounded-lg border border-border bg-secondary/50 py-1 ps-1.5 pe-2 text-xs text-muted-foreground"
          >
            {att.previewUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={att.previewUrl}
                alt=""
                className="size-8 shrink-0 rounded-md border border-border object-cover"
              />
            ) : (
              <span className="flex size-8 shrink-0 items-center justify-center rounded-md border border-border bg-card text-muted-foreground">
                <Icon size={14} aria-hidden="true" />
              </span>
            )}
            <span className="max-w-[10rem] truncate font-medium text-foreground" title={att.fileName}>
              {att.fileName}
            </span>
            <span className="tabular-nums">{formatFileSize(att.sizeBytes)}</span>
            <button
              type="button"
              onClick={() => onRemove(att.id)}
              disabled={disabled}
              aria-label={`Remove attachment ${att.fileName}`}
              // p-0/border-0/bg-transparent: globals.css's bare `button` rule
              // forces padding that clips icon-only buttons otherwise - see
              // the "global button rule clips icon buttons" note. -m-1.5/p-1.5
              // enlarge the tap target (was a bare 12px icon) without shifting
              // the chip's layout or the icon's visual position.
              className="-m-1.5 flex shrink-0 items-center justify-center rounded-full border-0 bg-transparent p-1.5 text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring disabled:opacity-50"
            >
              <X size={12} aria-hidden="true" />
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/**
 * Attachments on an already-sent user turn in the message history - image
 * thumbnails inline, everything else a small file chip.
 */
export function TurnAttachments({ attachments, align }: { attachments?: SentAttachment[]; align?: "end" | "start" }) {
  if (!attachments || attachments.length === 0) return null;
  return (
    <div className={cn("flex max-w-[85%] flex-wrap gap-2", align === "end" ? "justify-end" : "justify-start")}>
      {attachments.map((att, i) =>
        att.previewUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            key={i}
            src={att.previewUrl}
            alt={att.fileName}
            title={`${att.fileName} (${formatFileSize(att.sizeBytes)})`}
            className="max-h-40 max-w-full rounded-xl border border-border object-cover"
          />
        ) : (
          <span
            key={i}
            className="flex items-center gap-1.5 rounded-full border border-border bg-secondary/50 px-2.5 py-1 text-xs text-muted-foreground"
            title={att.mimeType}
          >
            {(() => {
              const Icon = iconForMime(att.mimeType);
              return <Icon size={11} aria-hidden="true" />;
            })()}
            <span className="max-w-[12rem] truncate">{att.fileName}</span>
            <span className="tabular-nums">{formatFileSize(att.sizeBytes)}</span>
          </span>
        )
      )}
    </div>
  );
}
