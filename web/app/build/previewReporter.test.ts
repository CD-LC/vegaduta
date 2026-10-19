import { describe, expect, it } from "vitest";
import { PREVIEW_REPORTER, addError, formatFixRequest, injectReporter, isNoise } from "./previewReporter";

describe("previewReporter", () => {
  it("injects the reporter at the top of <head>, or the doc start", () => {
    const out = injectReporter("<!doctype html><html><head><title>x</title></head><body></body></html>");
    expect(out.indexOf(PREVIEW_REPORTER)).toBe(out.indexOf("<head>") + "<head>".length);
    expect(injectReporter("<div></div>").startsWith(PREVIEW_REPORTER)).toBe(true);
  });

  it("reporter script never closes its own tag early", () => {
    expect(PREVIEW_REPORTER.slice(8, -9)).not.toContain("</script>");
  });

  it("filters React dev chatter but keeps real errors", () => {
    expect(isNoise("console.error: Warning: Each child in a list should have a unique key")).toBe(true);
    expect(isNoise("ResizeObserver loop limit exceeded")).toBe(true);
    expect(isNoise("Uncaught TypeError: x is not a function (line 4)")).toBe(false);
  });

  it("dedupes and caps the error list, returning the same array on no-op", () => {
    let list: string[] = [];
    list = addError(list, "boom");
    const same = addError(list, "boom");
    expect(same).toBe(list);
    expect(addError(list, "console.error: Warning: noisy")).toBe(list);
    for (let i = 0; i < 20; i++) list = addError(list, `e${i}`);
    expect(list).toHaveLength(8);
  });

  it("formats a fix request listing every error", () => {
    const text = formatFixRequest(["a", "b"]);
    expect(text).toContain("- a");
    expect(text).toContain("- b");
  });
});
