import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { reportClientError } from "./clientLogger";

// Covers the one thing worth locking down here: reportClientError is a
// best-effort beacon, never a source of new failures. It must always keep
// the console.error (existing debugging behavior, unchanged), must POST to
// /api/client-log with the right shape when a fetch exists, and must never
// let a network failure (or fetch being unavailable at all) throw back into
// the caller - a logging call failing should never be what breaks a UI
// action. The `typeof window === "undefined"` SSR guard is not separately
// exercised here (jsdom, this suite's environment, always defines window) -
// it's a one-line early return with no interesting branching to get wrong.
describe("clientLogger.reportClientError", () => {
  let fetchSpy: ReturnType<typeof vi.fn>;
  let consoleErrorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    fetchSpy = vi.fn(() => Promise.resolve(new Response(null, { status: 200 })));
    vi.stubGlobal("fetch", fetchSpy);
    consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    consoleErrorSpy.mockRestore();
  });

  it("always logs to the console, independent of the network call", () => {
    reportClientError("client_error", "boom", { where: "test" });
    expect(consoleErrorSpy).toHaveBeenCalledWith("[client_error]", "boom", { where: "test" });
  });

  it("posts to /api/client-log with the type/message/context, keepalive, and JSON content-type", () => {
    reportClientError("auth_failure", "token refresh failed", { attempt: 2 });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe("/api/client-log");
    expect(init.method).toBe("POST");
    expect(init.keepalive).toBe(true);
    expect(init.headers).toMatchObject({ "Content-Type": "application/json" });
    expect(JSON.parse(init.body)).toEqual({
      type: "auth_failure",
      message: "token refresh failed",
      context: { attempt: 2 },
    });
  });

  it("swallows a rejected fetch instead of throwing", () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new Error("network down")))
    );
    expect(() => reportClientError("api_call_failed", "network down")).not.toThrow();
  });

  it("never throws even if fetch itself is not available", () => {
    vi.stubGlobal("fetch", undefined);
    expect(() => reportClientError("client_error", "no fetch here")).not.toThrow();
  });
});
