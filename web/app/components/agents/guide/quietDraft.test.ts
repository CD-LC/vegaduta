import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const getToken = vi.fn();
const toastError = vi.fn();
vi.mock("../../../lib/auth", () => ({ auth: { getToken: (...a: unknown[]) => getToken(...a) } }));
vi.mock("../../../lib/api", () => ({ API_BASE: "https://api.example.test" }));
// Not imported by quietDraft at all - mocked so that any accidental route
// through the global toast bus would be caught by the assertions below.
vi.mock("../../../lib/toastBus", () => ({ toast: { error: (...a: unknown[]) => toastError(...a) } }));

import { requestDraftQuietly } from "./quietDraft";

const payload = { fieldLabel: "Agent system prompt", instruction: "draft it" };

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("requestDraftQuietly", () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    fetchMock.mockReset();
    getToken.mockReset();
    toastError.mockReset();
    getToken.mockResolvedValue("tok");
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("posts to the assist endpoint with the bearer token and returns the suggestion", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { suggestion: "NAME: Desk\n\nYou are..." }));
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000 })).resolves.toBe("NAME: Desk\n\nYou are...");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("https://api.example.test/api/assist/draft");
    expect(init.method).toBe("POST");
    expect(init.headers.Authorization).toBe("Bearer tok");
    expect(JSON.parse(init.body)).toEqual(payload);
    // A failed refresh must not raise the "stay signed in?" prompt from here.
    expect(getToken).toHaveBeenCalledWith({ silent: true });
  });

  it.each([400, 401, 403, 429, 500, 503])("HTTP %i resolves null - no throw, no toast", async (status) => {
    fetchMock.mockResolvedValue(jsonResponse(status, { error: "No active LLM provider - activate one on the Providers page first." }));
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000 })).resolves.toBeNull();
    expect(toastError).not.toHaveBeenCalled();
  });

  it("a network failure resolves null", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000 })).resolves.toBeNull();
    expect(toastError).not.toHaveBeenCalled();
  });

  it("an empty, missing or non-JSON suggestion resolves null", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { suggestion: "   " }));
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000 })).resolves.toBeNull();
    fetchMock.mockResolvedValueOnce(jsonResponse(200, {}));
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000 })).resolves.toBeNull();
    fetchMock.mockResolvedValueOnce(new Response("<html>bad gateway</html>", { status: 200 }));
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000 })).resolves.toBeNull();
  });

  it("a token refresh that throws still sends the request, without a token", async () => {
    getToken.mockRejectedValue(new Error("kc down"));
    fetchMock.mockResolvedValue(jsonResponse(401, {}));
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000 })).resolves.toBeNull();
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it("really aborts the request at the deadline, so a late reply cannot land", async () => {
    vi.useFakeTimers();
    let seen: AbortSignal | undefined;
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          seen = init.signal as AbortSignal;
          seen.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        })
    );
    const pending = requestDraftQuietly(payload, { timeoutMs: 40_000 });
    await vi.advanceTimersByTimeAsync(39_000);
    expect(seen?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(2_000);
    await expect(pending).resolves.toBeNull();
    expect(seen?.aborted).toBe(true);
  });

  it("the deadline also covers a token refresh that never settles", async () => {
    vi.useFakeTimers();
    getToken.mockReturnValue(new Promise(() => undefined));
    const pending = requestDraftQuietly(payload, { timeoutMs: 5_000 });
    await vi.advanceTimersByTimeAsync(5_100);
    await expect(pending).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("the caller's signal cancels it (page left, agent switched)", async () => {
    const outer = new AbortController();
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          (init.signal as AbortSignal).addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        })
    );
    const pending = requestDraftQuietly(payload, { timeoutMs: 60_000, signal: outer.signal });
    await Promise.resolve();
    await Promise.resolve();
    outer.abort();
    await expect(pending).resolves.toBeNull();

    const already = new AbortController();
    already.abort();
    fetchMock.mockClear();
    await expect(requestDraftQuietly(payload, { timeoutMs: 1000, signal: already.signal })).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
