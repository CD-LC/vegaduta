// The rail's data hook against a mocked server: the local index shows first, the server's list is merged in
// once (quietly nothing when the core answers 404), and a remove goes to both places.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

const apiMock = vi.hoisted(() => ({
  listThreads: vi.fn(),
  deleteServerThread: vi.fn(),
}));
vi.mock("../../lib/api/assistant", () => ({ assistantApi: apiMock }));
vi.mock("../../lib/auth", () => ({ auth: { getUserId: () => "u1" } }));

import { useAssistantThreads } from "./useAssistantThreads";
import { getThread, recordThreadActivity, resetThreadIndexForTests } from "./threadIndex";

beforeEach(() => {
  resetThreadIndexForTests();
  apiMock.listThreads.mockReset();
  apiMock.deleteServerThread.mockReset().mockResolvedValue(true);
});

describe("useAssistantThreads", () => {
  it("merges the server's sessions into the local list once, keeping local titles and pins", async () => {
    await recordThreadActivity("u1", "local", "a1", "typed here", 5_000);
    apiMock.listThreads.mockResolvedValue({
      threads: [
        { sessionId: "remote", agentId: "a1", title: "from another device", createdAt: "2026-10-17T09:00:00Z", updatedAt: "2026-10-17T10:00:00Z" },
        { sessionId: "local", agentId: "a1", title: "typed here", createdAt: "1970-01-01T00:00:01Z", updatedAt: "1970-01-01T00:00:02Z" },
      ],
      nextBefore: null,
    });

    const { result } = renderHook(() => useAssistantThreads());

    await waitFor(() => expect(result.current.threads.map((t) => t.id)).toEqual(["remote", "local"]));
    expect(result.current.threads[0]).toMatchObject({ title: "from another device", agentId: "a1", pinned: false });
    expect(result.current.threads[1]).toMatchObject({ title: "typed here", lastAt: 5_000 });
    expect(apiMock.listThreads).toHaveBeenCalledTimes(1);

    // Later reloads stay local: no second server read.
    await act(async () => {
      await result.current.reload();
    });
    expect(apiMock.listThreads).toHaveBeenCalledTimes(1);
  });

  it("keeps the local list when the server has no list (branching off: listThreads resolves null)", async () => {
    await recordThreadActivity("u1", "local", "a1", "typed here", 5_000);
    apiMock.listThreads.mockResolvedValue(null);

    const { result } = renderHook(() => useAssistantThreads());

    await waitFor(() => expect(result.current.loaded).toBe(true));
    await waitFor(() => expect(apiMock.listThreads).toHaveBeenCalledTimes(1));
    expect(result.current.threads.map((t) => t.id)).toEqual(["local"]);
  });

  it("removes a thread on the server and locally, even when the server refuses", async () => {
    await recordThreadActivity("u1", "s1", "a1", "hello", 5_000);
    apiMock.listThreads.mockResolvedValue({ threads: [], nextBefore: null });
    apiMock.deleteServerThread.mockResolvedValue(false);

    const { result } = renderHook(() => useAssistantThreads());
    await waitFor(() => expect(result.current.threads.map((t) => t.id)).toEqual(["s1"]));

    await act(async () => {
      await result.current.remove("s1");
    });

    expect(apiMock.deleteServerThread).toHaveBeenCalledWith("a1", "s1");
    expect(result.current.threads).toEqual([]);
    expect(await getThread("u1", "s1")).toBeNull();
  });
});
