import { describe, it, expect, vi } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

// seo-i18n.ts reads next/headers, which only exists inside a Next request.
// Nothing under test here calls it; the mock just lets the module import.
vi.mock("next/headers", () => ({ headers: async () => new Headers() }));

import {
  SUPPORTED_LOCALES,
  DEFAULT_LOCALE,
  INDEXABLE_LOCALES,
  LOCALIZED_ROUTES,
  splitLocalePrefix,
  localizedPath,
  isLocalizedRoute,
} from "./i18n-config";
import { SITE_URL, buildAlternates, hreflangMap, ogLocale } from "./seo-i18n";
import { matchesPublicRoute } from "./auth";
import sitemap from "../sitemap";

// Multilingual SEO contract (2026-09-19). These are the rules a search engine
// holds the site to, written as tests because every one of them fails
// SILENTLY in production: a broken hreflang cluster still renders a perfectly
// good page, it just quietly stops being treated as a translation.

const WEB_ROOT = join(__dirname, "..", "..");

describe("INDEXABLE_LOCALES", () => {
  it("only holds supported locales, never English, never a duplicate", () => {
    const supported = new Set(SUPPORTED_LOCALES.map((l) => l.code));
    for (const code of INDEXABLE_LOCALES) expect(supported.has(code), code).toBe(true);
    expect(INDEXABLE_LOCALES).not.toContain(DEFAULT_LOCALE);
    expect(new Set(INDEXABLE_LOCALES).size).toBe(INDEXABLE_LOCALES.length);
  });

  it("only holds two-letter ISO 639-1 codes - the only language codes hreflang accepts", () => {
    for (const code of INDEXABLE_LOCALES) expect(code, code).toMatch(/^[a-z]{2}$/);
  });

  it("has an Open Graph locale for every language version", () => {
    for (const code of [DEFAULT_LOCALE, ...INDEXABLE_LOCALES]) {
      expect(ogLocale(code), code).toMatch(new RegExp(`^${code}_[A-Z]{2}$`));
    }
  });

  // A top-level route named like a locale ("/es") would be swallowed by the
  // proxy's prefix handling and become unreachable. Catch it at test time.
  it("never collides with a top-level app route", () => {
    const appDir = join(WEB_ROOT, "app");
    const routes = readdirSync(appDir).filter((name) => statSync(join(appDir, name)).isDirectory());
    for (const code of [DEFAULT_LOCALE, ...INDEXABLE_LOCALES]) expect(routes, code).not.toContain(code);
  });

  it("has translated seoMeta copy for every translated route, hashed for the pipeline", () => {
    const pages = ["home", "billing", "help", "signup"];
    const en = JSON.parse(readFileSync(join(WEB_ROOT, "messages", "en.json"), "utf8"));
    for (const page of pages) {
      expect(en.seoMeta?.[page]?.title, `en ${page}`).toBeTruthy();
      expect(en.seoMeta?.[page]?.description, `en ${page}`).toBeTruthy();
    }
    for (const code of INDEXABLE_LOCALES) {
      const bundle = JSON.parse(readFileSync(join(WEB_ROOT, "messages", `${code}.json`), "utf8"));
      for (const page of pages) {
        for (const field of ["title", "description"]) {
          const value = bundle.seoMeta?.[page]?.[field];
          expect(value, `${code} seoMeta.${page}.${field}`).toBeTruthy();
          // An untranslated copy of the English string is a stand-in, not a translation.
          expect(value, `${code} seoMeta.${page}.${field}`).not.toBe(en.seoMeta[page][field]);
          expect(bundle._sourceHash?.[`seoMeta.${page}.${field}`], `${code} hash ${page}.${field}`).toBeTruthy();
        }
      }
    }
  });
});

describe("splitLocalePrefix / localizedPath", () => {
  it("splits a locale prefix off and leaves everything else alone", () => {
    expect(splitLocalePrefix("/hi")).toEqual({ locale: "hi", pathname: "/" });
    expect(splitLocalePrefix("/hi/billing")).toEqual({ locale: "hi", pathname: "/billing" });
    expect(splitLocalePrefix("/en/help")).toEqual({ locale: "en", pathname: "/help" });
    expect(splitLocalePrefix("/billing")).toEqual({ locale: null, pathname: "/billing" });
    expect(splitLocalePrefix("/")).toEqual({ locale: null, pathname: "/" });
  });

  it("never mistakes a real route for a locale", () => {
    // "/ai" is a public hub, "/a/" and "/w/" are share links; "/as-if" starts
    // with a locale code but is not that segment.
    for (const path of ["/ai", "/ai/mcp", "/a/abc", "/w/abc", "/as-if", "/hire", "/terms"]) {
      expect(splitLocalePrefix(path), path).toEqual({ locale: null, pathname: path });
    }
  });

  it("does not recognise a held-back locale as a prefix", () => {
    const heldBack = SUPPORTED_LOCALES.map((l) => l.code).filter(
      (c) => c !== DEFAULT_LOCALE && !INDEXABLE_LOCALES.includes(c)
    );
    expect(heldBack.length).toBeGreaterThan(0);
    for (const code of heldBack) {
      expect(splitLocalePrefix(`/${code}`).locale, code).toBeNull();
      expect(localizedPath("/", code), code).toBe("/");
    }
  });

  it("round-trips every language version of every translated route", () => {
    for (const route of LOCALIZED_ROUTES) {
      for (const code of INDEXABLE_LOCALES) {
        const url = localizedPath(route, code);
        expect(url.endsWith("/"), url).toBe(false);
        expect(splitLocalePrefix(url)).toEqual({ locale: code, pathname: route });
      }
      expect(localizedPath(route, DEFAULT_LOCALE)).toBe(route);
    }
  });

  it("gives an untranslated route no language URL at all", () => {
    expect(isLocalizedRoute("/help/workflow-recipes")).toBe(false);
    expect(localizedPath("/ai/mcp", "hi")).toBe("/ai/mcp");
    expect(localizedPath("/help/workflow-recipes", "hi")).toBe("/help/workflow-recipes");
  });
});

describe("auth gate under a locale prefix", () => {
  it("treats /hi/billing exactly like /billing, and still keeps console routes private", () => {
    expect(matchesPublicRoute("/hi/billing")).toBe(true);
    expect(matchesPublicRoute("/ta/signup")).toBe(true);
    expect(matchesPublicRoute("/hi/agents")).toBe(false);
    expect(matchesPublicRoute("/ai-models")).toBe(false);
  });
});

describe("hreflang", () => {
  it("lists every language version plus x-default, as absolute https URLs", () => {
    const map = hreflangMap("/billing");
    expect(Object.keys(map).sort()).toEqual([DEFAULT_LOCALE, ...INDEXABLE_LOCALES, "x-default"].sort());
    for (const url of Object.values(map)) expect(url.startsWith(`${SITE_URL}/`), url).toBe(true);
    expect(map["x-default"]).toBe(`${SITE_URL}/billing`);
    expect(map.en).toBe(`${SITE_URL}/billing`);
    expect(map.hi).toBe(`${SITE_URL}/hi/billing`);
    expect(hreflangMap("/").hi).toBe(`${SITE_URL}/hi`);
  });

  it("makes every language version canonical to ITSELF, never to English", () => {
    expect(buildAlternates("/billing", null).canonical).toBe("/billing");
    expect(buildAlternates("/billing", "hi").canonical).toBe("/hi/billing");
    expect(buildAlternates("/", "ta").canonical).toBe("/ta");
  });

  it("advertises the same cluster from every member (reciprocity)", () => {
    const fromEnglish = buildAlternates("/help", null).languages;
    for (const code of INDEXABLE_LOCALES) {
      expect(buildAlternates("/help", code).languages).toEqual(fromEnglish);
    }
  });

  it("emits no hreflang for a route that is not translated", () => {
    expect(buildAlternates("/ai/mcp", null)).toEqual({ canonical: "/ai/mcp" });
  });
});

// Billing ON here: /billing is a translated route, and these cluster checks
// need it listed. The billing-OFF shape is checked in its own block below.
function sitemapWithBilling(enabled: boolean) {
  const saved = process.env.NEXT_PUBLIC_BILLING_ENABLED;
  if (enabled) process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
  else delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
  try {
    return sitemap();
  } finally {
    if (saved === undefined) delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    else process.env.NEXT_PUBLIC_BILLING_ENABLED = saved;
  }
}

describe("sitemap (billing off)", () => {
  it("advertises no billing/plans URL in any language", () => {
    const urls = sitemapWithBilling(false).map((e) => e.url);
    expect(urls.some((u) => /\/(billing|features)$/.test(u))).toBe(false);
    expect(urls).toContain(`${SITE_URL}/help`);
  });
});

describe("sitemap", () => {
  const entries = sitemapWithBilling(true);
  const byUrl = new Map(entries.map((e) => [e.url, e]));

  it("has no duplicate URLs and no trailing-slash language URLs", () => {
    expect(byUrl.size).toBe(entries.length);
    for (const e of entries) {
      if (e.url !== `${SITE_URL}/`) expect(e.url.endsWith("/"), e.url).toBe(false);
    }
  });

  it("lists every language version of every translated route", () => {
    for (const route of LOCALIZED_ROUTES) {
      for (const code of INDEXABLE_LOCALES) {
        const url = `${SITE_URL}${localizedPath(route, code)}`;
        expect(byUrl.has(url), url).toBe(true);
      }
    }
  });

  it("gives every member of a cluster the identical alternates, each pointing at a listed URL", () => {
    for (const route of LOCALIZED_ROUTES) {
      const expected = hreflangMap(route);
      const members = [route, ...INDEXABLE_LOCALES.map((c) => localizedPath(route, c))];
      for (const member of members) {
        const entry = byUrl.get(`${SITE_URL}${member}`) ?? byUrl.get(`${SITE_URL}${member}`.replace(/\/$/, "") + "/");
        expect(entry, member).toBeTruthy();
        expect(entry?.alternates?.languages, member).toEqual(expected);
      }
      for (const url of Object.values(expected)) {
        expect(byUrl.has(url) || byUrl.has(`${url}/`) || byUrl.has(url.replace(/\/$/, "")), url).toBe(true);
      }
    }
  });

  it("leaves untranslated routes with no alternates", () => {
    const hub = byUrl.get(`${SITE_URL}/ai`);
    expect(hub).toBeTruthy();
    expect(hub?.alternates).toBeUndefined();
  });
});
