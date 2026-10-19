// WhatsApp approved-template picker, the pure half (nlflow W6).
//
// An Output node on the WhatsApp channel can carry, next to the recipient:
//   whatsappMessage         text to send; blank = the previous step's output
//   whatsappTemplate        { name, language } of a template Meta approved
//   whatsappTemplateParams  values for the template's {{1}}..{{n}}, in order
// Outside Meta's 24-hour customer-service window the official channel only
// delivers an approved template, which is why a scheduled reminder needs one.
//
// GET /api/channels/whatsapp/templates lists the tenant's APPROVED templates.
// It answers 404 while the feature is switched off on the server (the picker
// then does not render at all), 403 without the Manage channels permission,
// 400 when Meta refuses the listing, and [] when Meta Cloud API is not the
// connected WhatsApp provider.
//
// Pure module: no React, no runtime imports.

export type WhatsAppTemplate = {
  name: string;
  language: string;
  bodyParamCount: number;
  preview: string;
  category: string | null;
};

export type WhatsAppTemplateRef = { name: string; language: string };

/** Meta allows far fewer body variables in practice; this only bounds a hostile/garbled count. */
export const WHATSAPP_TEMPLATE_MAX_PARAMS = 20;

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const nonBlank = (v: unknown): v is string => typeof v === "string" && v.trim() !== "";

/** Defensive parse of the listing; anything without a name and a language is dropped. */
export function parseWhatsAppTemplates(raw: unknown): WhatsAppTemplate[] {
  if (!Array.isArray(raw)) return [];
  const out: WhatsAppTemplate[] = [];
  const seen = new Set<string>();
  for (const item of raw) {
    if (!isObj(item) || !nonBlank(item.name) || !nonBlank(item.language)) continue;
    const template: WhatsAppTemplate = {
      name: item.name.trim(),
      language: item.language.trim(),
      bodyParamCount: clampParamCount(item.bodyParamCount),
      preview: typeof item.preview === "string" ? item.preview : "",
      category: nonBlank(item.category) ? item.category.trim() : null,
    };
    const key = templateKey(template);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(template);
  }
  return out;
}

function clampParamCount(raw: unknown): number {
  const n = typeof raw === "number" && Number.isFinite(raw) ? Math.floor(raw) : 0;
  return Math.max(0, Math.min(n, WHATSAPP_TEMPLATE_MAX_PARAMS));
}

// A template name is lower-case letters, digits and underscores, a language is
// like "en_US"; neither can contain a newline, so it is a safe separator.
const KEY_SEPARATOR = "\n";

/** One <select> value per (name, language) - the same name exists once per language. */
export function templateKey(ref: WhatsAppTemplateRef): string {
  return `${ref.name}${KEY_SEPARATOR}${ref.language}`;
}

export function parseTemplateKey(key: unknown): WhatsAppTemplateRef | null {
  if (typeof key !== "string") return null;
  const at = key.indexOf(KEY_SEPARATOR);
  if (at <= 0 || at === key.length - 1) return null;
  return { name: key.slice(0, at), language: key.slice(at + 1) };
}

/** The node's stored template, or null for absent / {} / half-filled. */
export function selectedTemplate(config: { whatsappTemplate?: unknown } | null | undefined): WhatsAppTemplateRef | null {
  const raw = config?.whatsappTemplate;
  if (!isObj(raw) || !nonBlank(raw.name) || !nonBlank(raw.language)) return null;
  return { name: raw.name.trim(), language: raw.language.trim() };
}

export function findTemplate(templates: readonly WhatsAppTemplate[], ref: WhatsAppTemplateRef | null): WhatsAppTemplate | null {
  if (!ref) return null;
  return templates.find((t) => t.name === ref.name && t.language === ref.language) ?? null;
}

/**
 * The params array for a template with {@code count} body variables: existing
 * values keep their position, missing ones are "", extras are cut. Always a
 * new array of strings.
 */
export function resizeTemplateParams(params: unknown, count: number): string[] {
  const current = Array.isArray(params) ? params : [];
  const size = clampParamCount(count);
  return Array.from({ length: size }, (_, i) => (typeof current[i] === "string" ? (current[i] as string) : ""));
}

/** params with index i replaced; grows with "" when i is past the end. */
export function setTemplateParam(params: unknown, index: number, value: string): string[] {
  const current = Array.isArray(params) ? params : [];
  const size = Math.max(current.length, index + 1);
  const next = Array.from({ length: size }, (_, i) => (typeof current[i] === "string" ? (current[i] as string) : ""));
  next[index] = value;
  return next;
}

/** Config patch for picking a template: the ref plus params sized to its variables. */
export function templateConfigPatch(
  template: WhatsAppTemplate,
  currentParams: unknown,
): { whatsappTemplate: WhatsAppTemplateRef; whatsappTemplateParams: string[] } {
  return {
    whatsappTemplate: { name: template.name, language: template.language },
    whatsappTemplateParams: resizeTemplateParams(currentParams, template.bodyParamCount),
  };
}

/**
 * Config patch for "no template". undefined (not {} or []) so the keys drop
 * out of the saved JSON and the node sends plain text exactly as before.
 */
export function clearTemplateConfigPatch(): { whatsappTemplate: undefined; whatsappTemplateParams: undefined } {
  return { whatsappTemplate: undefined, whatsappTemplateParams: undefined };
}

export type TemplateLoadState =
  | { status: "loading" }
  /** Feature off on this server (404): render no template UI at all. */
  | { status: "hidden" }
  /** Caller lacks Manage channels (403). */
  | { status: "forbidden" }
  /** Meta refused the listing or the request failed; message is the server's. */
  | { status: "error"; message: string }
  | { status: "ready"; templates: WhatsAppTemplate[] };

/** Maps a rejected listing call onto what the picker shows. */
export function templateLoadFailure(err: unknown): TemplateLoadState {
  const e = isObj(err) ? err : {};
  if (e.status === 404) return { status: "hidden" };
  if (e.status === 403) return { status: "forbidden" };
  return { status: "error", message: nonBlank(e.message) ? e.message : "" };
}
