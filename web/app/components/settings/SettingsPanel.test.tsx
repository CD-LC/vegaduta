import { describe, expect, it } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { SettingsApiError } from "../../lib/api/settings";
import type { EffectiveSettings } from "../../lib/api/settings";
import { AuthContext } from "../AuthProvider";
import SettingsPanel from "./SettingsPanel";
import { CATALOG, defaultEffective, eff, makeClient, renderSettings, type FakeClient } from "./settingsTestKit";

const STEPS = "agent.limits.maxToolSteps";

async function ready(client: FakeClient, props: Partial<React.ComponentProps<typeof SettingsPanel>> = {}) {
  const view = renderSettings(<SettingsPanel scope="tenant" client={client} {...props} />);
  await screen.findByRole("spinbutton", { name: "Max tool steps" });
  return view;
}
const field = (key: string) => screen.getByTestId(`setting-${key}`);
const group = (name: string) => screen.getByTestId(`settings-group-${name}`);
const steps = () => screen.getByRole("spinbutton", { name: "Max tool steps" }) as HTMLInputElement;
const type = (el: HTMLElement, value: string) => fireEvent.change(el, { target: { value } });
const saveIn = (g: string) => within(group(g)).getByRole("button", { name: "Save changes" });

describe("SettingsPanel rendering by type", () => {
  it("shows a loading state, then renders every catalog type with an accessible name", async () => {
    const client = makeClient();
    renderSettings(<SettingsPanel scope="tenant" client={client} />);
    expect(screen.getByRole("status", { name: "Loading settings" })).toHaveAttribute("aria-busy", "true");

    expect(await screen.findByRole("spinbutton", { name: "Max tool steps" })).toHaveValue("8");
    expect(screen.getByRole("spinbutton", { name: "Turn timeout" })).toHaveValue("2"); // 120 s shown as 2 minutes
    expect(screen.getByRole("combobox", { name: "Unit for Turn timeout" })).toHaveTextContent("minutes");
    expect(screen.getByRole("spinbutton", { name: "Tool output limit" })).toHaveValue("20000");
    expect(screen.getByRole("spinbutton", { name: "Judge temperature" })).toHaveValue("0.2");
    expect(screen.getByRole("switch", { name: "Cache replies that used tools" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("textbox", { name: "Helper model" })).toHaveValue("small-model");
    expect(screen.getByRole("textbox", { name: "Operator note" })).toHaveValue("");
    expect(screen.getByRole("combobox", { name: "Guardrail failure mode" })).toHaveTextContent("Fail closed");
    expect(screen.getByRole("slider", { name: "Toxicity threshold (slider)" })).toHaveValue("80");
    expect(screen.getByRole("spinbutton", { name: "Toxicity threshold" })).toHaveValue("80");
    expect(screen.getByRole("spinbutton", { name: "Approval expiry" })).toHaveValue("1");
    expect(screen.getByRole("combobox", { name: "Unit for Approval expiry" })).toHaveTextContent("hours");

    // grouped under localized headings
    expect(screen.getByRole("heading", { name: "Agent runtime" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Guardrails" })).toBeInTheDocument();
    // description, unit and effective value are all there
    expect(within(field(STEPS)).getByText("How many tool calls one turn may make.")).toBeInTheDocument();
    expect(within(field(STEPS)).getByTestId("setting-effective")).toHaveTextContent("In effect now: 8");
    expect(within(field(STEPS)).getByText("Allowed: 1 to 50.")).toBeInTheDocument();
  });

  it("takes bounds and units from the catalog, not the component", async () => {
    const catalog = CATALOG.map((e) => (e.key === STEPS ? { ...e, min: 3, max: 9 } : e));
    await ready(makeClient({ catalog }));
    type(steps(), "10");
    expect(within(field(STEPS)).getByTestId("setting-validation")).toHaveTextContent("Must be at most 9.");
    type(steps(), "2");
    expect(within(field(STEPS)).getByTestId("setting-validation")).toHaveTextContent("Must be at least 3.");
  });

  it("falls back to a readable label when a setting has no translation", async () => {
    const catalog = [{ ...CATALOG[0], key: "agent.limits.newThing", labelKey: "settings.agent.limits.newThing.label", descriptionKey: "settings.agent.limits.newThing.description" }];
    renderSettings(<SettingsPanel scope="tenant" client={makeClient({ catalog, effective: { values: {} } })} />);
    expect(await screen.findByRole("spinbutton", { name: "New thing" })).toHaveValue("8"); // no value yet: catalog default
  });

  it("shows an error state with a working retry, and empty states", async () => {
    const client = makeClient();
    client.getCatalog.mockRejectedValueOnce(new Error("boom"));
    renderSettings(<SettingsPanel scope="tenant" client={client} />);
    const alert = await screen.findByTestId("settings-error");
    expect(alert).toHaveTextContent("Settings could not be loaded. boom");
    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("spinbutton", { name: "Max tool steps" })).toBeInTheDocument();
  });

  it("says so when the catalog has nothing editable at this scope", async () => {
    renderSettings(<SettingsPanel scope="agent" agentId="ag" client={makeClient({ catalog: [CATALOG.find((e) => e.key === "guardrails.failMode")!] })} />);
    expect(await screen.findByTestId("settings-empty")).toHaveTextContent("There are no settings you can change here.");
  });
});

describe("validation and dirty tracking", () => {
  it("shows inline bounds errors in an aria-live region and blocks saving", async () => {
    const client = makeClient();
    await ready(client);
    type(steps(), "99");
    const region = within(field(STEPS)).getByTestId("setting-validation");
    expect(region).toHaveAttribute("aria-live", "polite");
    expect(region).toHaveTextContent("Must be at most 50.");
    expect(steps()).toHaveAttribute("aria-invalid", "true");
    expect(steps().getAttribute("aria-describedby")).toContain(region.id);

    const save = saveIn("agent");
    expect(save).toHaveAttribute("aria-disabled", "true");
    expect(screen.getByTestId("settings-savebar-agent")).toHaveTextContent("Fix the highlighted fields before saving");
    fireEvent.click(save);
    expect(client.put).not.toHaveBeenCalled();
    expect(steps()).toHaveFocus(); // the problem is where the focus goes

    type(steps(), "2.5");
    expect(region).toHaveTextContent("Enter a whole number.");
    type(steps(), "abc");
    expect(region).toHaveTextContent("Enter a number.");
    type(steps(), "");
    expect(region).toHaveTextContent("Enter a value.");
  });

  it("tracks dirty fields, offers discard, and clears when the value goes back", async () => {
    await ready(makeClient());
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    type(steps(), "12");
    expect(within(field(STEPS)).getByTestId("setting-unsaved")).toBeInTheDocument();
    expect(screen.getByTestId("settings-savebar-agent")).toHaveTextContent("Unsaved changes: 1.");
    fireEvent.click(within(group("agent")).getByRole("button", { name: "Discard changes" }));
    expect(steps()).toHaveValue("8");
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();

    type(steps(), "9");
    type(steps(), "8"); // typed back to the saved value: not a change any more
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
  });

  it("warns before the page is closed while there are unsaved edits, and not otherwise", async () => {
    await ready(makeClient());
    const attempt = () => {
      const ev = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(ev);
      return ev.defaultPrevented;
    };
    expect(attempt()).toBe(false);
    type(steps(), "12");
    expect(attempt()).toBe(true);
    fireEvent.click(within(group("agent")).getByRole("button", { name: "Discard changes" }));
    expect(attempt()).toBe(false);
  });

  it("steps numbers with the arrow keys, clamped to the catalog bounds", async () => {
    await ready(makeClient());
    fireEvent.keyDown(steps(), { key: "ArrowUp" });
    expect(steps()).toHaveValue("9");
    fireEvent.keyDown(steps(), { key: "ArrowDown" });
    fireEvent.keyDown(steps(), { key: "ArrowDown" });
    expect(steps()).toHaveValue("7");
    type(steps(), "50");
    fireEvent.keyDown(steps(), { key: "ArrowUp" });
    expect(steps()).toHaveValue("50");
    const temp = screen.getByRole("spinbutton", { name: "Judge temperature" });
    fireEvent.keyDown(temp, { key: "ArrowUp" });
    expect(temp).toHaveValue("0.3");
  });
});

describe("saving", () => {
  it("saves a changed value to the tenant endpoint and shows it as a tenant override", async () => {
    const client = makeClient();
    await ready(client);
    type(steps(), "12");
    fireEvent.click(saveIn("agent"));
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("tenant", STEPS, { value: 12 }, undefined));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull());
    expect(within(field(STEPS)).getByTestId("setting-effective")).toHaveTextContent("In effect now: 12");
    expect(within(field(STEPS)).getByTestId("setting-source")).toHaveAttribute("data-source", "tenant");
    expect(screen.getByTestId("settings-message")).toHaveTextContent("Saved. Settings updated: 1.");
  });

  it("saves several fields of one group together, converting the duration unit back to the catalog unit", async () => {
    const client = makeClient();
    await ready(client);
    type(steps(), "20");
    type(screen.getByRole("spinbutton", { name: "Turn timeout" }), "3"); // 3 minutes
    fireEvent.click(screen.getByRole("switch", { name: "Cache replies that used tools" }));
    fireEvent.click(saveIn("agent"));
    await waitFor(() => expect(client.put).toHaveBeenCalledTimes(2));
    expect(client.put).toHaveBeenCalledWith("tenant", STEPS, { value: 20 }, undefined);
    expect(client.put).toHaveBeenCalledWith("tenant", "agent.limits.turnTimeoutSeconds", { value: 180 }, undefined);
    // the switch is in the "chat" group: its own bar, untouched by the agent group's save
    expect(within(group("chat")).getByRole("button", { name: "Save changes" })).toBeInTheDocument();
    fireEvent.click(within(group("chat")).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("tenant", "chat.cache.eligibleWithTools", { value: true }, undefined));
  });

  it("changing the duration unit converts what is typed without making the setting dirty", async () => {
    await ready(makeClient());
    const unit = screen.getByRole("combobox", { name: "Unit for Turn timeout" });
    fireEvent.click(unit);
    fireEvent.click(screen.getByRole("option", { name: "seconds" }));
    expect(screen.getByRole("spinbutton", { name: "Turn timeout" })).toHaveValue("120");
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
  });

  it("updates optimistically, then rolls back and explains a 400 from the server", async () => {
    const client = makeClient();
    let rejectPut: (e: unknown) => void = () => {};
    client.put.mockImplementation(() => new Promise<void>((_, rej) => { rejectPut = rej; }));
    await ready(client);
    type(steps(), "12");
    fireEvent.click(saveIn("agent"));

    await waitFor(() => expect(within(field(STEPS)).getByTestId("setting-effective")).toHaveTextContent("In effect now: 12"));
    expect(field(STEPS)).toHaveAttribute("aria-busy", "true");
    expect(steps()).toHaveAttribute("readonly"); // no edits while the write is in flight

    await act(async () => {
      rejectPut(new SettingsApiError("must be between 1 and 10", { status: 400, code: "SETTING_INVALID", key: STEPS, min: 1, max: 10 }));
    });
    await waitFor(() => expect(within(field(STEPS)).getByTestId("setting-effective")).toHaveTextContent("In effect now: 8"));
    const err = within(field(STEPS)).getByTestId("setting-server-error");
    expect(err).toHaveAttribute("role", "alert");
    expect(err).toHaveTextContent("The server did not accept this value: must be between 1 and 10 Allowed range: 1 to 10.");
    expect(within(field(STEPS)).getByTestId("setting-source")).toHaveAttribute("data-source", "default");
    expect(steps()).toHaveValue("12"); // the edit is kept so the user can fix it
    expect(screen.getByTestId("settings-message")).toHaveTextContent("Not saved.");
    expect(steps()).toHaveFocus();
    // editing again clears the stale server error
    type(steps(), "9");
    expect(screen.queryByTestId("setting-server-error")).toBeNull();
  });

  it("rolls back a locked (409) write and says the setting is locked", async () => {
    const client = makeClient();
    client.put.mockRejectedValue(new SettingsApiError("pinned", { status: 409, code: "SETTING_LOCKED", key: STEPS }));
    await ready(client);
    type(steps(), "12");
    fireEvent.click(saveIn("agent"));
    await waitFor(() => expect(within(field(STEPS)).getByTestId("setting-server-error")).toHaveTextContent("locked by your administrator"));
    expect(within(field(STEPS)).getByTestId("setting-effective")).toHaveTextContent("In effect now: 8");
  });

  it("keeps the fields that saved and rolls back only the one that failed", async () => {
    const client = makeClient();
    client.put.mockImplementation(async (_s: string, key: string) => {
      if (key === STEPS) throw new SettingsApiError("nope", { status: 400, code: "SETTING_INVALID", key });
    });
    await ready(client);
    type(steps(), "12");
    type(screen.getByRole("spinbutton", { name: "Tool output limit" }), "5000");
    fireEvent.click(saveIn("agent"));
    await waitFor(() => expect(client.put).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(within(field(STEPS)).getByTestId("setting-effective")).toHaveTextContent("8"));
    const out = field("agent.limits.toolOutputMaxChars");
    await waitFor(() => expect(within(out).getByTestId("setting-source")).toHaveAttribute("data-source", "tenant"));
    expect(within(out).getByTestId("setting-effective")).toHaveTextContent("5,000 characters");
    expect(within(out).queryByTestId("setting-unsaved")).toBeNull();
    expect(within(field(STEPS)).getByTestId("setting-unsaved")).toBeInTheDocument();
  });
});

describe("risky and security changes", () => {
  it("requires a typed reason and an acknowledgment, lists consequences, and sends the reason", async () => {
    const client = makeClient();
    await ready(client);
    type(screen.getByRole("spinbutton", { name: "Toxicity threshold" }), "60");
    fireEvent.click(saveIn("guardrails"));

    const dialog = await screen.findByRole("alertdialog");
    expect(client.put).not.toHaveBeenCalled();
    expect(within(dialog).getByText("Toxicity threshold")).toBeInTheDocument();
    expect(within(dialog).getByText(/Lowering this lets more content through/)).toBeInTheDocument(); // helpKey consequence
    expect(within(dialog).getByText("80%")).toBeInTheDocument();
    expect(within(dialog).getByText("60%")).toBeInTheDocument();
    const reason = within(dialog).getByRole("textbox", { name: "Reason for this change" });
    expect(reason).toHaveFocus();
    const confirm = within(dialog).getByRole("button", { name: "Confirm and save" });
    expect(confirm).toBeDisabled();

    fireEvent.change(reason, { target: { value: "ab" } }); // too short
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /I understand/ }));
    expect(confirm).toBeDisabled();
    fireEvent.change(reason, { target: { value: "Customer escalation, approved by the CISO" } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);

    await waitFor(() =>
      expect(client.put).toHaveBeenCalledWith("tenant", "guardrails.toxicity.threshold", { value: 60, reason: "Customer escalation, approved by the CISO" }, undefined)
    );
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(within(field("guardrails.toxicity.threshold")).getByTestId("setting-effective")).toHaveTextContent("60%");
  });

  it("does nothing when the dialog is cancelled and keeps the draft", async () => {
    const client = makeClient();
    await ready(client);
    fireEvent.click(screen.getByRole("switch", { name: "Cache replies that used tools" })); // low risk, unrelated
    const threshold = screen.getByRole("spinbutton", { name: "Toxicity threshold" });
    type(threshold, "70");
    fireEvent.click(saveIn("guardrails"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(client.put).not.toHaveBeenCalled();
    expect(threshold).toHaveValue("70");
  });

  it("closes on Escape without saving", async () => {
    const client = makeClient();
    await ready(client);
    type(screen.getByRole("spinbutton", { name: "Approval expiry" }), "2");
    fireEvent.click(saveIn("approvals"));
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.keyDown(dialog, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(client.put).not.toHaveBeenCalled();
  });

  it("asks high-risk (non-security) changes for a reason too, but needs no acknowledgment", async () => {
    const client = makeClient();
    await ready(client);
    type(screen.getByRole("spinbutton", { name: "Approval expiry" }), "2"); // 2 hours
    fireEvent.click(saveIn("approvals"));
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).queryByRole("checkbox")).toBeNull();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Reason for this change" }), { target: { value: "Longer review cycle" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm and save" }));
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("tenant", "approvals.expiry.ttl", { value: 7_200_000, reason: "Longer review cycle" }, undefined));
  });

  it("does not interrupt medium and low risk changes with a dialog", async () => {
    const client = makeClient();
    await ready(client);
    type(steps(), "10"); // medium
    fireEvent.click(saveIn("agent"));
    await waitFor(() => expect(client.put).toHaveBeenCalled());
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(client.put.mock.calls[0][2]).toEqual({ value: 10 });
  });

  it("selects enum options with the keyboard-operable select and routes the security change through the dialog", async () => {
    const client = makeClient();
    await ready(client);
    const mode = screen.getByRole("combobox", { name: "Guardrail failure mode" });
    fireEvent.keyDown(mode, { key: "ArrowDown" });
    fireEvent.click(screen.getByRole("option", { name: "Fail open" }));
    expect(mode).toHaveTextContent("Fail open");
    fireEvent.click(saveIn("guardrails"));
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByText("Fail closed")).toBeInTheDocument();
    expect(within(dialog).getByText("Fail open")).toBeInTheDocument();
  });
});

describe("source, inheritance and reset", () => {
  function mutableEffective() {
    const state: EffectiveSettings = defaultEffective();
    state.values[STEPS] = eff(12, "tenant");
    state.values["aux.model"] = eff("pinned-model", "platform", { locked: true, lockedReason: "Set by the platform team" });
    return state;
  }

  it("shows a badge with where each value comes from and explains it in a tooltip", async () => {
    await ready(makeClient({ effective: mutableEffective() }));
    expect(within(field(STEPS)).getByTestId("setting-source")).toHaveAccessibleName("Value source: Workspace");
    expect(within(field("quality.judge.temperature")).getByTestId("setting-source")).toHaveAccessibleName("Value source: Default");
    const badge = within(field("aux.model")).getByTestId("setting-source");
    expect(badge).toHaveAccessibleName("Value source: Platform");
    badge.focus();
    expect((await screen.findAllByText(/Your platform operator set this/)).length).toBeGreaterThan(0);
  });

  it("resets a tenant override to the inherited value with one click and moves focus to the control", async () => {
    const state = mutableEffective();
    const client = makeClient({ effective: state });
    client.reset.mockImplementation(async () => {
      state.values[STEPS] = eff(8, "default");
    });
    await ready(client);
    expect(steps()).toHaveValue("12");
    fireEvent.click(within(field(STEPS)).getByRole("button", { name: "Reset to inherited: Max tool steps" }));
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("tenant", STEPS, undefined));
    await waitFor(() => expect(within(field(STEPS)).getByTestId("setting-source")).toHaveAttribute("data-source", "default"));
    expect(steps()).toHaveValue("8");
    expect(within(field(STEPS)).queryByTestId("setting-reset")).toBeNull();
    expect(steps()).toHaveFocus();
    expect(screen.getByTestId("settings-message")).toHaveTextContent("Max tool steps now uses the inherited value.");
  });

  it("offers no reset for default or platform values, and a failed reset is rolled back", async () => {
    const state = mutableEffective();
    const client = makeClient({ effective: state });
    client.reset.mockRejectedValue(new SettingsApiError("gone", { status: 404 }));
    await ready(client);
    expect(within(field("quality.judge.temperature")).queryByTestId("setting-reset")).toBeNull();
    expect(within(field("aux.model")).queryByTestId("setting-reset")).toBeNull();
    fireEvent.click(within(field(STEPS)).getByTestId("setting-reset"));
    await waitFor(() => expect(within(field(STEPS)).getByTestId("setting-server-error")).toHaveTextContent("no longer exists"));
    expect(steps()).toHaveValue("12");
    expect(within(field(STEPS)).getByTestId("setting-source")).toHaveAttribute("data-source", "tenant");
  });

  it("renders locked settings read-only with the operator's reason", async () => {
    await ready(makeClient({ effective: mutableEffective() }));
    const model = screen.getByRole("textbox", { name: "Helper model" });
    expect(model).toHaveAttribute("readonly");
    expect(model).toHaveValue("pinned-model");
    expect(within(field("aux.model")).getByTestId("setting-locked")).toHaveTextContent("Locked by your administrator: Set by the platform team");
    type(model, "other"); // browsers never fire this for a read-only input; if one did, it must not become a draft
    expect(model).toHaveValue("pinned-model");
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
  });

  it("shows read-only fields with an explanation for a role below the setting's minimum", async () => {
    await ready(makeClient(), { role: "EDITOR" });
    expect(steps()).toHaveAttribute("readonly");
    expect(within(field(STEPS)).getByTestId("setting-blocked")).toHaveTextContent("Your role cannot change this setting");
    expect(screen.getByRole("switch", { name: "Cache replies that used tools" })).toBeDisabled();
  });

  it("is fully read-only with readOnly", async () => {
    await ready(makeClient(), { readOnly: true });
    expect(steps()).toHaveAttribute("readonly");
    expect(within(field(STEPS)).getByTestId("setting-blocked")).toHaveTextContent("read-only");
  });

  it("shows help on demand with aria-expanded", async () => {
    await ready(makeClient());
    const toggle = within(field(STEPS)).getByRole("button", { name: "More details" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(within(field(STEPS)).getByTestId("setting-help")).toHaveTextContent("Higher values let the agent finish bigger jobs");
  });
});

describe("agent scope", () => {
  const agentView = (agentId?: string): EffectiveSettings => {
    const v = defaultEffective();
    v.values[STEPS] = eff(12, "tenant");
    if (agentId) v.values["agent.limits.toolOutputMaxChars"] = eff(5000, "agent");
    return v;
  };

  it("loads the agent view and the tenant view, labels inherited values and writes to the agent endpoint", async () => {
    const client = makeClient({ effective: agentView });
    renderSettings(<SettingsPanel scope="agent" agentId="ag-1" client={client} />);
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(client.getEffective).toHaveBeenCalledWith("ag-1");
    expect(client.getEffective).toHaveBeenCalledWith();
    expect(screen.getByText(/These values apply to this agent only/)).toBeInTheDocument();
    // tenant-only entries are not offered for an agent
    expect(screen.queryByRole("combobox", { name: "Guardrail failure mode" })).toBeNull();

    const badge = within(field(STEPS)).getByTestId("setting-source");
    expect(badge).toHaveAccessibleName("Value source: Inherited from workspace");
    expect(within(field(STEPS)).queryByTestId("setting-reset")).toBeNull(); // nothing of its own to reset

    type(steps(), "30");
    fireEvent.click(saveIn("agent"));
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("agent", STEPS, { value: 30 }, "ag-1"));
    await waitFor(() => expect(within(field(STEPS)).getByTestId("setting-source")).toHaveAttribute("data-source", "agent"));
    expect(within(field(STEPS)).getByTestId("setting-reset")).toBeInTheDocument();
  });

  it("resets an agent override back to the tenant value and says what it returns to", async () => {
    const client = makeClient({ effective: agentView });
    renderSettings(<SettingsPanel scope="agent" agentId="ag-1" client={client} />);
    const out = await screen.findByRole("spinbutton", { name: "Tool output limit" });
    expect(out).toHaveValue("5000");
    expect(within(field("agent.limits.toolOutputMaxChars")).getByTestId("setting-inherited")).toHaveTextContent("If you reset it, it returns to: 20,000 characters.");
    fireEvent.click(within(field("agent.limits.toolOutputMaxChars")).getByTestId("setting-reset"));
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("agent", "agent.limits.toolOutputMaxChars", "ag-1"));
  });

  it("asks for an agent when none is given", () => {
    renderSettings(<SettingsPanel scope="agent" client={makeClient()} />);
    expect(screen.getByText("Choose an agent to see its settings.")).toBeInTheDocument();
  });
});

describe("filters", () => {
  it("narrows by search text, group and risk, and can be cleared", async () => {
    await ready(makeClient());
    const search = screen.getByRole("searchbox", { name: "Search settings" });
    type(search, "timeout");
    expect(screen.getByRole("spinbutton", { name: "Turn timeout" })).toBeInTheDocument();
    expect(screen.queryByRole("spinbutton", { name: "Max tool steps" })).toBeNull();
    type(search, "zzzz");
    expect(screen.getByTestId("settings-empty")).toHaveTextContent("No settings match your filters.");
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(steps()).toBeInTheDocument();

    fireEvent.click(screen.getByRole("combobox", { name: "Filter by group" }));
    fireEvent.click(screen.getByRole("option", { name: "Guardrails" }));
    expect(screen.queryByRole("spinbutton", { name: "Max tool steps" })).toBeNull();
    expect(screen.getByRole("combobox", { name: "Guardrail failure mode" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));

    fireEvent.click(screen.getByRole("combobox", { name: "Filter by risk" }));
    fireEvent.click(screen.getByRole("option", { name: "High risk" }));
    expect(screen.getByRole("spinbutton", { name: "Approval expiry" })).toBeInTheDocument();
    expect(screen.queryByRole("spinbutton", { name: "Toxicity threshold" })).toBeNull();
  });

  it("can restrict the panel to some groups or keys and hide the filters", async () => {
    renderSettings(<SettingsPanel scope="tenant" client={makeClient()} groups={["agent"]} showFilters={false} heading={null} />);
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(screen.queryByRole("searchbox")).toBeNull();
    expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
    expect(screen.queryByRole("combobox", { name: "Guardrail failure mode" })).toBeNull();
  });
});

describe("history drawer", () => {
  const history = [
    { version: 1, value: 8, previousValue: 6, actor: "alice@example.com", at: "2026-10-01T10:00:00Z", reason: null },
    { version: 2, value: 12, previousValue: 8, actor: "bob@example.com", at: "2026-10-05T10:00:00Z", reason: "Long investigations" },
  ];

  it("lists versions newest first with the diff, actor and reason, closes on Escape and returns focus", async () => {
    const client = makeClient({ history });
    await ready(client);
    const open = within(field(STEPS)).getByRole("button", { name: "History: Max tool steps" });
    open.focus();
    fireEvent.click(open);
    const drawer = await screen.findByRole("dialog", { name: "History: Max tool steps" });
    expect(client.getHistory).toHaveBeenCalledWith(STEPS, "tenant", undefined);
    const items = await within(drawer).findAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("Version 2");
    expect(items[0]).toHaveTextContent("8");
    expect(items[0]).toHaveTextContent("12");
    expect(items[0]).toHaveTextContent("bob@example.com");
    expect(items[0]).toHaveTextContent("Long investigations");
    expect(items[1]).toHaveTextContent("alice@example.com");
    expect(within(drawer).getByRole("button", { name: "Close history" })).toHaveFocus();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(open).toHaveFocus();
  });

  it("asks the agent history endpoint in agent scope", async () => {
    const client = makeClient({ history: [] });
    renderSettings(<SettingsPanel scope="agent" agentId="ag-9" client={client} />);
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    fireEvent.click(within(field(STEPS)).getByRole("button", { name: "History: Max tool steps" }));
    expect(await screen.findByTestId("setting-history-empty")).toHaveTextContent("No changes have been recorded yet.");
    expect(client.getHistory).toHaveBeenCalledWith(STEPS, "agent", "ag-9");
  });

  it("shows a retryable error when the history cannot be loaded", async () => {
    const client = makeClient();
    client.getHistory.mockRejectedValueOnce(new Error("503"));
    await ready(client);
    fireEvent.click(within(field(STEPS)).getByRole("button", { name: "History: Max tool steps" }));
    const drawer = await screen.findByRole("dialog");
    expect(await within(drawer).findByText("History could not be loaded. 503")).toBeInTheDocument();
    fireEvent.click(within(drawer).getByRole("button", { name: "Try again" }));
    expect(await within(drawer).findByTestId("setting-history-empty")).toBeInTheDocument();
  });
});

describe("which direction is stricter", () => {
  it("tells the person on every security field, and only there", async () => {
    await ready(makeClient());
    expect(within(field("guardrails.failMode")).getByTestId("setting-strictness")).toHaveTextContent(
      "Options run from least to most strict: Fail open → Fail closed. You can only move toward the strict end."
    );
    expect(within(field("guardrails.toxicity.threshold")).getByTestId("setting-strictness")).toHaveTextContent("Higher is stricter");
    expect(within(field(STEPS)).queryByTestId("setting-strictness")).toBeNull();
    expect(within(field("approvals.expiry.ttl")).queryByTestId("setting-strictness")).toBeNull();
  });

  it("repeats it in the confirmation of a security change", async () => {
    await ready(makeClient());
    type(screen.getByRole("spinbutton", { name: "Toxicity threshold" }), "90");
    fireEvent.click(saveIn("guardrails"));
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByTestId("risky-strictness")).toHaveTextContent("Higher is stricter: a higher value is accepted, a lower one is refused.");
  });

  it("uses the lower-is-stricter wording when the catalog says so", async () => {
    const catalog = CATALOG.map((e) => (e.key === "guardrails.toxicity.threshold" ? { ...e, strictness: "lower_is_stricter" as const } : e));
    await ready(makeClient({ catalog }));
    expect(within(field("guardrails.toxicity.threshold")).getByTestId("setting-strictness")).toHaveTextContent("Lower is stricter");
  });
});

describe("resetting a high-risk or security setting", () => {
  const EXPIRY = "approvals.expiry.ttl";
  const THRESHOLD = "guardrails.toxicity.threshold";
  const overridden = (): EffectiveSettings => {
    const v = defaultEffective();
    v.values[EXPIRY] = eff(7200000, "tenant");
    v.values[THRESHOLD] = eff(90, "tenant");
    return v;
  };

  it("asks for a typed reason first and sends it with the reset", async () => {
    const client = makeClient({ effective: overridden() });
    await ready(client);
    fireEvent.click(within(field(EXPIRY)).getByTestId("setting-reset"));
    const dialog = await screen.findByRole("alertdialog");
    expect(client.reset).not.toHaveBeenCalled();
    expect(within(dialog).getByTestId("risky-reset-note")).toHaveTextContent("the override is removed");
    expect(within(dialog).getByText("2 hours")).toBeInTheDocument();
    expect(within(dialog).getByText("1 hours")).toBeInTheDocument();
    const confirm = within(dialog).getByRole("button", { name: "Confirm and save" });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Reason for this change" }), { target: { value: "Back to the platform value" } });
    expect(confirm).toBeEnabled();
    fireEvent.click(confirm);
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("tenant", EXPIRY, undefined, "Back to the platform value"));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
  });

  it("does not reset when the prompt is cancelled", async () => {
    const client = makeClient({ effective: overridden() });
    await ready(client);
    fireEvent.click(within(field(EXPIRY)).getByTestId("setting-reset"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(client.reset).not.toHaveBeenCalled();
    expect(within(field(EXPIRY)).getByTestId("setting-source")).toHaveAttribute("data-source", "tenant");
  });

  it("also needs the acknowledgment for a security setting, and names the direction that is stricter", async () => {
    const client = makeClient({ effective: overridden() });
    await ready(client);
    fireEvent.click(within(field(THRESHOLD)).getByTestId("setting-reset"));
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByTestId("risky-strictness")).toHaveTextContent("Higher is stricter");
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Reason for this change" }), { target: { value: "Incident closed" } });
    const confirm = within(dialog).getByRole("button", { name: "Confirm and save" });
    expect(confirm).toBeDisabled();
    fireEvent.click(within(dialog).getByRole("checkbox", { name: /I understand/ }));
    fireEvent.click(confirm);
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("tenant", THRESHOLD, undefined, "Incident closed"));
  });

  it("still resets a low or medium setting in one click, with no reason", async () => {
    const v = defaultEffective();
    v.values[STEPS] = eff(12, "tenant");
    const client = makeClient({ effective: v });
    await ready(client);
    fireEvent.click(within(field(STEPS)).getByTestId("setting-reset"));
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("tenant", STEPS, undefined));
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });
});

describe("security settings for someone who is not a workspace admin", () => {
  const THRESHOLD = "guardrails.toxicity.threshold";
  const asSession = (isAdmin: boolean, isPlatformAdmin = false) => ({ isAdmin, isPlatformAdmin, hasPermission: () => false });
  const agentPanel = (session: ReturnType<typeof asSession> | null) =>
    renderSettings(
      <AuthContext.Provider value={session as never}>
        <SettingsPanel scope="agent" agentId="ag-1" client={makeClient()} />
      </AuthContext.Provider>
    );

  it("shows the security field read-only with the reason, while the other fields stay editable", async () => {
    agentPanel(asSession(false));
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(steps()).not.toHaveAttribute("readonly");
    expect(screen.getByRole("spinbutton", { name: "Toxicity threshold" })).toHaveAttribute("readonly");
    expect(within(field(THRESHOLD)).getByTestId("setting-blocked")).toHaveTextContent("Only a workspace admin can change this security setting");
    expect(within(field(STEPS)).queryByTestId("setting-blocked")).toBeNull();
    // and a draft cannot be made on it
    type(screen.getByRole("spinbutton", { name: "Toxicity threshold" }), "50");
    expect(screen.getByRole("spinbutton", { name: "Toxicity threshold" })).toHaveValue("80");
  });

  it("lets a workspace admin or platform admin edit it", async () => {
    agentPanel(asSession(true));
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(screen.getByRole("spinbutton", { name: "Toxicity threshold" })).not.toHaveAttribute("readonly");
    cleanup();
    agentPanel(asSession(false, true));
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(screen.getByRole("spinbutton", { name: "Toxicity threshold" })).not.toHaveAttribute("readonly");
  });

  it("leaves the decision to the server when there is no signed-in session, and obeys a role the page passes", async () => {
    agentPanel(null);
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(screen.getByRole("spinbutton", { name: "Toxicity threshold" })).not.toHaveAttribute("readonly");
    cleanup();
    renderSettings(
      <AuthContext.Provider value={asSession(true) as never}>
        <SettingsPanel scope="agent" agentId="ag-1" client={makeClient()} role="VIEWER" />
      </AuthContext.Provider>
    );
    await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(steps()).toHaveAttribute("readonly");
  });
});
