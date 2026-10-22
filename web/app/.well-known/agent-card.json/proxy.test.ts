// @vitest-environment node
//
// A2A discovery proxy (2026-09-09): the public domain must answer
// /.well-known/agent-card.json with core's card, a public 5-minute cache,
// and a JSON 503 (never HTML, never cached) when core is down - a peer's
// discovery step reads the body machine-to-machine.

import { afterEach, describe, expect, it, vi } from "vitest";
import { AGENT_CARD_CACHE_CONTROL, fetchAgentCard } from "./proxy";

const card = { name: "Vegaduta", protocolVersion: "1.0", capabilities: { streaming: true } };

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("fetchAgentCard", () => {
  it("proxies the upstream card verbatim with a public max-age=300 cache header", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(card), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const res = await fetchAgentCard();
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe(AGENT_CARD_CACHE_CONTROL);
    expect(AGENT_CARD_CACHE_CONTROL).toBe("public, max-age=300");
    expect(await res.json()).toEqual(card);
    const [url] = fetchMock.mock.calls[0] as unknown as [string];
    expect(url).toMatch(/\/\.well-known\/agent-card\.json$/);
  });

  it("answers 503 JSON, uncached, when upstream is unreachable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("fetch failed");
      })
    );
    const res = await fetchAgentCard();
    expect(res.status).toBe(503);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(res.headers.get("content-type")).toMatch(/application\/json/);
    expect(await res.json()).toMatchObject({ error: "agent_card_unavailable", reason: "upstream unreachable" });
  });

  it("answers 503 when upstream returns a non-2xx or a non-JSON body", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("nope", { status: 502 })));
    expect((await fetchAgentCard()).status).toBe(503);
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>oops</html>", { status: 200 })));
    const res = await fetchAgentCard();
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ reason: "upstream returned a non-JSON body" });
  });
});

describe("fetchAgentCard on a personal install", () => {
  afterEach(() => {
    delete process.env.NEXT_PUBLIC_EDITION;
  });

  it("answers 404 without calling core", async () => {
    process.env.NEXT_PUBLIC_EDITION = "personal";
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(card), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const res = await fetchAgentCard();
    expect(res.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("still proxies the card on lite and full", async () => {
    for (const edition of ["lite", "full"]) {
      process.env.NEXT_PUBLIC_EDITION = edition;
      vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(card), { status: 200 })));
      expect((await fetchAgentCard()).status, edition).toBe(200);
    }
  });
});
