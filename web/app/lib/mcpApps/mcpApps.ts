// MCP Apps (ui:// resources) - host-side validation. Pure functions, no DOM,
// so the security-relevant decisions are unit-testable. See
// docs/plans/adr/mcp-apps-ui-resources.md for the threat model.
//
// An MCP server may declare an HTML resource (`ui://server/view`, mimeType
// `text/html;profile=mcp-app`) for a tool. Core fetches it over the same
// authenticated MCP connection (GET /api/mcp-apps/{appId}); we render it in a
// sandboxed iframe with an opaque origin, a locked-down CSP (supplied by core,
// re-validated here) and the postMessage bridge in appBridge.ts. No
// credentials, cookies or tokens are ever given to the frame. Network requests
// from the initial document are blocked by CSP, but the frame can still
// self-navigate (see docs/plans/MCP-APPS-SECURITY-NOTES.md "Residual risks").

/** Build-time flag (NEXT_PUBLIC_* bakes at build). Default OFF. */
import { getPublicEnv } from "../env";
export const MCP_APPS_ENABLED = getPublicEnv("NEXT_PUBLIC_MCP_APPS") === "1";

// Matches core's app.mcp.apps.max-resource-bytes default; core refuses larger apps before they reach us.
export const MAX_APP_HTML_BYTES = 512 * 1024;
export const MAX_APP_MESSAGE_CHARS = 2000;
export const MIN_APP_HEIGHT = 80;
export const MAX_APP_HEIGHT = 1200;

const UI_URI = /^ui:\/\/[A-Za-z0-9][A-Za-z0-9._-]{0,127}(\/[A-Za-z0-9._~\-/]{0,255})?$/;

export function isUiResourceUri(uri: unknown): uri is string {
  return typeof uri === "string" && UI_URI.test(uri);
}

/** The additive `app` pointer core puts on a `tool_result` step event: a handle, never the HTML. */
export interface McpAppPointer {
  appId: string;
  server: string;
  tool: string;
  uri: string;
}

const APP_ID = /^[A-Za-z0-9_-]{16,64}$/;

/** Validates the wire pointer (untrusted shape: a newer or buggy core must not crash the trace). */
export function parseAppPointer(raw: unknown): McpAppPointer | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.appId !== "string" || !APP_ID.test(r.appId)) return null;
  if (typeof r.server !== "string" || r.server.length === 0 || r.server.length > 200) return null;
  if (typeof r.tool !== "string" || r.tool.length === 0 || r.tool.length > 200) return null;
  if (!isUiResourceUri(r.uri)) return null;
  return { appId: r.appId, server: r.server, tool: r.tool, uri: r.uri };
}

export interface RawUiResource {
  uri?: unknown;
  mimeType?: unknown;
  text?: unknown;
}

export type ParsedUiResource =
  | { ok: true; uri: string; html: string }
  | { ok: false; reason: string };

/** Accept only an inline `text/html;profile=mcp-app` resource under the cap.
 * Blob/binary payloads, plain text/html and remote URLs are refused. */
export function parseUiResource(raw: RawUiResource | null | undefined): ParsedUiResource {
  if (!raw || typeof raw !== "object") return { ok: false, reason: "no resource" };
  if (!isUiResourceUri(raw.uri)) return { ok: false, reason: "not a ui:// uri" };
  const mime = typeof raw.mimeType === "string" ? raw.mimeType.toLowerCase().replace(/\s+/g, "") : "";
  if (mime !== "text/html;profile=mcp-app" && mime !== 'text/html;profile="mcp-app"') {
    return { ok: false, reason: "unsupported mime type" };
  }
  if (typeof raw.text !== "string" || raw.text.length === 0) {
    return { ok: false, reason: "empty html" };
  }
  const bytes = typeof TextEncoder !== "undefined" ? new TextEncoder().encode(raw.text).length : raw.text.length;
  if (bytes > MAX_APP_HTML_BYTES) return { ok: false, reason: "html too large" };
  return { ok: true, uri: raw.uri, html: raw.text };
}

/** CSP applied inside the frame via <meta>. No subresource/fetch traffic (a meta
 * CSP does not govern the frame navigating itself, see the ADR); inline
 * script/style only (the app ships as one self-contained document); images
 * and fonts only as data: URIs; no nested frames, forms, or <base>. */
export const APP_FRAME_CSP = [
  "default-src 'none'",
  "script-src 'unsafe-inline'",
  "style-src 'unsafe-inline'",
  "img-src data:",
  "font-src data:",
  "media-src data:",
  "connect-src 'none'",
  "frame-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
].join("; ");

/** Defense in depth only: drop <meta http-equiv="refresh"> from app html.
 * This does NOT stop script navigation (location.href); a navigated frame is
 * outside our meta CSP. See ADR "Residual risks"; the real fix is a separate
 * sandbox origin with CSP headers (ADR option 3) before the flag is enabled. */
export function stripMetaRefresh(html: string): string {
  return html.replace(/<meta\b[^>]*http-equiv\s*=\s*["']?\s*refresh[^>]*>/gi, "");
}

/** Characters a CSP the host will inline into a meta attribute may contain: nothing that can break out of the
 * attribute (no quotes other than the single-quoted CSP keywords, no angle brackets, no newlines). */
const CSP_SAFE = /^[A-Za-z0-9 :/.*_;'-]+$/;
const CSP_FORBIDDEN = /'unsafe-eval'|'wasm-unsafe-eval'|frame-ancestors|report-uri|report-to|sandbox/i;

/** The CSP core sent, if it is well-formed and keeps the closed baseline; otherwise our own default. The CSP
 * comes from our own authenticated API, so this is defense in depth (a bug in core must not become an attribute
 * injection or a looser policy here), not a trust boundary. */
export function effectiveAppCsp(csp: unknown): string {
  if (typeof csp !== "string" || csp.length === 0 || csp.length > 2000) return APP_FRAME_CSP;
  if (!CSP_SAFE.test(csp) || CSP_FORBIDDEN.test(csp)) return APP_FRAME_CSP;
  const directives = csp.split(";").map((d) => d.trim());
  const need = ["default-src 'none'", "object-src 'none'", "form-action 'none'"];
  for (const n of need) if (!directives.includes(n)) return APP_FRAME_CSP;
  // script/style stay inline-only plus explicit https hosts; never a bare scheme or wildcard source.
  for (const d of directives) {
    const [name, ...sources] = d.split(" ");
    if (!name) continue;
    if (name === "script-src" || name === "style-src" || name === "connect-src" || name === "frame-src" || name === "base-uri") {
      for (const src of sources) {
        const ok = src === "'unsafe-inline'" || src === "'none'" || /^https:\/\/[a-z0-9.-]+(:[0-9]{1,5})?$/i.test(src);
        if (!ok) return APP_FRAME_CSP;
      }
    }
  }
  return csp;
}

/** Wrap the app html so our CSP meta is parsed before any app content. */
export function buildAppDoc(rawHtml: string, csp?: string): string {
  const html = stripMetaRefresh(rawHtml);
  return (
    `<!doctype html><html><head><meta charset="utf-8">` +
    `<meta http-equiv="Content-Security-Policy" content="${effectiveAppCsp(csp)}">` +
    `<meta name="referrer" content="no-referrer">` +
    `<meta name="viewport" content="width=device-width,initial-scale=1">` +
    `</head><body>${html}</body></html>`
  );
}

/** The ONLY sandbox flags the frame may carry. Never add allow-same-origin
 * (would hand the frame our origin, cookies and storage) or allow-top-
 * navigation / allow-popups / allow-forms / allow-modals. */
export const APP_FRAME_SANDBOX = "allow-scripts";
