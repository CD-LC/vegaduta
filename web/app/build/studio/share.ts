// Share links for studio apps - serverless. The whole app is deflate-
// compressed into the URL FRAGMENT (#app=...), which browsers never send to
// a server: nothing is uploaded, stored or logged anywhere. The link opens
// public/run-app.html, which unpacks it into a sandboxed frame and keeps a
// copy in that phone's own app library (installable as a home-screen app).
//
// Trade-off, stated honestly in the UI: a QR code holds ~2.9 KB, so only
// small apps fit in one; bigger apps share as a link (WhatsApp, copy, native
// share sheet) or a downloaded .html file.

import { QR_QUIET, qrPath } from "../../lib/qr";

export const RUNNER_PATH = "/run-app.html";
/** Longest URL we render as a QR (version 40, EC level L, byte mode ~2953). */
export const QR_MAX_URL = 2800;

function toBase64Url(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function pipe(bytes: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const out = new Blob([bytes as BlobPart]).stream().pipeThrough(stream);
  return new Uint8Array(await new Response(out).arrayBuffer());
}

export async function packApp(html: string): Promise<string> {
  const raw = new TextEncoder().encode(html);
  return toBase64Url(await pipe(raw, new CompressionStream("deflate-raw")));
}

export async function unpackApp(packed: string): Promise<string> {
  const bytes = await pipe(fromBase64Url(packed), new DecompressionStream("deflate-raw"));
  return new TextDecoder().decode(bytes);
}

export async function shareUrl(origin: string, html: string, name: string): Promise<string> {
  const packed = await packApp(html);
  return `${origin}${RUNNER_PATH}#n=${encodeURIComponent(name.slice(0, 60))}&app=${packed}`;
}

/** QR for the share link as an SVG string, built from the vendored encoder
 * (lib/qr.ts) - no third-party script load. The markup is assembled here from
 * numbers only (module count + path commands), so it is safe to inject. */
export async function qrSvg(text: string): Promise<string | null> {
  if (text.length > QR_MAX_URL) return null;
  try {
    const { size, d } = qrPath(text, "L");
    const box = size + 2 * QR_QUIET;
    return (
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${box} ${box}" shape-rendering="crispEdges">` +
      `<rect width="${box}" height="${box}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`
    );
  } catch {
    return null;
  }
}

export function downloadHtml(html: string, name: string): void {
  const blob = new Blob([html], { type: "text/html" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${name.replace(/[^a-z0-9-_ ]/gi, "").trim().replace(/\s+/g, "-").toLowerCase() || "my-app"}.html`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}
