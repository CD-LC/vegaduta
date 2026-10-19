import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { sendSharedChat } from "./api";
import { clearShareSession, getShareSession, setShareSession } from "./shareSession";

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

describe("share visitor session token (SEC-005)", () => {
  beforeEach(() => {
    clearShareSession("s1");
    clearShareSession("s2");
  });
  afterEach(() => vi.unstubAllGlobals());

  it("persists the issued token and sends it on the next turn", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ reply: "hi", sessionToken: "sv1.a.b" }))
      .mockResolvedValueOnce(json({ reply: "again", sessionToken: "sv1.a.b" }));
    vi.stubGlobal("fetch", fetchMock);

    await sendSharedChat("s1", "hello");
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ message: "hello" });
    expect(getShareSession("s1")).toBe("sv1.a.b");

    await sendSharedChat("s1", "more");
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ message: "more", sessionToken: "sv1.a.b" });
  });

  it("scopes the token per shareId", async () => {
    setShareSession("s1", "tok-1");
    expect(getShareSession("s2")).toBeNull();
  });

  it("falls back to the X-Session-Id header", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(json({ reply: "hi" }, 200, { "X-Session-Id": "hdr-tok" })));
    await sendSharedChat("s1", "hello");
    expect(getShareSession("s1")).toBe("hdr-tok");
  });

  it("clears a rejected token and retries once", async () => {
    setShareSession("s1", "stale");
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ error: "invalid" }, 409))
      .mockResolvedValueOnce(json({ reply: "ok", sessionToken: "fresh" }));
    vi.stubGlobal("fetch", fetchMock);
    const r = await sendSharedChat("s1", "hello");
    expect(r.ok).toBe(true);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ message: "hello" });
    expect(getShareSession("s1")).toBe("fresh");
  });

  it("does not retry more than once", async () => {
    setShareSession("s1", "stale");
    const fetchMock = vi.fn().mockResolvedValue(json({}, 401));
    vi.stubGlobal("fetch", fetchMock);
    const r = await sendSharedChat("s1", "hello");
    expect(r.ok).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
