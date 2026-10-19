import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { BUNDLE_FORMAT } from "../../lib/api/agentBundles";
import { READY_MADE, bundleFileName, parseBundle } from "./bundleLogic";

const good = JSON.stringify({ format: BUNDLE_FORMAT, title: "T", agent: { systemPrompt: "x" } });

describe("parseBundle", () => {
  it("accepts a well-formed bundle", () => {
    expect(parseBundle(good)).toMatchObject({ reason: null, bundle: { title: "T" } });
  });
  it("names each way a file can be wrong", () => {
    expect(parseBundle("  ")).toMatchObject({ bundle: null, reason: "empty" });
    expect(parseBundle("{nope")).toMatchObject({ bundle: null, reason: "invalid-json" });
    expect(parseBundle("[1]")).toMatchObject({ bundle: null, reason: "not-a-bundle" });
    expect(parseBundle(JSON.stringify({ title: "T" }))).toMatchObject({ bundle: null, reason: "not-a-bundle" });
    expect(parseBundle(JSON.stringify({ format: "other/2", title: "T", agent: {} }))).toMatchObject({
      bundle: null,
      reason: "wrong-format",
    });
    expect(parseBundle("x".repeat(90_000))).toMatchObject({ bundle: null, reason: "too-large" });
  });
});

describe("bundleFileName", () => {
  it("makes a safe file name", () => {
    expect(bundleFileName("Daily Briefing!")).toBe("daily-briefing.agent.json");
    expect(bundleFileName("???")).toBe("agent.agent.json");
  });
});

// The console serves copies of examples/agent-bundles from /public. A copy that drifts from the
// reviewed original (which the server test imports) would ship an unchecked agent.
describe("ready-made bundles", () => {
  const examples = path.resolve(__dirname, "../../../../examples/agent-bundles");
  const publicDir = path.resolve(__dirname, "../../../public/agent-bundles");

  it("are byte-identical to the validated examples and listed in the catalogue", () => {
    const files = readdirSync(examples).filter((f) => f.endsWith(".json")).sort();
    expect(READY_MADE.map((b) => b.file).sort()).toEqual(files);
    for (const f of files) {
      expect(readFileSync(path.join(publicDir, f), "utf8")).toBe(readFileSync(path.join(examples, f), "utf8"));
    }
  });
});
