// The real workspace settings screen with only the settings client faked: grouping, role handling, filters, saving
// (including the typed reason for risky changes), group reset, export / import with a diff, change history, deep links.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { SettingsApiError } from "../../lib/api/settings";
import type { EffectiveSettings, SettingHistoryRow } from "../../lib/api/settings";
import { CATALOG, defaultEffective, eff, historyPage, makeClient, renderSettings, type FakeClient } from "../../components/settings/settingsTestKit";
import RuntimeSettingsView from "./RuntimeSettingsView";
import { OVERRIDES_FORMAT } from "./overridesModel";

const STEPS = "agent.limits.maxToolSteps";
const TIMEOUT = "agent.limits.turnTimeoutSeconds";
const OUTPUT = "agent.limits.toolOutputMaxChars";
const EXPIRY = "approvals.expiry.ttl";
const FAILMODE = "guardrails.failMode";

function effectiveWith(over: Record<string, ReturnType<typeof eff>>): EffectiveSettings {
  return { values: { ...defaultEffective().values, ...over } };
}

async function open(client: FakeClient, role: "TENANT_ADMIN" | "EDITOR" | "VIEWER" = "TENANT_ADMIN") {
  const view = renderSettings(<RuntimeSettingsView client={client} viewerRole={role} />);
  await screen.findByRole("spinbutton", { name: "Max tool steps" });
  return view;
}
const steps = () => screen.getByRole("spinbutton", { name: "Max tool steps" }) as HTMLInputElement;
const field = (key: string) => screen.getByTestId(`setting-${key}`);
const type = (el: HTMLElement, value: string) => fireEvent.change(el, { target: { value } });
const saveIn = (g: string) => within(screen.getByTestId(`settings-group-${g}`)).getByRole("button", { name: "Save changes" });

beforeEach(() => {
  window.location.hash = "";
});
afterEach(() => {
  cleanup();
  window.location.hash = "";
  vi.restoreAllMocks();
});

describe("layout", () => {
  it("shows every group in the documented order with the security group set apart and explained", async () => {
    await open(makeClient({ effective: effectiveWith({ [STEPS]: eff(12, "tenant"), [TIMEOUT]: eff(300, "tenant") }) }));
    const sections = screen.getAllByTestId(/^runtime-section-/).map((n) => n.getAttribute("data-testid"));
    expect(sections).toEqual(["runtime-section-agent", "runtime-section-chat", "runtime-section-approvals", "runtime-section-quality", "runtime-section-guardrails", "runtime-section-aux"]);

    expect(screen.getByTestId("runtime-summary")).toHaveTextContent("2 of 10 settings are changed in this workspace");
    const note = screen.getByTestId("security-note-guardrails");
    expect(note).toHaveTextContent("can only be made stricter than the platform value, never weaker");
    expect(within(screen.getByTestId("runtime-section-guardrails")).getByTestId(`setting-${FAILMODE}`)).toBeInTheDocument();
    expect(screen.queryByTestId("security-note-agent")).toBeNull();
    // jump links to each group
    const nav = screen.getByRole("navigation", { name: "Jump to a group" });
    expect(within(nav).getByRole("link", { name: "Guardrails" })).toHaveAttribute("href", "#group-guardrails");
  });

  it("shows loading, then an error with retry", async () => {
    const client = makeClient();
    client.getEffective.mockRejectedValueOnce(new Error("down"));
    renderSettings(<RuntimeSettingsView client={client} viewerRole="TENANT_ADMIN" />);
    expect(screen.getByRole("status", { name: "Loading settings" })).toHaveAttribute("aria-busy", "true");
    const alert = await screen.findByTestId("settings-error");
    expect(alert).toHaveTextContent("down");
    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("spinbutton", { name: "Max tool steps" })).toBeInTheDocument();
  });

  it("says so when nothing is configurable", async () => {
    renderSettings(<RuntimeSettingsView client={makeClient({ catalog: [] })} viewerRole="TENANT_ADMIN" />);
    expect(await screen.findByTestId("settings-empty")).toHaveTextContent("There are no settings you can change here.");
  });
});

describe("roles", () => {
  it("lets a viewer read every value but not change, import or reset anything", async () => {
    const client = makeClient({ effective: effectiveWith({ [STEPS]: eff(12, "tenant") }) });
    await open(client, "VIEWER");
    expect(screen.getByTestId("runtime-readonly")).toHaveTextContent("Only workspace admins can change these settings");
    expect(steps()).toHaveValue("12");
    expect(steps()).toHaveAttribute("readonly");
    expect(within(field(STEPS)).getByTestId("setting-blocked")).toHaveTextContent("Your role cannot change this setting");
    expect(screen.queryByRole("button", { name: "Import overrides" })).toBeNull();
    expect(screen.queryByTestId("reset-group-agent")).toBeNull();
    expect(screen.getByRole("button", { name: "Export overrides" })).toBeEnabled(); // reading is fine
  });

  it("lets a tenant admin edit and does not show the view-only notice", async () => {
    await open(makeClient());
    expect(screen.queryByTestId("runtime-readonly")).toBeNull();
    expect(steps()).not.toHaveAttribute("readonly");
  });

  it("shows a viewer why there is no change history instead of calling the API", async () => {
    const client = makeClient();
    await open(client, "EDITOR");
    fireEvent.click(screen.getByRole("tab", { name: "Change history" }));
    expect(await screen.findByTestId("history-forbidden")).toHaveTextContent("only visible to workspace admins");
    expect(client.getHistory).not.toHaveBeenCalled();
  });

  it("shows a setting the operator pinned as read-only with the reason", async () => {
    await open(makeClient({ effective: effectiveWith({ [STEPS]: eff(5, "platform", { locked: true, lockedReason: "Cost control" }) }) }));
    expect(steps()).toHaveAttribute("readonly");
    expect(within(field(STEPS)).getByTestId("setting-locked")).toHaveTextContent("Locked by your administrator: Cost control");
  });
});

describe("filters", () => {
  it("filters to settings changed in this workspace, combines with search, and explains an empty result", async () => {
    await open(makeClient({ effective: effectiveWith({ [STEPS]: eff(12, "tenant"), [EXPIRY]: eff(7200000, "tenant"), [OUTPUT]: eff(5000, "platform") }) }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Changed in this workspace only" }));
    expect(screen.getByTestId(`setting-${STEPS}`)).toBeInTheDocument();
    expect(screen.getByTestId(`setting-${EXPIRY}`)).toBeInTheDocument();
    expect(screen.queryByTestId(`setting-${OUTPUT}`)).toBeNull(); // a platform value is not a workspace change
    expect(screen.queryByTestId(`setting-${FAILMODE}`)).toBeNull();

    type(screen.getByRole("searchbox", { name: "Search settings" }), "expiry");
    expect(screen.getByTestId(`setting-${EXPIRY}`)).toBeInTheDocument();
    expect(screen.queryByTestId(`setting-${STEPS}`)).toBeNull();

    type(screen.getByRole("searchbox", { name: "Search settings" }), "zzzz");
    expect(screen.getByTestId("settings-empty")).toHaveTextContent("No settings match your filters.");
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getByTestId(`setting-${FAILMODE}`)).toBeInTheDocument();
  });

  it("tells the admin when nothing has been changed yet", async () => {
    await open(makeClient());
    fireEvent.click(screen.getByRole("checkbox", { name: "Changed in this workspace only" }));
    expect(screen.getByTestId("settings-empty")).toHaveTextContent("No setting has been changed in this workspace yet");
  });
});

describe("saving", () => {
  it("writes a medium-risk change at tenant scope without a reason prompt and shows it as a workspace value", async () => {
    const client = makeClient();
    await open(client);
    type(steps(), "12");
    fireEvent.click(saveIn("agent"));
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("tenant", STEPS, { value: 12 }, undefined));
    expect(await screen.findByTestId("settings-message")).toHaveTextContent("Saved. Settings updated: 1.");
    expect(within(field(STEPS)).getByTestId("setting-source")).toHaveTextContent("Workspace");
    expect(screen.getByTestId("runtime-summary")).toHaveTextContent("1 of 10 settings");
  });

  it("asks for a typed reason before a high-risk change and sends it", async () => {
    const client = makeClient();
    await open(client);
    type(screen.getByRole("spinbutton", { name: "Approval expiry" }), "2");
    fireEvent.click(saveIn("approvals"));
    const dialog = await screen.findByTestId("risky-change-dialog");
    expect(client.put).not.toHaveBeenCalled();
    expect(within(dialog).getByTestId(`risky-change-${EXPIRY}`)).toHaveTextContent("Requests older than this are rejected automatically.");
    const confirm = within(dialog).getByRole("button", { name: "Confirm and save" });
    expect(confirm).toBeDisabled();
    type(within(dialog).getByRole("textbox", { name: "Reason for this change" }), "Legal review takes two hours");
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("tenant", EXPIRY, { value: 7200000, reason: "Legal review takes two hours" }, undefined));
  });

  it("rolls a rejected change back and shows the server's reason inline", async () => {
    const client = makeClient();
    client.put.mockRejectedValueOnce(new SettingsApiError("Maximum is 40 here", { status: 400, code: "SETTING_INVALID", key: STEPS, min: 1, max: 40 }));
    await open(client);
    type(steps(), "45");
    // 45 is inside the catalog's own bounds (1..50), so the client lets it through and the server refuses
    fireEvent.click(saveIn("agent"));
    const err = await within(field(STEPS)).findByTestId("setting-server-error");
    expect(err).toHaveTextContent("Maximum is 40 here");
    expect(within(field(STEPS)).getByTestId("setting-effective")).toHaveTextContent("In effect now: 8");
    expect(steps()).toHaveValue("45"); // the draft is kept for correction
  });

  it("keeps drafts when the user switches to the history tab and back", async () => {
    await open(makeClient());
    type(steps(), "20");
    fireEvent.click(screen.getByRole("tab", { name: "Change history" }));
    fireEvent.click(screen.getByRole("tab", { name: "Settings" }));
    expect(steps()).toHaveValue("20");
  });

  it("warns before the tab is closed while there are unsaved edits", async () => {
    await open(makeClient());
    const clean = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);
    type(steps(), "20");
    const dirty = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });
});

describe("reset a group", () => {
  const overridden = () => effectiveWith({ [STEPS]: eff(12, "tenant"), [TIMEOUT]: eff(300, "tenant"), [EXPIRY]: eff(7200000, "tenant") });

  it("confirms, then resets every override in that group only", async () => {
    const client = makeClient({ effective: overridden() });
    await open(client);
    const group = screen.getByTestId("runtime-section-agent");
    expect(within(group).getByText("2 changed in this group")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("reset-group-agent"));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Reset Agent runtime to inherited values?");
    expect(dialog).toHaveTextContent("removes 2 workspace overrides");
    expect(client.reset).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Reset to inherited" }));
    await waitFor(() => expect(client.reset).toHaveBeenCalledTimes(2));
    expect(client.reset.mock.calls.map((c) => c[1]).sort()).toEqual([STEPS, TIMEOUT].sort());
    expect(client.reset.mock.calls.every((c) => c[0] === "tenant")).toBe(true);
    await waitFor(() => expect(screen.getByTestId("settings-message")).toHaveTextContent("Reset 2 settings to their inherited values."));
  });

  it("changes nothing when cancelled and reports the ones that failed", async () => {
    const client = makeClient({ effective: overridden() });
    await open(client);
    fireEvent.click(screen.getByTestId("reset-group-agent"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel" }));
    expect(client.reset).not.toHaveBeenCalled();

    client.reset.mockRejectedValueOnce(new SettingsApiError("nope", { status: 500 }));
    fireEvent.click(screen.getByTestId("reset-group-agent"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Reset to inherited" }));
    await waitFor(() => expect(screen.getByTestId("settings-message")).toHaveTextContent("Reset 1 settings; 1 could not be reset"));
  });

  it("asks for ONE typed reason for the high-risk and security settings of a group, and sends it with each reset", async () => {
    const client = makeClient({ effective: effectiveWith({ [EXPIRY]: eff(7200000, "tenant"), [FAILMODE]: eff("open", "tenant") }) });
    await open(client);
    fireEvent.click(screen.getByTestId("reset-group-approvals"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Reset to inherited" }));
    const dialog = await screen.findByRole("alertdialog", { name: /Confirm risky changes/ });
    expect(client.reset).not.toHaveBeenCalled();
    expect(within(dialog).getByTestId(`risky-change-${EXPIRY}`)).toHaveTextContent("Approval expiry");
    expect(within(dialog).getByTestId("risky-reset-note")).toBeInTheDocument();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Reason for this change" }), { target: { value: "Back to the platform value" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm and save" }));
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("tenant", EXPIRY, undefined, "Back to the platform value"));
    await waitFor(() => expect(screen.getByTestId("settings-message")).toHaveTextContent("Reset 1 settings to their inherited values."));
  });

  it("leaves the settings that need a reason alone when that prompt is cancelled", async () => {
    const client = makeClient({ effective: effectiveWith({ [EXPIRY]: eff(7200000, "tenant") }) });
    await open(client);
    fireEvent.click(screen.getByTestId("reset-group-approvals"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Reset to inherited" }));
    fireEvent.click(within(await screen.findByRole("alertdialog", { name: /Confirm risky changes/ })).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.getByTestId("settings-message")).toHaveTextContent("Reset 0 settings. 1 that need a reason were left as they are."));
    expect(client.reset).not.toHaveBeenCalled();
  });

  it("asks for the reason when one high-risk setting is reset from its own field", async () => {
    const client = makeClient({ effective: effectiveWith({ [EXPIRY]: eff(7200000, "tenant") }) });
    await open(client);
    fireEvent.click(within(screen.getByTestId(`setting-${EXPIRY}`)).getByTestId("setting-reset"));
    const dialog = await screen.findByRole("alertdialog");
    expect(client.reset).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Reason for this change" }), { target: { value: "Legal no longer needs it" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm and save" }));
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("tenant", EXPIRY, undefined, "Legal no longer needs it"));
  });

  it("offers no group reset where nothing is overridden", async () => {
    await open(makeClient());
    expect(screen.queryByTestId("reset-group-agent")).toBeNull();
  });
});

describe("export", () => {
  it("downloads the workspace overrides as a file the importer reads back", async () => {
    let blob: Blob | null = null;
    const create = vi.fn((b: Blob) => {
      blob = b;
      return "blob:x";
    });
    Object.defineProperty(URL, "createObjectURL", { configurable: true, writable: true, value: create });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, writable: true, value: vi.fn() });
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    await open(makeClient({ effective: effectiveWith({ [STEPS]: eff(12, "tenant"), [OUTPUT]: eff(5000, "platform") }) }));
    fireEvent.click(screen.getByRole("button", { name: "Export overrides" }));
    expect(click).toHaveBeenCalled();
    const text = await new Promise<string>((resolve) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result));
      r.readAsText(blob as unknown as Blob);
    });
    const parsed = JSON.parse(text);
    expect(parsed).toMatchObject({ format: OVERRIDES_FORMAT, version: 1, scope: "tenant", overrides: { [STEPS]: 12 } });
    expect(Object.keys(parsed.overrides)).toEqual([STEPS]);
    expect(screen.getByTestId("settings-message")).toHaveTextContent("Exported 1 workspace overrides");
  });

  it("says there is nothing to export when no setting was changed", async () => {
    Object.defineProperty(URL, "createObjectURL", { configurable: true, writable: true, value: vi.fn(() => "blob:x") });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, writable: true, value: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    await open(makeClient());
    fireEvent.click(screen.getByRole("button", { name: "Export overrides" }));
    expect(screen.getByTestId("settings-message")).toHaveTextContent("Nothing to export");
  });
});

describe("import", () => {
  const fileText = (overrides: Record<string, unknown>) => JSON.stringify({ format: OVERRIDES_FORMAT, version: 1, overrides });
  async function openImport(client: FakeClient, text: string) {
    await open(client);
    fireEvent.click(screen.getByTestId("import-button"));
    const dialog = await screen.findByTestId("import-dialog");
    type(within(dialog).getByTestId("import-text"), text);
    fireEvent.click(within(dialog).getByTestId("import-review-button"));
    return dialog;
  }

  it("shows a diff and the entries that cannot be applied before writing anything", async () => {
    const client = makeClient();
    const dialog = await openImport(client, fileText({ [STEPS]: 12, [TIMEOUT]: 1, "no.such.key": 3, [FAILMODE]: "sideways" }));
    expect(within(dialog).getByTestId("import-summary")).toHaveTextContent("1 to change, 0 already in force, 3 cannot be applied.");
    const change = within(dialog).getByTestId(`import-change-${STEPS}`);
    expect(change).toHaveTextContent("Max tool steps");
    expect(change).toHaveTextContent("8");
    expect(change).toHaveTextContent("12");
    expect(within(dialog).getByTestId(`import-problem-${TIMEOUT}`)).toHaveTextContent("The value is too low. The minimum is");
    expect(within(dialog).getByTestId("import-problem-no.such.key")).toHaveTextContent("There is no setting with this key.");
    expect(within(dialog).getByTestId(`import-problem-${FAILMODE}`)).toHaveTextContent("not one of the allowed options");
    expect(client.put).not.toHaveBeenCalled();
  });

  it("applies the valid changes only after the admin confirms", async () => {
    const client = makeClient();
    const dialog = await openImport(client, fileText({ [STEPS]: 12, "quality.judge.temperature": 0.5 }));
    fireEvent.click(within(dialog).getByTestId("import-apply"));
    await waitFor(() => expect(client.put).toHaveBeenCalledTimes(2));
    expect(client.put).toHaveBeenCalledWith("tenant", STEPS, { value: 12 }, undefined);
    expect(client.put).toHaveBeenCalledWith("tenant", "quality.judge.temperature", { value: 0.5 }, undefined);
    await waitFor(() => expect(screen.queryByTestId("import-dialog")).toBeNull());
    expect(screen.getByTestId("settings-message")).toHaveTextContent("Import finished: 2 settings updated.");
  });

  it("asks for the reason when the file changes a high-risk setting, and writes nothing if that is cancelled", async () => {
    const client = makeClient();
    const dialog = await openImport(client, fileText({ [EXPIRY]: 7200000, [STEPS]: 12 }));
    expect(dialog).toHaveTextContent("you will be asked for a reason");
    fireEvent.click(within(dialog).getByTestId("import-apply"));
    const risky = await screen.findByTestId("risky-change-dialog");
    expect(client.put).not.toHaveBeenCalled();
    fireEvent.click(within(risky).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.getByTestId("settings-message")).toHaveTextContent("Import cancelled. No settings were changed."));
    expect(client.put).not.toHaveBeenCalled();
    expect(screen.getByTestId("import-dialog")).toBeInTheDocument(); // still open so the admin can retry
  });

  it("sends one reason with the risky changes of an import", async () => {
    const client = makeClient();
    const dialog = await openImport(client, fileText({ [EXPIRY]: 7200000, [STEPS]: 12 }));
    fireEvent.click(within(dialog).getByTestId("import-apply"));
    const risky = await screen.findByTestId("risky-change-dialog");
    type(within(risky).getByRole("textbox", { name: "Reason for this change" }), "Restore the agreed settings");
    fireEvent.click(within(risky).getByRole("button", { name: "Confirm and save" }));
    await waitFor(() => expect(client.put).toHaveBeenCalledTimes(2));
    expect(client.put).toHaveBeenCalledWith("tenant", EXPIRY, { value: 7200000, reason: "Restore the agreed settings" }, undefined);
    expect(client.put).toHaveBeenCalledWith("tenant", STEPS, { value: 12 }, undefined);
  });

  it("can also remove the overrides the file does not contain, only when asked", async () => {
    const client = makeClient({ effective: effectiveWith({ [STEPS]: eff(12, "tenant"), [TIMEOUT]: eff(300, "tenant") }) });
    const dialog = await openImport(client, fileText({ [STEPS]: 12 }));
    expect(within(dialog).getByTestId("import-nothing")).toBeInTheDocument();
    expect(within(dialog).getByTestId("import-apply")).toBeDisabled();
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /Also remove workspace overrides/ }));
    expect(within(dialog).getByTestId("import-resets")).toHaveTextContent("Turn timeout");
    fireEvent.click(within(dialog).getByTestId("import-apply"));
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("tenant", TIMEOUT, undefined));
    expect(client.reset).toHaveBeenCalledTimes(1);
    expect(client.put).not.toHaveBeenCalled();
  });

  it("rejects text that is not an overrides file, with a plain-language message", async () => {
    const client = makeClient();
    await open(client);
    fireEvent.click(screen.getByTestId("import-button"));
    const dialog = await screen.findByTestId("import-dialog");
    type(within(dialog).getByTestId("import-text"), "{ not json");
    fireEvent.click(within(dialog).getByTestId("import-review-button"));
    expect(within(dialog).getByTestId("import-error")).toHaveTextContent("This is not valid JSON");
    type(within(dialog).getByTestId("import-text"), JSON.stringify({ hello: 1 }));
    fireEvent.click(within(dialog).getByTestId("import-review-button"));
    expect(within(dialog).getByTestId("import-error")).toHaveTextContent("not a settings overrides file exported from this page");
  });

  it("reads a chosen file", async () => {
    const client = makeClient();
    await open(client);
    fireEvent.click(screen.getByTestId("import-button"));
    const dialog = await screen.findByTestId("import-dialog");
    const file = new File([fileText({ [STEPS]: 12 })], "overrides.json", { type: "application/json" });
    await act(async () => {
      fireEvent.change(within(dialog).getByTestId("import-file"), { target: { files: [file] } });
    });
    expect(await within(dialog).findByTestId(`import-change-${STEPS}`)).toBeInTheDocument();
  });

  it("keeps the review open and reports it when some changes fail", async () => {
    const client = makeClient();
    client.put.mockImplementation(async (_s, key) => {
      if (key === STEPS) throw new SettingsApiError("Too high for your plan", { status: 400, code: "SETTING_INVALID", key });
    });
    const dialog = await openImport(client, fileText({ [STEPS]: 12, "quality.judge.temperature": 0.5 }));
    fireEvent.click(within(dialog).getByTestId("import-apply"));
    await waitFor(() => expect(screen.getByTestId("settings-message")).toHaveTextContent("Import partly done: 1 settings updated, 1 could not be applied"));
    expect(screen.getByTestId("import-dialog")).toBeInTheDocument();
    // what is still left is shown: only the failed one
    expect(within(screen.getByTestId("import-dialog")).queryByTestId("import-change-quality.judge.temperature")).toBeNull();
    expect(within(screen.getByTestId("import-dialog")).getByTestId(`import-change-${STEPS}`)).toBeInTheDocument();
  });
});

describe("change history", () => {
  const row = (key: string, version: number, at: string, over: Partial<SettingHistoryRow> = {}): SettingHistoryRow => ({
    key,
    scope: "tenant",
    version,
    action: "SET",
    value: 12,
    previousValue: 8,
    actor: "ada@example.com",
    at,
    reason: "Longer investigations",
    ...over,
  });
  const AGENT = "0f5c2d7e-1111-4222-8333-944455556666";
  const openHistory = async (client: FakeClient) => {
    await open(client);
    fireEvent.click(screen.getByRole("tab", { name: "Change history" }));
  };

  it("loads the whole history in ONE request and shows workspace and agent changes together with their level", async () => {
    const client = makeClient();
    client.getHistoryPage.mockResolvedValue(
      historyPage([
        row(STEPS, 2, "2026-10-03T10:00:00Z", { value: null, previousValue: 12, reason: null, actor: "bob@example.com" }),
        row(STEPS, 1, "2026-10-02T12:00:00Z", { scope: "agent", agentId: AGENT, value: 30, previousValue: null, reason: "Big job" }),
        row(EXPIRY, 1, "2026-10-02T10:00:00Z", { value: 7200000, previousValue: 3600000, reason: "Legal needs two hours" }),
      ])
    );
    await openHistory(client);
    const rows = await screen.findAllByTestId("history-row");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Max tool steps");
    expect(rows[0]).toHaveTextContent("bob@example.com");
    expect(rows[0]).toHaveTextContent("inherited value");
    expect(rows[0]).toHaveTextContent("No reason given");
    expect(within(rows[0]).getByTestId("history-scope")).toHaveTextContent("Workspace");
    expect(within(rows[1]).getByTestId("history-scope")).toHaveTextContent("Agent 0f5c2d7e");
    expect(within(rows[1]).getByTestId("history-scope").querySelector("span")).toHaveAttribute("title", AGENT);
    expect(rows[1]).toHaveTextContent("Big job");
    expect(rows[2]).toHaveTextContent("1 hours");
    expect(rows[2]).toHaveTextContent("2 hours");
    // one request in all, for the workspace view, never one per setting
    expect(client.getHistoryPage).toHaveBeenCalledTimes(1);
    expect(client.getHistoryPage).toHaveBeenCalledWith({ scope: "all", limit: 100 });
    expect(client.getHistory).not.toHaveBeenCalled();

    type(screen.getByRole("searchbox", { name: "Search the change history" }), "legal");
    expect(screen.getAllByTestId("history-row")).toHaveLength(1);
    type(screen.getByRole("searchbox", { name: "Search the change history" }), "0f5c2d7e");
    expect(screen.getAllByTestId("history-row")).toHaveLength(1);
    type(screen.getByRole("searchbox", { name: "Search the change history" }), "nobody");
    expect(screen.getByTestId("history-no-match")).toBeInTheDocument();
  });

  it("knows a key that only agents have, and shows a key that left the catalog with its raw values", async () => {
    const agentOnly = { ...CATALOG[0], key: "agent.only.key", scopes: ["agent" as const] };
    const client = makeClient({ catalog: [...CATALOG, agentOnly] });
    client.getHistoryPage.mockResolvedValue(
      historyPage([
        row("agent.only.key", 1, "2026-10-03T10:00:00Z", { scope: "agent", agentId: AGENT }),
        row("removed.old.key", 1, "2026-10-02T10:00:00Z", { value: "x", previousValue: null }),
      ])
    );
    await openHistory(client);
    const rows = await screen.findAllByTestId("history-row");
    expect(rows[0]).toHaveTextContent("agent.only.key");
    expect(rows[1]).toHaveTextContent("Key");
    expect(rows[1]).toHaveTextContent("removed.old.key");
  });

  it("pages on the server: Show more asks for the next page, never repeats a row, and says how many exist", async () => {
    const client = makeClient();
    const first = [row(STEPS, 2, "2026-10-03T10:00:00Z"), row(STEPS, 1, "2026-10-02T10:00:00Z")];
    client.getHistoryPage
      .mockResolvedValueOnce(historyPage(first, { total: 3, hasMore: true }))
      .mockResolvedValueOnce(historyPage([first[1], row(EXPIRY, 1, "2026-10-01T10:00:00Z")], { total: 3, offset: 2, hasMore: false }));
    await openHistory(client);
    expect(await screen.findAllByTestId("history-row")).toHaveLength(2);
    expect(screen.getByText("Showing 2 of 3 changes, newest first.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show more" }));
    await waitFor(() => expect(screen.getAllByTestId("history-row")).toHaveLength(3));
    expect(client.getHistoryPage).toHaveBeenLastCalledWith({ scope: "all", limit: 100, offset: 2 });
    expect(screen.getByText("Showing 3 of 3 changes, newest first.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show more" })).toBeNull();
  });

  it("keeps what is shown when the next page fails, says so, and lets the person try again", async () => {
    const client = makeClient();
    client.getHistoryPage
      .mockResolvedValueOnce(historyPage([row(STEPS, 1, "2026-10-02T10:00:00Z")], { total: 2, hasMore: true }))
      .mockRejectedValueOnce(new Error("503"))
      .mockResolvedValueOnce(historyPage([row(EXPIRY, 1, "2026-10-01T10:00:00Z")], { total: 2, offset: 1 }));
    await openHistory(client);
    await screen.findAllByTestId("history-row");
    fireEvent.click(screen.getByRole("button", { name: "Show more" }));
    expect(await screen.findByTestId("history-more-error")).toHaveTextContent("More changes could not be loaded. 503");
    expect(screen.getAllByTestId("history-row")).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Show more" }));
    await waitFor(() => expect(screen.getAllByTestId("history-row")).toHaveLength(2));
    expect(screen.queryByTestId("history-more-error")).toBeNull();
  });

  it("filters by level on the server and starts over", async () => {
    const client = makeClient();
    client.getHistoryPage.mockResolvedValue(historyPage([row(STEPS, 1, "2026-10-02T10:00:00Z")]));
    await openHistory(client);
    await screen.findAllByTestId("history-row");
    fireEvent.click(screen.getByRole("combobox", { name: "Show changes at" }));
    fireEvent.click(await screen.findByRole("option", { name: "Workspace only" }));
    await waitFor(() => expect(client.getHistoryPage).toHaveBeenLastCalledWith({ scope: "tenant", limit: 100 }));
    fireEvent.click(screen.getByRole("combobox", { name: "Show changes at" }));
    fireEvent.click(await screen.findByRole("option", { name: "Agents only" }));
    await waitFor(() => expect(client.getHistoryPage).toHaveBeenLastCalledWith({ scope: "agent", limit: 100 }));
  });

  it("says when nothing was ever changed", async () => {
    await openHistory(makeClient());
    expect(await screen.findByTestId("history-empty")).toBeInTheDocument();
  });

  it("shows an error with a retry when the history cannot be loaded", async () => {
    const client = makeClient();
    client.getHistoryPage.mockRejectedValueOnce(new Error("boom"));
    await openHistory(client);
    const alert = await screen.findByTestId("history-error");
    expect(alert).toHaveTextContent("The change history could not be loaded. boom");
    client.getHistoryPage.mockResolvedValue(historyPage([row(STEPS, 1, "2026-10-02T10:00:00Z")]));
    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findAllByTestId("history-row")).toHaveLength(1);
  });
});

describe("deep links", () => {
  it("scrolls to, focuses and marks the setting named in the URL", async () => {
    window.location.hash = `#${EXPIRY}`;
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    renderSettings(<RuntimeSettingsView client={makeClient()} viewerRole="TENANT_ADMIN" />);
    const input = await screen.findByRole("spinbutton", { name: "Approval expiry" });
    await waitFor(() => expect(input).toHaveFocus());
    expect(scroll).toHaveBeenCalled();
    expect(field(EXPIRY)).toHaveAttribute("id", EXPIRY); // so the plain #key link works too
    expect(field(EXPIRY).className).toContain("ring-2");
  });

  it("follows a link that changes while the page is open", async () => {
    await open(makeClient());
    expect(steps()).not.toHaveFocus();
    await act(async () => {
      window.location.hash = `#${OUTPUT}`;
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: "Tool output limit" })).toHaveFocus());
  });

  it("clears a filter that hides the linked setting", async () => {
    await open(makeClient());
    type(screen.getByRole("searchbox", { name: "Search settings" }), "toxicity");
    expect(screen.queryByTestId(`setting-${EXPIRY}`)).toBeNull();
    await act(async () => {
      window.location.hash = `#${EXPIRY}`;
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: "Approval expiry" })).toHaveFocus());
    expect(screen.getByRole("searchbox", { name: "Search settings" })).toHaveValue("");
  });

  it("jumps to a group and says when the link names nothing", async () => {
    window.location.hash = "#group-guardrails";
    renderSettings(<RuntimeSettingsView client={makeClient()} viewerRole="TENANT_ADMIN" />);
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    await waitFor(() => expect(screen.getByTestId("runtime-section-guardrails")).toHaveFocus());
    cleanup();

    window.location.hash = "#not.a.setting";
    renderSettings(<RuntimeSettingsView client={makeClient()} viewerRole="TENANT_ADMIN" />);
    expect(await screen.findByTestId("anchor-unknown")).toHaveTextContent('no setting called "not.a.setting"');
  });

  it("opens the settings tab for a link even when the history tab was showing", async () => {
    await open(makeClient());
    fireEvent.click(screen.getByRole("tab", { name: "Change history" }));
    await act(async () => {
      window.location.hash = `#${STEPS}`;
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    await waitFor(() => expect(steps()).toHaveFocus());
  });
});
