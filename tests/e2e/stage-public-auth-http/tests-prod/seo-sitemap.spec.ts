import { test, expect } from "@playwright/test";
import { APP_URL, ROOT_URL, DOMAIN, FOREIGN_DOMAIN } from "../helpers/prod-env";
import { GATED_CONSOLE_ROUTES, PUBLIC_PAGES } from "../helpers/prod-routes";

/**
 * SEO/AEO surface. The sitemap is a public PROMISE: every URL advertised in
 * it must resolve and render. A stale sitemap entry is a crawlable 404 and a
 * direct ranking penalty, and it's exactly the kind of thing that rots
 * silently as routes get renamed.
 *
 * Runs on chromium only - these are HTTP-level assertions, so running them a
 * second time under the mobile project would just double the load on
 * production for an identical result.
 */
test.describe("SEO: sitemap, robots, metadata", () => {
  // HTTP-level assertions - no value in repeating them under the mobile
  // project. NOTE: test.skip(condition) passes ONLY fixtures to its callback,
  // never testInfo - reaching for testInfo there throws and fails every test
  // in the describe block. beforeEach is where testInfo is available.
  test.beforeEach(({}, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "HTTP-level, no need to repeat per device");
  });

  let sitemapUrls: string[] = [];

  test("sitemap.xml is well-formed and canonical to the production domain", async ({ request }) => {
    const res = await request.get(`${APP_URL}/sitemap.xml`);
    expect(res.status()).toBe(200);
    expect(res.headers()["content-type"]).toMatch(/xml/i);

    const body = await res.text();
    sitemapUrls = Array.from(body.matchAll(/<loc>([^<]+)<\/loc>/g)).map((m) => m[1].trim());

    expect(sitemapUrls.length, "sitemap should advertise a non-trivial number of URLs").toBeGreaterThan(20);

    // Every entry must be on THIS environment's domain - a sitemap that
    // advertises the staging domain from production leaks .xyz into search
    // results and splits ranking across two hosts.
    const offEnvironment = sitemapUrls.filter((u) => !u.includes(DOMAIN) || u.includes(FOREIGN_DOMAIN));
    expect(offEnvironment, "sitemap entries pointing at the wrong environment").toEqual([]);

    const insecure = sitemapUrls.filter((u) => !u.startsWith("https://"));
    expect(insecure, "sitemap entries must all be https").toEqual([]);

    const duplicates = sitemapUrls.filter((u, i) => sitemapUrls.indexOf(u) !== i);
    expect(duplicates, "duplicate sitemap entries").toEqual([]);
  });

  test("every sitemap URL resolves (no advertised 404s)", async ({ request }) => {
    test.setTimeout(10 * 60 * 1000);

    const res = await request.get(`${APP_URL}/sitemap.xml`);
    const body = await res.text();
    const urls = Array.from(body.matchAll(/<loc>([^<]+)<\/loc>/g)).map((m) => m[1].trim());
    expect(urls.length).toBeGreaterThan(0);

    // Bounded concurrency - this is production, not a load test target.
    const CONCURRENCY = 8;
    const broken: string[] = [];

    for (let i = 0; i < urls.length; i += CONCURRENCY) {
      const batch = urls.slice(i, i + CONCURRENCY);
      const results = await Promise.all(
        batch.map(async (url) => {
          try {
            const r = await request.get(url, { timeout: 45_000 });
            return { url, status: r.status() };
          } catch (err) {
            return { url, status: -1, error: (err as Error).message };
          }
        })
      );
      for (const r of results) {
        if (r.status < 200 || r.status >= 400) broken.push(`${r.status} ${r.url}`);
      }
    }

    expect(broken, `sitemap advertises URLs that do not resolve:\n${broken.join("\n")}`).toEqual([]);
  });

  test("robots.txt allows crawling and points at the sitemap", async ({ request }) => {
    const res = await request.get(`${APP_URL}/robots.txt`);
    expect(res.status()).toBe(200);

    const body = await res.text();
    expect(body).toMatch(/user-agent:/i);
    expect(body, "robots.txt should reference the sitemap").toMatch(/sitemap:\s*https:\/\//i);

    // A blanket "Disallow: /" on production would deindex the entire site.
    const blanketDisallow = /^\s*disallow:\s*\/\s*$/im.test(body);
    expect(blanketDisallow, "robots.txt blanket-disallows the whole site").toBe(false);
  });

  test("robots.txt disallow list covers every gated console route", async ({ request }) => {
    // GATED_CONSOLE_ROUTES is the same list auth-gating.spec.ts iterates -
    // every route on it must fall under SOME robots.txt Disallow prefix, or a
    // crawler is free to index tenant console URLs (they don't 401 on prod,
    // see README-PROD.md "Design notes" - GenesisExperience renders instead,
    // so robots.txt is the ONLY thing keeping these out of search results).
    const res = await request.get(`${APP_URL}/robots.txt`);
    expect(res.status()).toBe(200);
    const body = await res.text();

    const disallowed = Array.from(body.matchAll(/^\s*disallow:\s*(\S+)\s*$/gim)).map((m) =>
      m[1].trim().replace(/\*+$/, "")
    );

    // A route's "top-level path segment" is normally its first path
    // component ("/agents/learning" -> "/agents"). The one exception: when
    // that first component is ITSELF a fully public marketing page (e.g.
    // "/help" in PUBLIC_PAGES), robots.txt correctly leaves it crawlable and
    // disallows only the more specific gated sub-path ("/help/docs") - so the
    // lookup key has to be the two-segment path in that case, not the first
    // segment alone.
    function topLevelSegment(route: string): string {
      const parts = route.split("/").filter(Boolean);
      const first = `/${parts[0]}`;
      const firstIsPublicPage = PUBLIC_PAGES.some((p) => p.path === first);
      if (firstIsPublicPage && parts.length > 1) return `${first}/${parts[1]}`;
      return first;
    }

    const missing = new Set<string>();
    const uniqueSegments = new Set(GATED_CONSOLE_ROUTES.map(topLevelSegment));
    for (const segment of uniqueSegments) {
      const covered = disallowed.some((d) => {
        const normalized = d.replace(/\/$/, "") || "/";
        return normalized === segment;
      });
      if (!covered) missing.add(segment);
    }

    expect(
      Array.from(missing),
      `robots.txt is missing a Disallow entry for these gated route segments - they are ` +
        `crawlable and indexable even though the console renders no data for them:\n` +
        `${Array.from(missing).join("\n")}`
    ).toEqual([]);
  });

  test("public pages carry unique titles and social metadata", async ({ page }) => {
    const paths = ["/", "/billing", "/ai", "/integrations", "/platform", "/tutorials", "/compare", "/templates"];
    const seenTitles = new Map<string, string>();

    for (const path of paths) {
      await page.goto(path, { waitUntil: "domcontentloaded" });

      const title = await page.title();
      expect(title.length, `${path} has an empty/short <title>`).toBeGreaterThan(10);

      const previous = seenTitles.get(title);
      expect(previous, `${path} reuses the <title> already used by ${previous}`).toBeUndefined();
      seenTitles.set(title, path);

      const description = await page.locator('meta[name="description"]').first().getAttribute("content");
      expect(description, `${path} is missing a meta description`).toBeTruthy();
      expect(description!.length).toBeGreaterThan(30);

      const ogTitle = await page.locator('meta[property="og:title"]').first().getAttribute("content");
      expect(ogTitle, `${path} is missing og:title`).toBeTruthy();

      const canonical = await page.locator('link[rel="canonical"]').first().getAttribute("content").catch(() => null);
      const canonicalHref = canonical ?? (await page.locator('link[rel="canonical"]').first().getAttribute("href").catch(() => null));
      if (canonicalHref) {
        expect(canonicalHref, `${path} canonical points off-environment`).toContain(DOMAIN);
        expect(canonicalHref).not.toContain(FOREIGN_DOMAIN);
      }
    }
  });

  test("the bare domain and the app host serve the same marketing entry point", async ({ request }) => {
    const [root, app] = await Promise.all([request.get(`${ROOT_URL}/`), request.get(`${APP_URL}/`)]);
    expect(root.status()).toBeLessThan(400);
    expect(app.status()).toBeLessThan(400);
  });
});
