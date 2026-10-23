// Shared fixtures for the settings component tests: a catalog covering every setting type, a fake client (the only
// thing mocked - components, hooks and the real I18nProvider with the real English messages run for real) and a
// render helper. Not imported by production code.
import { vi, type Mock } from "vitest";
import type { ReactElement } from "react";
import { render } from "@testing-library/react";
import en from "../../../messages/en.json";
import { I18nProvider } from "../../lib/i18n";
import type {
  EffectiveSetting,
  EffectiveSettings,
  SettingCatalogEntry,
  SettingHistoryItem,
  SettingHistoryPage,
  SettingHistoryRow,
  SettingsHistoryClient,
} from "../../lib/api/settings";

// Radix popper (tooltips) measures with ResizeObserver, which jsdom lacks.
if (typeof globalThis.ResizeObserver === "undefined") {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

const base = {
  helpKey: undefined as string | undefined,
  scopes: ["tenant", "agent"] as SettingCatalogEntry["scopes"],
  minRole: "EDITOR" as const,
  restartRequired: false,
  risk: "low" as const,
};

function entry(e: Partial<SettingCatalogEntry> & Pick<SettingCatalogEntry, "key" | "type" | "default">): SettingCatalogEntry {
  return {
    ...base,
    group: e.key.split(".")[0],
    labelKey: `settings.${e.key}.label`,
    descriptionKey: `settings.${e.key}.description`,
    ...e,
  };
}

export const CATALOG: SettingCatalogEntry[] = [
  entry({ key: "agent.limits.maxToolSteps", type: "int", unit: "count", min: 1, max: 50, step: 1, default: 8, risk: "medium", helpKey: "settings.agent.limits.maxToolSteps.help" }),
  entry({ key: "agent.limits.turnTimeoutSeconds", type: "duration", unit: "s", min: 5, max: 600, default: 120, risk: "medium" }),
  entry({ key: "agent.limits.toolOutputMaxChars", type: "long", unit: "chars", min: 100, max: 200000, default: 20000 }),
  entry({ key: "quality.judge.temperature", type: "decimal", min: 0, max: 2, step: 0.1, default: 0.2 }),
  entry({ key: "chat.cache.eligibleWithTools", type: "bool", default: false }),
  entry({ key: "aux.model", type: "string", min: 1, max: 40, default: "small-model" }),
  entry({ key: "aux.note", type: "text", max: 200, default: "" }),
  entry({ key: "guardrails.failMode", type: "enum", enumValues: ["open", "closed"], default: "closed", risk: "security", minRole: "TENANT_ADMIN", scopes: ["tenant"], strictness: "enum_order" }),
  entry({ key: "guardrails.toxicity.threshold", type: "percent", unit: "percent", min: 0, max: 100, step: 5, default: 80, risk: "security", minRole: "TENANT_ADMIN", strictness: "higher_is_stricter", helpKey: "settings.guardrails.toxicity.threshold.help" }),
  entry({ key: "approvals.expiry.ttl", type: "duration", unit: "ms", min: 60000, max: 86400000, default: 3600000, risk: "high", helpKey: "settings.approvals.expiry.ttl.help" }),
];

const LABELS: Record<string, [string, string, string?]> = {
  "agent.limits.maxToolSteps": ["Max tool steps", "How many tool calls one turn may make.", "Higher values let the agent finish bigger jobs but cost more per turn."],
  "agent.limits.turnTimeoutSeconds": ["Turn timeout", "How long one turn may run."],
  "agent.limits.toolOutputMaxChars": ["Tool output limit", "Longest tool result kept."],
  "quality.judge.temperature": ["Judge temperature", "Randomness of the quality judge."],
  "chat.cache.eligibleWithTools": ["Cache replies that used tools", "Reuse cached replies even when tools ran."],
  "aux.model": ["Helper model", "Model used for small background tasks."],
  "aux.note": ["Operator note", "Free text shown to operators."],
  "guardrails.failMode": ["Guardrail failure mode", "What happens when a guardrail cannot decide."],
  "guardrails.toxicity.threshold": ["Toxicity threshold", "Score above which content is blocked.", "Lowering this lets more content through the safety filter."],
  "approvals.expiry.ttl": ["Approval expiry", "How long a request waits for a decision.", "Requests older than this are rejected automatically."],
};

/** The "settings" message namespace the catalog's i18n keys point at (owned by whoever adds a setting). */
function settingsMessages(): Record<string, unknown> {
  const root: Record<string, unknown> = {};
  const put = (path: string, text: string) => {
    const parts = path.split(".");
    let cur = root;
    parts.slice(0, -1).forEach((p) => {
      cur = (cur[p] ??= {}) as Record<string, unknown>;
    });
    cur[parts[parts.length - 1]] = text;
  };
  for (const [key, [label, description, help]] of Object.entries(LABELS)) {
    put(`${key}.label`, label);
    put(`${key}.description`, description);
    if (help) put(`${key}.help`, help);
  }
  put("guardrails.failMode.options.open", "Fail open");
  put("guardrails.failMode.options.closed", "Fail closed");
  return root;
}

export const MESSAGES = { ...(en as Record<string, unknown>), settings: settingsMessages() };

export function eff(value: unknown, source: EffectiveSetting["source"] = "default", extra: Partial<EffectiveSetting> = {}): EffectiveSetting {
  return { value, source, locked: false, ...extra };
}

export function defaultEffective(): EffectiveSettings {
  const values: Record<string, EffectiveSetting> = {};
  for (const e of CATALOG) values[e.key] = eff(e.default, "default");
  return { values };
}

export interface FakeClient extends SettingsHistoryClient {
  getCatalog: Mock<SettingsHistoryClient["getCatalog"]>;
  getEffective: Mock<SettingsHistoryClient["getEffective"]>;
  put: Mock<SettingsHistoryClient["put"]>;
  reset: Mock<SettingsHistoryClient["reset"]>;
  getHistory: Mock<SettingsHistoryClient["getHistory"]>;
  getHistoryPage: Mock<SettingsHistoryClient["getHistoryPage"]>;
}

/** A one-page answer of the all-keys history. */
export function historyPage(entries: SettingHistoryRow[], extra: Partial<SettingHistoryPage> = {}): SettingHistoryPage {
  return { entries, total: entries.length, offset: 0, limit: 100, hasMore: false, ...extra };
}

export function makeClient(opts: {
  catalog?: SettingCatalogEntry[];
  effective?: EffectiveSettings | ((agentId?: string) => EffectiveSettings);
  history?: SettingHistoryItem[];
  historyRows?: SettingHistoryRow[];
} = {}): FakeClient {
  const eff_ = opts.effective ?? defaultEffective();
  return {
    getCatalog: vi.fn(async () => ({ entries: opts.catalog ?? CATALOG })),
    getEffective: vi.fn(async (agentId?: string) => (typeof eff_ === "function" ? eff_(agentId) : eff_)),
    put: vi.fn(async () => undefined),
    reset: vi.fn(async () => undefined),
    getHistory: vi.fn(async () => opts.history ?? []),
    getHistoryPage: vi.fn(async () => historyPage(opts.historyRows ?? [])),
  };
}

export function renderSettings(ui: ReactElement) {
  return render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      {ui}
    </I18nProvider>
  );
}
