// Validation for the App Builder's deployed-preview URL (security review
// FE-005). The URL comes from the API (project.preview.preview_url) and is
// loaded in an <iframe> on the console origin, so it is checked before use:
//  - only https: (or http: on a loopback host, for local development);
//  - the frame is always sandboxed; allow-same-origin is added only when the
//    preview lives on a different site from the console. A same-site preview
//    with allow-same-origin could script its way to same-site cookies and, if
//    it were ever the console's own origin, to the console itself.

const LOOPBACK = new Set(["localhost", "127.0.0.1", "[::1]"]);

/** The URL, normalised, if it is safe to frame; otherwise null. */
export function safePreviewUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.username || url.password) return null;
  if (url.protocol === "https:") return url.href;
  if (url.protocol === "http:" && LOOPBACK.has(url.hostname)) return url.href;
  return null;
}

/** Last two DNS labels: an approximation of the registrable domain that errs
 *  towards "same site" (e.g. two *.co.uk hosts compare equal), which only ever
 *  withholds allow-same-origin - the safe direction. */
function siteOf(hostname: string): string {
  const labels = hostname.toLowerCase().replace(/\.$/, "").split(".");
  return labels.slice(-2).join(".");
}

export function isCrossSite(previewUrl: string, consoleHostname: string): boolean {
  try {
    const host = new URL(previewUrl).hostname;
    if (LOOPBACK.has(host) || LOOPBACK.has(consoleHostname)) return false;
    return siteOf(host) !== siteOf(consoleHostname);
  } catch {
    return false;
  }
}

const BASE_SANDBOX = "allow-scripts allow-forms allow-popups allow-popups-to-escape-sandbox";

/** sandbox attribute for the preview frame. */
export function previewSandbox(previewUrl: string, consoleHostname: string): string {
  return isCrossSite(previewUrl, consoleHostname) ? `${BASE_SANDBOX} allow-same-origin` : BASE_SANDBOX;
}
