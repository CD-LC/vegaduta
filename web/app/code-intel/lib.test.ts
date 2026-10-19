import { describe, expect, it } from "vitest";
import { errorHintKey, isLoopbackUrl, resolveDirectUrl } from "./lib";

describe("code-intel direct URL guard", () => {
  it("recognises loopback URLs", () => {
    expect(isLoopbackUrl("http://localhost:8090")).toBe(true);
    expect(isLoopbackUrl("http://127.0.0.1:8090/x")).toBe(true);
    expect(isLoopbackUrl("http://[::1]:8090")).toBe(true);
    expect(isLoopbackUrl("https://code-intel.example.com")).toBe(false);
    expect(isLoopbackUrl("not a url")).toBe(false);
    expect(isLoopbackUrl(undefined)).toBe(false);
  });

  it("drops a loopback URL when the page is served from another host", () => {
    expect(resolveDirectUrl("http://localhost:8090", "app.127.0.0.1.nip.io")).toBeUndefined();
  });

  it("keeps a loopback URL on a loopback page and any non-loopback URL", () => {
    expect(resolveDirectUrl("http://localhost:8090", "localhost")).toBe("http://localhost:8090");
    expect(resolveDirectUrl("https://ci.example.com", "app.example.com")).toBe("https://ci.example.com");
  });

  it("returns undefined when nothing is configured", () => {
    expect(resolveDirectUrl(undefined, "app.example.com")).toBeUndefined();
    expect(resolveDirectUrl("", "app.example.com")).toBeUndefined();
  });

  it("picks the old-build or check-url hint for a non-loopback target", () => {
    expect(errorHintKey("HTTP 404 not found", "http://app.example.com/code-intel")).toBe("errorOldBuildHint");
    expect(errorHintKey("boom", "http://app.example.com/code-intel")).toBe("errorCheckUrlHint");
  });

  it("says code-intel is not enabled when a leftover loopback default is configured", () => {
    expect(errorHintKey("boom", "https://app.example.com/code-intel", "http://localhost:8090")).toBe("errorNotEnabledHint");
    expect(errorHintKey("boom", "http://localhost:3000/code-intel", "http://localhost:8090")).toBe("errorCheckUrlHint");
  });
});
