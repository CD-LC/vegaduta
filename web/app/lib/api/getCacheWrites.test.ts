// SE-07: a list read that was on the wire while a write landed used to be
// shared with, and cached for, the reader that came after the write, so a new
// collection or file did not show until reload.

import { afterEach, describe, expect, it, vi } from "vitest";
import { apiFetch, clearApiGetCache } from "./core";

vi.mock("../auth", () => ({
  auth: { getToken: vi.fn().mockResolvedValue(null), refreshToken: vi.fn().mockResolvedValue(false) },
}));

function jsonRes(body: unknown, status = 200): Response {
  const text = JSON.stringify(body);
  return {
    ok: status < 400,
    status,
    headers: new Headers({ "content-type": "application/json" }),
    text: () => Promise.resolve(text),
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

describe("apiFetch GET cache vs writes", () => {
  afterEach(async () => {
    vi.unstubAllGlobals();
    await clearApiGetCache();
  });

  it("a GET in flight when a POST is sent neither feeds nor poisons the next GET", async () => {
    const staleGet = deferred<Response>();
    let listCalls = 0;
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      if ((init?.method || "GET") === "POST") return Promise.resolve(jsonRes({ id: "c2" }, 201));
      listCalls++;
      if (listCalls === 1) return staleGet.promise;
      return Promise.resolve(jsonRes(["c1", "c2"]));
    });
    vi.stubGlobal("fetch", fetchMock);

    const before = apiFetch("/api/knowledge/collections"); // stays pending
    await apiFetch("/api/knowledge/collections", { method: "POST", body: "{}" });
    const after = await apiFetch("/api/knowledge/collections").then((r) => r.json());
    expect(after).toEqual(["c1", "c2"]);
    expect(listCalls).toBe(2); // did not join the pre-write request

    staleGet.resolve(jsonRes(["c1"])); // the old answer lands late
    await before;
    await new Promise((r) => setTimeout(r, 0));

    const again = await apiFetch("/api/knowledge/collections").then((r) => r.json());
    expect(again).toEqual(["c1", "c2"]); // the stale body was not cached over it
  });

  it("a GET that finishes during the write is not cached", async () => {
    const post = deferred<Response>();
    let n = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => {
        if ((init?.method || "GET") === "POST") return post.promise;
        n++;
        return Promise.resolve(jsonRes(n === 1 ? ["old"] : ["old", "new"]));
      })
    );
    const write = apiFetch("/api/knowledge/documents", { method: "POST", body: "{}" });
    await apiFetch("/api/knowledge/documents"); // answered mid-write
    post.resolve(jsonRes({}, 201));
    await write;
    const after = await apiFetch("/api/knowledge/documents").then((r) => r.json());
    expect(after).toEqual(["old", "new"]);
  });
});
