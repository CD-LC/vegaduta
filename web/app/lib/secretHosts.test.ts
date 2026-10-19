import { describe, expect, it } from "vitest";
import { normaliseHostPattern, parseHostList } from "./secretHosts";

describe("secretHosts (mirrors core SecretHostPolicy)", () => {
  it("accepts exact hosts and leading wildcards, lower-casing", () => {
    expect(normaliseHostPattern("API.Example.com.")).toEqual({ ok: true, value: "api.example.com" });
    expect(normaliseHostPattern("*.example.com")).toEqual({ ok: true, value: "*.example.com" });
  });
  it.each([
    ["https://api.example.com", "shape"],
    ["api.example.com:443", "shape"],
    ["api.example.com/v1", "shape"],
    ["user@example.com", "shape"],
    ["*", "wildcardPosition"],
    ["a.*.example.com", "wildcardPosition"],
    ["*.com", "wildcardSuffix"],
    ["*.10.0.0.1", "wildcardIp"],
    ["bad host!.com", "shape"],
    ["-bad.example.com", "invalidHost"],
  ])("rejects %s", (raw, code) => {
    expect(normaliseHostPattern(raw)).toEqual({ ok: false, code });
  });
  it("parses mixed separators, dedupes, and reports the first bad entry", () => {
    expect(parseHostList("a.com, b.com\n*.c.com a.com")).toEqual({ ok: true, hosts: ["a.com", "b.com", "*.c.com"] });
    expect(parseHostList("a.com *")).toEqual({ ok: false, entry: "*", code: "wildcardPosition" });
    expect(parseHostList("")).toEqual({ ok: true, hosts: [] });
  });
  it("caps the list at 32", () => {
    const many = Array.from({ length: 33 }, (_, i) => `h${i}.example.com`).join(" ");
    expect(parseHostList(many)).toMatchObject({ ok: false, code: "tooMany" });
  });
});
