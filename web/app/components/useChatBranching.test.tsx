// The chat-branching hook against a mocked API: capability gating, labelling turns with server ids,
// flipping versions, and edit / regenerate going through the ordinary chat transport.

import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

const apiMock = vi.hoisted(() => ({
  getChatBranchingCapabilities: vi.fn(),
  getBranchPath: vi.fn(),
  getBranchSiblings: vi.fn(),
  setBranchActiveLeaf: vi.fn(),
  streamChat: vi.fn(),
}));
vi.mock("../lib/api", () => ({ api: apiMock }));
vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock("./agents/AgentActivityTrace", () => ({
  normalizeServerStep: (event: any, n: number) => ({ ...event, n }),
}));

import { useChatBranching } from "./useChatBranching";
import type { BranchPath, BranchTurn } from "../lib/chatBranching";

function path(messages: Array<[string, "user" | "assistant", string, number, number]>, leaf?: string): BranchPath {
  return {
    sessionId: "s1",
    activeLeafId: leaf ?? messages[messages.length - 1][0],
    messages: messages.map(([id, role, content, siblingIndex, siblingCount], i) => ({
      id,
      parentId: i === 0 ? null : messages[i - 1][0],
      role,
      content,
      siblingIndex,
      siblingCount,
    })),
  };
}

function useHarness(initialTurns: BranchTurn[], initialSession: string | null = "s1") {
  const [turns, setTurns] = useState<BranchTurn[]>(initialTurns);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(initialSession);
  const branching = useChatBranching({
    agentId: "a1",
    sessionId,
    turns,
    setTurns,
    sending,
    setSending,
    setError,
    setSessionId: (id: string) => setSessionId(id),
    setChatController: () => {},
  });
  return { branching, turns, sending, error, sessionId };
}

const unlabelled = (): BranchTurn[] => [
  { role: "user", content: "q1" },
  { role: "assistant", content: "r1", ref: "turn-1", steps: ["trace"] },
];

async function ready(turns: BranchTurn[], session: string | null = "s1") {
  const hook = renderHook(() => useHarness(turns, session));
  await waitFor(() => expect(hook.result.current.branching.enabled).toBe(true));
  return hook;
}

beforeEach(() => {
  Object.values(apiMock).forEach((fn) => fn.mockReset());
  apiMock.getChatBranchingCapabilities.mockResolvedValue({ enabled: true });
});

describe("capability gating", () => {
  it("stays off, and never reads a path, when the server reports the flag off", async () => {
    apiMock.getChatBranchingCapabilities.mockResolvedValue({ enabled: false });
    const { result } = renderHook(() => useHarness(unlabelled()));
    await waitFor(() => expect(apiMock.getChatBranchingCapabilities).toHaveBeenCalled());
    expect(result.current.branching.enabled).toBe(false);
    expect(result.current.branching.available).toBe(false);
    await act(async () => {
      await result.current.branching.syncAfterTurn("s1");
    });
    expect(apiMock.getBranchPath).not.toHaveBeenCalled();
  });

  it("stays off when the capabilities call fails (older core)", async () => {
    apiMock.getChatBranchingCapabilities.mockRejectedValue(new Error("404"));
    const { result } = renderHook(() => useHarness(unlabelled()));
    await waitFor(() => expect(apiMock.getChatBranchingCapabilities).toHaveBeenCalled());
    expect(result.current.branching.enabled).toBe(false);
  });

  it("is only available with a session and when no reply came from on-device", async () => {
    const withSession = await ready(unlabelled());
    expect(withSession.result.current.branching.available).toBe(true);
    const noSession = await ready(unlabelled(), null);
    expect(noSession.result.current.branching.available).toBe(false);
    const onDevice = await ready([{ role: "user", content: "q" }, { role: "assistant", content: "r", edgeTier: 1 }]);
    expect(onDevice.result.current.branching.available).toBe(false);
  });
});

describe("syncAfterTurn", () => {
  it("puts the server ids and version positions on the local turns, keeping local fields", async () => {
    apiMock.getBranchPath.mockResolvedValue(path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 1, 2]]));
    const { result } = await ready(unlabelled());
    await act(async () => {
      await result.current.branching.syncAfterTurn("s1");
    });
    expect(result.current.turns[1]).toMatchObject({
      messageId: "m2", siblingIndex: 1, siblingCount: 2, ref: "turn-1", steps: ["trace"], content: "r1",
    });
    expect(result.current.turns[0]).toMatchObject({ messageId: "m1", siblingCount: 1 });
  });

  it("does not relabel from a server path that is a turn behind", async () => {
    apiMock.getBranchPath.mockResolvedValue(path([["m1", "user", "q1", 0, 1]]));
    const { result } = await ready(unlabelled());
    await act(async () => {
      await result.current.branching.syncAfterTurn("s1");
    });
    expect(apiMock.getBranchPath).toHaveBeenCalledTimes(2);
    expect(result.current.turns).toEqual(unlabelled());
  }, 10000);
});

describe("switching versions", () => {
  it("asks for the neighbouring version and shows the path the server returns, restoring local extras", async () => {
    apiMock.getBranchPath.mockResolvedValue(path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 1, 2]]));
    const { result } = await ready(unlabelled());
    await act(async () => {
      await result.current.branching.syncAfterTurn("s1");
    });
    apiMock.getBranchSiblings.mockResolvedValue({ messageId: "m2", ids: ["m2b", "m2"], index: 1, count: 2 });
    apiMock.setBranchActiveLeaf.mockResolvedValue(path([["m1", "user", "q1", 0, 1], ["m2b", "assistant", "other answer", 0, 2]]));

    await act(async () => {
      await result.current.branching.switchSibling(1, -1);
    });

    expect(apiMock.getBranchSiblings).toHaveBeenCalledWith("a1", "s1", "m2");
    expect(apiMock.setBranchActiveLeaf).toHaveBeenCalledWith("a1", "s1", "m2b");
    expect(result.current.turns[1]).toMatchObject({ messageId: "m2b", content: "other answer", siblingIndex: 0 });

    // Flipping back restores the trace and eval ref the panel had for that version.
    apiMock.getBranchSiblings.mockResolvedValue({ messageId: "m2b", ids: ["m2b", "m2"], index: 0, count: 2 });
    apiMock.setBranchActiveLeaf.mockResolvedValue(path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 1, 2]]));
    await act(async () => {
      await result.current.branching.switchSibling(1, 1);
    });
    expect(result.current.turns[1]).toMatchObject({ messageId: "m2", ref: "turn-1", steps: ["trace"] });
  });

  it("does nothing at the end of the list", async () => {
    apiMock.getBranchPath.mockResolvedValue(path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 1, 2]]));
    const { result } = await ready(unlabelled());
    await act(async () => {
      await result.current.branching.syncAfterTurn("s1");
    });
    apiMock.getBranchSiblings.mockResolvedValue({ messageId: "m2", ids: ["m2b", "m2"], index: 1, count: 2 });
    await act(async () => {
      await result.current.branching.switchSibling(1, 1);
    });
    expect(apiMock.setBranchActiveLeaf).not.toHaveBeenCalled();
  });

  it("reports a failed switch and leaves the conversation as it was", async () => {
    apiMock.getBranchPath.mockResolvedValue(path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 1, 2]]));
    const { result } = await ready(unlabelled());
    await act(async () => {
      await result.current.branching.syncAfterTurn("s1");
    });
    apiMock.getBranchSiblings.mockRejectedValue(new Error("boom"));
    await act(async () => {
      await result.current.branching.switchSibling(1, -1);
    });
    expect(result.current.error).toBe("switchFailed");
    expect(result.current.turns[1].messageId).toBe("m2");
  });
});

describe("edit and regenerate", () => {
  const labelled = (): BranchTurn[] => [
    { role: "user", content: "q1", messageId: "m1", siblingIndex: 0, siblingCount: 1 },
    { role: "assistant", content: "r1", messageId: "m2", siblingIndex: 0, siblingCount: 1 },
    { role: "user", content: "q2", messageId: "m3", siblingIndex: 0, siblingCount: 1 },
    { role: "assistant", content: "r2", messageId: "m4", siblingIndex: 0, siblingCount: 1 },
  ];

  it("edit streams through the edit route with the new text and then relabels the turns", async () => {
    apiMock.streamChat.mockImplementation(async (_a: any, _m: any, _s: any, onChunk: (c: string) => void) => {
      onChunk("new ");
      onChunk("answer");
      return "s1";
    });
    apiMock.getBranchPath.mockResolvedValue(
      path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 0, 1], ["m5", "user", "q2 edited", 1, 2], ["m6", "assistant", "new answer", 0, 1]])
    );
    const { result } = await ready(labelled());

    await act(async () => {
      result.current.branching.submitEdit(2, "q2 edited");
    });

    await waitFor(() => expect(result.current.turns[2]?.messageId).toBe("m5"));
    const call = apiMock.streamChat.mock.calls[0];
    expect(call[0]).toBe("a1");
    expect(call[1]).toBe("q2 edited");
    expect(call[2]).toBe("s1");
    expect(call[10]).toBe("/api/agents/a1/sessions/s1/messages/m3/edit");
    expect(result.current.turns).toHaveLength(4);
    expect(result.current.turns[2]).toMatchObject({ content: "q2 edited", siblingIndex: 1, siblingCount: 2 });
    expect(result.current.turns[3]).toMatchObject({ content: "new answer", messageId: "m6" });
    expect(result.current.sending).toBe(false);
  });

  it("regenerate re-asks the user message before the reply through the regenerate route", async () => {
    apiMock.streamChat.mockImplementation(async (_a: any, _m: any, _s: any, onChunk: (c: string) => void) => {
      onChunk("again");
      return "s1";
    });
    apiMock.getBranchPath.mockResolvedValue(
      path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 0, 1], ["m3", "user", "q2", 0, 1], ["m7", "assistant", "again", 1, 2]])
    );
    const { result } = await ready(labelled());

    await act(async () => {
      result.current.branching.regenerate(3);
    });

    await waitFor(() => expect(result.current.turns[3]?.messageId).toBe("m7"));
    const call = apiMock.streamChat.mock.calls[0];
    expect(call[1]).toBe("q2");
    expect(call[10]).toBe("/api/agents/a1/sessions/s1/messages/m4/regenerate");
    expect(result.current.turns[3]).toMatchObject({ content: "again", siblingCount: 2, siblingIndex: 1 });
  });

  it("a refused turn shows the error and whatever path the server holds", async () => {
    apiMock.streamChat.mockRejectedValue(new Error("quota exhausted"));
    apiMock.getBranchPath.mockResolvedValue(path([["m1", "user", "q1", 0, 1], ["m2", "assistant", "r1", 0, 1], ["m3", "user", "q2", 0, 1], ["m4", "assistant", "r2", 0, 1]]));
    const { result } = await ready(labelled());

    await act(async () => {
      result.current.branching.submitEdit(2, "q2 edited");
    });

    await waitFor(() => expect(result.current.error).toBe("quota exhausted"));
    await waitFor(() => expect(result.current.turns[2]?.content).toBe("q2"));
    expect(result.current.turns).toHaveLength(4);
    expect(result.current.sending).toBe(false);
  });

  it("falls back to the previous view when even the path cannot be read", async () => {
    apiMock.streamChat.mockRejectedValue(new Error("down"));
    apiMock.getBranchPath.mockRejectedValue(new Error("down"));
    const { result } = await ready(labelled());

    await act(async () => {
      result.current.branching.regenerate(3);
    });

    await waitFor(() => expect(result.current.error).toBe("down"));
    await waitFor(() => expect(result.current.turns).toEqual(labelled()));
  });

  it("opens and closes the inline editor", async () => {
    const { result } = await ready(labelled());
    act(() => result.current.branching.startEdit(2));
    expect(result.current.branching.editingIndex).toBe(2);
    act(() => result.current.branching.cancelEdit());
    expect(result.current.branching.editingIndex).toBeNull();
  });
});
