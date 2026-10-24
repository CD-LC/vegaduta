import { describe, expect, it } from "vitest";
import { diffLines, hasChanges } from "./promptDiff";

describe("diffLines", () => {
  it("marks added, removed and unchanged lines in order", () => {
    const rows = diffLines("a\nb\nc", "a\nB\nc\nd");
    expect(rows).toEqual([
      { type: "same", text: "a" },
      { type: "del", text: "b" },
      { type: "add", text: "B" },
      { type: "same", text: "c" },
      { type: "add", text: "d" },
    ]);
    expect(hasChanges(rows)).toBe(true);
  });

  it("treats a missing side as empty and normalises line endings", () => {
    expect(diffLines(null, "x")).toEqual([{ type: "add", text: "x" }]);
    expect(diffLines("x", undefined)).toEqual([{ type: "del", text: "x" }]);
    expect(hasChanges(diffLines("a\r\nb", "a\nb"))).toBe(false);
  });

  it("does not blow up on very large inputs", () => {
    const big = Array.from({ length: 3000 }, (_, i) => `line ${i}`).join("\n");
    const rows = diffLines(big, big + "\nextra");
    expect(rows.length).toBeGreaterThan(3000);
    expect(rows.filter((r) => r.type === "add").length).toBeGreaterThan(0);
  });
});
