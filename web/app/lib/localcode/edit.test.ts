// Tests for the local-code edit path: the unified-diff generator, the
// truncation guard, the stale-file refusal and the permission refusal.
//
// These four are not "coverage" - they are the properties that make it safe
// to point this feature at a folder of someone's uncommitted source. A
// regression in any of them destroys work silently, so each is asserted at
// the level it actually protects: the guards through proposeLocalEdit /
// applyLocalEdits (the real entry points, with the edge stack mocked), not
// only through the exported predicate.
//
// The mocks below are held to fsAccess's REAL contract, and that is load-
// bearing rather than pedantic: an earlier version of this file asserted
// failure handling by making readLocalFile reject and writeLocalFile throw.
// Neither real function can do either - readLocalFile returns "" and
// writeLocalFile swallows every failure - so both tests passed while the two
// defects they appeared to cover (a failed write reported as applied, an
// unreadable file treated as an empty one) were live in the module. A mock
// that is kinder than the dependency is worse than no test at all.
//
// The edge modules are mocked wholesale rather than driven: importing the
// real capabilities/tier0/tier1 would pull WebGPU detection, the model
// manifest fetch and the WebLLM engine into a jsdom run. Mocking also lets
// these tests assert the thing that matters most about readiness - that
// NOTHING is downloaded, by checking createTier0Session/createTier1Session
// were never even called when no model is ready.

import { beforeEach, describe, expect, it, vi } from "vitest";
import type { LocalRepoHandle } from "./types";

// No writeLocalFile here on purpose: edit.ts must not reach for the
// outcome-swallowing variant, and leaving it out of the mock means a
// regression that does is a module-resolution failure, not a passing test.
const fs = vi.hoisted(() => ({
  readLocalFile: vi.fn(),
  writeLocalFileResult: vi.fn(),
  ensureWritePermission: vi.fn(),
}));

const edge = vi.hoisted(() => ({
  edgeEnabled: vi.fn(),
  getEdgeOptIn: vi.fn(),
  getEdgeRoutingPreference: vi.fn(),
  getStoredModelOverride: vi.fn(),
  detectCapabilities: vi.fn(),
  getTier1RunModel: vi.fn(),
  createTier1Session: vi.fn(),
  tier0Availability: vi.fn(),
  createTier0Session: vi.fn(),
}));

vi.mock("./fsAccess", () => ({
  readLocalFile: fs.readLocalFile,
  writeLocalFileResult: fs.writeLocalFileResult,
  ensureWritePermission: fs.ensureWritePermission,
}));

vi.mock("../edge/capabilities", () => ({
  detectCapabilities: edge.detectCapabilities,
  edgeEnabled: edge.edgeEnabled,
  getEdgeOptIn: edge.getEdgeOptIn,
  getEdgeRoutingPreference: edge.getEdgeRoutingPreference,
  getStoredModelOverride: edge.getStoredModelOverride,
}));

vi.mock("../edge/tier0", () => ({
  createTier0Session: edge.createTier0Session,
  tier0Availability: edge.tier0Availability,
}));

vi.mock("../edge/tier1", () => ({
  createTier1Session: edge.createTier1Session,
  getTier1RunModel: edge.getTier1RunModel,
}));

import {
  DEFAULT_CONTEXT_LINES,
  MAX_LCS_CELLS,
  NO_EOL_MARKER,
  diffHunks,
  splitLines,
  unifiedDiff,
  unifiedDiffResult,
} from "./diff";
import {
  MAX_EDITABLE_CHARS,
  UNREADABLE_REASON,
  applyLocalEdits,
  isPlausibleRewrite,
  localEditReadiness,
  proposeLocalEdit,
  setLocalEditRepo,
} from "./edit";
import type { ProposedEdit } from "./edit";

const repo: LocalRepoHandle = { id: "repo-1", name: "my-app", dir: {} as FileSystemDirectoryHandle };

/** A file long enough that a truncated reply is unambiguously below the 40%
 * floor, and short enough to stay under MAX_EDITABLE_CHARS. */
const FILE = Array.from({ length: 40 }, (_, i) => `const line${i} = ${i};`).join("\n") + "\n";

function mockTier0Reply(reply: string) {
  const destroy = vi.fn();
  const prompt = vi.fn(async () => reply);
  edge.tier0Availability.mockResolvedValue("available");
  edge.createTier0Session.mockResolvedValue({ prompt, destroy });
  return { prompt, destroy };
}

beforeEach(() => {
  vi.clearAllMocks();
  // Default world: on-device enabled and opted in, but NOTHING ready. Each
  // test opts itself into a tier, so a test that forgets to gets the honest
  // "no model" answer rather than a silently working one.
  edge.edgeEnabled.mockReturnValue(true);
  edge.getEdgeOptIn.mockReturnValue(true);
  edge.getEdgeRoutingPreference.mockReturnValue("edge-first");
  edge.getStoredModelOverride.mockReturnValue("auto");
  edge.detectCapabilities.mockResolvedValue({ webgpu: { available: true } });
  edge.getTier1RunModel.mockResolvedValue(null);
  edge.tier0Availability.mockResolvedValue("unavailable");
  fs.readLocalFile.mockResolvedValue(FILE);
  // The real writeLocalFileResult resolves a discriminated union and never
  // rejects; every write test moves the union, not an exception.
  fs.writeLocalFileResult.mockResolvedValue({ ok: true });
  fs.ensureWritePermission.mockResolvedValue(true);
  setLocalEditRepo(repo);
});

// ---------------------------------------------------------------------------
// diff.ts
// ---------------------------------------------------------------------------

describe("localcode/diff — splitLines", () => {
  it("does not invent a trailing empty line, and treats '' as zero lines", () => {
    expect(splitLines("a\nb\n")).toEqual(["a", "b"]);
    expect(splitLines("a\nb")).toEqual(["a", "b"]);
    expect(splitLines("")).toEqual([]);
    expect(splitLines("\n")).toEqual([""]);
  });
});

describe("localcode/diff — unifiedDiff", () => {
  it("reports an inserted line, counting both sides in the hunk header", () => {
    const out = unifiedDiff("a\nb\nc\n", "a\nb\nX\nc\n", "src/x.ts");
    expect(out).toContain("--- a/src/x.ts");
    expect(out).toContain("+++ b/src/x.ts");
    expect(out).toContain("@@ -1,3 +1,4 @@");
    expect(out).toContain("+X");
    expect(out).not.toContain("-X");
  });

  it("reports a removed line", () => {
    const out = unifiedDiff("a\nb\nc\n", "a\nc\n", "src/x.ts");
    expect(out).toContain("@@ -1,3 +1,2 @@");
    expect(out).toContain("-b");
    expect(out.split("\n").filter((l) => l.startsWith("+") && !l.startsWith("+++"))).toEqual([]);
  });

  it("reports a changed line as removal-then-addition, keeping the context", () => {
    const out = unifiedDiff("one\ntwo\nthree\n", "one\nTWO\nthree\n", "src/x.ts");
    expect(out).toContain("@@ -1,3 +1,3 @@");
    expect(out.indexOf("-two")).toBeGreaterThan(-1);
    expect(out.indexOf("+TWO")).toBeGreaterThan(out.indexOf("-two"));
    expect(out).toContain(" one");
    expect(out).toContain(" three");
  });

  it("uses the insert-after-line-0 convention for a file that had no contents", () => {
    expect(unifiedDiff("", "x\ny\n", "new.ts")).toContain("@@ -0,0 +1,2 @@");
  });

  it("returns the empty string - not a header stub - when nothing changed", () => {
    expect(unifiedDiff(FILE, FILE, "src/x.ts")).toBe("");
    expect(diffHunks(FILE, FILE).hunks).toEqual([]);
  });

  it("splits distant changes into separate hunks and merges nearby ones", () => {
    const lines = Array.from({ length: 20 }, (_, i) => `l${i}`);
    const far = [...lines];
    far[1] = "CHANGED-EARLY";
    far[17] = "CHANGED-LATE";
    const farOut = unifiedDiff(lines.join("\n") + "\n", far.join("\n") + "\n", "f.ts");
    expect(farOut.split("\n").filter((l) => l.startsWith("@@")).length).toBe(2);

    const near = [...lines];
    near[8] = "CHANGED-A";
    near[9] = "CHANGED-B";
    const nearOut = unifiedDiff(lines.join("\n") + "\n", near.join("\n") + "\n", "f.ts");
    expect(nearOut.split("\n").filter((l) => l.startsWith("@@")).length).toBe(1);
  });

  it("counts additions and removals, and stays exact for edits inside a large file", () => {
    const big = Array.from({ length: 3000 }, (_, i) => `line ${i}`);
    const edited = [...big];
    edited[1500] = "line 1500 // touched";
    const result = diffHunks(big.join("\n") + "\n", edited.join("\n") + "\n");
    // The prefix/suffix trim is what keeps this exact rather than coarse:
    // only one line ever reaches the quadratic core.
    expect(result.coarse).toBe(false);
    expect(result.added).toBe(1);
    expect(result.removed).toBe(1);
    expect(result.hunks.length).toBe(1);
    expect(result.hunks[0].lines.length).toBe(2 + DEFAULT_CONTEXT_LINES * 2);
  });

  // splitLines maps "a\nb" and "a\nb\n" to the same two lines, so without the
  // marker this real change in the bytes renders as an empty diff - and an
  // empty diff means "no changes needed" to every caller.
  it("shows a change that is only the end-of-file newline, rather than an empty diff", () => {
    const added = unifiedDiff("a\nb", "a\nb\n", "src/x.ts");
    expect(added).not.toBe("");
    expect(added).toContain("-b");
    expect(added).toContain(NO_EOL_MARKER);
    // The marker sits on the side that lacks the newline - here the original.
    expect(added.indexOf(NO_EOL_MARKER)).toBeGreaterThan(added.indexOf("-b"));
    expect(added.indexOf(NO_EOL_MARKER)).toBeLessThan(added.indexOf("+b"));

    const removed = unifiedDiff("a\nb\n", "a\nb", "src/x.ts");
    expect(removed).toContain(NO_EOL_MARKER);
    expect(removed.indexOf(NO_EOL_MARKER)).toBeGreaterThan(removed.indexOf("+b"));
  });

  it("does not count the marker as a line of either file in the hunk header", () => {
    const out = unifiedDiff("a\nb", "a\nb\n", "src/x.ts");
    // Two lines counted on each side (" a" plus the -/+ pair). The marker is
    // an annotation on the line above it, not a line of either file.
    expect(out).toContain("@@ -1,2 +1,2 @@");
    const result = diffHunks("a\nb", "a\nb\n");
    expect(result.added).toBe(1);
    expect(result.removed).toBe(1);
  });

  it("still returns '' for byte-identical sides, including two files that both lack the newline", () => {
    expect(unifiedDiff("a\nb", "a\nb", "src/x.ts")).toBe("");
  });

  it("marks the last line of a file that has no trailing newline on both sides", () => {
    const out = unifiedDiff("a\nb", "A\nb", "src/x.ts");
    expect(out).toContain("-a");
    expect(out).toContain("+A");
    // Both sides end without a newline, so the shared last line carries one
    // marker rather than being split.
    expect(out.split("\n").filter((l) => l === NO_EOL_MARKER)).toHaveLength(1);
  });

  it("degrades to a coarse whole-block diff instead of running an oversized LCS", () => {
    const a = Array.from({ length: 2100 }, (_, i) => `old ${i}`).join("\n") + "\n";
    const b = Array.from({ length: 2100 }, (_, i) => `new ${i}`).join("\n") + "\n";
    const result = diffHunks(a, b);
    expect(result.coarse).toBe(true);
    expect(result.removed).toBe(2100);
    expect(result.added).toBe(2100);
  });

  // The cap is on TABLE CELLS, and the table is (n+1) x (m+1) - so the
  // largest equal-length pair that still gets an exact diff is one line
  // shorter than the square root, not equal to it. Asserted rather than
  // described, because MAX_LCS_CELLS' docstring quotes this boundary and a
  // docstring nobody runs is how the off-by-one got written down in the first
  // place. Both sides are derived from the constant, so moving the constant
  // moves the test with it.
  it("runs the exact LCS right up to the cell cap, and degrades one line past it", () => {
    const exactSide = Math.floor(Math.sqrt(MAX_LCS_CELLS)) - 1;
    expect((exactSide + 1) * (exactSide + 1)).toBeLessThanOrEqual(MAX_LCS_CELLS);
    expect((exactSide + 2) * (exactSide + 2)).toBeGreaterThan(MAX_LCS_CELLS);

    const pair = (lines: number) => ({
      a: Array.from({ length: lines }, (_, i) => `old ${i}`).join("\n") + "\n",
      b: Array.from({ length: lines }, (_, i) => `new ${i}`).join("\n") + "\n",
    });

    const atCap = pair(exactSide);
    expect(diffHunks(atCap.a, atCap.b).coarse).toBe(false);

    const overCap = pair(exactSide + 1);
    expect(diffHunks(overCap.a, overCap.b).coarse).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// proposeLocalEdit — readiness, the download guard, the truncation guard
// ---------------------------------------------------------------------------

describe("localcode/edit — proposeLocalEdit readiness", () => {
  it("returns null and creates NO session when no model is ready on the device", async () => {
    const proposal = await proposeLocalEdit("add a comment", "src/x.ts");
    expect(proposal).toBeNull();
    expect(edge.createTier0Session).not.toHaveBeenCalled();
    expect(edge.createTier1Session).not.toHaveBeenCalled();
    expect(fs.readLocalFile).not.toHaveBeenCalled();
  });

  it("never starts a download: a merely 'downloadable' tier 0 does not count as ready", async () => {
    edge.tier0Availability.mockResolvedValue("downloadable");
    expect(await proposeLocalEdit("add a comment", "src/x.ts")).toBeNull();
    expect(edge.createTier0Session).not.toHaveBeenCalled();
    const readiness = await localEditReadiness();
    expect(readiness.ready).toBe(false);
    expect(readiness.detail).toContain("Edge console");
  });

  it("respects a hosted-only routing preference rather than running on-device anyway", async () => {
    mockTier0Reply(FILE);
    edge.getEdgeRoutingPreference.mockReturnValue("hosted-only");
    expect(await proposeLocalEdit("add a comment", "src/x.ts")).toBeNull();
    expect(edge.createTier0Session).not.toHaveBeenCalled();
  });

  it("prefers a ready tier-1 model over the built-in tier 0", async () => {
    const destroy = vi.fn();
    edge.getTier1RunModel.mockResolvedValue({ id: "qwen-1.5b" });
    edge.createTier1Session.mockResolvedValue({ prompt: vi.fn(async () => FILE), destroy });
    edge.tier0Availability.mockResolvedValue("available");
    const proposal = await proposeLocalEdit("no-op", "src/x.ts");
    expect(proposal).not.toBeNull();
    expect(edge.createTier1Session).toHaveBeenCalledTimes(1);
    expect(edge.createTier0Session).not.toHaveBeenCalled();
    expect(destroy).toHaveBeenCalledTimes(1);
  });

  it("returns null when no repo is bound, without touching the model", async () => {
    mockTier0Reply(FILE);
    setLocalEditRepo(null);
    expect(await proposeLocalEdit("add a comment", "src/x.ts")).toBeNull();
    expect(edge.createTier0Session).not.toHaveBeenCalled();
  });
});

describe("localcode/edit — proposeLocalEdit output handling", () => {
  it("strips a markdown fence and returns a reviewable diff", async () => {
    const updated = FILE.replace("const line0 = 0;", "// added by the local model\nconst line0 = 0;");
    const { destroy } = mockTier0Reply("```ts\n" + updated + "```");
    const proposal = await proposeLocalEdit("add a header comment", "src/x.ts");
    expect(proposal).not.toBeNull();
    expect(proposal!.path).toBe("src/x.ts");
    expect(proposal!.original).toBe(FILE);
    expect(proposal!.updated.startsWith("```")).toBe(false);
    expect(proposal!.updated).toContain("// added by the local model");
    expect(proposal!.diff).toContain("+// added by the local model");
    // The fence strip trims; a file that ended with a newline must still end
    // with one, or every written file gains a spurious git diff.
    expect(proposal!.updated.endsWith("\n")).toBe(true);
    expect(destroy).toHaveBeenCalledTimes(1);
  });

  // `coarse` used to be computed in diff.ts and thrown away by the
  // string-only unifiedDiff, so nothing downstream could ever tell someone
  // the diff they were approving was a whole-file block rather than a
  // reviewed change. It now rides on the proposal.
  it("carries the coarse flag from the diff onto the proposal", async () => {
    const small = "const a = 1;\nconst b = 2;\n";
    fs.readLocalFile.mockResolvedValue(small);
    mockTier0Reply("const a = 1;\nconst B = 2;\n");
    const proposal = await proposeLocalEdit("rename b", "src/x.ts");
    expect(proposal).not.toBeNull();
    expect(proposal!.coarse).toBe(false);
    // And the flag is a real reading of the diff, not a hardcoded false:
    const big = Array.from({ length: 2100 }, (_, i) => `old ${i}`).join("\n") + "\n";
    const rewritten = Array.from({ length: 2100 }, (_, i) => `new ${i}`).join("\n") + "\n";
    expect(unifiedDiffResult(big, rewritten, "src/big.ts").coarse).toBe(true);
  });

  it("returns a proposal with an empty diff when the model changed nothing", async () => {
    mockTier0Reply(FILE);
    const proposal = await proposeLocalEdit("do nothing", "src/x.ts");
    expect(proposal).not.toBeNull();
    expect(proposal!.diff).toBe("");
  });

  it("REFUSES a truncated rewrite - the file-destroying failure mode", async () => {
    // The realistic shape: the model emits the first few lines and stops.
    mockTier0Reply("const line0 = 0;\nconst line1 = 1;\n");
    expect(await proposeLocalEdit("rename the constants", "src/x.ts")).toBeNull();
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  it("refuses an empty or whitespace-only reply", async () => {
    mockTier0Reply("   \n  ");
    expect(await proposeLocalEdit("rename the constants", "src/x.ts")).toBeNull();
    mockTier0Reply("```ts\n```");
    expect(await proposeLocalEdit("rename the constants", "src/x.ts")).toBeNull();
  });

  it("refuses a file too large to round-trip through a small local context", async () => {
    fs.readLocalFile.mockResolvedValue("x".repeat(MAX_EDITABLE_CHARS + 1));
    mockTier0Reply(FILE);
    expect(await proposeLocalEdit("tidy this up", "src/big.ts")).toBeNull();
    expect(edge.createTier0Session).not.toHaveBeenCalled();
  });

  // readLocalFile answers "" for a missing file, a revoked permission and a
  // path outside the folder alike. Before this guard, "" sailed past the 40%
  // floor (nothing is below 40% of zero) and the model's whole reply became a
  // proposal - so a typo in the free-text path box produced a fully invented
  // file, and approving it minted the path.
  it("REFUSES a file that could not be read, instead of inventing one", async () => {
    fs.readLocalFile.mockResolvedValue("");
    const { prompt } = mockTier0Reply("export function invented() {\n  return 1;\n}\n");
    expect(await proposeLocalEdit("add a helper", "src/typo.ts")).toBeNull();
    // Not merely null: the model is never even asked, so there is no reply to
    // be tempted by later.
    expect(prompt).not.toHaveBeenCalled();
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  it("never throws when the model session blows up", async () => {
    edge.tier0Availability.mockResolvedValue("available");
    edge.createTier0Session.mockRejectedValue(new Error("WebGPU device lost"));
    await expect(proposeLocalEdit("add a comment", "src/x.ts")).resolves.toBeNull();
  });

  it("holds the 40% floor at its exported boundary", () => {
    const original = "x".repeat(100);
    expect(isPlausibleRewrite(original, "x".repeat(40))).toBe(true);
    expect(isPlausibleRewrite(original, "x".repeat(39))).toBe(false);
    expect(isPlausibleRewrite(original, "")).toBe(false);
    // A ratio against zero decides nothing, so the predicate abstains. That
    // is only safe because both callers refuse an empty original before they
    // reach it - asserted directly in the propose and apply tests above and
    // below, not left to this line to imply.
    expect(isPlausibleRewrite("", "anything")).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// applyLocalEdits
// ---------------------------------------------------------------------------

function edit(overrides: Partial<ProposedEdit> = {}): ProposedEdit {
  const updated = FILE + "const extra = 1;\n";
  return {
    path: "src/x.ts",
    original: FILE,
    updated,
    diff: unifiedDiff(FILE, updated, "src/x.ts"),
    coarse: false,
    ...overrides,
  };
}

describe("localcode/edit — applyLocalEdits", () => {
  it("writes a file whose contents still match the proposal", async () => {
    const result = await applyLocalEdits(repo, [edit()]);
    expect(result.applied).toEqual(["src/x.ts"]);
    expect(result.failed).toEqual([]);
    expect(fs.writeLocalFileResult).toHaveBeenCalledWith(repo, "src/x.ts", FILE + "const extra = 1;\n");
  });

  it("REFUSES to write when the file changed under us, and writes nothing", async () => {
    fs.readLocalFile.mockResolvedValue(FILE + "// the person saved their own edit\n");
    const result = await applyLocalEdits(repo, [edit()]);
    expect(result.applied).toEqual([]);
    expect(result.failed).toHaveLength(1);
    expect(result.failed[0].path).toBe("src/x.ts");
    expect(result.failed[0].reason).toContain("changed on disk");
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  it("fails every edit, reads nothing and writes nothing when write permission is denied", async () => {
    fs.ensureWritePermission.mockResolvedValue(false);
    const result = await applyLocalEdits(repo, [edit(), edit({ path: "src/y.ts" })]);
    expect(result.applied).toEqual([]);
    expect(result.failed.map((f) => f.path)).toEqual(["src/x.ts", "src/y.ts"]);
    expect(result.failed[0].reason).toContain("Write access");
    expect(fs.readLocalFile).not.toHaveBeenCalled();
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  it("treats a throwing permission check as a denial rather than propagating it", async () => {
    fs.ensureWritePermission.mockRejectedValue(new Error("user gesture required"));
    const result = await applyLocalEdits(repo, [edit()]);
    expect(result.applied).toEqual([]);
    expect(result.failed).toHaveLength(1);
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  it("re-runs the truncation guard at write time", async () => {
    const result = await applyLocalEdits(repo, [edit({ updated: "const line0 = 0;\n" })]);
    expect(result.applied).toEqual([]);
    expect(result.failed[0].reason).toContain("truncated");
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  // Replaces a test that made readLocalFile REJECT. It cannot: a missing
  // file, a revoked permission and a path outside the folder all come back as
  // "". So this is what a failed re-read actually looks like, and it must not
  // be reported as "someone changed the file" - that sends a person to their
  // editor to look for a change that was never there.
  it("refuses when the re-read comes back empty, and says so without blaming a phantom edit", async () => {
    fs.readLocalFile.mockResolvedValue("");
    const result = await applyLocalEdits(repo, [edit()]);
    expect(result.applied).toEqual([]);
    expect(result.failed).toEqual([{ path: "src/x.ts", reason: UNREADABLE_REASON }]);
    expect(result.failed[0].reason).not.toContain("changed on disk");
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  // The hole underneath that conflation: a proposal captured against "" plus
  // a read that fails the same way compares EQUAL, so the stale guard passes
  // and invented contents go over a file nobody could read.
  it("refuses a proposal whose original is empty, even though the re-read matches it byte for byte", async () => {
    fs.readLocalFile.mockResolvedValue("");
    const result = await applyLocalEdits(repo, [edit({ original: "", updated: "const invented = 1;\n" })]);
    expect(result.applied).toEqual([]);
    expect(result.failed).toEqual([{ path: "src/x.ts", reason: UNREADABLE_REASON }]);
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  // Guard ORDER, not merely guard presence. Both guards were already here,
  // but the ratio check ran first, and for a proposal that is empty on both
  // sides it is the one that fires - so an unreadable file came back as "the
  // proposed contents look truncated compared to the file on disk", a length
  // comparison against a file that was never read. The pair below pins the
  // order: same batch shape, two different causes, two different reasons.
  it("blames the unreadable file rather than truncation when both sides of the proposal are empty", async () => {
    fs.readLocalFile.mockResolvedValue("");
    const result = await applyLocalEdits(repo, [edit({ original: "", updated: "" })]);
    expect(result.applied).toEqual([]);
    expect(result.failed).toEqual([{ path: "src/x.ts", reason: UNREADABLE_REASON }]);
    expect(result.failed[0].reason).not.toContain("truncated");
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  it("still blames truncation when the original was real and only the proposal is empty", async () => {
    const result = await applyLocalEdits(repo, [edit({ updated: "" })]);
    expect(result.applied).toEqual([]);
    expect(result.failed).toHaveLength(1);
    expect(result.failed[0].reason).toContain("truncated");
    expect(result.failed[0].reason).not.toBe(UNREADABLE_REASON);
    expect(fs.writeLocalFileResult).not.toHaveBeenCalled();
  });

  // Replaces a test that made the write THROW. The real writeLocalFileResult
  // reports failure by resolving {ok:false} - which is precisely why the
  // void-returning writeLocalFile used to report a dead write as applied.
  it("reports a failed write per file, and keeps going for the rest of the batch", async () => {
    fs.writeLocalFileResult.mockImplementation(async (_repo: unknown, path: string) =>
      path === "src/x.ts" ? { ok: false, reason: "NoModificationAllowedError" } : { ok: true }
    );
    const result = await applyLocalEdits(repo, [edit(), edit({ path: "src/y.ts" })]);
    expect(result.applied).toEqual(["src/y.ts"]);
    expect(result.failed).toHaveLength(1);
    expect(result.failed[0].path).toBe("src/x.ts");
    // The DOMException name survives into the reason: for someone working out
    // what locked their file, the real name beats copy we invented.
    expect(result.failed[0].reason).toContain("NoModificationAllowedError");
  });

  it("never reports a refused write as applied, and translates the slug into something actionable", async () => {
    fs.writeLocalFileResult.mockResolvedValue({ ok: false, reason: "write-permission-denied" });
    const result = await applyLocalEdits(repo, [edit()]);
    expect(result.applied).toEqual([]);
    expect(result.failed).toHaveLength(1);
    expect(result.failed[0].path).toBe("src/x.ts");
    expect(result.failed[0].reason).toContain("Write access");
    // fsAccess's slug is fine in a log and useless to a person.
    expect(result.failed[0].reason).not.toContain("write-permission-denied");
  });

  it("does nothing at all - not even a permission prompt - for an empty batch", async () => {
    const result = await applyLocalEdits(repo, []);
    expect(result).toEqual({ applied: [], failed: [] });
    expect(fs.ensureWritePermission).not.toHaveBeenCalled();
  });
});
