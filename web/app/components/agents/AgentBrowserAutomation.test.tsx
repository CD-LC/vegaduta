// Browser automation cards: invisible until core switches the features on (flag-off identity), then the
// saved-logins card (list, attach, create with domain validation) and the recorded-tasks card (list, two-step
// delete), plus "save this run as a recipe" which only appears for a run that drove the browser. i18n is
// mocked to echo keys, so this asserts behaviour, not copy.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

const mockApi = vi.hoisted(() => ({
  getBrowserCapabilities: vi.fn(),
  listBrowserProfiles: vi.fn(),
  createBrowserProfile: vi.fn(),
  attachBrowserProfile: vi.fn(),
  detachBrowserProfile: vi.fn(),
  clearBrowserProfileState: vi.fn(),
  deleteBrowserProfile: vi.fn(),
  listBrowserRecipes: vi.fn(),
  deleteBrowserRecipe: vi.fn(),
  saveBrowserRunAsRecipe: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api: mockApi }));

import AgentBrowserAutomation from "./AgentBrowserAutomation";
import SaveRunAsRecipe from "./SaveRunAsRecipe";
import { resetBrowserCapabilitiesCache } from "./useBrowserCapabilities";

function profile(over: Record<string, unknown> = {}) {
  return {
    id: "p1",
    name: "Work login",
    scope: "TENANT",
    ownedByMe: false,
    allowedDomains: ["app.example.com"],
    hasState: false,
    stateBytes: 0,
    stateEntries: 0,
    ttlDays: 30,
    expiresAt: null as string | null,
    expired: false,
    lastSavedAt: null as string | null,
    lastUsedAt: null as string | null,
    createdAt: "2026-10-01T00:00:00Z",
    attachedAgentIds: [] as string[],
    ...over,
  };
}

function recipe(over: Record<string, unknown> = {}) {
  return {
    id: "r1",
    agentId: "agent-1",
    name: "Check order status",
    description: "Looks up an order",
    allowedDomains: ["shop.example.com"],
    parameters: ["orderId"],
    stepCount: 4,
    runCount: 0,
    failCount: 0,
    lastRunAt: null as string | null,
    lastFailure: null as string | null,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...over,
  };
}

function caps(over: Record<string, boolean> = {}) {
  mockApi.getBrowserCapabilities.mockResolvedValue({ profilesEnabled: false, recipesEnabled: false, totpEnabled: false, ...over });
}

beforeEach(() => {
  vi.clearAllMocks();
  resetBrowserCapabilitiesCache();
  mockApi.listBrowserProfiles.mockResolvedValue([profile()]);
  mockApi.listBrowserRecipes.mockResolvedValue([recipe()]);
  mockApi.createBrowserProfile.mockResolvedValue(profile());
  mockApi.attachBrowserProfile.mockResolvedValue(profile());
  mockApi.deleteBrowserRecipe.mockResolvedValue(null);
});

describe("flag-off identity", () => {
  it("renders nothing and fetches nothing else while every feature is off", async () => {
    caps();
    const { container } = render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    await waitFor(() => expect(mockApi.getBrowserCapabilities).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
    expect(mockApi.listBrowserProfiles).not.toHaveBeenCalled();
    expect(mockApi.listBrowserRecipes).not.toHaveBeenCalled();
  });

  it("renders nothing when the capability probe fails", async () => {
    mockApi.getBrowserCapabilities.mockRejectedValue(new Error("404"));
    const { container } = render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    await waitFor(() => expect(mockApi.getBrowserCapabilities).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it("does not cache a failed probe: the next mount asks again", async () => {
    mockApi.getBrowserCapabilities.mockRejectedValueOnce(new Error("503"));
    const first = render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);
    await waitFor(() => expect(mockApi.getBrowserCapabilities).toHaveBeenCalledTimes(1));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(first.container).toBeEmptyDOMElement();
    first.unmount();

    caps({ recipesEnabled: true });
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    expect(await screen.findByTestId("browser-recipes-card")).toBeInTheDocument();
    expect(mockApi.getBrowserCapabilities).toHaveBeenCalledTimes(2);
  });

  it("renders nothing when core reports that there is no headless browser", async () => {
    caps({ profilesEnabled: true, recipesEnabled: true, browserAvailable: false });
    const { container } = render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    await waitFor(() => expect(mockApi.getBrowserCapabilities).toHaveBeenCalled());
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(container).toBeEmptyDOMElement();
    expect(mockApi.listBrowserProfiles).not.toHaveBeenCalled();
  });

  it("shows only the card of the feature that is on", async () => {
    caps({ recipesEnabled: true });
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    expect(await screen.findByTestId("browser-recipes-card")).toBeInTheDocument();
    expect(screen.queryByTestId("browser-profiles-card")).not.toBeInTheDocument();
    expect(mockApi.listBrowserProfiles).not.toHaveBeenCalled();
  });
});

describe("saved logins card", () => {
  beforeEach(() => caps({ profilesEnabled: true }));

  it("lists profiles with their status and says when the agent has none attached", async () => {
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    expect(await screen.findByTestId("browser-profile-p1")).toHaveTextContent("Work login");
    expect(screen.getByText("statusNoLogin")).toBeInTheDocument();
    expect(screen.getByText("noCurrentProfile")).toBeInTheDocument();
  });

  it("attaches the chosen profile to this agent", async () => {
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    const select = await screen.findByLabelText("useForAgent");
    fireEvent.click(select);
    fireEvent.click(screen.getAllByRole("option").find((o) => /Work login/.test(o.textContent ?? ""))!);
    fireEvent.click(screen.getByRole("button", { name: "useForAgent" }));

    await waitFor(() => expect(mockApi.attachBrowserProfile).toHaveBeenCalledWith("p1", "agent-1"));
  });

  it("shows the profile the agent already uses and lets it be detached", async () => {
    mockApi.listBrowserProfiles.mockResolvedValue([profile({ attachedAgentIds: ["agent-1"], hasState: true, stateBytes: 2048, stateEntries: 4, expiresAt: "2099-01-01T00:00:00Z" })]);
    mockApi.detachBrowserProfile.mockResolvedValue(null);
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    expect(await screen.findByText("currentProfile:Work login")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "stopUsing" }));

    await waitFor(() => expect(mockApi.detachBrowserProfile).toHaveBeenCalledWith("p1", "agent-1"));
  });

  it("refuses a form with an unusable domain before calling core", async () => {
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    fireEvent.change(await screen.findByPlaceholderText("fieldNamePlaceholder"), { target: { value: "Work" } });
    fireEvent.change(screen.getByPlaceholderText("fieldDomainsPlaceholder"), { target: { value: "localhost, app.example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "createProfile" }));

    expect(await screen.findByText("invalidDomains:localhost")).toBeInTheDocument();
    expect(mockApi.createBrowserProfile).not.toHaveBeenCalled();
  });

  it("creates a profile with normalized domains", async () => {
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    fireEvent.change(await screen.findByPlaceholderText("fieldNamePlaceholder"), { target: { value: " Work " } });
    fireEvent.change(screen.getByPlaceholderText("fieldDomainsPlaceholder"), {
      target: { value: "https://App.Example.com/login, login.example.com" },
    });
    fireEvent.click(screen.getByRole("button", { name: "createProfile" }));

    await waitFor(() =>
      expect(mockApi.createBrowserProfile).toHaveBeenCalledWith({
        name: "Work",
        allowedDomains: ["app.example.com", "login.example.com"],
        scope: "TENANT",
        ttlDays: 30,
      }),
    );
    expect(await screen.findByText("profileCreated")).toBeInTheDocument();
  });

  it("locks the attach and create controls on a locked agent", async () => {
    render(<AgentBrowserAutomation agentId="agent-1" locked editLockedTooltip="locked" />);

    expect(await screen.findByRole("button", { name: "createProfile" })).toBeDisabled();
    expect(screen.getByLabelText("useForAgent")).toBeDisabled();
  });
});

describe("recorded tasks card", () => {
  beforeEach(() => caps({ recipesEnabled: true }));

  it("lists the recipes with their parameters and step count", async () => {
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    expect(await screen.findByText("Check order status", { exact: false })).toBeInTheDocument();
    expect(screen.getByText(/recipeSteps:4/)).toBeInTheDocument();
    expect(screen.getByText(/recipeParams:orderId/)).toBeInTheDocument();
    expect(mockApi.listBrowserRecipes).toHaveBeenCalledWith("agent-1");
  });

  it("deletes only after a second, confirming click", async () => {
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "deleteRecipe" }));
    expect(mockApi.deleteBrowserRecipe).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "confirmDelete" }));

    await waitFor(() => expect(mockApi.deleteBrowserRecipe).toHaveBeenCalledWith("r1"));
  });

  it("flags a recipe whose last replay failed", async () => {
    mockApi.listBrowserRecipes.mockResolvedValue([recipe({ runCount: 3, failCount: 1, lastFailure: "step 2: verification failed" })]);
    render(<AgentBrowserAutomation agentId="agent-1" locked={false} />);

    expect(await screen.findByText("healthFailing")).toBeInTheDocument();
    expect(screen.getByText("recipeLastFailure:step 2: verification failed")).toBeInTheDocument();
  });
});

describe("save this run as a recipe", () => {
  const browserRun = [
    { type: "tool_call", tool: "browser_navigate" },
    { type: "tool_call", tool: "browser_click" },
    { type: "tool_result", tool: "browser_click" },
  ];

  it("is absent for a run that did not drive the browser", async () => {
    caps({ recipesEnabled: true });
    const { container } = render(<SaveRunAsRecipe agentId="agent-1" sessionId="s1" steps={[{ type: "tool_call", tool: "fetch_web_page" }]} />);

    await waitFor(() => expect(mockApi.getBrowserCapabilities).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it("is absent while recipes are off", async () => {
    caps();
    const { container } = render(<SaveRunAsRecipe agentId="agent-1" sessionId="s1" steps={browserRun} />);

    await waitFor(() => expect(mockApi.getBrowserCapabilities).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it("is absent before the run has a session id", async () => {
    caps({ recipesEnabled: true });
    const { container } = render(<SaveRunAsRecipe agentId="agent-1" sessionId={null} steps={browserRun} />);

    await waitFor(() => expect(mockApi.getBrowserCapabilities).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it("sends the name, the parameters and the session, and shows what core warned about", async () => {
    caps({ recipesEnabled: true });
    mockApi.saveBrowserRunAsRecipe.mockResolvedValue({
      summary: { name: "Check order", stepCount: 3 },
      spec: {},
      warnings: ["1 step(s) look like they submit"],
    });
    render(<SaveRunAsRecipe agentId="agent-1" sessionId="s1" steps={browserRun} />);

    fireEvent.click(await screen.findByRole("button", { name: "saveRun" }));
    fireEvent.change(screen.getByPlaceholderText("recipeNamePlaceholder"), { target: { value: " Check order " } });
    fireEvent.click(screen.getByRole("button", { name: "addParam" }));
    fireEvent.change(screen.getByPlaceholderText("paramName"), { target: { value: "orderId" } });
    fireEvent.change(screen.getByPlaceholderText("paramValue"), { target: { value: "A-1042" } });
    fireEvent.click(screen.getByRole("button", { name: "saveRecipe" }));

    await waitFor(() =>
      expect(mockApi.saveBrowserRunAsRecipe).toHaveBeenCalledWith({
        agentId: "agent-1",
        sessionId: "s1",
        name: "Check order",
        description: undefined,
        parameters: { orderId: "A-1042" },
        allowLiteralText: false,
      }),
    );
    expect(await screen.findByText("recipeSaved:Check order,3")).toBeInTheDocument();
    expect(screen.getByText("1 step(s) look like they submit")).toBeInTheDocument();
  });

  it("does not call core with a nameless recipe or a bad parameter row", async () => {
    caps({ recipesEnabled: true });
    render(<SaveRunAsRecipe agentId="agent-1" sessionId="s1" steps={browserRun} />);

    fireEvent.click(await screen.findByRole("button", { name: "saveRun" }));
    fireEvent.click(screen.getByRole("button", { name: "saveRecipe" }));
    expect(await screen.findByText("needRecipeName")).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText("recipeNamePlaceholder"), { target: { value: "Check order" } });
    fireEvent.click(screen.getByRole("button", { name: "addParam" }));
    fireEvent.change(screen.getByPlaceholderText("paramName"), { target: { value: "secret" } });
    fireEvent.change(screen.getByPlaceholderText("paramValue"), { target: { value: "A-1042" } });
    fireEvent.click(screen.getByRole("button", { name: "saveRecipe" }));

    expect(await screen.findByText("paramReserved")).toBeInTheDocument();
    expect(mockApi.saveBrowserRunAsRecipe).not.toHaveBeenCalled();
  });
});
