// UI coverage of the REAL settings catalog (the snapshot of GET /api/settings/catalog written from the Java registry):
// every entry is explained in English, in every locale file, is named by a group the UI can label, and is rendered on
// the tenant Runtime settings screen and on the agent screen with its own copy, never a raw key.
import { afterEach, describe, expect, it } from "vitest";
import type { ReactElement } from "react";
import { cleanup, render, screen, within } from "@testing-library/react";
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";
import snapshot from "../../lib/api/fixtures/settingsCatalog.snapshot.json";
import type { EffectiveSettings, SettingCatalogEntry } from "../../lib/api/settings";
import { SettingsPanel } from "../../components/settings";
import { makeClient } from "../../components/settings/settingsTestKit";
import { I18nProvider } from "../../lib/i18n";
import RuntimeSettingsView from "./RuntimeSettingsView";

const ENTRIES = snapshot.entries as unknown as SettingCatalogEntry[];
const MESSAGES_DIR = path.resolve(__dirname, "../../../messages");

function at(tree: unknown, dotted: string): unknown {
  return dotted.split(".").reduce<unknown>((n, p) => (n && typeof n === "object" ? (n as Record<string, unknown>)[p] : undefined), tree);
}
const text = (tree: unknown, dotted: string): string => {
  const v = at(tree, dotted);
  return typeof v === "string" ? v : "";
};

/** The real English messages, including the real per-setting copy (the kit's renderSettings uses a reduced set). */
const MESSAGES = en as Record<string, unknown>;
const renderSettings = (ui: ReactElement) =>
  render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      {ui}
    </I18nProvider>
  );

const effective: EffectiveSettings = {
  values: Object.fromEntries(ENTRIES.map((e) => [e.key, { value: e.default, source: "default" as const, locked: false }])),
};

afterEach(() => cleanup());

describe("the catalog snapshot", () => {
  it("is the curated catalog: unique keys, scopes and the seven runtime limits", () => {
    const keys = ENTRIES.map((e) => e.key);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.length).toBeGreaterThanOrEqual(30);
    for (const k of ["maxToolSteps", "turnTimeoutSeconds", "toolTimeoutSeconds", "toolOutputMaxChars", "retrievalTopK", "retrievalMinScore", "continuationPasses"]) {
      expect(keys).toContain(`agent.limits.${k}`);
    }
    for (const e of ENTRIES) expect(e.scopes.length, e.key).toBeGreaterThan(0);
  });
});

describe("English copy for every catalog entry", () => {
  it("has a label, a description and a help text, none of them a placeholder", () => {
    const problems: string[] = [];
    for (const e of ENTRIES) {
      for (const k of [e.labelKey, e.descriptionKey, e.helpKey ?? `settings.${e.key}.help`]) {
        const s = text(en, k).trim();
        if (!s) problems.push(`missing ${k}`);
        else if (/\b(TODO|lorem|FIXME)\b/i.test(s)) problems.push(`placeholder ${k}`);
      }
      if (text(en, e.labelKey).length > 60) problems.push(`label too long ${e.labelKey}`);
    }
    expect(problems).toEqual([]);
  });

  it("names every group and every enum option", () => {
    const problems: string[] = [];
    for (const g of new Set(ENTRIES.map((e) => e.group))) {
      if (!text(en, `settings.groups.${g}.label`)) problems.push(`group label ${g}`);
      if (!text(en, `settings.groups.${g}.description`)) problems.push(`group description ${g}`);
    }
    for (const e of ENTRIES.filter((x) => x.type === "enum")) {
      for (const v of e.enumValues ?? []) if (!text(en, `settings.${e.key}.options.${v}`)) problems.push(`option settings.${e.key}.options.${v}`);
    }
    expect(problems).toEqual([]);
  });

  it("has a section on the help page for every docsAnchor", () => {
    const anchors = new Set(ENTRIES.map((e) => e.docsAnchor).filter((x): x is string => !!x));
    expect(anchors.size).toBeGreaterThan(0);
    const missing = [...anchors].filter((a) => !text(en, `controls.anchors.${a}.title`) || !text(en, `controls.anchors.${a}.intro`));
    expect(missing).toEqual([]);
  });

  it("is present in every locale file (the English text is the accepted fallback until translated)", () => {
    const problems: string[] = [];
    for (const f of readdirSync(MESSAGES_DIR).filter((n) => /^[a-z]{2,3}(-[A-Za-z]+)?\.json$/.test(n) && n !== "en.json")) {
      const tree = JSON.parse(readFileSync(path.join(MESSAGES_DIR, f), "utf8"));
      for (const e of ENTRIES) {
        for (const k of [e.labelKey, e.descriptionKey, e.helpKey ?? `settings.${e.key}.help`]) {
          if (!text(tree, k)) problems.push(`${f}: ${k}`);
        }
      }
      for (const g of new Set(ENTRIES.map((e) => e.group))) if (!text(tree, `settings.groups.${g}.label`)) problems.push(`${f}: group ${g}`);
    }
    expect(problems).toEqual([]);
  });
});

describe("the tenant Runtime settings screen against the real catalog", () => {
  it("shows every tenant-scoped entry with its own label, a named group, and no raw keys", async () => {
    const tenant = ENTRIES.filter((e) => e.scopes.includes("tenant"));
    renderSettings(<RuntimeSettingsView client={makeClient({ catalog: ENTRIES, effective })} viewerRole="TENANT_ADMIN" />);
    await screen.findByTestId(`setting-${tenant[0].key}`);

    const missing: string[] = [];
    const rawKey: string[] = [];
    for (const e of tenant) {
      const row = screen.queryByTestId(`setting-${e.key}`);
      if (!row) {
        missing.push(e.key);
        continue;
      }
      const label = text(en, e.labelKey);
      if (!within(row).queryByText(label, { exact: false })) rawKey.push(`${e.key}: label "${label}" not rendered`);
      if (row.textContent?.includes(e.key)) rawKey.push(`${e.key}: raw key leaked into the row`);
    }
    expect(missing).toEqual([]);
    expect(rawKey).toEqual([]);

    // every group has a section headed with its English name, not a humanised id
    for (const g of new Set(tenant.map((e) => e.group))) {
      const section = screen.getByTestId(`runtime-section-${g}`);
      expect(within(section).getByText(text(en, `settings.groups.${g}.label`)), g).toBeInTheDocument();
    }
  });

  it("puts every security entry in a section that explains the stricter-only rule", async () => {
    renderSettings(<RuntimeSettingsView client={makeClient({ catalog: ENTRIES, effective })} viewerRole="TENANT_ADMIN" />);
    await screen.findByTestId(`setting-${ENTRIES[0].key}`);
    for (const e of ENTRIES.filter((x) => x.risk === "security")) {
      expect(screen.getByTestId(`security-note-${e.group}`), e.key).toBeInTheDocument();
      expect(within(screen.getByTestId(`runtime-section-${e.group}`)).getByTestId(`setting-${e.key}`)).toBeInTheDocument();
    }
  });
});

describe("the agent screen against the real catalog", () => {
  it("shows every agent-scoped entry and none that cannot be set on an agent", async () => {
    renderSettings(<SettingsPanel scope="agent" agentId="a-1" client={makeClient({ catalog: ENTRIES, effective })} heading={null} />);
    const agentScoped = ENTRIES.filter((e) => e.scopes.includes("agent"));
    await screen.findByTestId(`setting-${agentScoped[0].key}`);
    const missing = agentScoped.filter((e) => !screen.queryByTestId(`setting-${e.key}`)).map((e) => e.key);
    expect(missing).toEqual([]);
    for (const e of ENTRIES.filter((x) => !x.scopes.includes("agent"))) {
      expect(screen.queryByTestId(`setting-${e.key}`), e.key).toBeNull();
    }
  });
});
