import { headers } from "next/headers";
import {
  DEFAULT_LOCALE,
  INDEXABLE_LOCALES,
  isIndexableLocale,
  isLocalizedRoute,
  localizedPath,
} from "./i18n-config";
import { loadMessages } from "./messages-loader";

// Multilingual SEO metadata (2026-09-19). One place that answers, for the
// request being rendered: which language version is this, what is its
// canonical, and which hreflang alternates may it advertise. Server-only
// (reads next/headers); the pure URL rules live in i18n-config.ts.
//
// The rules encoded here are Google's, not house style:
//   - every language version canonicalises to ITSELF. Pointing a translation's
//     canonical at the English page makes Google drop its hreflang entirely.
//   - the hreflang set is identical on every member of the cluster (that is
//     what makes the annotations reciprocal), lists the page itself, and
//     carries an x-default.
//   - only real translations are listed. isLocalizedRoute/INDEXABLE_LOCALES
//     are the gate; a page outside them gets a plain self-canonical and no
//     hreflang at all.

// Public origin of the marketing site - see site-url.ts (env-configured).
import { SITE_URL } from "./site-url";
export { SITE_URL };

// Open Graph wants language_TERRITORY. Territory is a rendering hint for the
// unfurling platform, not a targeting claim - hreflang above stays
// language-only because none of this copy is written per country.
const OG_LOCALE: Record<string, string> = {
  en: "en_US", es: "es_ES", fr: "fr_FR", de: "de_DE", pt: "pt_BR", ar: "ar_AR",
  zh: "zh_CN", ja: "ja_JP", hi: "hi_IN", bn: "bn_IN", mr: "mr_IN", te: "te_IN",
  ta: "ta_IN", gu: "gu_IN", ur: "ur_IN", kn: "kn_IN", or: "or_IN", ml: "ml_IN",
  pa: "pa_IN", as: "as_IN", ne: "ne_NP",
};

export function ogLocale(locale: string): string {
  return OG_LOCALE[locale] || OG_LOCALE[DEFAULT_LOCALE];
}

// hreflang map for one translated route: every language version plus
// x-default, as absolute URLs. x-default is the unprefixed URL - it is the
// one address that adapts to the visitor (cookie / Accept-Language), which is
// exactly the page x-default exists to describe. Shared by page metadata and
// sitemap.ts so the two can never disagree.
export function hreflangMap(pathname: string): Record<string, string> {
  const languages: Record<string, string> = {
    [DEFAULT_LOCALE]: `${SITE_URL}${pathname}`,
  };
  for (const code of INDEXABLE_LOCALES) {
    languages[code] = `${SITE_URL}${localizedPath(pathname, code)}`;
  }
  languages["x-default"] = `${SITE_URL}${pathname}`;
  return languages;
}

export interface SeoRequestContext {
  // Language the page body renders in (URL prefix, else cookie/negotiated).
  renderLocale: string;
  // Language pinned by the URL prefix; null on every unprefixed URL.
  urlLocale: string | null;
}

export async function getSeoRequestContext(): Promise<SeoRequestContext> {
  const headerStore = await headers();
  const fromUrl = headerStore.get("x-url-locale");
  return {
    renderLocale: headerStore.get("x-locale") || DEFAULT_LOCALE,
    urlLocale: isIndexableLocale(fromUrl) ? fromUrl : null,
  };
}

// `alternates` for a route. `pathname` is the unprefixed route path the page
// knows itself by ("/billing"), never the browser URL.
export function buildAlternates(pathname: string, urlLocale: string | null) {
  if (!isLocalizedRoute(pathname)) {
    return { canonical: pathname };
  }
  return {
    canonical: urlLocale ? localizedPath(pathname, urlLocale) : pathname,
    languages: hreflangMap(pathname),
  };
}

export type SeoPageKey = "home" | "billing" | "help" | "signup";

export interface SeoText {
  title: string;
  description: string;
}

// Title/description for a language version, from the `seoMeta` namespace of
// messages/<locale>.json. en.json is the single English source (so the
// translation pipeline's _sourceHash sees every copy change and re-translates
// it); a locale with no entry yet falls back to English field by field, the
// same rule t() follows, rather than rendering a key.
//
// Keyed on the URL locale, NOT the render locale: the unprefixed URL must
// describe itself identically to every crawler and link unfurler whatever
// cookie the requester happens to carry, so it is always English.
export async function localizedSeoText(page: SeoPageKey, urlLocale: string | null): Promise<SeoText> {
  const [active, en] = await Promise.all([
    loadMessages(urlLocale || DEFAULT_LOCALE),
    loadMessages(DEFAULT_LOCALE),
  ]);
  const pick = (bundle: Record<string, unknown>, field: keyof SeoText): string => {
    const entry = (bundle.seoMeta as Record<string, Record<string, unknown>> | undefined)?.[page];
    return typeof entry?.[field] === "string" ? (entry[field] as string) : "";
  };
  return {
    title: pick(active, "title") || pick(en, "title"),
    description: pick(active, "description") || pick(en, "description"),
  };
}

// The whole localized block for a route-level generateMetadata: text,
// canonical + hreflang, and Open Graph locale fields.
export async function localizedRouteMetadata(page: SeoPageKey, pathname: string) {
  const { urlLocale } = await getSeoRequestContext();
  const text = await localizedSeoText(page, urlLocale);
  const pageLocale = urlLocale || DEFAULT_LOCALE;
  return {
    title: text.title,
    description: text.description,
    alternates: buildAlternates(pathname, urlLocale),
    openGraph: {
      title: text.title,
      description: text.description,
      url: localizedPath(pathname, pageLocale),
      type: "website" as const,
      // Route-level openGraph REPLACES the root layout's block rather than
      // merging into it, so the share image has to be restated here.
      images: ["/og-image.png"],
      locale: ogLocale(pageLocale),
      alternateLocale: [DEFAULT_LOCALE, ...INDEXABLE_LOCALES]
        .filter((code) => code !== pageLocale)
        .map(ogLocale),
    },
  };
}
