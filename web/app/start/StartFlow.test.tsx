// Integration coverage for /start with the api, the chat panel, the router and
// the beacon mocked: the three steps, honest card states, resumability, skip,
// and the telemetry each transition counts. Never exercises a real model.

import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FLOW_STORAGE_KEY } from "./flow";
import StartFlow from "./StartFlow";

const h = vi.hoisted(() => ({
  api: {
    getTenantSettings: vi.fn(),
    getProviderAvailability: vi.fn(),
    getResearchStatus: vi.fn(),
    listProviders: vi.fn(),
    listAgents: vi.fn(),
    createAgent: vi.fn(),
    listAgentCapabilities: vi.fn(),
    setAgentCapability: vi.fn(),
    listAgentCapabilitiesQuiet: vi.fn(),
    setAgentCapabilityQuiet: vi.fn(),
  },
  send: vi.fn(),
  push: vi.fn(),
  tutor: vi.fn(),
  mcp: vi.fn(),
  e2b: vi.fn(),
}));

vi.mock("../lib/api", () => ({ api: h.api }));
vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: h.push }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: any) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("./telemetry", () => ({ sendOnboardingEvent: h.send }));
vi.mock("./probes", () => ({ probeMcpServers: h.mcp, probeE2bCredential: h.e2b }));
vi.mock("../learn/tutor/tutorAvailability", () => ({ tutorAvailable: h.tutor }));

// The real panel streams from a model; this stub lets the test place a turn
// without one. It reports the pre-filled box text and a button that stands in
// for "a reply came back".
vi.mock("../components/ChatPanel", () => ({
  default: ({ agent, initialInput, onTurnComplete }: any) => (
    <div data-testid="chat" data-agent={agent?.id}>
      <textarea aria-label="composer" defaultValue={initialInput} readOnly />
      <button type="button" onClick={() => onTurnComplete?.()}>
        finish-turn
      </button>
    </div>
  ),
}));

const AGENT = { id: "a1", name: "Client Call to Action Items" };

function seed(state: Record<string, unknown>) {
  window.localStorage.setItem(
    FLOW_STORAGE_KEY,
    JSON.stringify({
      step: "goal",
      goal: null,
      templateKey: null,
      agentId: null,
      startedAt: Date.now() - 60_000,
      prefilled: false,
      firstRunAt: null,
      firstRunSeconds: null,
      fired: [],
      ...state,
    })
  );
}

const flowStep = () => screen.getByTestId("start-flow").getAttribute("data-step");

describe("StartFlow", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    window.localStorage.clear();
    h.api.getTenantSettings.mockResolvedValue({ plan: "PREMIUM" });
    h.api.getProviderAvailability.mockResolvedValue({ canCreateAgents: true });
    h.api.getResearchStatus.mockRejectedValue({ status: 404 });
    h.api.listProviders.mockResolvedValue([]);
    h.api.createAgent.mockResolvedValue(AGENT);
    h.api.listAgents.mockResolvedValue([AGENT]);
    h.api.listAgentCapabilities.mockResolvedValue([]);
    h.api.setAgentCapability.mockResolvedValue({});
    h.api.listAgentCapabilitiesQuiet.mockResolvedValue([]);
    h.api.setAgentCapabilityQuiet.mockResolvedValue({});
    h.tutor.mockResolvedValue(true);
    h.mcp.mockResolvedValue([]);
    h.e2b.mockResolvedValue({ codeExecutionTemplateId: "t1" });
  });
  afterEach(() => {
    cleanup();
  });

  it("walks goal -> create -> try, pre-fills but never sends, and counts the first run", async () => {
    render(<StartFlow />);
    await screen.findByTestId("start-flow");
    expect(flowStep()).toBe("goal");
    await waitFor(() => expect(h.send).toHaveBeenCalledWith("onboarding_start"));
    await waitFor(() => expect(screen.getByTestId("start-goal-draft")).toHaveAttribute("data-state", "ready"));

    // step 1 -> 2
    fireEvent.click(within(screen.getByTestId("start-goal-draft")).getByRole("button"));
    expect(flowStep()).toBe("create");
    expect(h.send).toHaveBeenCalledWith("onboarding_goal_selected_draft");

    // step 2 -> 3: one click creates the agent from the existing template
    fireEvent.click(screen.getByRole("button", { name: "createButton" }));
    await waitFor(() => expect(flowStep()).toBe("try"));
    expect(h.api.createAgent).toHaveBeenCalledTimes(1);
    expect(h.api.createAgent).toHaveBeenCalledWith(
      expect.objectContaining({ name: "clientCallToActionsName", providerName: "ANTHROPIC", experienceMode: "SIMPLE" })
    );
    expect(h.send).toHaveBeenCalledWith("onboarding_agent_created");

    // step 3: the sample question is in the box, not sent
    const chat = await screen.findByTestId("chat");
    expect(chat).toHaveAttribute("data-agent", "a1");
    expect((screen.getByLabelText("composer") as HTMLTextAreaElement).value).toBe("promptDraft");
    await waitFor(() => expect(h.send).toHaveBeenCalledWith("onboarding_prompt_prefilled"));
    expect(h.send).not.toHaveBeenCalledWith("onboarding_first_run_completed");
    expect(screen.queryByTestId("start-first-run")).toBeNull();

    // the first completed turn: celebration + the counted events
    fireEvent.click(screen.getByRole("button", { name: "finish-turn" }));
    expect(await screen.findByTestId("start-first-run")).toBeInTheDocument();
    expect(h.send).toHaveBeenCalledWith("onboarding_first_run_completed");
    expect(h.send).toHaveBeenCalledWith(expect.stringMatching(/^onboarding_first_run_(lt1m|lt2m|lt5m|lt10m|gte10m)$/));
    const saved = JSON.parse(window.localStorage.getItem(FLOW_STORAGE_KEY) as string);
    expect(saved.firstRunAt).toEqual(expect.any(Number));
    expect(saved.agentId).toBe("a1");
  });

  it("counts each event once even when the same transitions re-render", async () => {
    render(<StartFlow />);
    await waitFor(() => expect(h.send).toHaveBeenCalledWith("onboarding_start"));
    await waitFor(() => expect(screen.getByTestId("start-goal-draft")).toHaveAttribute("data-state", "ready"));
    expect(h.send.mock.calls.filter((c) => c[0] === "onboarding_start")).toHaveLength(1);
  });

  it("shows each card's honest prerequisite state and never claims a capability that is off", async () => {
    h.api.getTenantSettings.mockResolvedValue({ plan: "FREE" });
    h.e2b.mockResolvedValue(null); // no E2B credential
    render(<StartFlow />);
    await waitFor(() => expect(screen.getByTestId("start-goal-research")).toHaveAttribute("data-state", "locked"));
    const analyze = screen.getByTestId("start-goal-analyze");
    expect(analyze).toHaveAttribute("data-state", "reduced");
    expect(within(analyze).getByText("missingCodeExecution")).toBeInTheDocument();
    expect(screen.getByTestId("start-goal-draft")).toHaveAttribute("data-state", "ready");
    expect(screen.getByTestId("start-goal-build")).toHaveAttribute("data-state", "ready");
    // a card with a missing prerequisite can still be chosen
    expect(within(analyze).getByRole("button")).toBeEnabled();
  });

  it("explains the missing prerequisite on step 2 and still lets the person continue in reduced mode", async () => {
    h.e2b.mockResolvedValue(null);
    render(<StartFlow />);
    await waitFor(() => expect(screen.getByTestId("start-goal-analyze")).toHaveAttribute("data-state", "reduced"));
    fireEvent.click(within(screen.getByTestId("start-goal-analyze")).getByRole("button"));
    const prereqs = screen.getByTestId("start-prereqs");
    expect(within(prereqs).getByText("missingCodeExecution")).toBeInTheDocument();
    expect(within(prereqs).getByRole("link", { name: "setupCodeExecution" })).toHaveAttribute("href", "/providers");
    expect(screen.getByRole("button", { name: "createButtonReduced" })).toBeEnabled();
  });

  it("offers setup instead of a create button when no model can run an agent", async () => {
    h.api.getProviderAvailability.mockResolvedValue({ canCreateAgents: false });
    render(<StartFlow />);
    await waitFor(() => expect(screen.getByTestId("start-goal-draft")).toHaveAttribute("data-state", "blocked"));
    fireEvent.click(within(screen.getByTestId("start-goal-draft")).getByRole("button"));
    expect(screen.queryByRole("button", { name: "createButton" })).toBeNull();
    // The goal card's hint and the step panel each offer setup: every one of them must lead to /providers.
    const setupLinks = screen.getAllByRole("link", { name: "setupProvider" });
    expect(setupLinks.length).toBeGreaterThanOrEqual(1);
    for (const link of setupLinks) expect(link).toHaveAttribute("href", "/providers");
  });

  it("opens the tutor page for the learn goal, and sets up the fallback tutor agent when the tutor is off", async () => {
    render(<StartFlow />);
    await waitFor(() => expect(screen.getByTestId("start-goal-learn")).toHaveAttribute("data-state", "ready"));
    fireEvent.click(within(screen.getByTestId("start-goal-learn")).getByRole("button"));
    expect(screen.getByRole("link", { name: "openLearn" })).toHaveAttribute("href", "/learn/tutor");
    cleanup();
    window.localStorage.clear();

    h.tutor.mockResolvedValue(false);
    render(<StartFlow />);
    await waitFor(() => expect(screen.getByTestId("start-goal-learn")).toHaveAttribute("data-state", "ready"));
    fireEvent.click(within(screen.getByTestId("start-goal-learn")).getByRole("button"));
    await waitFor(() => expect(screen.getByRole("button", { name: "createButton" })).toBeInTheDocument());
    expect(screen.getByText("noteTutorOff")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "openLearn" })).toBeNull();
  });

  it("can be skipped at the first step: leaves for the dashboard, counts it, stays resumable", async () => {
    render(<StartFlow />);
    await screen.findByTestId("start-flow");
    fireEvent.click(screen.getByRole("button", { name: "skipForNow" }));
    expect(h.push).toHaveBeenCalledWith("/home");
    expect(h.send).toHaveBeenCalledWith("onboarding_skipped_goal");
    const saved = JSON.parse(window.localStorage.getItem(FLOW_STORAGE_KEY) as string);
    expect(saved.fired).toContain("onboarding_skipped_goal");
  });

  it("goes back from step 2 to the goal list", async () => {
    render(<StartFlow />);
    await waitFor(() => expect(screen.getByTestId("start-goal-draft")).toHaveAttribute("data-state", "ready"));
    fireEvent.click(within(screen.getByTestId("start-goal-draft")).getByRole("button"));
    fireEvent.click(screen.getByRole("button", { name: "back" }));
    expect(flowStep()).toBe("goal");
  });

  it("resumes where the person left off, and does not recount the start", async () => {
    seed({
      step: "create",
      goal: "research",
      templateKey: "research-assistant",
      fired: ["onboarding_start", "onboarding_goal_selected_research"],
    });
    render(<StartFlow />);
    await screen.findByTestId("start-flow");
    expect(flowStep()).toBe("create");
    expect(screen.getByTestId("start-resumed")).toBeInTheDocument();
    await waitFor(() => expect(h.api.getTenantSettings).toHaveBeenCalled());
    expect(h.send).not.toHaveBeenCalledWith("onboarding_start");
    expect(h.send).not.toHaveBeenCalledWith("onboarding_goal_selected_research");
  });

  it("resumes into the try step by finding the agent again", async () => {
    seed({ step: "try", goal: "draft", templateKey: "client-call-to-actions", agentId: "a1", prefilled: true });
    render(<StartFlow />);
    expect(await screen.findByTestId("chat")).toHaveAttribute("data-agent", "a1");
    expect(h.api.createAgent).not.toHaveBeenCalled();
  });

  it("steps back to create when the resumed agent no longer exists", async () => {
    seed({ step: "try", goal: "draft", templateKey: "client-call-to-actions", agentId: "gone" });
    h.api.listAgents.mockResolvedValue([]);
    render(<StartFlow />);
    await waitFor(() => expect(flowStep()).toBe("create"));
    expect(screen.getByText("agentGone")).toBeInTheDocument();
    expect(screen.queryByTestId("chat")).toBeNull();
  });

  it("shows the server's plain-language reason when the create fails, and stays on step 2", async () => {
    h.api.createAgent.mockRejectedValue(new Error("You have reached the agent limit for your plan."));
    render(<StartFlow />);
    await waitFor(() => expect(screen.getByTestId("start-goal-draft")).toHaveAttribute("data-state", "ready"));
    fireEvent.click(within(screen.getByTestId("start-goal-draft")).getByRole("button"));
    fireEvent.click(screen.getByRole("button", { name: "createButton" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("You have reached the agent limit for your plan.");
    expect(flowStep()).toBe("create");
    expect(h.send).not.toHaveBeenCalledWith("onboarding_agent_created");
  });

  it("works with storage blocked: no resume, but the flow still runs", async () => {
    const getSpy = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const setSpy = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    try {
      render(<StartFlow />);
      await screen.findByTestId("start-flow");
      await waitFor(() => expect(screen.getByTestId("start-goal-draft")).toHaveAttribute("data-state", "ready"));
      fireEvent.click(within(screen.getByTestId("start-goal-draft")).getByRole("button"));
      expect(flowStep()).toBe("create");
    } finally {
      getSpy.mockRestore();
      setSpy.mockRestore();
    }
  });
});
