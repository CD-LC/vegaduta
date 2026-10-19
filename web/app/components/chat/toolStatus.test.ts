import { describe, expect, it } from "vitest";
import { describeToolCall, summarizeToolArgs } from "./toolStatus";

const t = (key: string, values?: Record<string, unknown>) =>
  `${key}${values ? ":" + Object.values(values).join("|") : ""}`;

describe("summarizeToolArgs", () => {
  it("returns null for absent or empty args", () => {
    expect(summarizeToolArgs(undefined)).toBeNull();
    expect(summarizeToolArgs({})).toBeNull();
  });
  it("renders object args as key: value pairs and truncates long text", () => {
    expect(summarizeToolArgs({ a: "x", b: 2 })).toBe("a: x, b: 2");
    const long = summarizeToolArgs({ q: "y".repeat(200) }) as string;
    expect(long.length).toBe(90);
    expect(long.endsWith("…")).toBe(true);
  });
});

describe("describeToolCall", () => {
  it("labels a local call without args", () => {
    expect(describeToolCall({ name: "clock", phase: "start", source: "local" } as never, t)).toBe(
      "toolRunningLocal:clock"
    );
  });
  it("only shows the round from the second round and marks server calls", () => {
    expect(describeToolCall({ name: "x", phase: "start", round: 0 } as never, t)).toBe("toolRunning:x");
    expect(describeToolCall({ name: "x", phase: "start", round: 1, arguments: { k: 1 } } as never, t)).toBe(
      "toolRunningWithArgs:toolRound:x|2|k: 1"
    );
  });
});
