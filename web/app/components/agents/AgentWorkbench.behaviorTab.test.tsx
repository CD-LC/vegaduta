import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../../messages/en.json";
import { I18nProvider } from "../../lib/i18n";
import type { SettingCatalogEntry, SettingsClient } from "../../lib/api/settings";
import AgentWorkbench from "./AgentWorkbench";
import { agentBehaviorHref } from "./playground/runSettingsModel";

const h = vi.hoisted(() => ({
  params: new URLSearchParams(),
  client: null as unknown as SettingsClient,
}));

vi.mock("../../lib/api", () => {
  const handler: ProxyHandler<object> = {
    get: (_t, prop): unknown => (prop === "then" ? undefined : () => Promise.resolve([])),
  };
  return { api: new Proxy({}, handler) };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/agents",
  useSearchParams: () => h.params,
}));
vi.mock("../templates/ShareAsTemplateButton", () => ({ default: (): null => null }));
vi.mock("./AgentPlayground", () => ({ default: (): unknown => <div data-testid="playground" /> }));
vi.mock("../../lib/api/settings", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  get settingsApi() {
    return h.client;
  },
}));

const STEPS = "agent.limits.maxToolSteps";
const ENTRY: SettingCatalogEntry = {
  key: STEPS,
  group: "agent-limits",
  labelKey: "settings.agent.limits.maxToolSteps.label",
  descriptionKey: "settings.agent.limits.maxToolSteps.description",
  type: "int",
  unit: "count",
  min: 1,
  max: 50,
  risk: "low",
  scopes: ["tenant", "agent"],
  default: 8,
  minRole: "EDITOR",
  restartRequired: false,
};

function fakeClient(): SettingsClient {
  return {
    getCatalog: vi.fn(async () => ({ entries: [ENTRY] })),
    getEffective: vi.fn(async () => ({ values: { [STEPS]: { value: 8, source: "default" as const, locked: false } } })),
    put: vi.fn(async () => undefined),
    reset: vi.fn(async () => undefined),
    getHistory: vi.fn(async () => []),
  };
}

const provider: any = { id: "p1", providerName: "OPENAI", status: "ACTIVE" };
const agent = (id: string, name: string): any => ({
  id,
  name,
  systemPrompt: "Be brief.",
  providerName: "OPENAI",
  model: "gpt-x",
  maxTokens: 512,
  temperature: 0.4,
  topP: 1,
  editable: true,
  allowedKnowledgeCollectionIds: [],
});

function mount(props: Partial<React.ComponentProps<typeof AgentWorkbench>> = {}) {
  const onSelect = vi.fn();
  const view = render(
    <I18nProvider locale="en" messages={en as any} enMessages={en as any} complete>
      <AgentWorkbench
        agents={[agent("a1", "Support bot"), agent("a2", "Sales bot")]}
        providers={[provider]}
        collections={[]}
        selectedAgentId="a1"
        onSelect={onSelect}
        onChanged={vi.fn()}
        agentsLoaded
        {...props}
      />
    </I18nProvider>
  );
  return { onSelect, ...view };
}
const behaviorTab = () => screen.getByRole("tab", { name: /Behavior & limits/ });
const setupTab = () => screen.getByRole("tab", { name: "Setup" });
const steps = () => screen.findByRole("spinbutton", { name: "Most tool calls per turn" });

beforeEach(() => {
  h.params = new URLSearchParams();
  h.client = fakeClient();
});

describe("AgentWorkbench tabs", () => {
  it("offers Setup and Behavior & limits tabs, Setup first, and loads behavior data only when asked", async () => {
    mount();
    await waitFor(() => expect(screen.getByTestId("playground")).toBeTruthy());
    expect(screen.getByRole("tablist", { name: "Agent configuration" })).toBeTruthy();
    expect(setupTab()).toHaveAttribute("aria-selected", "true");
    expect(behaviorTab()).toHaveAttribute("aria-selected", "false");
    expect(h.client.getCatalog).not.toHaveBeenCalled();
    expect(document.getElementById("workbench-panel-behavior")).toHaveAttribute("hidden");

    fireEvent.click(behaviorTab());
    expect(await steps()).toBeTruthy();
    expect(behaviorTab()).toHaveAttribute("aria-selected", "true");
    expect(document.getElementById("workbench-panel-setup")).toHaveAttribute("hidden");
    expect(document.getElementById("workbench-panel-behavior")).not.toHaveAttribute("hidden");
    // the tab and its panel are wired for assistive technology
    expect(behaviorTab()).toHaveAttribute("aria-controls", "workbench-panel-behavior");
    expect(document.getElementById("workbench-panel-behavior")).toHaveAttribute("aria-labelledby", behaviorTab().id);
  });

  it("opens straight on Behavior and focuses the setting for ?section=behavior&focus=<key>", async () => {
    h.params = new URLSearchParams({ agentId: "a1", view: "workbench", section: "behavior", focus: STEPS });
    mount();
    expect(behaviorTab()).toHaveAttribute("aria-selected", "true");
    await waitFor(async () => expect(await steps()).toHaveFocus());
  });

  it("the drawer's group link (?focus=agent.limits) opens Behavior and focuses the first limit", async () => {
    // The very link the Run settings drawer's limits heading builds.
    const href = agentBehaviorHref("a1", "agent.limits");
    const params = new URL(href, "http://x").searchParams;
    expect(params.get("section")).toBe("behavior");
    h.params = params;
    mount();
    expect(behaviorTab()).toHaveAttribute("aria-selected", "true");
    await waitFor(async () => expect(await steps()).toHaveFocus());
  });

  it("moves between tabs with the arrow keys", async () => {
    mount();
    setupTab().focus();
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowRight" });
    expect(behaviorTab()).toHaveAttribute("aria-selected", "true");
    expect(behaviorTab()).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "Home" });
    expect(setupTab()).toHaveAttribute("aria-selected", "true");
    expect(setupTab()).toHaveFocus();
  });

  it("tells a new, unsaved agent to save first instead of showing settings", async () => {
    mount({ agents: [], selectedAgentId: null, startInDraft: true });
    fireEvent.click(behaviorTab());
    expect(await screen.findByTestId("behavior-save-first")).toHaveTextContent("Save the agent first");
    expect(h.client.getCatalog).not.toHaveBeenCalled();
  });

  it("keeps an edit when the person looks at Setup and comes back, and marks the tab as unsaved", async () => {
    mount();
    fireEvent.click(behaviorTab());
    fireEvent.change(await steps(), { target: { value: "12" } });
    await waitFor(() => expect(within(behaviorTab()).getByText("Unsaved changes")).toBeTruthy());
    fireEvent.click(setupTab());
    fireEvent.click(behaviorTab());
    expect(await steps()).toHaveValue("12");
  });
});

describe("AgentWorkbench switching agents with unsaved behavior edits", () => {
  async function dirtyThenOpenSwitcher() {
    const ctx = mount();
    fireEvent.click(behaviorTab());
    fireEvent.change(await steps(), { target: { value: "12" } });
    await waitFor(() => expect(within(behaviorTab()).getByText("Unsaved changes")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: /Support bot/ }));
    return ctx;
  }

  it("asks first; staying keeps the agent and the edit, leaving switches and drops the guard", async () => {
    const { onSelect } = await dirtyThenOpenSwitcher();
    fireEvent.click(screen.getByRole("option", { name: /Sales bot/ }));
    const dialog = await screen.findByTestId("discard-changes-dialog");
    expect(onSelect).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByTestId("discard-stay"));
    await waitFor(() => expect(screen.queryByTestId("discard-changes-dialog")).toBeNull());
    expect(onSelect).not.toHaveBeenCalled();
    expect(await steps()).toHaveValue("12");

    fireEvent.click(screen.getByRole("button", { name: /Support bot/ }));
    fireEvent.click(screen.getByRole("option", { name: /Sales bot/ }));
    fireEvent.click(await screen.findByTestId("discard-confirm"));
    expect(onSelect).toHaveBeenCalledWith("a2");
  });

  it("switches without asking when nothing is unsaved", async () => {
    const { onSelect } = mount();
    fireEvent.click(screen.getByRole("button", { name: /Support bot/ }));
    fireEvent.click(screen.getByRole("option", { name: /Sales bot/ }));
    expect(onSelect).toHaveBeenCalledWith("a2");
    expect(screen.queryByTestId("discard-changes-dialog")).toBeNull();
  });
});
