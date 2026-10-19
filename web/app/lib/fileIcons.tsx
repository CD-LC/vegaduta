// Shared file-type -> icon/color mapping (2026-08-05). Generalizes
// ChatAttachments.tsx's iconForMime helper (which only distinguishes
// audio/video/"document"/generic) into a real per-extension mapping so the
// Files page and chat MediaCard can both render a specific icon+color for
// docx/pdf/xlsx/pptx/csv/txt/json/md/images/audio/video instead of one
// generic FileText for every non-media attachment. Extension takes
// precedence over mimeType when both are given (a mimeType like
// "application/octet-stream" carries no type information at all, but a
// filename extension almost always does).
import type { LucideIcon } from "lucide-react";
import {
  File,
  FileArchive,
  FileAudio,
  FileCode,
  FileImage,
  FileJson,
  FileSpreadsheet,
  FileText,
  FileVideo,
} from "lucide-react";
import { cn } from "./utils";

export interface FileTypeIconInfo {
  Icon: LucideIcon;
  colorClassName: string;
}

const FALLBACK: FileTypeIconInfo = { Icon: File, colorClassName: "text-gray-500 dark:text-gray-400" };

// Keyed by lowercase extension, no leading dot. Each entry mirrors a small
// cluster of related mimetypes below (EXTENSION_BY_MIME) so a caller that
// only has a mimeType (e.g. a raw upload before a filename is known) still
// resolves to the same icon/color as the extension would.
const BY_EXTENSION: Record<string, FileTypeIconInfo> = {
  doc: { Icon: FileText, colorClassName: "text-blue-500 dark:text-blue-400" },
  docx: { Icon: FileText, colorClassName: "text-blue-500 dark:text-blue-400" },
  pdf: { Icon: FileText, colorClassName: "text-red-500 dark:text-red-400" },
  xls: { Icon: FileSpreadsheet, colorClassName: "text-green-600 dark:text-green-400" },
  xlsx: { Icon: FileSpreadsheet, colorClassName: "text-green-600 dark:text-green-400" },
  ppt: { Icon: FileText, colorClassName: "text-orange-500 dark:text-orange-400" },
  pptx: { Icon: FileText, colorClassName: "text-orange-500 dark:text-orange-400" },
  csv: { Icon: FileSpreadsheet, colorClassName: "text-teal-600 dark:text-teal-400" },
  txt: { Icon: FileText, colorClassName: "text-slate-500 dark:text-slate-400" },
  json: { Icon: FileJson, colorClassName: "text-amber-500 dark:text-amber-400" },
  md: { Icon: FileCode, colorClassName: "text-slate-500 dark:text-slate-400" },
  markdown: { Icon: FileCode, colorClassName: "text-slate-500 dark:text-slate-400" },
  // images
  png: { Icon: FileImage, colorClassName: "text-purple-500 dark:text-purple-400" },
  jpg: { Icon: FileImage, colorClassName: "text-purple-500 dark:text-purple-400" },
  jpeg: { Icon: FileImage, colorClassName: "text-purple-500 dark:text-purple-400" },
  gif: { Icon: FileImage, colorClassName: "text-purple-500 dark:text-purple-400" },
  webp: { Icon: FileImage, colorClassName: "text-purple-500 dark:text-purple-400" },
  svg: { Icon: FileImage, colorClassName: "text-purple-500 dark:text-purple-400" },
  // audio
  mp3: { Icon: FileAudio, colorClassName: "text-pink-500 dark:text-pink-400" },
  wav: { Icon: FileAudio, colorClassName: "text-pink-500 dark:text-pink-400" },
  ogg: { Icon: FileAudio, colorClassName: "text-pink-500 dark:text-pink-400" },
  m4a: { Icon: FileAudio, colorClassName: "text-pink-500 dark:text-pink-400" },
  // video
  mp4: { Icon: FileVideo, colorClassName: "text-indigo-500 dark:text-indigo-400" },
  webm: { Icon: FileVideo, colorClassName: "text-indigo-500 dark:text-indigo-400" },
  mov: { Icon: FileVideo, colorClassName: "text-indigo-500 dark:text-indigo-400" },
  // common archive extensions - not in the task's required list, but a
  // reasonable fallback improvement over the generic gray File icon for a
  // type this codebase already deals with (knowledge doc uploads).
  zip: { Icon: FileArchive, colorClassName: "text-gray-500 dark:text-gray-400" },
};

// Keyed by lowercase mimetype (or mimetype prefix via startsWith checks
// below for the audio/video/image families, which have far more distinct
// subtypes than extensions do). Only consulted when no extension match was
// found.
const BY_EXACT_MIME: Record<string, FileTypeIconInfo> = {
  "application/msword": BY_EXTENSION.doc,
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": BY_EXTENSION.docx,
  "application/pdf": BY_EXTENSION.pdf,
  "application/vnd.ms-excel": BY_EXTENSION.xls,
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": BY_EXTENSION.xlsx,
  "application/vnd.ms-powerpoint": BY_EXTENSION.ppt,
  "application/vnd.openxmlformats-officedocument.presentationml.presentation": BY_EXTENSION.pptx,
  "text/csv": BY_EXTENSION.csv,
  "text/plain": BY_EXTENSION.txt,
  "application/json": BY_EXTENSION.json,
  "text/markdown": BY_EXTENSION.md,
  "application/zip": BY_EXTENSION.zip,
};

/**
 * Resolve a file-type icon + Tailwind text-color class for an extension
 * and/or mimetype. Extension takes precedence when both are given (and
 * either may be omitted - callers pass whichever they have). The extension
 * may be given bare ("pdf") or as a filename/path ("report.PDF",
 * "/tmp/x.docx") - only the suffix after the last "." is used, case-
 * insensitively.
 */
export function getFileTypeIcon(extension?: string | null, mimeType?: string | null): FileTypeIconInfo {
  const ext = normalizeExtension(extension);
  if (ext && BY_EXTENSION[ext]) return BY_EXTENSION[ext];

  const mime = (mimeType || "").toLowerCase().trim();
  if (mime) {
    if (BY_EXACT_MIME[mime]) return BY_EXACT_MIME[mime];
    if (mime.startsWith("image/")) return { Icon: FileImage, colorClassName: "text-purple-500 dark:text-purple-400" };
    if (mime.startsWith("audio/")) return { Icon: FileAudio, colorClassName: "text-pink-500 dark:text-pink-400" };
    if (mime.startsWith("video/")) return { Icon: FileVideo, colorClassName: "text-indigo-500 dark:text-indigo-400" };
  }

  return FALLBACK;
}

function normalizeExtension(extension?: string | null): string {
  const raw = (extension || "").trim().toLowerCase();
  if (!raw) return "";
  // Accept a bare extension, a leading-dot extension, or a full filename/path.
  const afterDot = raw.includes(".") ? raw.slice(raw.lastIndexOf(".") + 1) : raw;
  return afterDot.replace(/^\.+/, "");
}

/**
 * Renders the resolved icon for `extension`/`mimeType` (extension wins when
 * both are given - see getFileTypeIcon) with its resolved color class merged
 * into `className`.
 */
export function FileTypeIcon({
  extension,
  mimeType,
  className,
}: {
  extension?: string | null;
  mimeType?: string | null;
  className?: string;
}) {
  const { Icon, colorClassName } = getFileTypeIcon(extension, mimeType);
  return <Icon className={cn(colorClassName, className)} />;
}
