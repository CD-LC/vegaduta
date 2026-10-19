// Client-side helpers for AI image generation. The server (app-builder
// imagegen.py) owns the real per-kind prompt scaffolding; this module only
// holds the kind list, size defaults, input validation, and the chat
// instruction offered after an image lands as an asset.

export const IMAGE_KINDS = ["logo", "hero", "icon", "illustration"] as const;
export type ImageKind = (typeof IMAGE_KINDS)[number];

export const IMAGE_SIZES = ["1024x1024", "1024x1536", "1536x1024"] as const;
export const MAX_IMAGE_PROMPT_CHARS = 1000;

export const KIND_LABELS: Record<ImageKind, string> = {
  logo: "Logo",
  hero: "Hero image",
  icon: "Icon",
  illustration: "Illustration",
};

const DEFAULT_SIZE: Record<ImageKind, string> = {
  logo: "1024x1024",
  icon: "1024x1024",
  hero: "1536x1024",
  illustration: "1024x1024",
};

export function defaultSizeFor(kind: ImageKind): string {
  return DEFAULT_SIZE[kind];
}

/** Returns an error message, or null when the request is sendable. */
export function validateImageRequest(prompt: string, kind: string): string | null {
  const p = (prompt || "").replace(/\s+/g, " ").trim();
  if (!p) return "Describe the image you want.";
  if (p.length > MAX_IMAGE_PROMPT_CHARS) return `Keep the description under ${MAX_IMAGE_PROMPT_CHARS} characters.`;
  if (!(IMAGE_KINDS as readonly string[]).includes(kind)) return "Pick an image type.";
  return null;
}

/** Chat instruction that tells the builder how to use a generated asset. */
export function assetUsageInstruction(kind: ImageKind, assetPath: string): string {
  switch (kind) {
    case "logo":
      return `Use ${assetPath} as the app logo in the header (and as the favicon if it fits).`;
    case "hero":
      return `Use ${assetPath} as the hero image on the home page, keeping the headline readable over it.`;
    case "icon":
      return `Use ${assetPath} as the app icon.`;
    default:
      return `Add ${assetPath} as an illustration where it fits the page best.`;
  }
}
