import { describe, it, expect } from "vitest";
import { toast, onToast, getProblems, markAllProblemsRead, type Toast } from "./toastBus";

describe("toastBus", () => {
  it("delivers a toast with the right kind, message, and a well-formed shape", () => {
    const seen: Toast[] = [];
    const off = onToast((t) => seen.push(t));
    toast.success("boom-unique-1");
    expect(seen).toHaveLength(1);
    expect(seen[0]).toMatchObject({ kind: "success", message: "boom-unique-1" });
    expect(typeof seen[0].id).toBe("string");
    expect(typeof seen[0].duration).toBe("number");
    off();
  });

  it("ignores empty / falsy messages (push returns null, no delivery)", () => {
    const seen: Toast[] = [];
    const off = onToast((t) => seen.push(t));
    expect(toast.error("")).toBeNull();
    expect(seen).toHaveLength(0);
    off();
  });

  it("collapses identical messages within the dedupe window", () => {
    const seen: Toast[] = [];
    const off = onToast((t) => seen.push(t));
    toast.info("same-problem-unique-2");
    toast.info("same-problem-unique-2");
    expect(seen).toHaveLength(1);
    off();
  });

  it("allowDuplicate bypasses the dedupe window", () => {
    const seen: Toast[] = [];
    const off = onToast((t) => seen.push(t));
    toast.info("dupe-ok-unique-3", { allowDuplicate: true });
    toast.info("dupe-ok-unique-3", { allowDuplicate: true });
    expect(seen).toHaveLength(2);
    off();
  });

  it("stops delivering after unsubscribe", () => {
    const seen: Toast[] = [];
    const off = onToast((t) => seen.push(t));
    off();
    toast.info("nobody-home-unique-4");
    expect(seen).toHaveLength(0);
  });

  it("sends errors and warnings to the problem log instead of popping them up", () => {
    const seen: Toast[] = [];
    const off = onToast((t) => seen.push(t));
    toast.error("quiet-error-unique-5");
    toast.warn("quiet-warning-unique-6");
    expect(seen).toHaveLength(0);
    expect(getProblems().slice(0, 2).map((p) => p.message)).toEqual(["quiet-warning-unique-6", "quiet-error-unique-5"]);
    markAllProblemsRead();
    expect(getProblems().every((p) => p.read)).toBe(true);
    off();
  });
});
