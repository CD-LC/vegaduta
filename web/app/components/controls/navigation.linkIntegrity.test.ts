// Link integrity for the navigation model: every internal href the console's wayfinding offers (the rail, the
// controls areas, the cross-links between an agent and the pages that shape it, the "Where do I control...?" page)
// must resolve to a page that exists, and the query parameters it sends must be ones the target page reads. A page
// that is renamed or removed, or a parameter the target stopped reading, fails here instead of becoming a dead link.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import snapshot from "../../lib/api/fixtures/settingsCatalog.snapshot.json";
import type { SettingCatalogEntry } from "../../lib/api/settings";
import { NAV_SECTIONS } from "../nav/navSections";
import { agentRelatedLinks, openAgentHref } from "../agents/AgentWorkbench/agentLinks";
import { TENANT_RUNTIME_SETTINGS_HREF } from "../agents/AgentWorkbench/behaviorModel";
import { turnTimelineHref } from "../agents/ViewTurnLink";
import { PROMPTS_ADMIN_HREF, agentBehaviorHref } from "../agents/playground/runSettingsModel";
import { CONTROLS_HELP_HREF, CONTROL_AREAS } from "./controlAreas";
import { settingLinks } from "./controlsModel";

const APP_DIR = path.resolve(__dirname, "../..");
const AGENT_ID = "7b0e9d4c-1c2f-4e7a-9a43-0d3b6f1b2a10";
const ENTRIES = snapshot.entries as unknown as SettingCatalogEntry[];

// ---- route table: every page under app/, as path segments (route groups dropped, dynamic segments as patterns)

const PAGE_FILE = /^page\.(tsx|ts|jsx|js|mdx)$/;

function collectRoutes(dir: string, segments: string[], out: string[][]): void {
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === "node_modules" || name.startsWith("@") || name.startsWith("_")) continue;
      const isGroup = name.startsWith("(") && name.endsWith(")");
      collectRoutes(full, isGroup ? segments : [...segments, name], out);
    } else if (PAGE_FILE.test(name)) {
      out.push(segments);
    }
  }
}

const ROUTES: string[][] = [];
collectRoutes(APP_DIR, [], ROUTES);

function matches(route: string[], parts: string[]): boolean {
  for (let i = 0; i < route.length; i++) {
    const seg = route[i];
    if (/^\[\[\.\.\..+\]\]$/.test(seg)) return true;
    if (/^\[\.\.\..+\]$/.test(seg)) return parts.length > i;
    if (i >= parts.length) return false;
    if (!/^\[.+\]$/.test(seg) && seg !== parts[i]) return false;
  }
  return route.length === parts.length;
}

function resolves(pathname: string): boolean {
  const parts = pathname.split("/").filter(Boolean);
  return ROUTES.some((r) => matches(r, parts));
}

function split(href: string): { pathname: string; params: string[]; hash: string } {
  const url = new URL(href, "http://console.invalid");
  return { pathname: url.pathname, params: [...url.searchParams.keys()], hash: url.hash.replace(/^#/, "") };
}

// ---- which source files read which query parameters (a route with parameters must be listed, so a new one is a decision)

const READS_PARAMS: Record<string, string[]> = {
  "/admin/instructions": ["admin/instructions/page.tsx", "admin/instructions/registryDeepLink.ts"],
  "/admin/approvals": ["admin/approvals/page.tsx"],
  "/agents/turns": ["agents/turns/page.tsx"],
  "/agents": ["agents/page.tsx", "components/agents/workbench/ConfigTabs.tsx"],
};

function readsParam(pathname: string, param: string): boolean {
  const files = READS_PARAMS[pathname] ?? [];
  return files.some((f) => new RegExp(`["'\`]${param}["'\`]`).test(readFileSync(path.join(APP_DIR, f), "utf8")));
}

// ---- the navigation model: every href the code builds

const modelHrefs: Array<{ from: string; href: string }> = [
  ...NAV_SECTIONS.flatMap((s) => s.items.map((i) => ({ from: `nav ${i.labelKey}`, href: i.href }))),
  ...CONTROL_AREAS.map((a) => ({ from: `controls area ${a.id}`, href: a.href })),
  { from: "controls help", href: CONTROLS_HELP_HREF },
  { from: "tenant runtime settings", href: TENANT_RUNTIME_SETTINGS_HREF },
  { from: "prompts admin", href: PROMPTS_ADMIN_HREF },
  { from: "agent behavior", href: agentBehaviorHref(AGENT_ID) },
  { from: "agent behavior focus", href: agentBehaviorHref(AGENT_ID, "agent.limits.maxToolSteps") },
  { from: "open agent", href: openAgentHref(AGENT_ID, { section: "behavior", focus: "agent.limits.maxToolSteps" }) },
  { from: "turn timeline", href: turnTimelineHref(AGENT_ID, "sess-1", "turn-1") },
  ...agentRelatedLinks(AGENT_ID).map((l) => ({ from: `agent link ${l.id}`, href: l.href })),
  ...ENTRIES.flatMap((e) =>
    Object.entries(settingLinks(e)).map(([where, href]) => ({ from: `setting ${e.key} (${where})`, href: href as string }))
  ),
];

describe("navigation link integrity", () => {
  it("found the app's pages (guards against an empty route table making everything pass)", () => {
    expect(ROUTES.length).toBeGreaterThan(50);
    expect(resolves("/agents")).toBe(true);
    expect(resolves("/this/does/not/exist")).toBe(false);
  });

  it.each(modelHrefs.map((h) => [h.from, h.href]))("%s -> %s resolves to an existing page", (_from, href) => {
    expect(resolves(split(href).pathname)).toBe(true);
  });

  it("sends only query parameters the target page reads", () => {
    const problems: string[] = [];
    for (const { from, href } of modelHrefs) {
      const { pathname, params } = split(href);
      for (const param of params) {
        if (!(pathname in READS_PARAMS)) problems.push(`${from}: ${href} sends ?${param} to a page not listed in READS_PARAMS`);
        else if (!readsParam(pathname, param)) problems.push(`${from}: ${pathname} does not read ?${param}`);
      }
    }
    expect(problems).toEqual([]);
  });

  it("points /help/controls anchors at sections the page renders", () => {
    const anchors = (en as unknown as { controls: { anchors: Record<string, unknown> } }).controls.anchors;
    const used = new Set(ENTRIES.map((e) => e.docsAnchor?.trim()).filter(Boolean) as string[]);
    for (const a of used) expect(Object.keys(anchors), `controls.anchors.${a}`).toContain(a);
  });

  it("covers the cross-link components' own static hrefs too (a typo in a literal path is caught)", () => {
    const dirs = [
      "components/controls",
      "components/agents/AgentWorkbench",
      "components/agents/playground",
      "components/agents/workbench",
      "components/approvals",
      "admin/approvals",
      "admin/instructions",
      "admin/runtime-settings",
      "help/controls",
    ];
    const literal = /href(?:=\{?|:\s*)\s*[`"'](\/[A-Za-z0-9\-_/]*)/g;
    const files: string[] = [];
    const walk = (d: string) => {
      for (const n of readdirSync(d)) {
        const full = path.join(d, n);
        if (statSync(full).isDirectory()) walk(full);
        else if (/\.(tsx|ts)$/.test(n) && !/\.test\./.test(n)) files.push(full);
      }
    };
    for (const d of dirs) if (existsSync(path.join(APP_DIR, d))) walk(path.join(APP_DIR, d));
    const dead: string[] = [];
    for (const f of files) {
      for (const m of readFileSync(f, "utf8").matchAll(literal)) {
        const p = m[1].replace(/\/$/, "") || "/";
        if (!resolves(p)) dead.push(`${path.relative(APP_DIR, f)}: ${m[1]}`);
      }
    }
    expect(files.length).toBeGreaterThan(10);
    expect(dead).toEqual([]);
  });
});
