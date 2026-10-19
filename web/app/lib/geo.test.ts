import { describe, it, expect, vi } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

import robots from "../robots";
import { AI_CRAWLERS, classifyAiCrawler, classifyAiReferral } from "./ai-crawlers";
import { FEATURE_LIST, PLATFORM_FACTS } from "./platform-facts";
import { HUB_DATES } from "./seo-hub";
import { GLOSSARY, GLOSSARY_DATES } from "../glossary/glossary-data";
import { COMPARE_DATES } from "../compare/compare-data";
import { AI_TOPICS } from "../ai/ai-data";
import { PLATFORM_TOPICS } from "../platform/platform-data";
import { INTEGRATIONS } from "../integrations/integrations-data";
import { TUTORIALS } from "../tutorials/tutorials-data";
import { GET as llmsTxt } from "../llms.txt/route";
import { SITE_URL } from "./site-url";

// Generative-engine-optimization contract (2026-09-19). Like the hreflang
// tests next door, every rule here is one that fails SILENTLY: a wrong
// robots group, a drifted number or a vendor pitch inside a "definition" all
// still render a fine-looking page.

const WEB_ROOT = join(__dirname, "..", "..");
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
// "Not in the future", with one day of slack: these dates are written in IST,
// and at 02:00 IST it is still yesterday in UTC, where CI runs.
const today = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);

describe("robots.txt", () => {
  const rules = robots().rules;
  const groups = Array.isArray(rules) ? rules : [rules];
  const star = groups.find((g) => g.userAgent === "*");
  const ai = groups.find((g) => Array.isArray(g.userAgent));

  it("names the crawlers that decide whether an AI answer can cite the site", () => {
    expect(ai).toBeTruthy();
    for (const bot of ["OAI-SearchBot", "ChatGPT-User", "Claude-SearchBot", "Claude-User", "PerplexityBot", "GPTBot", "ClaudeBot"]) {
      expect(ai?.userAgent, bot).toContain(bot);
    }
  });

  // A crawler matching a named group ignores "*" entirely, so a named group
  // without the disallow list would open the console to every AI crawler.
  it("gives the AI group exactly the disallow list everyone else gets", () => {
    expect(star?.disallow).toBeTruthy();
    expect((star?.disallow as string[]).length).toBeGreaterThan(10);
    expect(ai?.disallow).toEqual(star?.disallow);
    expect(ai?.disallow).toContain("/admin");
    expect(ai?.disallow).toContain("/api/");
  });

  it("does not block any public GEO surface", () => {
    for (const path of ["/glossary", "/ai", "/compare", "/llms.txt", "/integrations"]) {
      for (const rule of star?.disallow as string[]) {
        expect(path === rule || path.startsWith(rule.endsWith("/") ? rule : rule + "/"), `${path} vs ${rule}`).toBe(false);
      }
    }
  });
});

describe("AI crawler / referral classification", () => {
  it("recognises real crawler user agents", () => {
    expect(classifyAiCrawler("Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; GPTBot/1.2; +https://openai.com/gptbot)")).toBe("GPTBot");
    expect(classifyAiCrawler("Mozilla/5.0 (compatible; OAI-SearchBot/1.0; +https://openai.com/searchbot)")).toBe("OAI-SearchBot");
    expect(classifyAiCrawler("Mozilla/5.0 (compatible; ClaudeBot/1.0; +claudebot@anthropic.com)")).toBe("ClaudeBot");
    expect(classifyAiCrawler("Mozilla/5.0 (compatible; Claude-SearchBot/1.0)")).toBe("Claude-SearchBot");
    expect(classifyAiCrawler("Mozilla/5.0 (compatible; PerplexityBot/1.0; +https://perplexity.ai/perplexitybot)")).toBe("PerplexityBot");
    expect(classifyAiCrawler("Mozilla/5.0 (compatible; Perplexity-User/1.0)")).toBe("Perplexity-User");
  });

  it("does not mistake people or ordinary search bots for AI crawlers", () => {
    expect(classifyAiCrawler("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0 Safari/537.36")).toBeNull();
    expect(classifyAiCrawler("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")).toBeNull();
    expect(classifyAiCrawler("Mozilla/5.0 (compatible; bingbot/2.0)")).toBeNull();
    expect(classifyAiCrawler(null)).toBeNull();
  });

  it("never reports a robots-only token as a visitor", () => {
    // Google-Extended / Applebot-Extended never appear in a User-Agent.
    expect(AI_CRAWLERS).toContain("Google-Extended");
    expect(classifyAiCrawler("Google-Extended")).toBeNull();
  });

  it("buckets assistant referrals by host or ChatGPT's utm_source, nothing finer", () => {
    expect(classifyAiReferral("https://chatgpt.com/c/abc123?q=best+whatsapp+ai+agent", null)).toBe("chatgpt");
    expect(classifyAiReferral("https://www.perplexity.ai/search/some-private-query", null)).toBe("perplexity");
    expect(classifyAiReferral(null, "chatgpt.com")).toBe("chatgpt");
    expect(classifyAiReferral("https://www.google.com/search?q=chatgpt.com", null)).toBeNull();
    expect(classifyAiReferral("not a url", "newsletter")).toBeNull();
  });
});

describe("dates are real, ISO, and never in the future", () => {
  const all = { ...HUB_DATES, "/glossary": GLOSSARY_DATES, "/compare": COMPARE_DATES };
  for (const [hub, d] of Object.entries(all)) {
    it(hub, () => {
      expect(d.published).toMatch(ISO_DATE);
      expect(d.updated).toMatch(ISO_DATE);
      expect(d.updated >= d.published).toBe(true);
      expect(d.updated <= today, `${hub} updated is in the future`).toBe(true);
    });
  }

  it("covers every hub the shared renderer serves", () => {
    expect(Object.keys(HUB_DATES).sort()).toEqual(["/ai", "/integrations", "/platform", "/tutorials"]);
  });
});

describe("glossary", () => {
  it("has unique, URL-safe, stable ids", () => {
    const ids = GLOSSARY.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
  });

  // The whole value of the page: a definition that pitches the product is an
  // advert, and an advert is neither cited nor trusted.
  it("keeps every definition vendor-neutral", () => {
    for (const t of GLOSSARY) {
      expect(t.definition, t.id).not.toMatch(/vegad[uū]ta/i);
      expect(t.detail ?? "", t.id).not.toMatch(/vegad[uū]ta/i);
      expect(t.definition.length, t.id).toBeGreaterThan(80);
      expect(t.question.endsWith("?"), t.id).toBe(true);
    }
  });

  it("cites only https sources, and no source twice within a term", () => {
    for (const t of GLOSSARY) {
      const hrefs = t.sources.map((s) => s.href);
      expect(new Set(hrefs).size, t.id).toBe(hrefs.length);
      for (const h of hrefs) expect(h.startsWith("https://"), h).toBe(true);
    }
  });

  it("links every term to a page that exists", () => {
    const known = new Set<string>(["/why-vegaduta"]);
    for (const [base, pages] of [["/ai", AI_TOPICS], ["/platform", PLATFORM_TOPICS], ["/integrations", INTEGRATIONS], ["/tutorials", TUTORIALS]] as const) {
      for (const p of pages) known.add(`${base}/${p.slug}`);
    }
    for (const t of GLOSSARY) expect(known.has(t.learnMore.href), `${t.id} -> ${t.learnMore.href}`).toBe(true);
  });

  it("never makes the claims the ledger forbids", () => {
    const text = GLOSSARY.map((t) => t.onVegaDuta).join(" ");
    expect(text).not.toMatch(/runs on Temporal|uptime|latency|\bSLA\b|reinforcement|Home Assistant|SAP\b/i);
  });
});

describe("hub summaries and citations", () => {
  const pages = [...AI_TOPICS, ...PLATFORM_TOPICS, ...INTEGRATIONS, ...TUTORIALS];

  it("writes takeaways that survive being quoted out of context", () => {
    for (const p of pages) {
      for (const k of p.keyTakeaways ?? []) {
        // No "It…", "This…", "They…" openers: a lifted sentence has no antecedent.
        expect(k, `${p.slug}: ${k.slice(0, 40)}`).not.toMatch(/^(It|This|They|These|That|Those|We)\b/);
        expect(k.endsWith("."), p.slug).toBe(true);
      }
    }
  });

  it("cites https sources only", () => {
    for (const p of pages) for (const c of p.citations ?? []) expect(c.href.startsWith("https://"), c.href).toBe(true);
  });
});

describe("one number, everywhere", () => {
  const F = PLATFORM_FACTS;

  it("builds the feature list from the counted facts", () => {
    const text = FEATURE_LIST.join(" | ");
    expect(text).toContain(`${F.channels} channels`);
    expect(text).toContain(`${F.byokProviders} LLM providers`);
    expect(text).toContain(`${F.workflowNodeTypes} node types`);
    expect(text).toContain(`${F.builtInToolsFloor}+ built-in`);
  });

  it("generates an llms.txt that lists every page and carries none of the old drift", async () => {
    // The commercial text is the classic-mode variant; oss mode (the default)
    // serves a shorter open-source one (publicSiteSeo.test.ts).
    vi.stubEnv("NEXT_PUBLIC_SITE_MODE", "classic");
    const body = await llmsTxt().text();
    vi.unstubAllEnvs();
    for (const [base, pages] of [["/ai", AI_TOPICS], ["/platform", PLATFORM_TOPICS], ["/integrations", INTEGRATIONS], ["/tutorials", TUTORIALS]] as const) {
      for (const p of pages) expect(body, `${base}/${p.slug}`).toContain(`${SITE_URL}${base}/${p.slug})`);
    }
    for (const t of GLOSSARY) expect(body).toContain(`/glossary#${t.id}`);
    expect(body).toContain(`${F.byokProviders} LLM providers`);
    expect(body).toContain(`${F.workflowNodeTypes} node types`);
    // Each of these shipped in the hand-written file this route replaced.
    expect(body).not.toMatch(/\b13 (bring|node|LLM|provider)/i);
    expect(body).not.toMatch(/35\+|~35|around 35/);
    expect(body).not.toMatch(/WhatsApp[^.\n]*paid plans/i);
    expect(body).not.toMatch(/automation on Temporal|runs? (workflows )?on Temporal/i);
  });

  it("no longer ships a static llms.txt that could shadow the generated one", () => {
    expect(() => readFileSync(join(WEB_ROOT, "public", "llms.txt"))).toThrow();
  });

  // Translated copy cannot import a constant, so the English source is pinned.
  it("keeps the English marketing copy agreeing with the free-channel fact", () => {
    const en = JSON.parse(readFileSync(join(WEB_ROOT, "messages", "en.json"), "utf8"));
    for (const channel of F.freeChannels) {
      expect(en.landing.faq_a1, `faq_a1 should name ${channel}`).toContain(channel);
      expect(en.seoMeta.home.description, `home description should name ${channel}`).toContain(channel);
    }
    expect(en.seoMeta.home.description).not.toMatch(/paid plan/i);
  });
});
