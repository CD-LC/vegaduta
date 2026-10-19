// Artifacts pane - shared types and the pure type -> file-format tables.
//
// An "artifact" is a self-contained deliverable the model puts in ONE fenced
// block whose info string is `artifact:<type>:<title>` (see artifactParser.ts).
// Everything here is pure (no DOM, no React) so the security-relevant and
// format decisions are unit-testable.
//
// Artifacts are NOT persisted server-side in this lane: they are re-derived
// from the chat message text on every render, so a reloaded session shows them
// only for as long as the transcript is kept by whoever renders it.

export type ArtifactKind = "html" | "svg" | "markdown" | "code" | "mermaid" | "csv";

export const ARTIFACT_KINDS: readonly ArtifactKind[] = ["html", "svg", "markdown", "code", "mermaid", "csv"];

/** Info-string type token (lower-cased) -> kind. Aliases are forgiving on purpose: models drift. */
const KIND_ALIASES: Record<string, ArtifactKind> = {
  html: "html",
  htm: "html",
  svg: "svg",
  markdown: "markdown",
  md: "markdown",
  mermaid: "mermaid",
  csv: "csv",
  code: "code",
};

export function normalizeKind(raw: string | null | undefined): ArtifactKind | null {
  if (typeof raw !== "string") return null;
  const k = raw.trim().toLowerCase();
  return Object.prototype.hasOwnProperty.call(KIND_ALIASES, k) ? KIND_ALIASES[k] : null;
}

/** One parsed `artifact:` fenced block. `content` is the raw, untrusted body. */
export interface ParsedArtifact {
  kind: ArtifactKind;
  /** Programming language for kind "code" (sanitised, lower-case); null otherwise. */
  lang: string | null;
  title: string;
  content: string;
  /** False when the closing fence has not arrived (still streaming, or the reply was cut off). */
  complete: boolean;
  /** True when the body exceeded MAX_ARTIFACT_CHARS and was cut. Preview/Run are refused. */
  truncated: boolean;
}

/** Hard cap on one artifact's body (characters). Beyond it the body is cut and Preview is refused. */
export const MAX_ARTIFACT_CHARS = 512_000;
/** Cap on what is ever handed to an iframe / data URL (bytes of UTF-8). */
export const MAX_PREVIEW_BYTES = 256 * 1024;
/** Longest title kept (characters). */
export const MAX_TITLE_CHARS = 80;

/** Kinds that have a Preview tab. mermaid/code are source-only (no new dependency, nothing to execute). */
export function hasPreview(kind: ArtifactKind): boolean {
  return kind === "html" || kind === "svg" || kind === "markdown" || kind === "csv";
}

// ---------------------------------------------------------------- download

const LANG_EXT: Record<string, string> = {
  javascript: "js", js: "js", node: "js", jsx: "jsx", typescript: "ts", ts: "ts", tsx: "tsx",
  python: "py", py: "py", java: "java", kotlin: "kt", kt: "kt", go: "go", golang: "go",
  rust: "rs", rs: "rs", c: "c", cpp: "cpp", "c++": "cpp", cc: "cpp", csharp: "cs", "c#": "cs", cs: "cs",
  ruby: "rb", rb: "rb", php: "php", swift: "swift", sql: "sql",
  bash: "sh", sh: "sh", shell: "sh", zsh: "sh", powershell: "ps1", ps1: "ps1",
  json: "json", yaml: "yaml", yml: "yaml", toml: "toml", xml: "xml",
  css: "css", scss: "scss", html: "html", markdown: "md", md: "md", csv: "csv",
  r: "r", lua: "lua", dart: "dart", scala: "scala",
};

const EXT_MIME: Record<string, string> = {
  html: "text/html",
  svg: "image/svg+xml",
  md: "text/markdown",
  csv: "text/csv",
  mmd: "text/plain",
  json: "application/json",
  css: "text/css",
  xml: "application/xml",
  js: "text/javascript",
};

export interface DownloadInfo {
  filename: string;
  /** Bare mime type; the downloader appends the charset. */
  mime: string;
  extension: string;
}

export function extensionFor(kind: ArtifactKind, lang: string | null): string {
  switch (kind) {
    case "html":
      return "html";
    case "svg":
      return "svg";
    case "markdown":
      return "md";
    case "csv":
      return "csv";
    case "mermaid":
      return "mmd";
    case "code": {
      const key = (lang || "").toLowerCase();
      return Object.prototype.hasOwnProperty.call(LANG_EXT, key) ? LANG_EXT[key] : "txt";
    }
    default:
      return "txt";
  }
}

export function mimeForExtension(ext: string): string {
  return Object.prototype.hasOwnProperty.call(EXT_MIME, ext) ? EXT_MIME[ext] : "text/plain";
}

const WINDOWS_RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

/** Title -> a filename stem that is safe on every OS: no separators, no leading dots, no reserved device names. */
export function safeFilenameStem(title: string): string {
  let stem = String(title || "")
    .normalize("NFKC")
    .replace(/[^\p{L}\p{N}._-]+/gu, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^[-.]+|[-.]+$/g, "")
    .slice(0, 60)
    .replace(/[-.]+$/g, "");
  if (!stem) stem = "artifact";
  if (WINDOWS_RESERVED.test(stem)) stem = `artifact-${stem}`;
  return stem;
}

export function downloadInfo(kind: ArtifactKind, lang: string | null, title: string): DownloadInfo {
  const extension = extensionFor(kind, lang);
  return {
    filename: `${safeFilenameStem(title)}.${extension}`,
    mime: mimeForExtension(extension),
    extension,
  };
}

// ---------------------------------------------------------------- identity

/**
 * Stable id for an artifact: the same title (case, spacing and Unicode-form
 * insensitive) is the SAME artifact, so a model that re-emits
 * `artifact:html:Pricing page` later produces a new VERSION of it rather than a
 * second artifact. The kind is deliberately not part of the id.
 */
export function artifactIdForTitle(title: string): string {
  const norm = String(title || "")
    .normalize("NFKC")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_TITLE_CHARS);
  return `a:${norm}`;
}

/** Cheap, non-cryptographic content fingerprint (FNV-1a 32-bit + length). Used only for "have we run this exact content" bookkeeping. */
export function contentFingerprint(content: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < content.length; i++) {
    h ^= content.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return `${h.toString(16)}-${content.length}`;
}

/** UTF-8 byte length without allocating a huge buffer when TextEncoder is missing. */
export function utf8Length(s: string): number {
  if (typeof TextEncoder !== "undefined") return new TextEncoder().encode(s).length;
  return s.length * 3;
}
