import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { api } from "../../lib/api";
import * as download from "../../lib/api/browserDownload";
import AgentsIndex from "./AgentsIndex";

vi.mock("../../lib/i18n", async () => (await import("./testI18n")).i18nMock);
vi.mock("../../lib/api", () => ({ api: { duplicateAgent: vi.fn(), deleteAgent: vi.fn(), exportAgentBundle: vi.fn() } }));

const mk = (i: number) =>
  ({ id: `a${i}`, name: `Agent ${i}`, providerName: i % 2 ? "OPENAI" : "ANTHROPIC", model: "m", updatedAt: `2026-01-${String(i).padStart(2, "0")}T00:00:00Z` }) as any;

function renderIdx(agents: any[], extra: Record<string, any> = {}) {
  return render(
    <AgentsIndex agents={agents} loaded onRetry={() => {}} onChanged={() => {}} agentHref={(id) => `/agents?agentId=${id}`} newHref="/agents?new=1" {...extra} />
  );
}

describe("AgentsIndex", () => {
  it("pages and searches the list", () => {
    renderIdx(Array.from({ length: 25 }, (_, i) => mk(i + 1)));
    expect(screen.getAllByRole("row")).toHaveLength(11); // header + 10
    fireEvent.change(screen.getByLabelText("Search agents by name or model"), { target: { value: "Agent 25" } });
    expect(screen.getAllByRole("row")).toHaveLength(2);
  });
  it("shows the empty state", () => {
    renderIdx([]);
    expect(screen.getByText("No agents yet")).toBeTruthy();
  });
  it("shows an error with retry", () => {
    const onRetry = vi.fn();
    renderIdx([], { error: "boom", onRetry });
    fireEvent.click(screen.getByText("Retry"));
    expect(onRetry).toHaveBeenCalled();
  });
  it("disables delete for a viewer and duplicate for a runner instead of failing with a toast", () => {
    renderIdx([
      { ...mk(1), accessLevel: "VIEWER" },
      { ...mk(2), accessLevel: "RUNNER" },
      { ...mk(3), accessLevel: "EDITOR" },
      { ...mk(4), accessLevel: null },
    ]);
    const state = (kind: string) =>
      Object.fromEntries(
        screen
          .getAllByRole("button")
          .filter((b) => new RegExp(kind, "i").test(b.getAttribute("aria-label") ?? ""))
          .map((b) => [(b.getAttribute("aria-label") ?? "").match(/Agent \d/)?.[0], (b as HTMLButtonElement).disabled])
      );
    expect(state("delete")).toEqual({ "Agent 1": true, "Agent 2": true, "Agent 3": false, "Agent 4": false });
    expect(state("duplicate")).toEqual({ "Agent 1": false, "Agent 2": true, "Agent 3": false, "Agent 4": false });
  });
  it("exports an agent as a downloadable file named after it", async () => {
    const save = vi.spyOn(download, "triggerBrowserDownload").mockImplementation(() => {});
    (api as any).exportAgentBundle.mockResolvedValue({ format: "vegaduta.agent-bundle/1", title: "Agent 1", agent: {} });
    renderIdx([mk(1)]);
    fireEvent.click(screen.getByLabelText("Export Agent 1 as a file"));
    await waitFor(() => expect(save).toHaveBeenCalled());
    expect((api as any).exportAgentBundle).toHaveBeenCalledWith("a1");
    expect(save.mock.calls[0][1]).toBe("agent-1.agent.json");
  });
});
