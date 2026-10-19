// Artifacts pane - the sandbox for HTML/JS previews and the SVG data-URL path.
//
// THREAT MODEL: the artifact body is model output, i.e. UNTRUSTED (it can be
// steered by a prompt-injected tool result or a hostile document). It must never
// be able to reach the console's origin, cookies, localStorage, tokens or DOM,
// never be able to phone home, and never run a script the person did not ask
// for. The controls, all enforced here as pure data so they are unit-tested:
//
//  1. iframe sandbox: "" (paused) or "allow-scripts" (after an explicit Run).
//     NEVER allow-same-origin (the frame would get our origin), allow-top-
//     navigation, allow-popups, allow-forms or allow-modals. With no
//     allow-same-origin the frame has an opaque origin: no cookies, no storage,
//     no parent DOM access.
//  2. A strict CSP <meta> injected FIRST in the document: default-src 'none';
//     script-src 'none' until Run, then 'unsafe-inline' only (no eval, no remote
//     script); style-src 'unsafe-inline'; img/media only data:/blob:;
//     connect-src 'none'; no frames, objects, <base> or form posts. So the page
//     can neither fetch nor load a subresource from anywhere.
//  3. srcdoc (not a URL): the document never has a network origin of its own.
//  4. Size cap (MAX_PREVIEW_BYTES) and "complete blocks only".
//  5. SVG is never inlined into the console DOM; it is shown through <img> with
//     a data: URL, where browsers run no script and load no external resource.
//
// RESIDUAL RISK (same as docs/plans/adr/mcp-apps-ui-resources.md): a meta CSP
// cannot stop the frame navigating ITSELF. Two kinds:
//  - ZERO-CLICK: <meta http-equiv=refresh> is governed by neither the sandbox nor
//    the CSP and would fire even in the paused state. It is closed here by
//    sanitizeArtifactHtml, which PARSES the body with DOMParser (inert: no script
//    runs, no resource loads) and removes every <meta>, <base> and <link> element
//    from the tree before re-serialising, then re-parses its own output and
//    refuses (fails closed) if any survived, or if DOMParser is unavailable. A
//    regex over the raw text is NOT used: entity-encoded attribute values
//    (re&#102;resh) and a ">" inside an earlier quoted attribute both defeat one.
//  - USER-INITIATED: a clicked <a href>, or location= after the person pressed
//    Run. The navigation stays inside the same sandbox (opaque origin, no
//    credentials, no access to us) and the frame holds no user data to leak, but
//    it can show a look-alike remote page inside the pane, and that remote page
//    carries NO CSP of ours. Not fixable from inside a srcdoc; the real fix is a
//    separate sandbox origin serving CSP response headers.
//
// PARENT CSP: a srcdoc frame INHERITS the console page's Content-Security-Policy
// in addition to the meta CSP above (the stricter of the two wins). Today the
// console's script-src/img-src/connect-src are report-only, so previews that use
// inline style or images only generate violation reports; once script-src is
// promoted to enforced WITHOUT 'unsafe-inline', the inherited policy will
// silently break the Run preview. Serve the preview from a separate sandbox
// origin (above) before promoting it.

import { MAX_PREVIEW_BYTES, utf8Length } from "./artifactTypes";

/** sandbox attribute while scripts are paused: every restriction on, nothing allowed. */
export const SANDBOX_PAUSED = "";
/** sandbox attribute after the person pressed "Run preview". The ONLY flag ever granted. */
export const SANDBOX_RUN = "allow-scripts";

/** Flags that must never appear in a sandbox attribute built by this module. */
export const FORBIDDEN_SANDBOX_FLAGS: readonly string[] = [
  "allow-same-origin",
  "allow-top-navigation",
  "allow-top-navigation-by-user-activation",
  "allow-top-navigation-to-custom-protocols",
  "allow-popups",
  "allow-popups-to-escape-sandbox",
  "allow-forms",
  "allow-modals",
  "allow-downloads",
  "allow-storage-access-by-user-activation",
  "allow-presentation",
  "allow-pointer-lock",
  "allow-orientation-lock",
];

export function artifactSandbox(run: boolean): string {
  return run ? SANDBOX_RUN : SANDBOX_PAUSED;
}

export function buildArtifactCsp(run: boolean): string {
  return [
    "default-src 'none'",
    run ? "script-src 'unsafe-inline'" : "script-src 'none'",
    "style-src 'unsafe-inline'",
    "img-src data: blob:",
    "font-src data:",
    "media-src data: blob:",
    "connect-src 'none'",
    "frame-src 'none'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
  ].join("; ");
}

export interface ArtifactFrameProps {
  sandbox: string;
  referrerPolicy: "no-referrer";
  allow: string;
  loading: "lazy";
}

/** Every iframe attribute that matters for isolation, in one place. */
export function artifactFrameProps(run: boolean): ArtifactFrameProps {
  return { sandbox: artifactSandbox(run), referrerPolicy: "no-referrer", allow: "", loading: "lazy" };
}

/** Elements removed from the parsed tree: none of them is needed to render an artifact, all can navigate or re-target the frame. */
const NAVIGATION_ELEMENTS = "meta, base, link";
const SAFE_ATTR_NAME = /^[A-Za-z][A-Za-z0-9:_-]{0,63}$/;

function escapeAttr(v: string): string {
  return v.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
}

export interface SanitizedArtifactHtml {
  /** head + body content, ready to place inside OUR body. */
  html: string;
  /** Attributes of the artifact's own <body> (class/style/...), already escaped, leading space included. */
  bodyAttrs: string;
}

/**
 * Remove every <meta>, <base> and <link> from the artifact by PARSING it (DOMParser: inert - no script
 * runs and no resource loads) rather than pattern-matching text, so entity-encoded attribute values and
 * quote tricks cannot hide a <meta http-equiv=refresh> (see RESIDUAL RISK). The output is re-parsed and
 * checked; null (fail closed - show nothing) when DOMParser is unavailable, parsing throws, or any such
 * element survives the round trip.
 */
export function sanitizeArtifactHtml(rawHtml: string): SanitizedArtifactHtml | null {
  if (typeof DOMParser === "undefined") return null;
  try {
    const doc = new DOMParser().parseFromString(String(rawHtml || ""), "text/html");
    doc.querySelectorAll(NAVIGATION_ELEMENTS).forEach((el) => el.remove());
    const html = doc.head.innerHTML + doc.body.innerHTML;
    let bodyAttrs = "";
    for (const attr of Array.from(doc.body.attributes)) {
      if (SAFE_ATTR_NAME.test(attr.name)) bodyAttrs += ` ${attr.name}="${escapeAttr(attr.value)}"`;
    }
    // Round-trip check: what the real iframe will parse must contain none of them either.
    const check = new DOMParser().parseFromString(`<!doctype html><html><head></head><body${bodyAttrs}>${html}</body></html>`, "text/html");
    if (check.querySelector(NAVIGATION_ELEMENTS)) return null;
    return { html, bodyAttrs };
  } catch {
    return null;
  }
}

/** Artifact markup with the navigation elements removed; "" when it cannot be sanitised (fail closed). */
export function stripNavigationHelpers(html: string): string {
  const clean = sanitizeArtifactHtml(html);
  return clean ? clean.html : "";
}

/** The full srcdoc: our CSP is parsed before any artifact byte. An unsanitisable body renders as an empty page. */
export function buildArtifactDoc(rawHtml: string, run: boolean): string {
  const clean = sanitizeArtifactHtml(String(rawHtml || ""));
  return (
    `<!doctype html><html><head><meta charset="utf-8">` +
    `<meta http-equiv="Content-Security-Policy" content="${buildArtifactCsp(run)}">` +
    `<meta name="referrer" content="no-referrer">` +
    `<meta name="viewport" content="width=device-width,initial-scale=1">` +
    `</head><body${clean ? clean.bodyAttrs : ""}>${clean ? clean.html : ""}</body></html>`
  );
}

export type PreviewReason = "empty" | "incomplete" | "truncated" | "too-large" | "unsafe" | "not-svg";
export interface PreviewVerdict {
  status: "ok" | "refused";
  reason?: PreviewReason;
}

const OK: PreviewVerdict = { status: "ok" };
const refuse = (reason: PreviewReason): PreviewVerdict => ({ status: "refused", reason });

/** May this HTML/CSV/Markdown body be previewed at all? Pure gate used by every Preview path. */
export function previewVerdict(content: string, complete: boolean, truncated: boolean): PreviewVerdict {
  if (!complete) return refuse("incomplete");
  if (truncated) return refuse("truncated");
  if (!content || content.trim() === "") return refuse("empty");
  if (utf8Length(content) > MAX_PREVIEW_BYTES) return refuse("too-large");
  return OK;
}

/** Verdict plus the SVG-specific refusals (not an <svg>, or DTD entity declarations). */
export function svgPreviewVerdict(content: string, complete: boolean, truncated: boolean): PreviewVerdict {
  const base = previewVerdict(content, complete, truncated);
  if (base.status !== "ok") return base;
  if (!/<svg[\s>]/i.test(content)) return refuse("not-svg");
  // Internal-entity bombs and external entities have no place in a model-written image.
  if (/<!ENTITY/i.test(content)) return refuse("unsafe");
  return OK;
}

/**
 * data: URL for showing an SVG through <img>. Returns null when the SVG is
 * refused. The URL is built HERE from the body (it is not a model-supplied
 * URL), which is why it does not go through SafeModelImage's host gate;
 * safeImageUrl.isTrustedImageUrl accepts data:image/ for the same reason.
 */
export function svgDataUrl(content: string, complete = true, truncated = false): string | null {
  if (svgPreviewVerdict(content, complete, truncated).status !== "ok") return null;
  let svg = content.trim();
  const root = /<svg\b[^>]*>/i.exec(svg);
  if (root && !/\sxmlns\s*=/.test(root[0])) {
    svg = svg.slice(0, root.index) + root[0].replace(/<svg\b/i, '<svg xmlns="http://www.w3.org/2000/svg"') + svg.slice(root.index + root[0].length);
  }
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}
