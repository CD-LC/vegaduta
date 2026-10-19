import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import AgentVersionsCard from "./AgentVersionsCard";

const mocks = vi.hoisted(() => ({
  list: vi.fn(), diff: vi.fn(), promote: vi.fn(), rollback: vi.fn(),
}));
vi.mock("../../lib/i18n", async () => (await import("./testI18n")).i18nMock);
vi.mock("../../lib/api", () => ({
  api: {
    listAgentVersions: mocks.list,
    diffAgentVersions: mocks.diff,
    promoteAgentVersion: mocks.promote,
    rollbackAgentVersion: mocks.rollback,
  },
}));

const v = (n: number, label: any = null): any => ({ id: `v${n}`, agentId: "a", versionNumber: n, agentName: "A", providerName: "P", model: "m", changeSummary: null, label, createdBy: null, createdAt: "2026-01-01T00:00:00Z" });

describe("AgentVersionsCard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.list.mockResolvedValue([v(2), v(1, "PRODUCTION")]);
    mocks.diff.mockResolvedValue({ fields: [{ field: "model", from: "a", to: "b" }], unchanged: false });
  });
  it("shows the diff before promoting, then promotes", async () => {
    mocks.promote.mockResolvedValue(v(2, "PRODUCTION"));
    render(<AgentVersionsCard agentId="a" locked={false} />);
    await screen.findByText("v2");
    fireEvent.click(screen.getByRole("button", { name: "Promote" }));
    await screen.findByText("model");
    expect(mocks.diff).toHaveBeenCalledWith("a", 1, 2);
    fireEvent.click(screen.getAllByRole("button", { name: "Promote" }).pop()!);
    await waitFor(() => expect(mocks.promote).toHaveBeenCalled());
  });
  it("rolls back production", async () => {
    mocks.rollback.mockResolvedValue(v(1));
    render(<AgentVersionsCard agentId="a" locked={false} />);
    await screen.findByText("v1");
    fireEvent.click(screen.getByRole("button", { name: /Roll back/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Roll back", hidden: false }).then(() => screen.getAllByRole("button", { name: "Roll back" }).pop()!));
    await waitFor(() => expect(mocks.rollback).toHaveBeenCalledWith("a"));
  });
});
