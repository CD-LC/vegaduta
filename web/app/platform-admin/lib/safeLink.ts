// Link validation for server-supplied URLs (FE-006, 2026-09-30).
//
// Notification links (and anything similar) are data from the API, so they
// must never be handed to window.open / router.push unchecked: a
// `javascript:` / `data:` URL, a protocol-relative `//evil.example`, or a
// plain-http link to a foreign host would otherwise run or phish from the
// admin console's origin. resolveSafeLink() classifies a raw value into one
// of three outcomes and rejects everything else:
//
//   { kind: "path" }      a single-slash relative path ("/platform-admin/tenants/x"), or an
//                         absolute URL on the CURRENT origin reduced to its
//                         path - the caller routes it (router.push or the
//                         tenant-console mapping).
//   { kind: "external" }  an https: URL whose origin is one of the
//                         configured first-party origins.
//   null                  anything else - ignore it.

import { getPublicEnv } from "./env";

export type SafeLink = { kind: "path"; path: string } | { kind: "external"; href: string } | null;

// Rejects ASCII control characters and whitespace anywhere in the value:
// browsers strip tabs/newlines while parsing, which is how "java\tscript:"
// style payloads slip past naive prefix checks.
function hasUnsafeChars(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code <= 0x20 || code === 0x7f) return true;
  }
  return false;
}

function isSingleSlashPath(value: string): boolean {
  // "//host" and "/\host" are both protocol-relative to a browser.
  return value.startsWith("/") && value[1] !== "/" && value[1] !== "\\";
}

function originOf(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const u = new URL(value);
    return u.protocol === "https:" || u.protocol === "http:" ? u.origin : null;
  } catch {
    return null;
  }
}

/** Origins of the configured first-party public URLs (API, tenant console,
 *  Keycloak), plus any extra origins the caller already knows about. */
export function firstPartyOrigins(extra: Array<string | null | undefined> = []): string[] {
  const candidates = [
    getPublicEnv("NEXT_PUBLIC_API_BASE_URL"),
    getPublicEnv("NEXT_PUBLIC_APP_URL"),
    getPublicEnv("NEXT_PUBLIC_KEYCLOAK_URL"),
    ...extra,
  ];
  const out = new Set<string>();
  for (const c of candidates) {
    const o = originOf(c);
    if (o) out.add(o);
  }
  return [...out];
}

export function resolveSafeLink(
  raw: unknown,
  opts: { currentOrigin?: string | null; trustedOrigins?: string[] } = {},
): SafeLink {
  if (typeof raw !== "string") return null;
  const value = raw;
  if (!value || hasUnsafeChars(value)) return null;

  if (isSingleSlashPath(value)) return { kind: "path", path: value };

  let url: URL;
  try {
    // No base: only absolute URLs parse here, so any other relative form
    // ("tenants", "./x", "?q") is rejected rather than guessed at.
    url = new URL(value);
  } catch {
    return null;
  }

  const currentOrigin =
    opts.currentOrigin !== undefined
      ? opts.currentOrigin
      : typeof window !== "undefined"
        ? window.location.origin
        : null;
  if (currentOrigin && (url.protocol === "https:" || url.protocol === "http:") && url.origin === currentOrigin) {
    return { kind: "path", path: `${url.pathname}${url.search}${url.hash}` };
  }

  if (url.protocol !== "https:") return null;
  const trusted = opts.trustedOrigins ?? firstPartyOrigins();
  return trusted.includes(url.origin) ? { kind: "external", href: url.href } : null;
}

/** SEC-037: href guard for third-party URLs rendered as links (e.g. the
 *  public MCP registry's repositoryUrl/docsUrl). Returns the normalised URL
 *  only for absolute http:/https: values; anything else (javascript:, data:,
 *  vbscript:, relative or protocol-relative forms, junk) returns undefined so
 *  the caller renders plain text / no link instead. */
export function safeHref(raw: unknown): string | undefined {
  if (typeof raw !== "string") return undefined;
  const value = raw.trim();
  if (!value || hasUnsafeChars(value)) return undefined;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}
