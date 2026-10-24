// Accessibility (axe) of the agent's "Behavior and limits" tab against the real catalog: editable, read-only and
// plan-locked, with inherited, overridden and pinned values.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import en from "../../../../messages/en.json";
import snapshot from "../../../lib/api/fixtures/settingsCatalog.snapshot.json";
import { axeViolations } from "../../../lib/axeTestKit";
import { I18nProvider } from "../../../lib/i18n";
import type { EffectiveSettings, SettingCatalogEntry } from "../../../lib/api/settings";
import { makeClient } from "../../settings/settingsTestKit";
import BehaviorLimitsTab from "./BehaviorLimitsTab";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/agents",
  useSearchParams: () => new URLSearchParams(),
}));

const ENTRIES = snapshot.entries as unknown as SettingCatalogEntry[];
const MESSAGES = en as Record<string, unknown>;
const effective: EffectiveSettings = {
  values: {
    ...Object.fromEntries(ENTRIES.map((e) => [e.key, { value: e.default, source: "default" as const, locked: false }])),
    "agent.limits.maxToolSteps": { value: 30, source: "agent", locked: false },
    "agent.limits.turnTimeoutSeconds": { value: 300, source: "tenant", locked: false },
    "approvals.continuation.enabled": { value: true, source: "platform", locked: true, lockedReason: "Pinned by the platform operator" },
  },
};
const AGENT = { id: "ag1", name: "Support bot", providerName: "OPENAI", model: "gpt-x", autonomyTier: "SEMI_AUTONOMOUS", accessLevel: "EDITOR" };

function show(props: Partial<React.ComponentProps<typeof BehaviorLimitsTab>> = {}) {
  return render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      <BehaviorLimitsTab agent={AGENT} client={makeClient({ catalog: ENTRIES, effective })} {...props} />
    </I18nProvider>
  );
}

afterEach(() => cleanup());

describe("agent Behavior tab accessibility (axe)", () => {
  it("editable, with the summary card and every agent-scoped setting", async () => {
    show();
    await screen.findByTestId("summary-live");
    await screen.findByTestId("setting-agent.limits.maxToolSteps");
    expect(await axeViolations()).toEqual([]);
  });

  it("read-only because the plan locks the agent", async () => {
    show({ locked: true });
    await screen.findByTestId("behavior-readonly");
    await screen.findByTestId("setting-agent.limits.maxToolSteps");
    expect(await axeViolations()).toEqual([]);
  });

  it("the load error with its retry", async () => {
    const client = makeClient({ catalog: ENTRIES, effective });
    client.getCatalog.mockRejectedValue(new Error("offline"));
    show({ client });
    expect((await screen.findAllByText(/offline/)).length).toBeGreaterThan(0);
    expect(await axeViolations()).toEqual([]);
  });
});
