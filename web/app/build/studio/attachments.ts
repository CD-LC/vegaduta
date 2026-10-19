// Files the person attaches to a change request in the Studio composer.
//
// Images cannot ride inside the model prompt (a data URL would eat the whole
// context), so the model only ever sees a short placeholder token and the
// real bytes are swapped in AFTER the edit is applied. Text-like files are
// small enough to quote into the prompt directly.

export type Attachment = {
  id: string;
  name: string;
  kind: "image" | "text";
  /** Downscaled data URL (images only). */
  dataUrl?: string;
  /** File contents, truncated (text only). */
  text?: string;
};

export const MAX_ATTACHMENTS = 4;
const MAX_TEXT_CHARS = 12_000;
const MAX_IMAGE_EDGE = 640;
const MAX_FILE_BYTES = 8 * 1024 * 1024;

export const ATTACH_ACCEPT = "image/*,.txt,.md,.csv,.json,.html,.css,.js,.ts,.xml,.yaml,.yml";

const TEXT_EXT = /\.(txt|md|csv|json|html?|css|jsx?|tsx?|xml|ya?ml)$/i;

function readAsDataUrl(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(new Error("Couldn't read that file."));
    r.readAsDataURL(file);
  });
}

async function downscale(file: File): Promise<string> {
  const src = await readAsDataUrl(file);
  const img = new Image();
  await new Promise<void>((ok, bad) => {
    img.onload = () => ok();
    img.onerror = () => bad(new Error("That image couldn't be opened."));
    img.src = src;
  });
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(img.width, img.height));
  if (scale === 1 && file.size < 150_000) return src;
  const c = document.createElement("canvas");
  c.width = Math.max(1, Math.round(img.width * scale));
  c.height = Math.max(1, Math.round(img.height * scale));
  const ctx = c.getContext("2d");
  if (!ctx) return src;
  ctx.drawImage(img, 0, 0, c.width, c.height);
  // PNGs may be transparent logos - keep those lossless, photos go JPEG.
  return file.type === "image/png" ? c.toDataURL("image/png") : c.toDataURL("image/jpeg", 0.82);
}

/** Turn a picked file into an Attachment, or throw a message fit to show. */
export async function readAttachment(file: File): Promise<Attachment> {
  if (file.size > MAX_FILE_BYTES) throw new Error(`${file.name} is over 8 MB.`);
  const id = Math.random().toString(36).slice(2, 8);
  if (file.type.startsWith("image/")) {
    return { id, name: file.name, kind: "image", dataUrl: await downscale(file) };
  }
  if (file.type.startsWith("text/") || file.type === "application/json" || TEXT_EXT.test(file.name)) {
    const text = (await file.text()).slice(0, MAX_TEXT_CHARS);
    return { id, name: file.name, kind: "text", text };
  }
  throw new Error(`${file.name}: attach an image or a text file (txt, csv, json, md, html…).`);
}

export const assetToken = (a: Attachment) => `{{asset:${a.id}}}`;

/** The request the model sees: the person's words plus what was attached. */
export function withAttachments(request: string, list: Attachment[]): string {
  if (list.length === 0) return request;
  const parts = [request];
  for (const a of list) {
    if (a.kind === "image") {
      parts.push(
        `ATTACHED IMAGE "${a.name}": use it wherever the request calls for it as <img src="${assetToken(a)}" alt="..."> - write that exact token as the src (it is replaced with the real image afterwards). Never invent another image URL.`
      );
    } else {
      parts.push(`ATTACHED FILE "${a.name}" (use as data/reference for the request):\n${a.text}`);
    }
  }
  return parts.join("\n\n");
}

/** Swap the placeholder tokens for the real image data in the finished app. */
export function resolveAssets(html: string, list: Attachment[]): string {
  let out = html;
  for (const a of list) {
    if (a.kind === "image" && a.dataUrl) out = out.split(assetToken(a)).join(a.dataUrl);
  }
  return out;
}
