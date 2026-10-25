import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import KnowledgeWriteSection from "./KnowledgeWriteSection";

const hoisted = vi.hoisted(() => ({
  get: vi.fn(),
  setWrite: vi.fn(),
  setAccess: vi.fn(),
}));

vi.mock("../../../lib/api", () => ({
  api: {
    getAgentKnowledgeWriteAccess: (...args: unknown[]) => hoisted.get(...args),
    setAgentKnowledgeWrite: (...args: unknown[]) => hoisted.setWrite(...args),
    setAgentKnowledgeWriteAccess: (...args: unknown[]) => hoisted.setAccess(...args),
  },
}));

const t = (key: string) => key;
const collections = [
  { id: "c1", name: "Runbooks" },
  { id: "c2", name: "Policies" },
];

describe("KnowledgeWriteSection", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hoisted.get.mockResolvedValue({
      knowledgeWriteEnabled: true,
      allowedKnowledgeWriteCollectionIds: ["c1"],
    });
    hoisted.setWrite.mockResolvedValue({});
    hoisted.setAccess.mockResolvedValue({});
  });

  it("renders nothing for a draft (no agent id) and skips the fetch", () => {
    const { container } = render(
      <KnowledgeWriteSection t={t} locked={false} agentId={null} collections={collections} />
    );
    expect(container.innerHTML).toBe("");
    expect(hoisted.get).not.toHaveBeenCalled();
  });

  it("loads the agent's write posture and patches the flag", async () => {
    render(<KnowledgeWriteSection t={t} locked={false} agentId="a1" collections={collections} />);
    const toggle = await screen.findByRole("checkbox", { name: /knowledgeWriteLabel/ });
    await waitFor(() => expect((toggle as HTMLInputElement).checked).toBe(true));
    fireEvent.click(toggle);
    await waitFor(() => expect(hoisted.setWrite).toHaveBeenCalledWith("a1", false));
  });

  it("patches the grant list when a writable collection is toggled", async () => {
    render(<KnowledgeWriteSection t={t} locked={false} agentId="a1" collections={collections} />);
    const policies = await screen.findByRole("checkbox", { name: "Policies" });
    fireEvent.click(policies);
    await waitFor(() =>
      expect(hoisted.setAccess).toHaveBeenCalledWith("a1", ["c1", "c2"])
    );
  });

  it("hides the grant list while write is disabled", async () => {
    hoisted.get.mockResolvedValue({ knowledgeWriteEnabled: false, allowedKnowledgeWriteCollectionIds: [] });
    render(<KnowledgeWriteSection t={t} locked={false} agentId="a1" collections={collections} />);
    await screen.findByRole("checkbox", { name: /knowledgeWriteLabel/ });
    await waitFor(() => expect(hoisted.get).toHaveBeenCalledWith("a1"));
    expect(screen.queryByRole("checkbox", { name: "Runbooks" })).toBeNull();
  });
});
