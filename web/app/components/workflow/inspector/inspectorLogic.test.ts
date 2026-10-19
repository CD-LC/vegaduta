import { describe, expect, it } from "vitest";
import { classifyLine, formatDuration, isNum } from "./format";
import { childPath, childrenOf, normalizePayload } from "./payload";
import { statusKey } from "./status";

describe("step inspector status", () => {
  it("maps wire statuses to display keys", () => {
    expect(statusKey("COMPLETED")).toBe("ok");
    expect(statusKey("success")).toBe("ok");
    expect(statusKey("FAILED")).toBe("error");
    expect(statusKey("CANCELED")).toBe("cancelled");
    expect(statusKey("AWAITING_APPROVAL")).toBe("approval");
    expect(statusKey("STARTED")).toBe("running");
    expect(statusKey(undefined)).toBe("unknown");
    expect(statusKey("something-new")).toBe("unknown");
  });
});

describe("step inspector formatting", () => {
  it("formats durations across ranges and rejects bad input", () => {
    expect(formatDuration(250)).toBe("250 ms");
    expect(formatDuration(1500)).toBe("1.50 s");
    expect(formatDuration(12_345)).toBe("12.3 s");
    expect(formatDuration(125_000)).toBe("2m 05s");
    expect(formatDuration(-1)).toBe("-");
    expect(formatDuration(Number.NaN)).toBe("-");
    expect(isNum(Infinity)).toBe(false);
  });

  it("classifies log lines by severity", () => {
    expect(classifyLine("java.lang.IllegalStateException: boom")).toBe("error");
    expect(classifyLine("WARN retrying")).toBe("warn");
    expect(classifyLine("fetched 3 rows")).toBe("info");
  });
});

describe("step inspector payloads", () => {
  it("parses JSON strings into a tree and keeps truncated text raw", () => {
    const tree = normalizePayload('{"a":1}');
    expect(tree.kind).toBe("tree");
    expect(normalizePayload('{"a":').kind).toBe("raw");
    expect(normalizePayload(null).kind).toBe("none");
    expect(normalizePayload(undefined).kind).toBe("none");
  });

  it("lists children of arrays and objects only", () => {
    expect(childrenOf([10, 20])).toEqual([
      [0, 10],
      [1, 20],
    ]);
    expect(childrenOf({ k: 1 })).toEqual([["k", 1]]);
    expect(childrenOf("x")).toBeNull();
    expect(childrenOf(null)).toBeNull();
  });

  it("builds copyable expression paths", () => {
    expect(childPath("$", "name")).toBe("$.name");
    expect(childPath("$", "has space")).toBe('$["has space"]');
    expect(childPath("$.items", 2)).toBe("$.items[2]");
  });
});
