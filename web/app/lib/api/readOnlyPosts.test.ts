// SE-37 (api layer): read-only POSTs must not flush the GET cache, concurrent
// callers of a raw/POST read share one call, and capability endpoints stay
// fresh longer than list reads.

import { afterEach, describe, expect, it, vi } from "vitest";
import { apiFetch, clearApiGetCache, shareInflight } from "./core";

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

describe("read-only POSTs and shared calls", () => {
  afterEach(async () => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    await clearApiGetCache();
  });

  it("POST /api/model-pricing does not flush cached reads", async () => {
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => {
        if ((init?.method || "GET") === "POST") return Promise.resolve(jsonRes([]));
        gets++;
        return Promise.resolve(jsonRes({ ok: true }));
      })
    );
    await apiFetch("/api/providers/availability");
    await apiFetch("/api/model-pricing", { method: "POST", body: "{}" });
    await apiFetch("/api/providers/availability");
    expect(gets).toBe(1);
  });

  it("checking a prompt draft and previewing an agent's prompt do not flush cached reads, saving one does", async () => {
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => {
        if ((init?.method || "GET") !== "GET") return Promise.resolve(jsonRes({}));
        gets++;
        return Promise.resolve(jsonRes({ ok: true }));
      })
    );
    await apiFetch("/api/prompts");
    await apiFetch("/api/prompts/directive.tool_trust/validate", { method: "POST", body: "{}" });
    await apiFetch("/api/agents/a-1/prompt-preview", { method: "POST", body: "{}" });
    await apiFetch("/api/prompts");
    expect(gets).toBe(1);
    await apiFetch("/api/prompts/directive.tool_trust/overrides/tenant", { method: "PUT", body: "{}" });
    await apiFetch("/api/prompts");
    expect(gets).toBe(2);
  });

  it("an ordinary POST still flushes cached reads", async () => {
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn((_url: string, init?: RequestInit) => {
        if ((init?.method || "GET") === "POST") return Promise.resolve(jsonRes({}, 201));
        gets++;
        return Promise.resolve(jsonRes({ ok: true }));
      })
    );
    await apiFetch("/api/providers/availability");
    await apiFetch("/api/agents", { method: "POST", body: "{}" });
    await apiFetch("/api/providers/availability");
    expect(gets).toBe(2);
  });

  it("shareInflight gives concurrent callers one call and forgets it once settled", async () => {
    let resolve!: (v: string) => void;
    const first = vi.fn(() => new Promise<string>((r) => (resolve = r)));
    const a = shareInflight("k", first);
    const b = shareInflight("k", first);
    expect(first).toHaveBeenCalledTimes(1);
    resolve("x");
    await expect(Promise.all([a, b])).resolves.toEqual(["x", "x"]);
    const next = vi.fn(() => Promise.resolve("y"));
    await expect(shareInflight("k", next)).resolves.toBe("y");
    expect(next).toHaveBeenCalledTimes(1);
  });

  it("capability endpoints stay fresh past the list-read TTL", async () => {
    vi.useFakeTimers();
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(() => {
        gets++;
        return Promise.resolve(jsonRes({ enabled: true }));
      })
    );
    await apiFetch("/api/data-tables/config");
    await apiFetch("/api/agents");
    expect(gets).toBe(2);
    vi.advanceTimersByTime(60_000);
    await apiFetch("/api/data-tables/config");
    expect(gets).toBe(2); // still fresh: no request
    await apiFetch("/api/agents"); // past 15 s: served stale, refresh goes out
    expect(gets).toBe(3);
  });
});
