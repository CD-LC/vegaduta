import { describe, expect, it } from "vitest";
import {
  classifyImportError,
  groupReport,
  needsAttention,
  nonEmptySections,
  normalizeReport,
  reportCounts,
  truncateText,
} from "./importReport";

const sample = {
  converted: [{ what: "Draft agent", detail: "Created unpublished" }],
  needsManual: [
    { what: "Instructions", why: "Source had none", hint: "Write them" },
    { what: "Function tool 'lookup'", why: "Code is not imported", hint: "Recreate it" },
  ],
  unsupported: [{ what: "Guardrails (2)", why: "Different model" }],
  warnings: ["Description is preview only"],
};

describe("normalizeReport", () => {
  it("passes a well-formed report through", () => {
    expect(normalizeReport(sample)).toEqual(sample);
  });

  it("tolerates missing lists, junk entries and non-objects", () => {
    expect(normalizeReport(undefined)).toEqual({ converted: [], needsManual: [], unsupported: [], warnings: [] });
    expect(normalizeReport("nope")).toEqual({ converted: [], needsManual: [], unsupported: [], warnings: [] });
    const out = normalizeReport({
      converted: [null, 3, { what: "ok" }],
      needsManual: "x",
      unsupported: [{ why: 5 }],
      warnings: ["a", 4, "", null],
    });
    expect(out.converted).toEqual([{ what: "ok", detail: "" }]);
    expect(out.needsManual).toEqual([]);
    expect(out.unsupported).toEqual([{ what: "", why: "" }]);
    expect(out.warnings).toEqual(["a"]);
  });
});

describe("groupReport", () => {
  it("returns Converted, Needs attention, Not supported in that order with merged fields", () => {
    const sections = groupReport(normalizeReport(sample));
    expect(sections.map((s) => s.id)).toEqual(["converted", "needsManual", "unsupported"]);
    expect(sections[1].items[0]).toEqual({
      key: "needsManual-0",
      title: "Instructions",
      body: "Source had none",
      hint: "Write them",
    });
    expect(sections[0].items[0].body).toBe("Created unpublished");
    expect(sections[2].items[0].hint).toBe("");
  });

  it("gives every item a unique key", () => {
    const keys = groupReport(normalizeReport(sample)).flatMap((s) => s.items.map((i) => i.key));
    expect(new Set(keys).size).toBe(keys.length);
  });

  it("nonEmptySections drops empty groups", () => {
    const report = normalizeReport({ converted: [{ what: "a", detail: "b" }] });
    expect(nonEmptySections(groupReport(report)).map((s) => s.id)).toEqual(["converted"]);
  });
});

describe("reportCounts / needsAttention", () => {
  it("counts each list and flags manual work", () => {
    const report = normalizeReport(sample);
    expect(reportCounts(report)).toEqual({ converted: 1, needsManual: 2, unsupported: 1, warnings: 1 });
    expect(needsAttention(report)).toBe(true);
    expect(needsAttention(normalizeReport({}))).toBe(false);
  });
});

describe("truncateText", () => {
  it("leaves short text alone", () => {
    expect(truncateText("hello", 10)).toEqual({ text: "hello", truncated: false });
  });

  it("cuts long text and reports it", () => {
    expect(truncateText("abcdefghij", 5)).toEqual({ text: "abcde", truncated: true });
  });

  it("does not split a surrogate pair", () => {
    const out = truncateText("ab\u{1F600}cd", 3);
    expect(out.truncated).toBe(true);
    expect(out.text).toBe("ab");
  });

  it("handles empty input", () => {
    expect(truncateText("", 5)).toEqual({ text: "", truncated: false });
  });
});

describe("classifyImportError", () => {
  it("maps statuses onto the page's inline states", () => {
    expect(classifyImportError({ status: 404, message: "x" }).kind).toBe("unavailable");
    expect(classifyImportError({ status: 403, message: "cap" }).kind).toBe("upgrade");
    expect(classifyImportError({ status: 413, message: "big" }).kind).toBe("too-large");
    expect(classifyImportError({ status: 400, message: "Choose a source" })).toEqual({
      kind: "invalid",
      message: "Choose a source",
    });
  });

  it("recognises an unreachable backend and falls back to other", () => {
    expect(classifyImportError({ isBackendUnreachable: true, message: "down" }).kind).toBe("network");
    expect(classifyImportError({ status: 500, message: "boom" }).kind).toBe("other");
    expect(classifyImportError(undefined)).toEqual({ kind: "other", message: "" });
  });
});
