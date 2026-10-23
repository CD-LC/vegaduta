// Accessibility of the settings UI in every state a person can put it in, against the real catalog: the panel for a
// workspace and for an agent, read-only, with an invalid draft, the typed-reason confirmation, the history drawer
// and the full Runtime settings page.
import { afterEach, describe, expect, it } from "vitest";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import type { ReactElement } from "react";
import en from "../../../messages/en.json";
import snapshot from "../../lib/api/fixtures/settingsCatalog.snapshot.json";
import { axeViolations } from "../../lib/axeTestKit";
import { I18nProvider } from "../../lib/i18n";
import type { EffectiveSettings, SettingCatalogEntry } from "../../lib/api/settings";
import RuntimeSettingsView from "../../admin/runtime-settings/RuntimeSettingsView";
import { SettingsPanel } from ".";
import { makeClient } from "./settingsTestKit";

const ADMIN = "TENANT_ADMIN" as const;
const VIEWER = "VIEWER" as const;
const EDITOR = "EDITOR" as const;
const ENTRIES = snapshot.entries as unknown as SettingCatalogEntry[];
const MESSAGES = en as Record<string, unknown>;
const view = (ui: ReactElement) =>
  render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      {ui}
    </I18nProvider>
  );
const effective = (over: EffectiveSettings["values"] = {}): EffectiveSettings => ({
  values: {
    ...Object.fromEntries(ENTRIES.map((e) => [e.key, { value: e.default, source: "default" as const, locked: false }])),
    ...over,
  },
});

afterEach(() => cleanup());

describe("settings accessibility (axe)", () => {
  it("the workspace panel has no violations, and none while read-only", async () => {
    const { unmount } = view(<SettingsPanel scope="tenant" client={makeClient({ catalog: ENTRIES, effective: effective() })} role={ADMIN} />);
    await screen.findByTestId("setting-agent.limits.maxToolSteps");
    expect(await axeViolations()).toEqual([]);
    unmount();
    view(<SettingsPanel scope="tenant" client={makeClient({ catalog: ENTRIES, effective: effective() })} role={VIEWER} readOnly />);
    await screen.findByTestId("setting-agent.limits.maxToolSteps");
    expect(await axeViolations()).toEqual([]);
  });

  it("the agent panel shows inherited, overridden and locked values without violations", async () => {
    const values = effective({
      "agent.limits.maxToolSteps": { value: 30, source: "agent", locked: false },
      "chat.retry.maxAttempts": { value: 2, source: "tenant", locked: false },
      "approvals.continuation.enabled": { value: true, source: "platform", locked: true, lockedReason: "Pinned by the platform operator" },
    });
    view(<SettingsPanel scope="agent" agentId="a-1" client={makeClient({ catalog: ENTRIES, effective: values })} role={EDITOR} />);
    await screen.findByTestId("setting-agent.limits.maxToolSteps");
    expect(await axeViolations()).toEqual([]);
  });

  it("an invalid draft with its inline error has no violations", async () => {
    view(<SettingsPanel scope="tenant" client={makeClient({ catalog: ENTRIES, effective: effective() })} role={ADMIN} />);
    const field = await screen.findByTestId("setting-agent.limits.maxToolSteps");
    fireEvent.change(within(field).getByRole("spinbutton"), { target: { value: "99999" } });
    await act(async () => {});
    expect(within(field).getByTestId("setting-validation")).not.toBeEmptyDOMElement();
    expect(await axeViolations()).toEqual([]);
  });

  it("the typed-reason confirmation dialog for a security change has no violations", async () => {
    view(<RuntimeSettingsView client={makeClient({ catalog: ENTRIES, effective: effective() })} viewerRole="TENANT_ADMIN" />);
    const field = await screen.findByTestId("setting-guardrails.toxicity.blockScore");
    fireEvent.change(within(field).getByRole("spinbutton"), { target: { value: "40" } });
    fireEvent.click(within(screen.getByTestId("settings-group-guardrails")).getByRole("button", { name: "Save changes" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it("the history drawer has no violations", async () => {
    const client = makeClient({
      catalog: ENTRIES,
      effective: effective(),
      history: [{ version: 2, value: 20, previousValue: 15, actor: "ana@example.com", at: "2026-10-01T10:00:00Z", reason: "Longer jobs" }],
    });
    view(<SettingsPanel scope="tenant" client={client} role={ADMIN} />);
    const field = await screen.findByTestId("setting-agent.limits.maxToolSteps");
    fireEvent.click(within(field).getByRole("button", { name: /History/ }));
    await screen.findByRole("dialog");
    expect(await axeViolations()).toEqual([]);
  });

  it("the Runtime settings page, including its change-history tab, has no violations", async () => {
    view(<RuntimeSettingsView client={makeClient({ catalog: ENTRIES, effective: effective() })} viewerRole="TENANT_ADMIN" />);
    await screen.findByTestId("setting-agent.limits.maxToolSteps");
    expect(await axeViolations()).toEqual([]);
    const tab = screen.getByRole("tab", { name: /history/i });
    fireEvent.mouseDown(tab);
    fireEvent.click(tab);
    await act(async () => {});
    expect(await axeViolations()).toEqual([]);
  });
});
