// The "Where do I control...?" finder rendered for real (real English messages, the real catalog snapshot); only the
// settings client and the signed-in user are faked.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactElement } from "react";
import en from "../../../messages/en.json";
import snapshot from "../../lib/api/fixtures/settingsCatalog.snapshot.json";
import type { SettingCatalogEntry } from "../../lib/api/settings";
import { I18nProvider } from "../../lib/i18n";
import { makeClient } from "../settings/settingsTestKit";

const auth = { isAdmin: true, hasPermission: (_: string) => true };
vi.mock("../AuthProvider", () => ({ useAuth: () => auth }));

import ControlsFinder from "./ControlsFinder";

const ENTRIES = snapshot.entries as unknown as SettingCatalogEntry[];
const MESSAGES = en as Record<string, unknown>;
const view = (ui: ReactElement) =>
  render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      {ui}
    </I18nProvider>
  );

afterEach(() => {
  cleanup();
  auth.isAdmin = true;
  auth.hasPermission = () => true;
});

describe("ControlsFinder", () => {
  it("lists the control areas and every catalog setting, each with the pages that change it", async () => {
    view(<ControlsFinder client={makeClient({ catalog: ENTRIES })} />);
    await screen.findByTestId(`control-setting-${ENTRIES[0].key}`);
    for (const id of ["runtimeSettings", "prompts", "approvals", "guardrails", "turns", "agentBehavior"]) {
      expect(screen.getByTestId(`control-area-${id}`)).toBeInTheDocument();
    }
    expect(within(screen.getByTestId("control-area-turns")).getByRole("link", { name: /Open.*Turn inspector/ })).toHaveAttribute("href", "/agents/turns");
    const missing = ENTRIES.filter((e) => !screen.queryByTestId(`control-setting-${e.key}`)).map((e) => e.key);
    expect(missing).toEqual([]);

    const steps = screen.getByTestId("control-setting-agent.limits.maxToolSteps");
    expect(within(steps).getByText("Most tool calls per turn")).toBeInTheDocument();
    expect(within(steps).getByRole("link", { name: /Change for the workspace/ })).toHaveAttribute("href", "/admin/runtime-settings#agent.limits.maxToolSteps");
    expect(within(steps).getByRole("link", { name: /Change on an agent/ })).toHaveAttribute(
      "href",
      "/agents?section=behavior&focus=agent.limits.maxToolSteps"
    );
    // a workspace-only setting says so and offers no agent link
    const ttl = screen.getByTestId("control-setting-approvals.expiry.ttl");
    expect(within(ttl).queryByRole("link", { name: /Change on an agent/ })).toBeNull();
    expect(within(ttl).getByText("Workspace-wide only")).toBeInTheDocument();
  });

  it("sections carry the catalog's docsAnchor as their id so /help/controls#<anchor> works", async () => {
    view(<ControlsFinder client={makeClient({ catalog: ENTRIES })} />);
    await screen.findByTestId("controls-anchor-agent-runtime-limits");
    expect(document.getElementById("agent-runtime-limits")).not.toBeNull();
    expect(document.getElementById("behaviour-settings")).not.toBeNull();
    expect(within(screen.getByTestId("controls-anchor-agent-runtime-limits")).getAllByRole("heading", { level: 4 })).toHaveLength(11);
  });

  it("finds a setting or a page by the words people use and announces the count", async () => {
    view(<ControlsFinder client={makeClient({ catalog: ENTRIES })} />);
    await screen.findByTestId("control-setting-agent.limits.maxToolSteps");
    fireEvent.change(screen.getByRole("searchbox", { name: "Search controls" }), { target: { value: "timeline" } });
    expect(screen.getByTestId("control-area-turns")).toBeInTheDocument();
    expect(screen.queryByTestId("control-area-approvals")).toBeNull();
    expect(screen.getByTestId("controls-count")).toHaveTextContent(/matching controls/);

    fireEvent.change(screen.getByRole("searchbox", { name: "Search controls" }), { target: { value: "time per tool" } });
    expect(screen.getByTestId("control-setting-agent.limits.toolTimeoutSeconds")).toBeInTheDocument();
    expect(screen.queryByTestId("control-setting-agent.limits.maxToolSteps")).toBeNull();

    fireEvent.change(screen.getByRole("searchbox", { name: "Search controls" }), { target: { value: "zzzzqq" } });
    expect(screen.getByTestId("controls-count")).toHaveTextContent('Nothing matches "zzzzqq"');
    expect(screen.getByTestId("controls-no-match")).toBeInTheDocument();
  });

  it("does not offer a page the person has no scope for", async () => {
    auth.isAdmin = false;
    auth.hasPermission = (n) => n === "MANAGE_APPROVALS";
    view(<ControlsFinder client={makeClient({ catalog: ENTRIES })} />);
    await screen.findByTestId("control-setting-agent.limits.maxToolSteps");
    expect(within(screen.getByTestId("control-area-approvals")).getByRole("link")).toHaveAttribute("href", "/admin/approvals");
    expect(within(screen.getByTestId("control-area-guardrails")).queryByRole("link")).toBeNull();
    expect(screen.getByTestId("control-area-guardrails-restricted")).toBeInTheDocument();
    expect(within(screen.getByTestId("control-area-agentBehavior")).getByRole("link")).toBeInTheDocument();
  });

  it("shows a loading state, then an error with retry that works", async () => {
    const client = makeClient({ catalog: ENTRIES });
    client.getCatalog.mockRejectedValueOnce(new Error("offline"));
    view(<ControlsFinder client={client} />);
    expect(screen.getByRole("status", { name: "Loading the list of settings" })).toHaveAttribute("aria-busy", "true");
    const alert = await screen.findByTestId("controls-error");
    expect(alert).toHaveTextContent("offline");
    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(screen.getByTestId("control-setting-agent.limits.maxToolSteps")).toBeInTheDocument());
  });

  it("says so when the catalog is empty, while the control areas stay available", async () => {
    view(<ControlsFinder client={makeClient({ catalog: [] })} />);
    expect(await screen.findByTestId("controls-empty")).toBeInTheDocument();
    expect(screen.getByTestId("control-area-runtimeSettings")).toBeInTheDocument();
  });
});
