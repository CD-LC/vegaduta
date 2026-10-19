// Design-input helpers for the /build console — turn whatever the user
// drops in (logo, screenshot, hand-drawn wireframe photo, Figma export,
// the SketchPad's block layout) into TEXT the model can consume plus
// files the repo can keep.
//
// The free-first rule (maintainer decision: "leverage all the technologies we have
// free... so user doesn't have to pay"):
//   * SVG markup IS the design — fed to the text model verbatim, zero
//     vision cost, works even on a 1B on-device model.
//   * Raster images get a client-side canvas palette extraction (free,
//     instant) that goes into the context as hex colors; the pixels
//     themselves only travel to a vision-capable HOSTED model — never to
//     the on-device path (WebLLM's text models can't see them anyway).
//   * The SketchPad's block layout serializes to a plain-text wireframe
//     spec — again just text.
//
// All functions are browser-side only and never throw (callers render a
// soft error instead).

import type { AppBuilderImage } from "./lib";

const MAX_IMAGE_EDGE = 1024; // downscale bound for vision attachments
const MAX_IMAGE_BYTES = 3 * 1024 * 1024; // post-encode cap (~4MB base64)
const MAX_SVG_CONTEXT_CHARS = 8000;
// Input caps, checked BEFORE the file is read/decoded so a huge drop cannot
// freeze the tab.
export const MAX_SVG_FILE_BYTES = 2 * 1024 * 1024;
export const MAX_RASTER_FILE_BYTES = 20 * 1024 * 1024;

export interface DesignInput {
  /** Stable id for list keys/removal. */
  id: string;
  kind: "image" | "svg" | "sketch" | "note";
  /** Display name (filename or "Wireframe sketch"). */
  label: string;
  /** Text contributed to the generation context. */
  contextText: string;
  /** For raster images only: ready-to-send vision attachment. */
  image?: AppBuilderImage;
  /** Object URL for the thumbnail strip. */
  previewUrl?: string;
}

/** Hex-encode an rgb triple. */
function hex(r: number, g: number, b: number): string {
  const c = (v: number) => v.toString(16).padStart(2, "0");
  return `#${c(r)}${c(g)}${c(b)}`;
}

/**
 * Dominant-palette extraction via a downscaled canvas — simple
 * median-cut-lite: quantize to 4-bit buckets, take the 5 most populous.
 * Deliberately skips near-white/near-black/near-gray so the palette lands
 * on the DESIGN colors, not the page chrome. Free and instant.
 */
export function extractPalette(img: HTMLImageElement): string[] {
  const canvas = document.createElement("canvas");
  const w = 64;
  const h = Math.max(1, Math.round((img.naturalHeight / Math.max(1, img.naturalWidth)) * w));
  canvas.width = w;
  canvas.height = h;
  const ctx2d = canvas.getContext("2d");
  if (!ctx2d) return [];
  ctx2d.drawImage(img, 0, 0, w, h);
  let data: Uint8ClampedArray;
  try {
    data = ctx2d.getImageData(0, 0, w, h).data;
  } catch {
    return []; // tainted canvas (cross-origin) — no palette
  }
  const buckets = new Map<string, number>();
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i], g = data[i + 1], b = data[i + 2], a = data[i + 3];
    if (a < 128) continue;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    if (max - min < 16) continue; // gray-ish — skip
    const key = hex(r >> 4 << 4 | 8, g >> 4 << 4 | 8, b >> 4 << 4 | 8);
    buckets.set(key, (buckets.get(key) || 0) + 1);
  }
  return [...buckets.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([color]) => color);
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      // Decoded pixels stay with the element; the temporary URL is done.
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("unreadable image"));
    };
    img.src = url;
  });
}

/** Downscale + re-encode a raster file as a vision attachment. */
async function toImageAttachment(
  img: HTMLImageElement,
  name: string
): Promise<AppBuilderImage | undefined> {
  const scale = Math.min(
    1,
    MAX_IMAGE_EDGE / Math.max(img.naturalWidth, img.naturalHeight)
  );
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
  const ctx2d = canvas.getContext("2d");
  if (!ctx2d) return undefined;
  // JPEG has no alpha: paint white first so transparent logos don't go black.
  ctx2d.fillStyle = "#fff";
  ctx2d.fillRect(0, 0, canvas.width, canvas.height);
  ctx2d.drawImage(img, 0, 0, canvas.width, canvas.height);
  const blob: Blob | null = await new Promise((r) =>
    canvas.toBlob(r, "image/jpeg", 0.82)
  );
  if (!blob || blob.size > MAX_IMAGE_BYTES) return undefined;
  const buf = await blob.arrayBuffer();
  let bin = "";
  new Uint8Array(buf).forEach((byte) => (bin += String.fromCharCode(byte)));
  return { name, media_type: "image/jpeg", data_base64: btoa(bin) };
}

/** Release the object URL a DesignInput holds for its thumbnail. Call when an
 * attachment is removed or the strip is cleared; safe to call twice. */
export function revokeDesignInput(input: Pick<DesignInput, "previewUrl"> | null | undefined): void {
  if (input?.previewUrl && input.previewUrl.startsWith("blob:")) URL.revokeObjectURL(input.previewUrl);
}

export function revokeDesignInputs(inputs: Pick<DesignInput, "previewUrl">[]): void {
  inputs.forEach(revokeDesignInput);
}

/** Context line for a raster reference. "attached" is only claimed when the
 * pixels really travel with the request. */
export function imageContextText(name: string, palette: string[], attached: boolean): string {
  const paletteNote = palette.length ? ` Dominant palette: ${palette.join(", ")}.` : "";
  const head = attached
    ? `Design reference "${name}" attached.`
    : `Design reference "${name}" was provided but the image itself could not be sent (too large or unreadable), so only its colours are known.`;
  return `${head}${paletteNote}`.trim();
}

/**
 * Turn a dropped/selected file into a DesignInput.
 *  - .svg  -> text path: markup becomes context (Figma "Export as SVG"),
 *             also previewed.
 *  - raster-> palette context + optional vision attachment + preview.
 * Returns null for unsupported types.
 */
export async function designInputFromFile(file: File): Promise<DesignInput | null> {
  const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
  const lower = file.name.toLowerCase();

  if (lower.endsWith(".svg") || file.type === "image/svg+xml") {
    if (file.size > MAX_SVG_FILE_BYTES) return null;
    let text: string;
    try {
      text = await file.text();
    } catch {
      return null;
    }
    return {
      id,
      kind: "svg",
      label: file.name,
      contextText:
        `SVG design "${file.name}" — reproduce its layout/look:\n` +
        text.slice(0, MAX_SVG_CONTEXT_CHARS),
      previewUrl: URL.createObjectURL(file),
    };
  }

  if (file.type.startsWith("image/")) {
    if (file.size > MAX_RASTER_FILE_BYTES) return null;
    try {
      const img = await loadImage(file);
      const palette = extractPalette(img);
      const image = await toImageAttachment(img, file.name);
      return {
        id,
        kind: "image",
        label: file.name,
        contextText: imageContextText(file.name, palette, !!image),
        image,
        previewUrl: URL.createObjectURL(file),
      };
    } catch {
      return null;
    }
  }

  return null;
}

/** Serialize the SketchPad's block list into a wireframe spec the model
 * reads as layout intent. */
export function serializeSketch(blocks: { type: string; label: string }[]): string {
  if (!blocks.length) return "";
  const lines = blocks.map(
    (b, i) => `${i + 1}. ${b.type}${b.label ? ` — "${b.label}"` : ""}`
  );
  return `WIREFRAME (top to bottom):\n${lines.join("\n")}`;
}

/** Compose every attached input's contextText into one DESIGN CONTEXT
 * block (what goes to `context` on /generate and the edge prompt). */
export function composeContext(inputs: DesignInput[], assetNames: string[]): string {
  const parts: string[] = [];
  if (assetNames.length) {
    parts.push(
      "Uploaded assets available under assets/ (reference them by path " +
        `in <img>/CSS where appropriate): ${assetNames.join(", ")}`
    );
  }
  for (const input of inputs) {
    if (input.contextText) parts.push(input.contextText);
  }
  return parts.join("\n\n");
}
