import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import AgentImprovementsCard from "./AgentImprovementsCard";

const mocks = vi.hoisted(() => ({
  list: vi.fn(), generate: vi.fn(), accept: vi.fn(), dismiss: vi.fn(),
}));
vi.mock("../../lib/i18n", async () => (await import("./testI18n")).i18nMock);
vi.mock("../../lib/api", () => ({
  api: {
    listAgentImprovements: mocks.list,
    generateAgentImprovements: mocks.generate,
    acceptAgentImprovement: mocks.accept,
    dismissAgentImprovement: mocks.dismiss,
  },
}));

const s: any = { id: "s1", agentId: "a", kind: "PROMPT_REVISION", title: "Tighten prompt", rationale: "why", suggestedText: "new", status: "PENDING", appliedRef: null, createdAt: "2026-01-01T00:00:00Z", decidedAt: null, decidedBy: null };

describe("AgentImprovementsCard", () => {
  beforeEach(() => vi.clearAllMocks());
  it("lists and accepts a suggestion", async () => {
    mocks.list.mockResolvedValue([s]);
    mocks.accept.mockResolvedValue({ ...s, status: "ACCEPTED" });
    render(<AgentImprovementsCard agentId="a" />);
    await screen.findByText("Tighten prompt");
    fireEvent.click(screen.getByText("Accept"));
    await waitFor(() => expect(mocks.accept).toHaveBeenCalledWith("a", "s1"));
    await screen.findByText("Suggestion accepted.");
  });
  it("generates suggestions", async () => {
    mocks.list.mockResolvedValue([]);
    mocks.generate.mockResolvedValue([s]);
    render(<AgentImprovementsCard agentId="a" />);
    await screen.findByText("No suggestions");
    mocks.list.mockResolvedValue([s]);
    fireEvent.click(screen.getByText("Generate suggestions"));
    await screen.findByText("Tighten prompt");
  });
  it("shows error with retry", async () => {
    mocks.list.mockRejectedValueOnce(new Error("down"));
    render(<AgentImprovementsCard agentId="a" />);
    await screen.findByText("down");
    expect(screen.getByText("Retry")).toBeTruthy();
  });
});
