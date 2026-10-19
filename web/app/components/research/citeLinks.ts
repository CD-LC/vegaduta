// Pure helpers that make the [S1]-style markers of a research report
// clickable WITHOUT touching RichText: the report markdown is pre-processed so
// each marker becomes an ordinary markdown link, which RichText already
// renders safely (scheme-checked, noopener).
//
// Everything here treats the report and its source list as untrusted model
// output:
//  - a source URL is only ever linked when it parses as http/https; anything
//    else (javascript:, data:, a relative path, garbage) leaves the marker as
//    inert text;
//  - a marker whose id is not in the source list stays inert too, so a made-up
//    [S9] never points anywhere.

import type { ResearchSource } from "../../lib/api/types";

/**
 * A URL safe to put in an href and inside a markdown link target, or null.
 * RichText's link pattern stops at whitespace and at ")", so those are
 * percent-encoded rather than left to cut the link short.
 */
export function safeSourceUrl(url: unknown): string | null {
  if (typeof url !== "string") return null;
  const trimmed = url.trim();
  if (!trimmed) return null;
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
  if (!parsed.hostname) return null;
  return parsed.href.replace(/\s/g, "%20").replace(/\(/g, "%28").replace(/\)/g, "%29");
}

/** "s 3" / "S3" -> "S3". Source ids are compared in this form. */
export function normalizeSourceId(id: unknown): string {
  return typeof id === "string" ? id.replace(/\s+/g, "").toUpperCase() : "";
}

/** id -> safe URL, for the sources that have one. First occurrence of an id wins. */
export function sourceUrlMap(sources: ReadonlyArray<Pick<ResearchSource, "id" | "url">> | null | undefined): Map<string, string> {
  const map = new Map<string, string>();
  for (const s of sources ?? []) {
    const id = normalizeSourceId(s?.id);
    const url = safeSourceUrl(s?.url);
    if (id && url && !map.has(id)) map.set(id, url);
  }
  return map;
}

// [S1], [S 1], and grouped forms [S1, S2] / [S1; S2]. Not followed by "(" -
// that is already a markdown link the model wrote itself.
const MARKER = /\[(S\s?\d+(?:\s*[,;]\s*S\s?\d+)*)\](?!\()/gi;

/**
 * Walk report markdown (skipping fenced code, inline code and the "![S1]"
 * image form) and let `render` replace each citation marker group that names
 * at least one id in `known`. `render` receives the normalised ids of the whole
 * group, in order. A group naming only unknown ids is left exactly as written.
 */
export function transformCitations(
  markdown: string | null | undefined,
  known: ReadonlySet<string>,
  render: (ids: string[]) => string
): string {
  if (!markdown) return "";
  if (known.size === 0) return markdown;
  const rewriteLine = (line: string): string =>
    line
      .split(/(`[^`]*`)/)
      .map((part) => {
        if (part.startsWith("`") && part.endsWith("`") && part.length >= 2) return part;
        return part.replace(MARKER, (whole: string, group: string, offset: number, full: string) => {
          if (offset > 0 && full[offset - 1] === "!") return whole;
          const ids = group.split(/[,;]/).map((raw) => normalizeSourceId(raw));
          if (!ids.some((id) => known.has(id))) return whole;
          return render(ids);
        });
      })
      .join("");
  let inFence = false;
  return markdown
    .split("\n")
    .map((line) => {
      if (/^```/.test(line.replace(/\r$/, ""))) {
        inFence = !inFence;
        return line;
      }
      return inFence ? line : rewriteLine(line);
    })
    .join("\n");
}

/**
 * Rewrite [S n] markers in report markdown to markdown links to the matching
 * source. Fenced code blocks and inline code are left untouched; unknown ids
 * and non-http(s) sources stay as plain text.
 */
export function linkCitations(
  markdown: string | null | undefined,
  sources: ReadonlyArray<Pick<ResearchSource, "id" | "url">> | null | undefined
): string {
  const urls = sourceUrlMap(sources);
  return transformCitations(markdown, new Set(urls.keys()), (ids) =>
    ids
      .map((id) => {
        const url = urls.get(id);
        return url ? `[${id}](${url})` : `[${id}]`;
      })
      .join(", ")
  );
}
