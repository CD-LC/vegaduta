// After a settled Assistant turn the server tree is read once so the turn is recorded: only when the
// agent's branching capability is on (read once per agent), never applied, never thrown.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  getChatBranchingCapabilities: vi.fn(),
  getBranchPath: vi.fn(),
}));
vi.mock("../../lib/api/chatBranching", () => ({ chatBranchingApi: apiMock }));

import { branchingEnabledFor, persistTurnInBranchTree, resetBranchPersistForTests } from "./branchPersist";

beforeEach(() => {
  resetBranchPersistForTests();
  apiMock.getChatBranchingCapabilities.mockReset();
  apiMock.getBranchPath.mockReset().mockResolvedValue({ sessionId: "s1", activeLeafId: null, messages: [] });
});

describe("persistTurnInBranchTree", () => {
  it("reads the active path when the capability is on, and asks for the capability once per agent", async () => {
    apiMock.getChatBranchingCapabilities.mockResolvedValue({ enabled: true });

    expect(await persistTurnInBranchTree("a1", "s1", 0)).toBe(true);
    expect(await persistTurnInBranchTree("a1", "s2", 0)).toBe(true);

    expect(apiMock.getChatBranchingCapabilities).toHaveBeenCalledTimes(1);
    expect(apiMock.getBranchPath).toHaveBeenCalledWith("a1", "s1");
    expect(apiMock.getBranchPath).toHaveBeenCalledWith("a1", "s2");
  });

  it("does nothing when the flag is off or the capability call fails (404 on an older core)", async () => {
    apiMock.getChatBranchingCapabilities.mockResolvedValueOnce({ enabled: false });
    expect(await persistTurnInBranchTree("off", "s1", 0)).toBe(false);

    const notFound = Object.assign(new Error("HTTP 404"), { status: 404 });
    apiMock.getChatBranchingCapabilities.mockRejectedValueOnce(notFound);
    expect(await persistTurnInBranchTree("old", "s1", 0)).toBe(false);
    expect(await branchingEnabledFor("old")).toBe(false);

    expect(apiMock.getBranchPath).not.toHaveBeenCalled();
  });

  it("never throws when the path read fails, and skips a turn without a session", async () => {
    apiMock.getChatBranchingCapabilities.mockResolvedValue({ enabled: true });
    apiMock.getBranchPath.mockRejectedValueOnce(new Error("HTTP 500"));

    await expect(persistTurnInBranchTree("a1", "s1", 0)).resolves.toBe(false);
    await expect(persistTurnInBranchTree("a1", "", 0)).resolves.toBe(false);
  });
});
