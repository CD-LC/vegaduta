// Locale → on-screen keyboard layout, backed by simple-keyboard-layouts'
// per-language data files (lazy-loaded so a page pays for one layout, never
// 60). The map is deliberately small and honest:
//
// - Latin locales map too — accented characters on an English physical
//   keyboard are exactly the case an on-screen board is for.
// - Several Indic locales share one script: Devanagari (hi, mr, ne, kok, doi,
//   mai, brx, sa) uses the Hindi layout, Bengali-script Meitei the Bengali
//   layout, Perso-Arabic Kashmiri the Urdu layout.
// - Locales with no usable layout answer `null` — the toggle hides itself
//   rather than offering a board in the wrong script (ta, gu and sat ship no
//   Tamil/Gujarati/Ol-Chiki layout in the pack today; en has no need of one).
const LOCALE_LAYOUT: Record<string, string> = {
  ar: "arabic",
  as: "assamese",
  bn: "bengali",
  zh: "chinese",
  de: "german",
  es: "spanish",
  fr: "french",
  ja: "japanese",
  kn: "kannada",
  ml: "malayalam",
  or: "odia",
  pa: "punjabi",
  pt: "brazilian",
  sd: "sindhi",
  te: "telugu",
  ur: "urdu",
  hi: "hindi",
  mr: "hindi",
  ne: "hindi",
  kok: "hindi",
  doi: "hindi",
  mai: "hindi",
  brx: "hindi",
  sa: "hindi",
  ks: "urdu",
  mni: "bengali",
};

export interface KeyboardLayoutData {
  layout: { default?: string[]; shift?: string[] };
}

/** The layout module name for a locale, or null when no board exists. */
export function keyboardLayoutFor(locale: string): string | null {
  return LOCALE_LAYOUT[locale] ?? null;
}

/** Every locale that can show an on-screen keyboard (test + UI source). */
export const KEYBOARD_LOCALES = new Set(Object.keys(LOCALE_LAYOUT));

// Static loader map — a computed `import(".../" + name)` defeats bundler
// splitting, so each layout gets its own thunk and its own chunk.
type LayoutModule = { default: KeyboardLayoutData };
const LOADERS: Record<string, () => Promise<LayoutModule>> = {
  arabic: () => import("simple-keyboard-layouts/build/layouts/arabic"),
  assamese: () => import("simple-keyboard-layouts/build/layouts/assamese"),
  bengali: () => import("simple-keyboard-layouts/build/layouts/bengali"),
  brazilian: () => import("simple-keyboard-layouts/build/layouts/brazilian"),
  chinese: () => import("simple-keyboard-layouts/build/layouts/chinese"),
  french: () => import("simple-keyboard-layouts/build/layouts/french"),
  german: () => import("simple-keyboard-layouts/build/layouts/german"),
  hindi: () => import("simple-keyboard-layouts/build/layouts/hindi"),
  japanese: () => import("simple-keyboard-layouts/build/layouts/japanese"),
  kannada: () => import("simple-keyboard-layouts/build/layouts/kannada"),
  malayalam: () => import("simple-keyboard-layouts/build/layouts/malayalam"),
  odia: () => import("simple-keyboard-layouts/build/layouts/odia"),
  punjabi: () => import("simple-keyboard-layouts/build/layouts/punjabi"),
  sindhi: () => import("simple-keyboard-layouts/build/layouts/sindhi"),
  spanish: () => import("simple-keyboard-layouts/build/layouts/spanish"),
  telugu: () => import("simple-keyboard-layouts/build/layouts/telugu"),
  urdu: () => import("simple-keyboard-layouts/build/layouts/urdu"),
};

const cache = new Map<string, Promise<KeyboardLayoutData | null>>();

/** Fetch the layout for a locale (null when the locale has none). Cached:
 * the file is a static asset, so one fetch serves every open/close cycle. */
export function loadKeyboardLayout(locale: string): Promise<KeyboardLayoutData | null> {
  const name = keyboardLayoutFor(locale);
  if (!name) return Promise.resolve(null);
  const cached = cache.get(name);
  if (cached !== undefined) return cached;
  const loader = LOADERS[name];
  const pending: Promise<KeyboardLayoutData | null> = loader
    ? loader()
        .then((m): KeyboardLayoutData | null => m?.default ?? null)
        .catch((): null => null)
    : Promise.resolve(null);
  cache.set(name, pending);
  return pending;
}
