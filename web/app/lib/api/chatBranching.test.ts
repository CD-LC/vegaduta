// Chat-branching HTTP seam: the exact path / method / body each call sends. apiFetch is mocked
// at the seam - no network.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
const quietCalls = vi.hoisted(() => [] as number[][]);
vi.mock("./core", () => ({
  apiFetch: apiFetchMock,
  toJson: async (res: Response) => {
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  },
  toJsonQuietOn: (statuses: number[]) => {
    quietCalls.push(statuses);
    return async (res: Response) => {
      if (!res.ok) {
        const err = new Error(`HTTP ${res.status}`) as Error & { status?: number };
        err.status = res.status;
        throw err;
      }
      return res.json();
    };
  },
  jsonBody: (payload: unknown) => ({
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  }),
}));

import { branchEditPath, branchMessagesPath, branchRegeneratePath, chatBranchingApi } from "./chatBranching";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  apiFetchMock.mockReset();
  quietCalls.length = 0;
});

describe("path builders", () => {
  it("nest under the agent and session and escape every segment", () => {
    expect(branchMessagesPath("a1", "s1")).toBe("/api/agents/a1/sessions/s1/messages");
    expect(branchEditPath("a1", "s 1", "m/1")).toBe("/api/agents/a1/sessions/s%201/messages/m%2F1/edit");
    expect(branchRegeneratePath("a1", "s1", "m1")).toBe("/api/agents/a1/sessions/s1/messages/m1/regenerate");
  });
});

describe("chatBranchingApi", () => {
  it("reads capabilities quietly on 403 and 404", async () => {
    apiFetchMock.mockResolvedValue(json({ enabled: false }));
    await expect(chatBranchingApi.getChatBranchingCapabilities("a1")).resolves.toEqual({ enabled: false });
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/agents/a1/chat-branching/capabilities");
    expect(quietCalls).toEqual([[403, 404]]);
  });

  it("asks for the active branch explicitly", async () => {
    apiFetchMock.mockResolvedValue(json({ sessionId: "s1", activeLeafId: null, messages: [] }));
    await chatBranchingApi.getBranchPath("a1", "s1");
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/agents/a1/sessions/s1/messages?branch=active");
  });

  it("GETs the siblings of one message", async () => {
    apiFetchMock.mockResolvedValue(json({ messageId: "m1", ids: ["m1"], index: 0, count: 1 }));
    await chatBranchingApi.getBranchSiblings("a1", "s1", "m1");
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/agents/a1/sessions/s1/messages/m1/siblings");
  });

  it("switches the active leaf with a PUT carrying the message id", async () => {
    apiFetchMock.mockResolvedValue(json({ sessionId: "s1", activeLeafId: "m2", messages: [] }));
    await chatBranchingApi.setBranchActiveLeaf("a1", "s1", "m2");
    const [path, init] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/agents/a1/sessions/s1/messages/active-leaf");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body)).toEqual({ messageId: "m2" });
  });

  it("surfaces a failing switch rather than swallowing it", async () => {
    apiFetchMock.mockResolvedValue(json({ error: "nope" }, 404));
    await expect(chatBranchingApi.setBranchActiveLeaf("a1", "s1", "m2")).rejects.toBeDefined();
  });
});
