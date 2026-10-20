import { afterEach, describe, expect, it, vi } from "vitest";

import robots from "./robots";
import sitemap from "./sitemap";
import { GET as llmsTxt } from "./llms.txt/route";
import { academyUrl, hasAcademy } from "./lib/academyUrl";
import { applyCatalog, SURFACES } from "./components/plugins/surfaces";

afterEach(() => vi.unstubAllEnvs());

describe("sitemap and robots use the request-time origin (SE-05)", () => {
  it("carries SITE_URL from the environment, never localhost", () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://app.example.test/");
    const urls = sitemap().map((e) => e.url);
    expect(urls.length).toBeGreaterThan(20);
    expect(urls.every((u) => u.startsWith("https://app.example.test/"))).toBe(true);
    expect(robots().sitemap).toBe("https://app.example.test/sitemap.xml");
  });
});

describe("sitemap skips routes that oss mode redirects to / (SE-24)", () => {
  it("omits commercial routes in oss mode and keeps them in classic mode", () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://s.test");
    vi.stubEnv("NEXT_PUBLIC_BILLING_ENABLED", "1");
    vi.stubEnv("NEXT_PUBLIC_SITE_MODE", "oss");
    const oss = sitemap().map((e) => e.url.replace("https://s.test", ""));
    for (const p of ["/hire", "/enterprise", "/earn", "/compare", "/why-vegaduta", "/pricing"]) {
      expect(oss, p).not.toContain(p);
    }
    expect(oss).toContain("/docs");

    vi.stubEnv("NEXT_PUBLIC_SITE_MODE", "classic");
    const classic = sitemap().map((e) => e.url.replace("https://s.test", ""));
    expect(classic).toContain("/hire");
    expect(classic).toContain("/enterprise");
  });
});

describe("llms.txt (SE-67)", () => {
  it("serves an open-source variant with no plans or UPI in oss mode", async () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://s.test");
    vi.stubEnv("NEXT_PUBLIC_SITE_MODE", "oss");
    const body = await llmsTxt().text();
    expect(body).toContain("Business Source License");
    expect(body).not.toMatch(/UPI|\/month|Pricing|Hire AI staff/);
    expect(body).not.toContain("localhost");
  });

  it("keeps the commercial text in classic mode", async () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://s.test");
    vi.stubEnv("NEXT_PUBLIC_SITE_MODE", "classic");
    expect(await llmsTxt().text()).toMatch(/UPI/);
  });
});

describe("academyUrl has no localhost default (SE-25)", () => {
  it("is empty when unset and hides the link", () => {
    vi.stubEnv("NEXT_PUBLIC_ACADEMY_URL", "");
    expect(academyUrl()).toBe("");
    expect(hasAcademy()).toBe(false);
  });

  it("returns the configured origin without a trailing slash", () => {
    vi.stubEnv("NEXT_PUBLIC_ACADEMY_URL", "https://academy.example.test/");
    expect(academyUrl()).toBe("https://academy.example.test");
    expect(hasAcademy()).toBe(true);
  });
});

describe("plugins page has one source of truth (SE-46)", () => {
  const catalog = (artifact: unknown) => ({
    plugins: ["vscode", "jetbrains", "chrome"].map((id) => ({ id, artifact })),
  });

  it("does not say Available for a plugin the catalog has no build for", () => {
    const out = applyCatalog(SURFACES, catalog(null));
    for (const id of ["vscode", "jetbrains", "chrome"]) {
      expect(out.find((s) => s.id === id)?.status, id).toBe("preview");
    }
    expect(out.find((s) => s.id === "mcp-server")?.status).toBe("available");
  });

  it("keeps Available when the catalog holds a build, or the catalog is unreachable", () => {
    expect(applyCatalog(SURFACES, catalog({ fileName: "x" })).find((s) => s.id === "vscode")?.status).toBe("available");
    expect(applyCatalog(SURFACES, null)).toBe(SURFACES);
  });
});
