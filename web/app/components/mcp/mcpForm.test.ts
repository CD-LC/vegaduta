import { describe, expect, it } from "vitest";
import { parseArgs, parseEnv } from "./mcpForm";

describe("mcpForm", () => {
  it("splits args on lines and whitespace and drops blanks", () => {
    expect(parseArgs("-y @scope/pkg\n--flag  value\n\n")).toEqual(["-y", "@scope/pkg", "--flag", "value"]);
    expect(parseArgs("")).toEqual([]);
  });

  it("parses KEY=VALUE lines and ignores lines without a key", () => {
    expect(parseEnv("A=1\n B = two=2 \n=nokey\njunk")).toEqual({ A: "1", B: "two=2" });
  });
});
