import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { SettingsApiError } from "../../../lib/api/settings";
import type { EffectiveSetting, EffectiveSettings, SettingCatalogEntry } from "../../../lib/api/settings";
import { CATALOG, eff, makeClient, renderSettings, type FakeClient } from "../../settings/settingsTestKit";
import BehaviorLimitsTab, { type BehaviorAgent } from "./BehaviorLimitsTab";
import { BEHAVIOR_ANCHORS, TENANT_RUNTIME_SETTINGS_HREF } from "./behaviorModel";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => "/agents",
  useSearchParams: () => new URLSearchParams(),
}));

const STEPS = "agent.limits.maxToolSteps";
const TURN = "agent.limits.turnTimeoutSeconds";
const OUTPUT = "agent.limits.toolOutputMaxChars";
const CONT = "approvals.continuation.enabled";
const JUDGE = "quality.judge.model";

function extra(e: Partial<SettingCatalogEntry> & Pick<SettingCatalogEntry, "key" | "type" | "default">): SettingCatalogEntry {
  return {
    group: e.key.split(".")[0],
    labelKey: `settings.${e.key}.label`,
    descriptionKey: `settings.${e.key}.description`,
    risk: "low",
    scopes: ["tenant", "agent"],
    minRole: "EDITOR",
    restartRequired: false,
    ...e,
  };
}

// Test-kit catalog plus the keys the summary card reads. The judge model is workspace-only: it must show in the
// summary (it affects this agent's quality scores) but never as something to override for one agent.
const CATALOG_ALL: SettingCatalogEntry[] = [
  ...CATALOG,
  extra({ key: CONT, type: "bool", default: true, risk: "medium" }),
  extra({ key: JUDGE, type: "string", default: "", scopes: ["tenant"], min: 0, max: 120 }),
];

const AGENT: BehaviorAgent = { id: "ag1", name: "Support bot", providerName: "OPENAI", model: "gpt-x", autonomyTier: "SEMI_AUTONOMOUS" };

/** A fake server holding workspace values and this agent's overrides; getEffective answers like the real one. */
function server(tenant: Record<string, EffectiveSetting> = {}, agent: Record<string, EffectiveSetting> = {}) {
  const base: Record<string, EffectiveSetting> = {};
  for (const e of CATALOG_ALL) base[e.key] = eff(e.default, "default");
  Object.assign(base, { [CONT]: eff(true, "default"), [JUDGE]: eff("", "default") }, tenant);
  const own: Record<string, EffectiveSetting> = { ...agent };
  const client: FakeClient = makeClient({
    catalog: CATALOG_ALL,
    effective: (agentId?: string): EffectiveSettings => ({ values: agentId ? { ...base, ...own } : { ...base } }),
  });
  client.put.mockImplementation(async (_scope, key, body) => {
    own[key] = eff(body.value, "agent");
  });
  client.reset.mockImplementation(async (_scope, key) => {
    delete own[key];
  });
  return client;
}

async function open(props: Partial<React.ComponentProps<typeof BehaviorLimitsTab>> = {}, client: FakeClient = server()) {
  const view = renderSettings(<BehaviorLimitsTab agent={AGENT} client={client} {...props} />);
  await screen.findByRole("spinbutton", { name: "Max tool steps" });
  await screen.findByTestId("summary-live");
  return { client, ...view };
}
const fact = (id: string) => screen.getByTestId(`summary-fact-${id}`);
const steps = () => screen.getByRole("spinbutton", { name: "Max tool steps" }) as HTMLInputElement;
const type = (el: HTMLElement, value: string) => fireEvent.change(el, { target: { value } });

beforeEach(() => push.mockClear());

describe("BehaviorLimitsTab summary", () => {
  it("shows what is in force, where each value comes from, and what it means in plain words", async () => {
    const client = server({ [STEPS]: eff(20, "tenant"), [TURN]: eff(300, "platform") }, { [OUTPUT]: eff(5000, "agent") });
    await open({}, client);

    expect(fact("steps")).toHaveTextContent("Tool calls per turn");
    expect(fact("steps")).toHaveTextContent("20");
    expect(within(fact("steps")).getByTestId("setting-source")).toHaveAttribute("data-source", "tenant");
    expect(within(fact("turnTimeout")).getByTestId("setting-source")).toHaveAttribute("data-source", "platform");
    expect(fact("turnTimeout")).toHaveTextContent("5 minutes");
    expect(fact("outputCap")).toHaveTextContent("5,000 characters");
    expect(within(fact("outputCap")).getByTestId("setting-source")).toHaveAttribute("data-source", "agent");
    expect(fact("model")).toHaveTextContent("OPENAI / gpt-x");
    expect(fact("approvals")).toHaveTextContent("Semi-autonomous");
    expect(fact("approvals")).toHaveTextContent("Carries on by itself after an approval");
    expect(fact("judge")).toHaveTextContent("Chosen by the platform");

    expect(screen.getByTestId("impact-steps")).toHaveTextContent("This agent may call up to 20 tools in one turn.");
    expect(screen.getByTestId("impact-turnTimeout")).toHaveTextContent("One turn is cut off after 5 minutes.");
    expect(screen.getByTestId("impact-outputCap")).toHaveTextContent("longer than 5,000 characters is shortened");
    expect(screen.getByTestId("summary-overrides")).toHaveTextContent("Settings overridden for this agent: 1.");
    expect(screen.getByTestId("summary-live")).toHaveAttribute("aria-live", "polite");
  });

  it("says when nothing is overridden and shows the workspace judge model", async () => {
    await open({}, server({ [JUDGE]: eff("big-judge", "tenant") }));
    expect(screen.getByTestId("summary-overrides")).toHaveTextContent("This agent uses your workspace values everywhere.");
    expect(fact("judge")).toHaveTextContent("big-judge");
  });

  it("explains that a supervised agent's inherited step ceiling is adjusted at run time", async () => {
    await open({ agent: { ...AGENT, autonomyTier: "SUPERVISED" } });
    expect(screen.getByTestId("impact-stepsTier")).toHaveTextContent("Supervised");
    expect(fact("approvals")).toHaveTextContent("every tool call waits for a person");
  });

  it("shows a loading state and then an error state with retry when the settings cannot be loaded", async () => {
    const client = server();
    // The summary and the settings panel each load the catalog; both are told it failed.
    client.getCatalog.mockRejectedValueOnce(new Error("boom")).mockRejectedValueOnce(new Error("boom"));
    renderSettings(<BehaviorLimitsTab agent={AGENT} client={client} />);
    expect(screen.getByTestId("summary-loading")).toHaveAttribute("aria-busy", "true");
    const err = await screen.findByTestId("summary-error");
    expect(err).toHaveTextContent("The current behavior could not be loaded. boom");
    fireEvent.click(within(err).getByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId("summary-live")).toBeInTheDocument();
  });
});

describe("BehaviorLimitsTab editing", () => {
  it("lists every agent-scoped catalog setting, grouped, and nothing that is workspace-only", async () => {
    await open();
    const expected = CATALOG_ALL.filter((e) => e.scopes.includes("agent"));
    for (const e of expected) expect(screen.getByTestId(`setting-${e.key}`)).toBeInTheDocument();
    for (const e of CATALOG_ALL.filter((c) => !c.scopes.includes("agent"))) {
      expect(screen.queryByTestId(`setting-${e.key}`)).toBeNull();
    }
    expect(screen.getByRole("heading", { name: "Agent runtime" })).toBeInTheDocument();
  });

  it("saves an override for this agent and the summary and sentences follow", async () => {
    const { client } = await open({}, server({ [STEPS]: eff(8, "tenant") }));
    expect(fact("steps")).toHaveTextContent("8");
    type(steps(), "12");
    fireEvent.click(within(screen.getByTestId("settings-group-agent")).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("agent", STEPS, { value: 12 }, "ag1"));
    await waitFor(() => expect(screen.getByTestId("impact-steps")).toHaveTextContent("up to 12 tools"));
    expect(within(fact("steps")).getByTestId("setting-source")).toHaveAttribute("data-source", "agent");
    expect(screen.getByTestId("summary-overrides")).toHaveTextContent("Settings overridden for this agent: 1.");
  });

  it("resets an override to the inherited value and the summary goes back to the workspace layer", async () => {
    const client = server({ [STEPS]: eff(8, "tenant") }, { [STEPS]: eff(30, "agent") });
    await open({}, client);
    expect(fact("steps")).toHaveTextContent("30");
    fireEvent.click(within(screen.getByTestId(`setting-${STEPS}`)).getByTestId("setting-reset"));
    await waitFor(() => expect(client.reset).toHaveBeenCalledWith("agent", STEPS, "ag1"));
    await waitFor(() => expect(fact("steps")).toHaveTextContent("8"));
    expect(within(fact("steps")).getByTestId("setting-source")).toHaveAttribute("data-source", "tenant");
  });

  it("keeps the old summary and shows the server's reason when a save is rejected", async () => {
    const client = server({ [STEPS]: eff(8, "tenant") });
    client.put.mockRejectedValueOnce(new SettingsApiError("too high", { status: 400, code: "SETTING_INVALID", key: STEPS, min: 1, max: 10 }));
    await open({}, client);
    type(steps(), "12");
    fireEvent.click(within(screen.getByTestId("settings-group-agent")).getByRole("button", { name: "Save changes" }));
    expect(await screen.findByTestId("setting-server-error")).toHaveTextContent("too high");
    expect(fact("steps")).toHaveTextContent("8");
    expect(steps()).toHaveValue("12"); // the draft is kept so it can be corrected
  });

  it("is read-only when the agent is edit-locked, with a reason, and sends nothing", async () => {
    const { client } = await open({ locked: true });
    expect(screen.getByTestId("behavior-readonly")).toHaveTextContent("locked for editing");
    expect(steps()).toHaveAttribute("readonly");
    type(steps(), "12");
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    expect(client.put).not.toHaveBeenCalled();
  });

  it("is read-only for a viewer of the agent", async () => {
    await open({ agent: { ...AGENT, accessLevel: "VIEWER" } });
    expect(screen.getByTestId("behavior-readonly")).toHaveTextContent("does not allow changing them");
    expect(steps()).toHaveAttribute("readonly");
  });

  it("reads and writes only the agent it is given", async () => {
    const { client } = await open({ agent: { ...AGENT, id: "other-agent" } });
    expect(client.getEffective).toHaveBeenCalledWith("other-agent");
    type(steps(), "9");
    fireEvent.click(within(screen.getByTestId("settings-group-agent")).getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(client.put).toHaveBeenCalledWith("agent", STEPS, { value: 9 }, "other-agent"));
  });
});

describe("BehaviorLimitsTab navigation", () => {
  it("links to the workspace defaults and can be pointed elsewhere", async () => {
    const { unmount } = await open();
    expect(screen.getByTestId("tenant-settings-link")).toHaveAttribute("href", TENANT_RUNTIME_SETTINGS_HREF);
    unmount();
    await open({ tenantSettingsHref: "/admin/runtime" });
    expect(screen.getByTestId("tenant-settings-link")).toHaveAttribute("href", "/admin/runtime");
  });

  it("opens the playground and the cards that hold the controls the catalog does not cover", async () => {
    const onOpenPlayground = vi.fn();
    const onGoToSection = vi.fn();
    await open({ onOpenPlayground, onGoToSection });
    fireEvent.click(screen.getByTestId("try-in-playground"));
    expect(onOpenPlayground).toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("goto-generation"));
    fireEvent.click(screen.getByTestId("goto-autonomy"));
    expect(onGoToSection).toHaveBeenNthCalledWith(1, BEHAVIOR_ANCHORS.generation);
    expect(onGoToSection).toHaveBeenNthCalledWith(2, BEHAVIOR_ANCHORS.autonomy);
  });

  it("scrolls to and focuses the setting named by ?focus once it has rendered", async () => {
    renderSettings(<BehaviorLimitsTab agent={AGENT} client={server()} focusKey={TURN} />);
    await waitFor(() => expect(screen.getByRole("spinbutton", { name: "Turn timeout" })).toHaveFocus());
  });
});

describe("BehaviorLimitsTab unsaved changes", () => {
  it("announces unsaved edits, reports them upward and clears the notice when they are discarded", async () => {
    const onDirtyChange = vi.fn();
    await open({ onDirtyChange });
    expect(screen.getByTestId("behavior-unsaved")).toBeEmptyDOMElement();
    type(steps(), "12");
    await waitFor(() => expect(screen.getByTestId("behavior-unsaved")).toHaveTextContent("You have unsaved changes in this tab"));
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);
    fireEvent.click(within(screen.getByTestId("settings-group-agent")).getByRole("button", { name: "Discard changes" }));
    await waitFor(() => expect(screen.getByTestId("behavior-unsaved")).toBeEmptyDOMElement());
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);
  });

  it("asks before an in-app link is followed, and follows it only after the person agrees", async () => {
    await open();
    type(steps(), "12");
    await waitFor(() => expect(screen.getByTestId("behavior-unsaved")).toHaveTextContent("unsaved"));
    fireEvent.click(screen.getByTestId("tenant-settings-link"));
    const dialog = await screen.findByTestId("discard-changes-dialog");
    expect(push).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByTestId("discard-stay"));
    await waitFor(() => expect(screen.queryByTestId("discard-changes-dialog")).toBeNull());
    expect(push).not.toHaveBeenCalled();
    expect(steps()).toHaveValue("12");

    fireEvent.click(screen.getByTestId("tenant-settings-link"));
    fireEvent.click(await screen.findByTestId("discard-confirm"));
    expect(push).toHaveBeenCalledWith(TENANT_RUNTIME_SETTINGS_HREF);
  });

  it("does not interfere with links when nothing is unsaved", async () => {
    await open();
    const link = screen.getByTestId("tenant-settings-link");
    let heldBack: boolean | null = null;
    // Observe at the end of the bubble phase, then stop jsdom from really navigating.
    const observe = (e: Event) => {
      heldBack = e.defaultPrevented;
      e.preventDefault();
    };
    document.addEventListener("click", observe);
    fireEvent.click(link);
    document.removeEventListener("click", observe);
    expect(heldBack).toBe(false);
    expect(screen.queryByTestId("discard-changes-dialog")).toBeNull();
    expect(push).not.toHaveBeenCalled();
    await act(async () => {});
  });
});
