import { test, expect } from "@playwright/test";
import { SITE_URL } from "../helpers/e2e-env";

// requires V131 + 2743b59/6d3b474 deployed (plus ef96f36/99dbbda for the
// SEO routes themselves).
//
// JS-DISABLED crawler assertions: every test here uses Playwright's `request`
// fixture to fetch RAW HTML/text — no browser, no hydration, no client JS.
// This is exactly what a crawler that doesn't execute JavaScript sees, which
// is the entire point of the server-rendered SEO work:
//   - /help/workflow-recipes/[industry] (ef96f36): statically generated
//     per-industry pages with real title/description/canonical/JSON-LD.
//   - robots.txt + sitemap.xml (99dbbda): Next Metadata API routes.
//   - "/" landing SSR: being fixed by another agent in parallel — that one
//     test carries its own extra tag (@pending-landing-ssr) so it can be
//     excluded independently if the fix slips.
//
// Tagged @pending-deploy: include with `--grep @pending-deploy`, exclude with
// `--grep-invert @pending-deploy`.

test.describe("SEO / SSR raw-HTML (no-JS crawler view)", { tag: "@pending-deploy" }, () => {
  test("/help/workflow-recipes/solar serves title, meta description, canonical and JSON-LD in raw HTML", async ({
    request,
  }) => {
    const res = await request.get("/help/workflow-recipes/solar");
    expect(res.ok()).toBe(true);
    const html = await res.text();

    // <title> — the shortened industry name form ("Solar Installers", see
    // SHORT_NAME in [industry]/page.tsx).
    expect(html).toContain("AI Agent Workflows for Solar Installers | VegaDūta");

    // The industry's real catalogue name in the body. "&" may arrive escaped.
    expect(html).toMatch(/Solar (&amp;|&) renewable-energy installers/);

    // Meta description — the industry's real tagline (it's under 155 chars,
    // so it is used unclipped). A distinctive fragment is enough; the exact
    // punctuation of the tail shouldn't fail SEO tests.
    expect(html).toContain('name="description"');
    expect(html).toContain("Qualify enquiries, book site surveys and catch underperforming inverters");

    // Canonical.
    expect(html).toContain('rel="canonical"');
    expect(html).toContain("/help/workflow-recipes/solar");

    // JSON-LD structured data, embedded server-side.
    expect(html).toContain('type="application/ld+json"');
  });

  test(
    "GET / serves real landing copy in raw HTML",
    // Extra tag on purpose: the landing-SSR fix is being landed by another
    // agent in parallel. If it slips the deploy, exclude just this test with
    // `--grep-invert @pending-landing-ssr` while keeping the rest of the
    // SEO assertions active.
    { tag: "@pending-landing-ssr" },
    async ({ request }) => {
      const res = await request.get("/");
      expect(res.ok()).toBe(true);
      const html = await res.text();

      // The landing hero is geo-conditional (Cloudflare CF-IPCountry), so
      // accept either stable form: the India brand tagline, or the hero
      // headline prefix both geo variants share (HeroLanguageDemo.tsx's <h1>
      // starts "An AI agent that talks to your customers" in both).
      const hasHindiTagline = html.includes("देश का अपना AI एजेंट");
      const hasHeroHeadline = html.includes("An AI agent that talks to your customers");
      expect(
        hasHindiTagline || hasHeroHeadline,
        "raw landing HTML contains neither the Hindi brand tagline nor the hero headline — landing SSR is not serving real copy to no-JS crawlers"
      ).toBe(true);
    }
  );

  test("robots.txt does NOT disallow the /a/ share pages (they rely on noindex)", async ({ request }) => {
    const res = await request.get("/robots.txt");
    expect(res.ok()).toBe(true);
    const text = await res.text();
    // 2026-09-17: inverted - web/app/robots.ts deliberately omits /a/ (a
    // Disallow'd URL is never fetched, so its noindex meta would never be
    // read); a/[shareId]/layout.tsx sets robots noindex instead.
    expect(text).not.toContain("Disallow: /a/");
    // Sanity: the public funnel is not blanket-disallowed.
    expect(text).toContain("Allow: /");
    expect(text).toContain("Sitemap:");
  });

  test("sitemap.xml lists the programmatic workflow-recipes pages", async ({ request }) => {
    const res = await request.get("/sitemap.xml");
    expect(res.ok()).toBe(true);
    const xml = await res.text();

    // The library root and at least one known per-industry page (the sitemap
    // is generated from the same INDUSTRY_INDEX the pages build from, and is
    // always on the apex host — see web/app/sitemap.ts).
    // sitemap.ts emits absolute URLs on the configured canonical site URL
    // (SITE_URL), regardless of which host serves it - set E2E_SITE_URL to match.
    expect(xml).toContain(`${SITE_URL}/help/workflow-recipes`);
    expect(xml).toContain(`${SITE_URL}/help/workflow-recipes/solar`);
    // The /a/ share pages must NOT be sitemapped — they are noindex.
    expect(xml).not.toContain(`${SITE_URL}/a/`);
  });
});
