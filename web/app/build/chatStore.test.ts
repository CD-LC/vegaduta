import { afterEach, describe, expect, it, vi } from "vitest";
import {
  MAX_ENTRIES,
  appendStored,
  buildUndo,
  canUndo,
  clearChat,
  loadDraft,
  loadThread,
  sanitizeThread,
  saveDraft,
  saveThread,
  subscribeThread,
} from "./chatStore";

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe("chatStore", () => {
  it("round-trips a thread per project", () => {
    saveThread("a", [{ kind: "user", text: "hi" }]);
    saveThread("b", [{ kind: "user", text: "other" }]);
    expect(loadThread("a")).toEqual([{ kind: "user", text: "hi" }]);
    expect(loadThread("b")[0].text).toBe("other");
    expect(loadThread("missing")).toEqual([]);
  });

  it("caps entries, truncates diffs and ignores junk", () => {
    const big = Array.from({ length: MAX_ENTRIES + 10 }, (_, i) => ({ kind: "user", text: `m${i}` }));
    const out = sanitizeThread([...big, { kind: "weird" }, null, { kind: "result", diff: "x".repeat(30_000) }]);
    expect(out.length).toBe(MAX_ENTRIES);
    const last = out[out.length - 1];
    expect(last.diff.length).toBe(20_000);
    expect(last.diffTruncated).toBe(true);
    expect(sanitizeThread("nope")).toEqual([]);
  });

  it("keeps only the newest undo snapshots within budget", () => {
    const snap = (n: number) => ({ files: [{ path: "a", content: "y".repeat(n) }] });
    const out = sanitizeThread([
      { kind: "result", undo: snap(100_000) },
      { kind: "result", undo: snap(100_000) },
    ]);
    expect(out[0].undo).toBeUndefined();
    expect(out[1].undo).toBeDefined();
  });

  it("stores and clears drafts", () => {
    saveDraft("a", "half typed");
    expect(loadDraft("a")).toBe("half typed");
    saveDraft("a", "");
    expect(loadDraft("a")).toBe("");
    saveDraft("a", "x");
    saveThread("a", [{ kind: "user", text: "t" }]);
    clearChat("a");
    expect(loadDraft("a")).toBe("");
    expect(loadThread("a")).toEqual([]);
  });

  it("survives corrupt JSON and throwing storage", () => {
    window.localStorage.setItem("vd.build.chat.a", "{not json");
    expect(loadThread("a")).toEqual([]);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    expect(() => saveThread("a", [{ kind: "user", text: "x" }])).not.toThrow();
    expect(() => saveDraft("a", "x")).not.toThrow();
  });

  it("appendStored persists and notifies subscribers", () => {
    saveThread("p", [{ kind: "user", text: "q" }]);
    const cb = vi.fn();
    const off = subscribeThread("p", cb);
    appendStored("p", { kind: "result", success: true });
    expect(cb).toHaveBeenCalledTimes(1);
    expect(loadThread("p").map((e) => e.kind)).toEqual(["user", "result"]);
    off();
    appendStored("p", { kind: "result", success: false });
    expect(cb).toHaveBeenCalledTimes(1);
  });
});

describe("undo helpers", () => {
  it("restores modified files and reports created ones", () => {
    const before = new Map([
      ["a.css", "old"],
      ["b.html", "same"],
    ]);
    const u = buildUndo(before, [
      { path: "a.css", content: "new" },
      { path: "b.html", content: "same" },
      { path: "c.js", content: "fresh" },
    ]);
    expect(u).toEqual({ files: [{ path: "a.css", content: "old" }], created: ["c.js"] });
  });

  it("offers no undo when nothing restorable changed or no snapshot", () => {
    expect(buildUndo(new Map(), [{ path: "n.js", content: "x" }])).toBeNull();
    expect(buildUndo(null, [{ path: "a", content: "x" }])).toBeNull();
    expect(buildUndo(new Map([["a", "1"]]), undefined)).toBeNull();
  });

  it("blocks undo when a later successful turn touched the same file", () => {
    const undo = { files: [{ path: "a.css", content: "old" }], created: [] as string[] };
    const thread = [
      { kind: "result", success: true, undo, touched: ["a.css"] },
      { kind: "result", success: false, touched: ["a.css"] },
      { kind: "result", success: true, touched: ["other.js"] },
    ] as any[];
    expect(canUndo(thread, 0)).toBe(true);
    thread.push({ kind: "result", success: true, touched: ["a.css"] });
    expect(canUndo(thread, 0)).toBe(false);
    expect(canUndo([{ kind: "result", undo, undone: true }] as any[], 0)).toBe(false);
  });
});
