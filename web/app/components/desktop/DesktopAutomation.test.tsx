// Desktop automation cards: invisible until core's /api/desktop/* answers (flag-off identity - the
// availability probe IS the recipe list read), then the recipe card (list, per-recipe run panel with
// params + AUTO/MONITOR + irreversible confirm, two-step delete), "save this run as a desktop recipe"
// (only for a run that drove the desktop), and the monitor pane (polls frames only while a run is live).
// i18n is mocked to echo keys, so this asserts behaviour, not copy.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

const mockApi = vi.hoisted(() => ({
  listDesktopRecipes: vi.fn(),
  getDesktopRecipe: vi.fn(),
  createDesktopRecipe: vi.fn(),
  saveDesktopRunAsRecipe: vi.fn(),
  runDesktopRecipe: vi.fn(),
  updateDesktopRecipe: vi.fn(),
  deleteDesktopRecipe: vi.fn(),
  fetchDesktopMonitorFrame: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api: mockApi }));

import AgentDesktopAutomation from "./AgentDesktopAutomation";
import DesktopMonitor from "./DesktopMonitor";
import SaveRunAsDesktopRecipe, { isDesktopRun } from "./SaveRunAsDesktopRecipe";
import { resetDesktopAvailabilityCache } from "./useDesktopAvailability";

function recipe(over: Record<string, unknown> = {}) {
  return {
    id: "r1",
    agentId: "agent-1",
    name: "File the weekly report",
    description: "Opens the CMS and files the report",
    stepCount: 5,
    runCount: 0,
    failCount: 0,
    lastRunAt: null as string | null,
    lastFailure: null as string | null,
    defaultMode: "AUTO" as const,
    ...over,
  };
}

function detail(over: Record<string, unknown> = {}) {
  return {
    summary: recipe(),
    spec: {
      version: 1,
      allowedApps: ["Notes"],
      params: [{ name: "week", required: true }],
      steps: [{ action: "click", label: "File" }],
    },
    warnings: [] as string[],
    ...over,
  };
}

function available() {
  mockApi.listDesktopRecipes.mockResolvedValue([recipe()]);
}

beforeEach(() => {
  vi.clearAllMocks();
  resetDesktopAvailabilityCache();
  mockApi.listDesktopRecipes.mockResolvedValue([recipe()]);
  mockApi.getDesktopRecipe.mockResolvedValue(detail());
  mockApi.deleteDesktopRecipe.mockResolvedValue(null);
  mockApi.fetchDesktopMonitorFrame.mockRejectedValue(Object.assign(new Error("off"), { status: 503 }));
  // jsdom has no URL.createObjectURL; each test file runs isolated, so patching it here is safe.
  (URL as unknown as Record<string, unknown>).createObjectURL = vi.fn(() => "blob:frame-1");
  (URL as unknown as Record<string, unknown>).revokeObjectURL = vi.fn();
});

describe("flag-off identity", () => {
  it("renders nothing when the recipe list read fails (feature or sidecar off)", async () => {
    mockApi.listDesktopRecipes.mockRejectedValue(Object.assign(new Error("404"), { status: 404 }));
    const { container } = render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);

    await waitFor(() => expect(mockApi.listDesktopRecipes).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
    expect(mockApi.getDesktopRecipe).not.toHaveBeenCalled();
  });

  it("does not cache a failed probe: the next mount asks again", async () => {
    mockApi.listDesktopRecipes.mockRejectedValueOnce(new Error("503"));
    const first = render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);
    await waitFor(() => expect(mockApi.listDesktopRecipes).toHaveBeenCalledTimes(1));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(first.container).toBeEmptyDOMElement();
    first.unmount();

    available();
    render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);
    expect(await screen.findByTestId("desktop-recipes-card")).toBeInTheDocument();
    expect(mockApi.listDesktopRecipes).toHaveBeenCalledTimes(3); // probe + card's own list read
  });
});

describe("recorded desktop tasks card", () => {
  beforeEach(() => available());

  it("lists recipes with health and mode chips", async () => {
    render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);

    expect(await screen.findByTestId("desktop-recipe-r1")).toHaveTextContent("File the weekly report");
    expect(screen.getByText("healthNew")).toBeInTheDocument();
    expect(screen.getByText("modeAuto")).toBeInTheDocument();
    expect(screen.getByText(/recipeSteps:5/)).toBeInTheDocument();
    expect(mockApi.listDesktopRecipes).toHaveBeenCalledWith("agent-1");
  });

  it("deletes only after a second, confirming click", async () => {
    render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "deleteRecipe" }));
    expect(mockApi.deleteDesktopRecipe).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "confirmDelete" }));

    await waitFor(() => expect(mockApi.deleteDesktopRecipe).toHaveBeenCalledWith("r1"));
  });

  it("runs a recipe with its params and mode, then shows the step ticker", async () => {
    mockApi.runDesktopRecipe.mockResolvedValue({
      ok: true,
      recipe: { id: "r1", name: "File the weekly report", steps: 5 },
      mode: "MONITOR",
      stepsRun: 5,
      stepResults: [
        { index: 1, action: "open", ok: true },
        { index: 2, action: "click", ok: true, note: "File menu" },
      ],
    });
    render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "runRecipe" }));
    expect(await screen.findByTestId("desktop-run-r1")).toBeInTheDocument();
    await waitFor(() => expect(mockApi.getDesktopRecipe).toHaveBeenCalledWith("r1"));

    fireEvent.change(await screen.findByLabelText(/week/), { target: { value: "2026-W41" } });
    // Switch to MONITOR through the combobox.
    fireEvent.click(screen.getByRole("combobox", { name: "modeLabel" }));
    fireEvent.click(screen.getAllByRole("option").find((o) => /modeMonitor/.test(o.textContent ?? ""))!);
    fireEvent.click(screen.getByRole("button", { name: "runNow" }));

    await waitFor(() =>
      expect(mockApi.runDesktopRecipe).toHaveBeenCalledWith("r1", {
        params: { week: "2026-W41" },
        confirmIrreversible: undefined,
        mode: "MONITOR",
      })
    );
    expect(await screen.findByText("runDone:5,5")).toBeInTheDocument();
    const ticker = screen.getByTestId("desktop-monitor-ticker");
    expect(ticker).toHaveTextContent("monitorStep:2,click");
    expect(ticker).toHaveTextContent("File menu");
  });

  it("refuses to run while a required parameter is empty", async () => {
    render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "runRecipe" }));
    await waitFor(() => expect(mockApi.getDesktopRecipe).toHaveBeenCalledWith("r1"));
    fireEvent.click(await screen.findByRole("button", { name: "runNow" }));

    expect(await screen.findByText("needParam:week")).toBeInTheDocument();
    expect(mockApi.runDesktopRecipe).not.toHaveBeenCalled();
  });

  it("holds the run button until the irreversible steps are confirmed", async () => {
    mockApi.getDesktopRecipe.mockResolvedValue(
      detail({ spec: { version: 1, allowedApps: [], params: [], steps: [{ action: "click", irreversible: true }] } })
    );
    mockApi.runDesktopRecipe.mockResolvedValue({
      ok: true,
      recipe: { id: "r1", name: "r", steps: 1 },
      mode: "AUTO",
      stepsRun: 1,
      stepResults: [{ index: 1, action: "click", ok: true }],
    });
    render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "runRecipe" }));
    const runButton = await screen.findByRole("button", { name: "runNow" });
    expect(runButton).toBeDisabled();

    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "runNow" }));

    await waitFor(() =>
      expect(mockApi.runDesktopRecipe).toHaveBeenCalledWith("r1", {
        params: {},
        confirmIrreversible: true,
        mode: "AUTO",
      })
    );
  });

  it("shows a failed run's reason", async () => {
    mockApi.runDesktopRecipe.mockResolvedValue({
      ok: false,
      recipe: { id: "r1", name: "r", steps: 5 },
      mode: "AUTO",
      stepsRun: 1,
      failure: "step 2: element not found",
      stepResults: [
        { index: 1, action: "open", ok: true },
        { index: 2, action: "click", ok: false, note: "element not found" },
      ],
    });
    render(<AgentDesktopAutomation agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "runRecipe" }));
    await waitFor(() => expect(mockApi.getDesktopRecipe).toHaveBeenCalledWith("r1"));
    fireEvent.change(await screen.findByLabelText(/week/), { target: { value: "2026-W41" } });
    fireEvent.click(screen.getByRole("button", { name: "runNow" }));

    expect(await screen.findByText("runFailedAt:step 2: element not found")).toBeInTheDocument();
  });
});

describe("monitor pane", () => {
  it("renders nothing until a run, a frame or step results exist", () => {
    const { container } = render(<DesktopMonitor active={false} steps={null} />);
    expect(container).toBeEmptyDOMElement();
    expect(mockApi.fetchDesktopMonitorFrame).not.toHaveBeenCalled();
  });

  it("polls a frame while a run is active and keeps it after the run ends", async () => {
    mockApi.fetchDesktopMonitorFrame.mockResolvedValue(new Blob(["x"], { type: "image/png" }));
    const { rerender } = render(<DesktopMonitor active={true} steps={null} />);

    expect(await screen.findByTestId("desktop-monitor-frame")).toHaveAttribute("src", "blob:frame-1");

    rerender(<DesktopMonitor active={false} steps={[{ index: 1, action: "click", ok: true }]} />);
    expect(screen.getByTestId("desktop-monitor-frame")).toBeInTheDocument();
    expect(screen.getByTestId("desktop-monitor-ticker")).toHaveTextContent("monitorStep:1,click");
  });

  it("says the monitor is unavailable when the frame read 503s, without a toast", async () => {
    render(<DesktopMonitor active={true} steps={null} />);

    expect(await screen.findByText("monitorDown")).toBeInTheDocument();
    expect(screen.queryByTestId("desktop-monitor-frame")).not.toBeInTheDocument();
  });
});

describe("save this run as a desktop recipe", () => {
  const desktopRun = [
    { type: "tool_call", tool: "local_desktop_click" },
    { type: "tool_call", tool: "local_desktop_type" },
    { type: "tool_result", tool: "local_desktop_type" },
  ];

  it("isDesktopRun needs at least two desktop action calls", () => {
    expect(isDesktopRun(desktopRun)).toBe(true);
    expect(isDesktopRun([{ type: "tool_call", tool: "local_desktop_screen" }])).toBe(false);
    expect(
      isDesktopRun([
        { type: "tool_call", tool: "browser_click" },
        { type: "tool_call", tool: "browser_type" },
      ])
    ).toBe(false);
  });

  it("is absent for a run that did not drive the desktop", async () => {
    available();
    const { container } = render(
      <SaveRunAsDesktopRecipe
        agentId="agent-1"
        sessionId="s1"
        steps={[{ type: "tool_call", tool: "fetch_web_page" }]}
      />
    );

    await waitFor(() => expect(mockApi.listDesktopRecipes).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it("is absent while the feature is off", async () => {
    mockApi.listDesktopRecipes.mockRejectedValue(new Error("404"));
    const { container } = render(<SaveRunAsDesktopRecipe agentId="agent-1" sessionId="s1" steps={desktopRun} />);

    await waitFor(() => expect(mockApi.listDesktopRecipes).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
  });

  it("sends the name, parameters, default mode and session, and shows core's warnings", async () => {
    available();
    mockApi.saveDesktopRunAsRecipe.mockResolvedValue({
      summary: { name: "File report", stepCount: 3 },
      spec: {},
      warnings: ["1 step(s) look like they submit"],
    });
    render(<SaveRunAsDesktopRecipe agentId="agent-1" sessionId="s1" steps={desktopRun} />);

    fireEvent.click(await screen.findByRole("button", { name: "saveRun" }));
    fireEvent.change(screen.getByPlaceholderText("recipeNamePlaceholder"), { target: { value: " File report " } });
    fireEvent.click(screen.getByRole("button", { name: "addParam" }));
    fireEvent.change(screen.getByPlaceholderText("paramName"), { target: { value: "week" } });
    fireEvent.change(screen.getByPlaceholderText("paramValue"), { target: { value: "2026-W41" } });
    fireEvent.click(screen.getByRole("combobox", { name: "defaultModeLabel" }));
    fireEvent.click(screen.getAllByRole("option").find((o) => /modeMonitor/.test(o.textContent ?? ""))!);
    fireEvent.click(screen.getByRole("button", { name: "saveRecipe" }));

    await waitFor(() =>
      expect(mockApi.saveDesktopRunAsRecipe).toHaveBeenCalledWith({
        agentId: "agent-1",
        sessionId: "s1",
        name: "File report",
        description: undefined,
        parameters: { week: "2026-W41" },
        allowLiteralText: false,
        defaultMode: "MONITOR",
      })
    );
    expect(await screen.findByText("recipeSaved:File report,3")).toBeInTheDocument();
    expect(screen.getByText("1 step(s) look like they submit")).toBeInTheDocument();
  });
});
