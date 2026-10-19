// local_transform: text transforms, base64 and JSON path. Split out of ../localTools.ts.
// Pure computation: runtime imports are limited to sibling pure modules.

import { refuse } from "./common";

// ---------------------------------------------------------------------------
// local_transform
// ---------------------------------------------------------------------------

export const TRANSFORM_OPS = [
  "count",
  "sort_lines",
  "dedupe_lines",
  "json_path",
  "json_pretty",
  "base64_encode",
  "base64_decode",
] as const;

export function splitLines(text: string): string[] {
  // A trailing newline is a terminator, not an empty last line — otherwise
  // every sort and dedupe of a normal file grows a phantom blank entry.
  const normalized = text.replace(/\r\n/g, "\n");
  const lines = normalized.split("\n");
  if (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
  return lines;
}

export function base64Encode(text: string): string {
  if (typeof btoa !== "function" || typeof TextEncoder === "undefined") {
    refuse("This browser does not expose base64 encoding to the page.");
  }
  // btoa is latin1-only, so UTF-8 bytes go through it one char code at a time —
  // otherwise any non-ASCII character throws InvalidCharacterError.
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...Array.from(bytes.subarray(i, i + CHUNK)));
  }
  return btoa(binary);
}

export function base64Decode(encoded: string): string {
  if (typeof atob !== "function" || typeof TextDecoder === "undefined") {
    refuse("This browser does not expose base64 decoding to the page.");
  }
  const compact = encoded.replace(/\s+/g, "");
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(compact) || compact.length % 4 !== 0) {
    refuse("That is not valid base64 (expected A-Z a-z 0-9 + / and = padding, length a multiple of 4).");
  }
  let binary: string;
  try {
    binary = atob(compact);
  } catch {
    refuse("That base64 string could not be decoded.");
  }
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return new TextDecoder("utf-8").decode(bytes);
}

/** Dotted path with numeric indices: `user.addresses[0].city`. Keys may contain
 * anything except "." and "[", which is the whole of the syntax. */
export function parseJsonPath(path: string): Array<string | number> {
  const segments: Array<string | number> = [];
  let i = 0;
  while (i < path.length) {
    if (path[i] === ".") {
      i += 1;
      continue;
    }
    if (path[i] === "[") {
      const end = path.indexOf("]", i);
      if (end === -1) refuse(`Unclosed "[" in path "${path}".`);
      const index = path.slice(i + 1, end);
      if (!/^\d+$/.test(index)) refuse(`"[${index}]" is not an array index; use [0], [1], …`);
      segments.push(Number(index));
      i = end + 1;
      continue;
    }
    let end = i;
    while (end < path.length && path[end] !== "." && path[end] !== "[") end += 1;
    segments.push(path.slice(i, end));
    i = end;
  }
  if (segments.length === 0) refuse("The path is empty.");
  return segments;
}

export function readJsonPath(root: unknown, path: string): unknown {
  const segments = parseJsonPath(path);
  let current: unknown = root;
  const walked: string[] = [];
  for (const segment of segments) {
    const where = walked.length === 0 ? "the root" : `"${walked.join(".")}"`;
    if (current === null || typeof current !== "object") {
      refuse(`Cannot read "${String(segment)}": ${where} is ${current === null ? "null" : typeof current}, not an object or array.`);
    }
    if (typeof segment === "number") {
      if (!Array.isArray(current)) refuse(`Cannot index ${where} with [${segment}] — it is an object, not an array.`);
      if (segment >= current.length) refuse(`Index [${segment}] is past the end of ${where} (length ${current.length}).`);
      current = current[segment];
    } else {
      if (Array.isArray(current)) refuse(`Cannot read key "${segment}" from ${where} — it is an array; use [0], [1], …`);
      const keys = Object.keys(current as Record<string, unknown>);
      if (!keys.includes(segment)) {
        const shown = keys.slice(0, 20).join(", ");
        refuse(
          `"${segment}" is not present at ${where}. Available keys: ${shown || "(none)"}${keys.length > 20 ? `, … (${keys.length} total)` : ""}.`
        );
      }
      current = (current as Record<string, unknown>)[segment];
    }
    walked.push(String(segment));
  }
  return current;
}

export function parseJsonOrRefuse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch (err) {
    refuse(`"text" is not valid JSON: ${err instanceof Error ? err.message : String(err)}`);
  }
}

