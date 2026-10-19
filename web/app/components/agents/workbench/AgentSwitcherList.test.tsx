import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import AgentSwitcherList, { agentMatchesQuery } from "./AgentSwitcherList";

vi.mock("../../../lib/i18n", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...(await import("../testI18n")).i18nMock,
}));

const rowNames = () => screen.queryAllByText(/^Agent \d+$/);
const make = (n: number): any[] =>
  Array.from({ length: n }, (_, i) => ({ id: `a${i}`, name: `Agent ${i}`, model: i % 2 ? "gpt-x" : "claude-y" }));

describe("agentMatchesQuery", () => {
  it("matches every term against name, category and model", () => {
    const a: any = { name: "Support bot", model: "gpt-x" };
    expect(agentMatchesQuery(a, "Assistant", "")).toBe(true);
    expect(agentMatchesQuery(a, "Assistant", "support GPT")).toBe(true);
    expect(agentMatchesQuery(a, "Assistant", "assistant")).toBe(true);
    expect(agentMatchesQuery(a, "Assistant", "sales")).toBe(false);
  });
});

describe("AgentSwitcherList", () => {
  it("pages long lists and filters with the search box", () => {
    const onPick = vi.fn();
    render(
      <ul>
        <AgentSwitcherList agents={make(23)} selectedAgentId={null} disabled={false} onPick={onPick} />
      </ul>
    );
    expect(rowNames()).toHaveLength(10);
    fireEvent.change(screen.getByRole("searchbox", { name: "Search agents" }), { target: { value: "Agent 22" } });
    expect(rowNames()).toHaveLength(1);
    fireEvent.click(rowNames()[0]);
    expect(onPick).toHaveBeenCalledWith("a22");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "zzz" } });
    expect(rowNames()).toHaveLength(0);
    expect(screen.getByText('No agents match "zzz".')).toBeTruthy();
  });

  it("hides the search and pager for a short list", () => {
    render(
      <ul>
        <AgentSwitcherList agents={make(3)} selectedAgentId="a1" disabled={false} onPick={vi.fn()} />
      </ul>
    );
    expect(screen.queryByRole("searchbox")).toBeNull();
    expect(rowNames()).toHaveLength(3);
  });
});
