import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchWithRetry, fetchWithTimeout } from "./fetchWithRetry";

describe("fetchWithRetry", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers();
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("returns the first response, including non-2xx, without retrying", async () => {
    fetchMock.mockResolvedValueOnce(new Response("nope", { status: 503 }));
    const res = await fetchWithRetry("https://x.test/a", {}, 1000, 2);
    expect(res.status).toBe(503);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("retries thrown errors with 100ms/200ms backoff, then succeeds", async () => {
    fetchMock
      .mockRejectedValueOnce(new TypeError("network"))
      .mockRejectedValueOnce(new TypeError("network"))
      .mockResolvedValueOnce(new Response("ok", { status: 200 }));
    const pending = fetchWithRetry("https://x.test/a", { method: "POST" }, 1000, 2);
    await vi.advanceTimersByTimeAsync(99);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(200);
    const res = await pending;
    expect(res.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "POST" });
  });

  it("rethrows the last error after maxRetries extra attempts", async () => {
    const last = new Error("third");
    fetchMock.mockRejectedValueOnce(new Error("first")).mockRejectedValueOnce(new Error("second")).mockRejectedValueOnce(last);
    const pending = fetchWithRetry("https://x.test/a", {}, 1000, 2);
    const assertion = expect(pending).rejects.toBe(last);
    await vi.advanceTimersByTimeAsync(1000);
    await assertion;
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("maxRetries 0 makes exactly one attempt", async () => {
    fetchMock.mockRejectedValueOnce(new Error("down"));
    await expect(fetchWithRetry("https://x.test/a", {}, 1000, 0)).rejects.toThrow("down");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("fetchWithTimeout", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("aborts the request once the timeout elapses", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url: string, init: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
          })
      )
    );
    const pending = fetchWithTimeout("https://x.test/slow", {}, 500);
    const assertion = expect(pending).rejects.toMatchObject({ name: "AbortError" });
    await vi.advanceTimersByTimeAsync(500);
    await assertion;
  });
});
