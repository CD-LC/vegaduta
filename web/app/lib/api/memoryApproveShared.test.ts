import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  toJson: async (res: Response) => {
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
  toJsonQuietOn: () => async (res: Response) => {
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
}));

import { memoryApi } from "./memory";
import { memoryItemsApi } from "./memoryItems";

beforeEach(() => {
  apiFetchMock.mockReset();
  apiFetchMock.mockImplementation(async () => new Response(null, { status: 204 }));
});

describe("approve a shared fact", () => {
  it("POSTs the user-level shared approve endpoint with the id encoded and no subject key", async () => {
    await memoryApi.approveSharedMemoryFact("a b");
    expect(apiFetchMock).toHaveBeenCalledWith("/api/memory/facts/shared/a%20b/approve", { method: "POST" });
  });
});

describe("memory items edit and pin", () => {
  it("PATCHes the new text and resolves to the new version", async () => {
    apiFetchMock.mockImplementation(
      async () => new Response(JSON.stringify({ id: "n1", content: "x" }), { status: 200 })
    );
    const next = await memoryItemsApi.editMemoryItem("a1", "i 1", "x");
    const [url, init] = apiFetchMock.mock.calls[0];
    expect(url).toBe("/api/agents/a1/memory-items/i%201");
    expect(init.method).toBe("PATCH");
    expect(JSON.parse(init.body)).toEqual({ content: "x" });
    expect(next).toEqual({ id: "n1", content: "x" });
  });

  it("pins with POST and unpins with DELETE on /pin", async () => {
    await memoryItemsApi.pinMemoryItem("a1", "i1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/agents/a1/memory-items/i1/pin", { method: "POST" });
    await memoryItemsApi.unpinMemoryItem("a1", "i1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/agents/a1/memory-items/i1/pin", { method: "DELETE" });
  });
});
