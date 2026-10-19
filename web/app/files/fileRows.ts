// Row model + normalizers for the Files page. Every source (knowledge documents, generated images,
// speech clips, video jobs, generated documents) maps into one FileRow so they share one table.
// Pure and React-free: user-facing text comes from the `Tr` translator the page passes in.
import { api } from "../lib/api";
import { formatFileSize } from "../components/ChatAttachments";
import { getFileTypeIcon } from "../lib/fileIcons";
import type { RowKind } from "./filesLogic";

export type Tr = (key: string, values?: Record<string, any>) => string;

export interface FileRow {
  kind: RowKind;
  id: any;
  name: string;
  detail: string;
  collectionName?: string;
  createdAt: string;
  downloadable: boolean;
  filenameFallback: string;
  // Filename extension when one is known (knowledge docs, generated documents) - drives the real
  // per-type icon. Rows without a filename fall back to a representative mimeType for their kind.
  extension?: string;
}

export const PAGE_SIZE = 20;

/** The artifact list endpoints (images, speech, video) return everything in one response and cannot page. */
export const ARTIFACT_LIST_CAP = 200;

const KIND_MIME_FALLBACK: Partial<Record<RowKind, string>> = {
  image: "image/png",
  "elevenlabs-audio": "audio/mpeg",
  "sarvam-audio": "audio/mpeg",
  video: "video/mp4",
  document: "application/pdf",
};

const KIND_LABEL_KEY: Record<RowKind, string> = {
  "knowledge-document": "typeDocument",
  image: "typeImage",
  "elevenlabs-audio": "typeAudio",
  "sarvam-audio": "typeAudio",
  video: "typeVideo",
  document: "typeDocument",
};

export function rowTypeLabel(row: FileRow, t: Tr): string {
  if (row.extension) return row.extension.replace(/^\./, "").toUpperCase();
  return t(KIND_LABEL_KEY[row.kind]);
}

export function resolveRowIcon(row: FileRow) {
  if (row.extension) return getFileTypeIcon(row.extension);
  return getFileTypeIcon(undefined, KIND_MIME_FALLBACK[row.kind]);
}

function truncate(text: string, max: number) {
  if (!text) return text;
  return text.length > max ? text.slice(0, max).trimEnd() + "…" : text;
}

function statusLabel(status: string) {
  return status.charAt(0) + status.slice(1).toLowerCase();
}

export function normalizeKnowledgeDoc(d: any): FileRow {
  return {
    kind: "knowledge-document",
    id: d.id,
    name: d.filename,
    detail: [d.fileExtension ? d.fileExtension.replace(/^\./, "").toUpperCase() : null, formatFileSize(d.fileSizeBytes)]
      .filter(Boolean)
      .join(" · "),
    collectionName: d.collectionName,
    createdAt: d.createdAt,
    downloadable: !!d.hasOriginalFile,
    filenameFallback: d.filename,
    extension: d.fileExtension,
  };
}

export function normalizeImage(row: any, t: Tr): FileRow {
  return {
    kind: "image",
    id: row.id,
    name: truncate(row.prompt, 60) || t("nameGeneratedImage"),
    detail: [row.model, row.size].filter(Boolean).join(" · "),
    createdAt: row.createdAt,
    downloadable: true,
    filenameFallback: `image-${row.id}.png`,
  };
}

export function normalizeElevenLabsAudio(row: any, t: Tr): FileRow {
  return {
    kind: "elevenlabs-audio",
    id: row.id,
    name: t("nameSpeechElevenLabs"),
    detail: row.voiceId ? t("voiceDetail", { voice: row.voiceId }) : "",
    createdAt: row.createdAt,
    downloadable: true,
    filenameFallback: `speech-${row.id}.mp3`,
  };
}

export function normalizeSarvamAudio(row: any, t: Tr): FileRow {
  return {
    kind: "sarvam-audio",
    id: row.id,
    name: t("nameSpeechSarvam"),
    detail: "",
    createdAt: row.createdAt,
    downloadable: true,
    filenameFallback: `speech-${row.id}.wav`,
  };
}

export function normalizeVideoJob(row: any, t: Tr): FileRow {
  const status = row.status || "QUEUED";
  return {
    kind: "video",
    id: row.jobId,
    name: truncate(row.prompt, 60) || t("nameGeneratedVideo"),
    detail: status === "COMPLETE" ? `${row.durationSeconds}s` : statusLabel(status),
    createdAt: row.createdAt,
    downloadable: status === "COMPLETE",
    filenameFallback: `video-${row.jobId}.mp4`,
  };
}

export function normalizeDocument(row: any, t: Tr): FileRow {
  const status = row.status || "PENDING";
  const filename: string = row.filename || "";
  const dotIndex = filename.lastIndexOf(".");
  const extension =
    dotIndex > -1 ? filename.slice(dotIndex + 1) : row.type ? String(row.type).toLowerCase() : undefined;
  return {
    kind: "document",
    id: row.id,
    name: filename || t("nameGeneratedDocument"),
    detail: status === "COMPLETE" ? row.type || "" : statusLabel(status),
    createdAt: row.createdAt,
    downloadable: status === "COMPLETE",
    filenameFallback: filename || `document-${row.id}`,
    extension,
  };
}

const ARTIFACT_DOWNLOAD_FN: Partial<Record<RowKind, (id: any) => Promise<{ blob: Blob; filename: string }>>> = {
  image: api.downloadGeneratedImage,
  "elevenlabs-audio": api.downloadElevenLabsAudio,
  "sarvam-audio": api.downloadSarvamAudio,
  video: api.downloadGeneratedVideo,
  document: api.downloadGeneratedDocument,
};

export function fetchRowBlob(row: FileRow): Promise<{ blob: Blob; filename: string }> {
  return row.kind === "knowledge-document"
    ? api.downloadKnowledgeDocumentFile(row.id)
    : ARTIFACT_DOWNLOAD_FN[row.kind]!(row.id);
}

export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
