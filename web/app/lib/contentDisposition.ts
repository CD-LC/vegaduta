/**
 * The one place the console reads a download's file name out of a
 * Content-Disposition header.
 *
 * Order, per RFC 6266: the `filename*` parameter (RFC 5987, percent-encoded
 * UTF-8) wins over the plain `filename`. The previous per-call regexes took
 * whichever came FIRST in the header, and core used to send
 * `filename="=?UTF-8?Q?name?="; filename*=UTF-8''name` - so a workflow export
 * was saved as `=?UTF-8?Q?e2e-workflow.vegaduta-workflow.json?=` (prod,
 * 2026-10-05). Core no longer sends that form, but a plain `filename` that is
 * still an RFC 2047 encoded-word (an older core, a proxy, another server) is
 * decoded here too rather than shown raw.
 *
 * The header is untrusted: any path part is dropped and control characters
 * and quotes are removed, so the result is always a bare file name.
 */
export function filenameFromContentDisposition(header: string | null | undefined, fallback: string): string {
  if (!header) return fallback;
  const name = extendedFilename(header) ?? plainFilename(header);
  if (name == null) return fallback;
  return sanitize(name) || fallback;
}

/** `filename*=UTF-8''percent-encoded` (charset and optional language tag are read, only UTF-8/ASCII/Latin-1 decoded). */
function extendedFilename(header: string): string | null {
  const match = header.match(/(?:^|;)\s*filename\*\s*=\s*([^;]+)/i);
  if (!match) return null;
  const value = match[1].trim().replace(/^"(.*)"$/, "$1");
  const parts = value.match(/^([^']*)'[^']*'(.*)$/);
  if (!parts) return null;
  const charset = parts[1].toLowerCase();
  try {
    if (charset === "iso-8859-1") {
      return parts[2].replace(/%([0-9A-Fa-f]{2})/g, (_m, hex: string) => String.fromCharCode(parseInt(hex, 16)));
    }
    return decodeURIComponent(parts[2]);
  } catch {
    // A stray "%" is not valid percent-encoding; the raw value is still the best name on offer.
    return parts[2];
  }
}

/** `filename="quoted"` or `filename=token`, with an RFC 2047 encoded-word decoded when that is what it holds. */
function plainFilename(header: string): string | null {
  const quoted = header.match(/(?:^|;)\s*filename\s*=\s*"((?:[^"\\]|\\.)*)"/i);
  const token = quoted ? null : header.match(/(?:^|;)\s*filename\s*=\s*([^;]+)/i);
  const raw = quoted ? quoted[1].replace(/\\(.)/g, "$1") : token ? token[1].trim() : null;
  if (raw == null) return null;
  return decodeEncodedWords(raw);
}

/** Decodes `=?charset?Q?...?=` / `=?charset?B?...?=` runs (UTF-8 only); anything else is returned untouched. */
export function decodeEncodedWords(value: string): string {
  if (!value.includes("=?")) return value;
  return value.replace(/=\?([^?]+)\?([QqBb])\?([^?]*)\?=/g, (whole, charset: string, enc: string, text: string) => {
    if (!/^utf-?8$/i.test(charset)) return whole;
    try {
      const bytes =
        enc.toUpperCase() === "B"
          ? Uint8Array.from(atob(text), (c) => c.charCodeAt(0))
          : qDecode(text);
      return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      return whole;
    }
  });
}

function qDecode(text: string): Uint8Array {
  const out: number[] = [];
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === "_") {
      out.push(0x20);
    } else if (ch === "=" && /^[0-9A-Fa-f]{2}$/.test(text.slice(i + 1, i + 3))) {
      out.push(parseInt(text.slice(i + 1, i + 3), 16));
      i += 2;
    } else {
      out.push(ch.charCodeAt(0) & 0xff);
    }
  }
  return Uint8Array.from(out);
}

function sanitize(name: string): string {
  const base = name.split(/[\\/]/).pop() || "";
  return Array.from(base)
    .filter((ch) => ch.charCodeAt(0) >= 32 && ch.charCodeAt(0) !== 127 && ch !== '"')
    .join("")
    .trim();
}
