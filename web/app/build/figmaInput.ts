// Pure helpers for the Figma import dialog (no React, no network).

import type { DesignInput } from "./designInputs";
import type { AppBuilderImage } from "./lib";

export interface FigmaImportResult {
  context: string;
  image?: AppBuilderImage;
  file_key?: string;
  node_id?: string;
  name?: string;
}

/** Cheap client-side check so obviously wrong links fail before a request.
 * The server re-validates authoritatively. */
export function validateFigmaUrl(raw: string): string | null {
  let u: URL;
  try {
    u = new URL(raw.trim());
  } catch {
    return "Paste the link to a Figma frame.";
  }
  if (u.protocol !== "https:" || !["figma.com", "www.figma.com"].includes(u.hostname) || u.port !== "" || u.username !== "" || u.password !== "") {
    return "Link must be an https://www.figma.com URL.";
  }
  if (!/^\/(file|design)\/[A-Za-z0-9]{10,64}/.test(u.pathname)) {
    return "Link must point to a Figma file or design.";
  }
  if (!u.searchParams.get("node-id")) {
    return "In Figma, right-click a frame and choose Copy link to selection.";
  }
  return null;
}

/** Turn the server response into a DesignInput (same shape the attach bar
 * already consumes; the image rides along as a vision attachment). */
export function designInputFromFigma(res: FigmaImportResult, now = Date.now()): DesignInput {
  const label = res.name ? `Figma: ${res.name}` : "Figma frame";
  return {
    id: `figma-${now.toString(36)}`,
    kind: "image",
    label,
    contextText: res.context,
    image: res.image,
  };
}
