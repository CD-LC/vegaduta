// Plain constants only, no "use client" directive - shared between
// proxy.js (Node.js runtime, outside the React tree entirely; formerly
// middleware.js on the Edge runtime) and i18n.js (a client component
// module). Keeping these in a directive-free file avoids any ambiguity
// about a client-boundary module being imported into proxy.js.
// Shape of one entry in SUPPORTED_LOCALES.
export interface LocaleDef {
  code: string;
  label: string;
  dir: "ltr" | "rtl";
}

export const SUPPORTED_LOCALES: LocaleDef[] = [
  { code: "en", label: "English", dir: "ltr" },
  { code: "es", label: "Español", dir: "ltr" },
  { code: "fr", label: "Français", dir: "ltr" },
  { code: "de", label: "Deutsch", dir: "ltr" },
  { code: "pt", label: "Português", dir: "ltr" },
  { code: "ar", label: "العربية", dir: "rtl" },
  { code: "zh", label: "中文", dir: "ltr" },
  { code: "ja", label: "日本語", dir: "ltr" },
  // All 22 languages of the Indian Constitution's Eighth Schedule (direct
  // answer to "add all indian language support in this application" -
  // previously only Hindi existed here). Grouped together rather than
  // interleaved so this block can be found/audited as one unit. Kashmiri,
  // Sindhi and Urdu are conventionally written in Perso-Arabic script (dir:
  // "rtl") in India; the rest use their own Brahmic-family scripts (Odia,
  // Santali's Ol Chiki, etc.) and all read left-to-right. Translation
  // *quality* from the weekly LLM job (see translate-messages.mjs) will
  // vary by language - lower-resource languages here (Bodo, Dogri, Santali)
  // are known to be weaker for most general-purpose LLMs than Hindi/Bengali/
  // Tamil - that's a real, disclosed limitation, not a claim of uniform quality.
  { code: "hi", label: "हिन्दी", dir: "ltr" }, // Hindi
  { code: "bn", label: "বাংলা", dir: "ltr" }, // Bengali
  { code: "mr", label: "मराठी", dir: "ltr" }, // Marathi
  { code: "te", label: "తెలుగు", dir: "ltr" }, // Telugu
  { code: "ta", label: "தமிழ்", dir: "ltr" }, // Tamil
  { code: "gu", label: "ગુજરાતી", dir: "ltr" }, // Gujarati
  { code: "ur", label: "اردو", dir: "rtl" }, // Urdu
  { code: "kn", label: "ಕನ್ನಡ", dir: "ltr" }, // Kannada
  { code: "or", label: "ଓଡ଼ିଆ", dir: "ltr" }, // Odia
  { code: "ml", label: "മലയാളം", dir: "ltr" }, // Malayalam
  { code: "pa", label: "ਪੰਜਾਬੀ", dir: "ltr" }, // Punjabi
  { code: "as", label: "অসমীয়া", dir: "ltr" }, // Assamese
  { code: "mai", label: "मैथिली", dir: "ltr" }, // Maithili
  { code: "sat", label: "ᱥᱟᱱᱛᱟᱲᱤ", dir: "ltr" }, // Santali (Ol Chiki)
  { code: "ks", label: "كٲشُر", dir: "rtl" }, // Kashmiri
  { code: "ne", label: "नेपाली", dir: "ltr" }, // Nepali
  { code: "kok", label: "कोंकणी", dir: "ltr" }, // Konkani
  { code: "sd", label: "سنڌي", dir: "rtl" }, // Sindhi
  { code: "doi", label: "डोगरी", dir: "ltr" }, // Dogri
  { code: "mni", label: "মৈতৈলোন্", dir: "ltr" }, // Manipuri (Meitei)
  { code: "brx", label: "बड़ो", dir: "ltr" }, // Bodo
  { code: "sa", label: "संस्कृतम्", dir: "ltr" }, // Sanskrit
];

export const DEFAULT_LOCALE: string = "en";
export const LOCALE_COOKIE: string = "NEXT_LOCALE";

// ---------------------------------------------------------------------------
// Multilingual SEO (2026-09-19) — locale-prefixed URLs + hreflang.
//
// This replaces the 2026-07-30 audit note that stood here and ruled hreflang
// out. Its two blockers, and what happened to each:
//   1. "Locale is cookie-based, every language renders at the SAME URL, and
//      hreflang needs distinct URLs." Still true of the cookie path, which is
//      unchanged — so this pass ADDS a URL per language instead: "/hi",
//      "/hi/billing". proxy.js strips the prefix and rewrites to the existing
//      route with the locale pinned, so no file moved under app/[locale]/ and
//      every pre-existing URL behaves exactly as before.
//   2. "Coverage is under 14%." Stale since the 2026-09-12 pipeline run:
//      every locale file is now 95-98% of en.json's keys.
//
// Both gates below are deliberate and each guards a different Google rule.
// Widen them only when the condition in the comment actually becomes true.
// ---------------------------------------------------------------------------

// Locales that get their own indexable URLs and hreflang entries. English is
// not listed: it lives at the unprefixed URL and is always indexable.
//
// Two conditions, both required:
//   a. A two-letter ISO 639-1 code. Google's hreflang spec accepts nothing
//      else, which rules out mai/sat/kok/doi/mni/brx (639-2/3 only) no matter
//      how good their translations are.
//   b. Translation quality we are willing to put in front of a search engine.
//      Google's scaled-content-abuse policy names unreviewed machine
//      translation used to multiply pages, and SUPPORTED_LOCALES' own comment
//      discloses that the lowest-resource languages are weak. ks/sd/sa are
//      held back on that ground.
// A held-back locale loses nothing it had: it still works through the
// language switcher and the NEXT_LOCALE cookie exactly as before. It just has
// no URL of its own and is never advertised to crawlers.
export const INDEXABLE_LOCALES: string[] = [
  "es", "fr", "de", "pt", "ar", "zh", "ja",
  "hi", "bn", "mr", "te", "ta", "gu", "ur", "kn", "or", "ml", "pa", "as", "ne",
];

// Routes whose BODY copy actually comes from messages/<locale>.json. Exact
// paths, no sub-paths ("/help" does not cover "/help/workflow-recipes").
//
// Most of the crawlable funnel is NOT here on purpose: /ai, /platform,
// /integrations, /tutorials, /compare, /hire, /earn, /about and the workflow
// recipes render hardcoded English from TS data modules. A "/hi/ai/mcp" would
// be an English article under a Hindi URL — the mixed-language stand-in page
// hreflang must never point at. Translate a page's content first, then add
// its path here; the sitemap, hreflang and proxy all key off this one list.
export const LOCALIZED_ROUTES: string[] = ["/", "/billing", "/help", "/signup"];

const INDEXABLE_SET = new Set(INDEXABLE_LOCALES);
const LOCALIZED_ROUTE_SET = new Set(LOCALIZED_ROUTES);

export function isIndexableLocale(code: string | null | undefined): boolean {
  return !!code && INDEXABLE_SET.has(code);
}

export function isLocalizedRoute(pathname: string): boolean {
  return LOCALIZED_ROUTE_SET.has(pathname);
}

// "/hi/billing" -> { locale: "hi", pathname: "/billing" }; "/hi" -> "/".
// Anything without a recognised prefix comes back untouched with locale null.
// "en" IS recognised (proxy.js redirects "/en/..." to the unprefixed URL so
// English never exists at two addresses). The segment must match exactly, so
// "/ai" and "/as-if" are never mistaken for a locale.
export function splitLocalePrefix(pathname: string | null | undefined): {
  locale: string | null;
  pathname: string;
} {
  const path = pathname || "/";
  const match = /^\/([a-z]{2})(?=\/|$)/.exec(path);
  if (!match || !(match[1] === DEFAULT_LOCALE || INDEXABLE_SET.has(match[1]))) {
    return { locale: null, pathname: path };
  }
  return { locale: match[1], pathname: path.slice(match[0].length) || "/" };
}

// The URL a given language version of `pathname` lives at. Falls back to the
// unprefixed path whenever a prefixed one would not exist (English, a
// held-back locale, or a route that is not translated) — callers can link to
// the result without checking anything themselves.
export function localizedPath(pathname: string, locale: string): string {
  if (locale === DEFAULT_LOCALE || !INDEXABLE_SET.has(locale) || !LOCALIZED_ROUTE_SET.has(pathname)) {
    return pathname;
  }
  return pathname === "/" ? `/${locale}` : `/${locale}${pathname}`;
}
