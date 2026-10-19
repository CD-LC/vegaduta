// Client-side mirror of core's SecretHostPolicy.normalisePattern (SEC-006).
// A convenience so the inline error matches the 400 the server would send; the
// server re-validates and stays the control.
//
// Grammar: an exact host (api.example.com) or a leading-label wildcard
// (*.example.com). No scheme, port, path, userinfo, bare "*", single-label
// wildcard ("*.com"), wildcard on an IP, or wildcard anywhere but the first
// label. IDN is left to the server (it converts to punycode); Unicode letters
// are accepted here and validated after conversion by core.

export const MAX_ALLOWED_HOSTS = 32;

export type HostErrorCode = "shape" | "wildcardPosition" | "invalidHost" | "wildcardIp" | "wildcardSuffix";

const LABEL = /^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$/;
const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;

/** Lower-cased pattern, or the reason it is invalid. */
export function normaliseHostPattern(raw: string): { ok: true; value: string } | { ok: false; code: HostErrorCode } {
  const p = raw.trim();
  if (/[/@:,\s?#]/.test(p)) return { ok: false, code: "shape" };
  const wildcard = p.startsWith("*.");
  let host = (wildcard ? p.slice(2) : p).toLowerCase();
  if (host.includes("*")) return { ok: false, code: "wildcardPosition" };
  while (host.endsWith(".")) host = host.slice(0, -1);
  if (!host || host.length > 253) return { ok: false, code: "invalidHost" };
  // Non-ASCII labels are punycoded server-side; only check the ASCII ones here.
  // eslint-disable-next-line no-control-regex
  const ascii = /^[\x00-\x7f]*$/.test(host);
  if (ascii && !host.split(".").every((l) => LABEL.test(l))) return { ok: false, code: "invalidHost" };
  if (wildcard) {
    if (IPV4.test(host)) return { ok: false, code: "wildcardIp" };
    if (!host.includes(".")) return { ok: false, code: "wildcardSuffix" };
    return { ok: true, value: `*.${host}` };
  }
  return { ok: true, value: host };
}

/** Splits free text on commas, whitespace and newlines. */
export function splitHostInput(text: string): string[] {
  return text.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean);
}

/** Parses + validates a textarea value: de-duplicated normalised list, or the first offending entry. */
export function parseHostList(
  text: string,
): { ok: true; hosts: string[] } | { ok: false; entry: string; code: HostErrorCode | "tooMany" } {
  const out: string[] = [];
  for (const entry of splitHostInput(text)) {
    const r = normaliseHostPattern(entry);
    if (r.ok === false) return { ok: false, entry, code: r.code };
    if (!out.includes(r.value)) out.push(r.value);
  }
  if (out.length > MAX_ALLOWED_HOSTS) return { ok: false, entry: String(out.length), code: "tooMany" };
  return { ok: true, hosts: out };
}
