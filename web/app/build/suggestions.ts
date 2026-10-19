// Pure helpers for the chat composer: suggestion chips, diff line
// classification and dictation language. No React, no network.

export interface Suggestion {
  id: string;
  label: string;
  prompt: string;
}

interface Ctx {
  template: string;
  names: string;
  web: boolean;
  has: (re: RegExp) => boolean;
}

const ALL: (Suggestion & { when?: (ctx: Ctx) => boolean })[] = [
  {
    id: "dark-mode",
    label: "Add dark mode",
    prompt: "Add a dark mode with a toggle in the header that remembers the choice and respects the system setting.",
    when: (c) => !c.has(/dark/i),
  },
  {
    id: "contact-form",
    label: "Add a contact form",
    prompt: "Add a contact form (name, email, message) with validation, a clear success message, and accessible labels.",
    when: (c) => !c.has(/contact/i),
  },
  {
    id: "responsive",
    label: "Make it responsive",
    prompt: "Make the layout fully responsive for phones, tablets and desktops, with comfortable tap targets.",
  },
  {
    id: "animations",
    label: "Add subtle animations",
    prompt: "Add subtle, tasteful animations (fade-ins, hover states) and respect prefers-reduced-motion.",
    when: (c) => !c.has(/anim|motion|transition/i),
  },
  {
    id: "accessibility",
    label: "Improve accessibility",
    prompt: "Improve accessibility: semantic landmarks, labels, focus styles and sufficient colour contrast.",
  },
  {
    id: "seo",
    label: "Add SEO basics",
    prompt: "Add SEO basics: a descriptive title, meta description, Open Graph tags and a favicon.",
    when: (c) => c.web && !c.has(/seo|og:/i),
  },
  {
    id: "offline",
    label: "Work offline",
    prompt: "Make the app work offline with a service worker and a friendly offline message.",
    when: (c) => c.web && !c.has(/sw\.js|service-?worker|manifest/i),
  },
  {
    id: "empty-states",
    label: "Polish empty states",
    prompt: "Add friendly empty, loading and error states throughout the app.",
    when: (c) => !c.template.includes("landing"),
  },
];

/** Up to `limit` chips for the template + the project's file names, hiding any
 * whose idea the project already seems to contain or that were already asked. */
export function suggestionsFor(
  templateId: string | null | undefined,
  paths: string[] | null | undefined,
  asked: string[] = [],
  limit = 4
): Suggestion[] {
  const names = (paths || []).join("\n");
  const template = String(templateId || "").toLowerCase();
  const ctx: Ctx = {
    template,
    names,
    web: !/capacitor|android|ios|native/.test(template),
    has: (re) => re.test(names),
  };
  const done = new Set(asked.map((a) => a.trim().toLowerCase()));
  return ALL.filter((s) => !done.has(s.prompt.toLowerCase()) && (!s.when || s.when(ctx)))
    .slice(0, limit)
    .map(({ id, label, prompt }) => ({ id, label, prompt }));
}

export type DiffLineKind = "add" | "del" | "hunk" | "meta" | "ctx";

/** Classify a unified diff for +/- colouring. Caps the number of lines. */
export function parseDiff(
  diff: string,
  maxLines = 400
): { lines: { kind: DiffLineKind; text: string }[]; truncated: boolean } {
  const raw = String(diff || "").replace(/\r\n/g, "\n").split("\n");
  if (raw.length && raw[raw.length - 1] === "") raw.pop();
  const truncated = raw.length > maxLines;
  const lines = raw.slice(0, maxLines).map((text) => {
    let kind: DiffLineKind = "ctx";
    if (/^(diff --git|index |--- |\+\+\+ |new file|deleted file|similarity|rename )/.test(text)) kind = "meta";
    else if (text.startsWith("@@")) kind = "hunk";
    else if (text.startsWith("+")) kind = "add";
    else if (text.startsWith("-")) kind = "del";
    return { kind, text };
  });
  return { lines, truncated };
}

/** Web Speech API language tag from <html lang>. Hindi dictates as hi-IN. */
export function speechLang(lang: string | null | undefined): string {
  const l = String(lang || "").trim().replace("_", "-");
  if (!l) return "en-US";
  if (l.includes("-")) return l;
  const base = l.toLowerCase();
  const map: Record<string, string> = {
    en: "en-US",
    hi: "hi-IN",
    es: "es-ES",
    fr: "fr-FR",
    de: "de-DE",
    pt: "pt-BR",
    ta: "ta-IN",
    te: "te-IN",
    kn: "kn-IN",
    mr: "mr-IN",
    bn: "bn-IN",
    gu: "gu-IN",
    ja: "ja-JP",
    zh: "zh-CN",
  };
  return map[base] || base;
}
