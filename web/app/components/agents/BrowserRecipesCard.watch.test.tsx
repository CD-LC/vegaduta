// The "Watch" affordance on the recorded-tasks card: a per-recipe toggle that polls the agent's
// tool-call log (GET /api/agents/{id}/tool-calls, bypassing the GET cache) and lists this recipe's
// run_browser_recipe calls as a live ticker - running / done / failed. i18n echoes keys; the fetch
// layer and the api object are both mocked.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

const mockApi = vi.hoisted(() => ({
  listBrowserRecipes: vi.fn(),
  deleteBrowserRecipe: vi.fn(),
}));
const apiFetchMock = vi.hoisted(() => vi.fn());

vi.mock("../../lib/api", () => ({ api: mockApi }));
vi.mock("../../lib/api/core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  toJson: async (res: Response) => res.json(),
}));

import BrowserRecipesCard, { replayEntriesForRecipe } from "./BrowserRecipesCard";

function recipe(over: Record<string, unknown> = {}) {
  return {
    id: "r1",
    agentId: "agent-1",
    name: "Check order status",
    description: null as string | null,
    allowedDomains: ["shop.example.com"],
    parameters: [] as string[],
    stepCount: 4,
    runCount: 2,
    failCount: 0,
    lastRunAt: null as string | null,
    lastFailure: null as string | null,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...over,
  };
}

function toolCall(over: Record<string, unknown> = {}) {
  return {
    id: "tc1",
    sessionId: "s1",
    toolName: "run_browser_recipe",
    toolInput: '{"recipeId":"r1"}',
    toolOutput: null as string | null,
    errorMessage: null as string | null,
    startedAt: "2026-10-20T10:00:00Z",
    completedAt: "2026-10-20T10:00:05Z",
    mcpGrantId: null as string | null,
    ...over,
  };
}

const toolCallsRes = (rows: unknown[]) =>
  new Response(JSON.stringify(rows), { status: 200, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  vi.clearAllMocks();
  mockApi.listBrowserRecipes.mockResolvedValue([recipe()]);
  apiFetchMock.mockImplementation(async () => toolCallsRes([]));
});

describe("replayEntriesForRecipe", () => {
  it("keeps only run_browser_recipe calls that name the recipe, oldest first", () => {
    const entries = [
      toolCall({ id: "c", startedAt: "2026-10-20T10:05:00Z" }),
      toolCall({ id: "a", startedAt: "2026-10-20T10:01:00Z" }),
      toolCall({ id: "b", startedAt: "2026-10-20T10:03:00Z", toolInput: '{"recipeId":"other"}' }),
      toolCall({ id: "d", toolName: "browser_click" }),
    ];
    const out = replayEntriesForRecipe(entries as never, { id: "r1", name: "Check order status" });
    expect(out.map((e) => e.id)).toEqual(["a", "c"]);
  });

  it("also matches a replay the agent launched by name instead of id", () => {
    const entries = [toolCall({ toolInput: '{"recipeId":"Check order status","params":{}}' })];
    expect(replayEntriesForRecipe(entries as never, { id: "r1", name: "Check order status" })).toHaveLength(1);
  });
});

describe("watch ticker", () => {
  it("polls the tool-call log uncached and shows the recipe's replays with their state", async () => {
    apiFetchMock.mockImplementation(async () =>
      toolCallsRes([
        toolCall({ id: "running", completedAt: null, startedAt: "2026-10-20T10:02:00Z" }),
        toolCall({ id: "done", startedAt: "2026-10-20T10:00:00Z" }),
        toolCall({ id: "other", toolInput: '{"recipeId":"other"}' }),
      ])
    );
    render(<BrowserRecipesCard agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "watch" }));

    const pane = await screen.findByTestId("browser-recipe-watch-r1");
    await waitFor(() => expect(apiFetchMock).toHaveBeenCalledWith("/api/agents/agent-1/tool-calls", { skipGetCache: true }));
    await waitFor(() => expect(pane).toHaveTextContent("watchRunning"));
    expect(pane).toHaveTextContent("watchDone");
    // A replay of a different recipe is filtered out.
    expect(pane.querySelectorAll("li")).toHaveLength(2);
  });

  it("marks a failed replay and shows its reason", async () => {
    apiFetchMock.mockImplementation(async () =>
      toolCallsRes([toolCall({ errorMessage: "step 2: verification failed" })])
    );
    render(<BrowserRecipesCard agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "watch" }));
    const pane = await screen.findByTestId("browser-recipe-watch-r1");
    await waitFor(() => expect(pane).toHaveTextContent("watchFailed"));
    expect(pane).toHaveTextContent("step 2: verification failed");
  });

  it("says when nothing is replaying and stops polling when toggled off", async () => {
    render(<BrowserRecipesCard agentId="agent-1" locked={false} />);

    fireEvent.click(await screen.findByRole("button", { name: "watch" }));
    expect(await screen.findByText("watchEmpty")).toBeInTheDocument();
    await waitFor(() => expect(apiFetchMock).toHaveBeenCalled());
    const calls = apiFetchMock.mock.calls.length;

    fireEvent.click(screen.getByRole("button", { name: "watchStop" }));
    expect(screen.queryByTestId("browser-recipe-watch-r1")).not.toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(apiFetchMock.mock.calls.length).toBe(calls);
  });
});
