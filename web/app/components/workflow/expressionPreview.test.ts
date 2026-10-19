import { describe, expect, it, vi } from "vitest";
import { previewText, resolvePreview, type Evaluate } from "./expressionPreview";
import type { EvaluateResponse } from "../../lib/api/workflowExpressions";

const resp = (over: Partial<EvaluateResponse> = {}): EvaluateResponse => ({
  ok: true,
  value: "x",
  type: "string",
  rendered: "x",
  error: null,
  sources: ["n1"],
  warnings: [],
  ...over,
});
const good = (over: Partial<EvaluateResponse> = {}) => ({ ok: true as const, data: resp(over) });
const fail = (unsupported = false) => ({ ok: false as const, unsupported });

describe("resolvePreview", () => {
  it("uses pinned data when it answers and never asks for the last run", async () => {
    const ev = vi.fn<Evaluate>().mockResolvedValue(good({ rendered: "42", type: "number", value: 42 }));
    const out = await resolvePreview(ev, "{{ $json.a }}", "n2", true);
    expect(out).toMatchObject({ kind: "ok", rendered: "42", type: "number", source: "pinned" });
    expect(ev).toHaveBeenCalledTimes(1);
    expect(ev.mock.calls[0][0]).toMatchObject({ template: "{{ $json.a }}", nodeId: "n2", dataSource: "pinned" });
  });

  it("falls back to the last run when nothing was pinned (no sources read)", async () => {
    const ev = vi
      .fn<Evaluate>()
      .mockResolvedValueOnce(good({ sources: [], rendered: "" }))
      .mockResolvedValueOnce(good({ rendered: "from run" }));
    const out = await resolvePreview(ev, "{{ $json.a }}", "n2", true);
    expect(out).toMatchObject({ kind: "ok", rendered: "from run", source: "lastRun" });
    expect(ev.mock.calls[1][0].dataSource).toBe("lastRun");
  });

  it("accepts a pure expression with no sample data", async () => {
    const ev = vi.fn<Evaluate>().mockResolvedValue(good({ sources: [], rendered: "3", type: "number" }));
    const out = await resolvePreview(ev, "{{ 1 + 2 }}", "n2", false);
    expect(out).toMatchObject({ kind: "ok", rendered: "3", source: "pinned" });
  });

  it("reports the neutral no-data state when neither source has data", async () => {
    const ev = vi.fn<Evaluate>().mockResolvedValue(good({ sources: [] }));
    expect(await resolvePreview(ev, "{{ $json.a }}", "n2", true)).toEqual({ kind: "nodata" });
  });

  it("reports an error, preferring the side that actually had data", async () => {
    const ev = vi
      .fn<Evaluate>()
      .mockResolvedValueOnce(good({ ok: false, sources: [], error: { message: "pinned says no", start: 1, end: 2 } }))
      .mockResolvedValueOnce(good({ ok: false, sources: ["n1"], error: { message: "run says no", start: 3, end: 4 } }));
    expect(await resolvePreview(ev, "{{ bad( }}", "n2", true)).toEqual({ kind: "error", message: "run says no", start: 3, end: 4 });
  });

  it("is unavailable when both requests fail, including an unsupported route", async () => {
    expect(await resolvePreview(vi.fn<Evaluate>().mockResolvedValue(fail()), "{{ a }}", "n", true)).toEqual({ kind: "unavailable" });
    expect(await resolvePreview(vi.fn<Evaluate>().mockResolvedValue(fail(true)), "{{ a }}", "n", true)).toEqual({ kind: "unavailable" });
  });

  it("survives one failing side", async () => {
    const ev = vi.fn<Evaluate>().mockResolvedValueOnce(fail()).mockResolvedValueOnce(good({ rendered: "ok" }));
    expect(await resolvePreview(ev, "{{ a }}", "n", true)).toMatchObject({ kind: "ok", source: "lastRun" });
    const ev2 = vi.fn<Evaluate>().mockResolvedValueOnce(fail()).mockResolvedValueOnce(good({ sources: [] }));
    expect(await resolvePreview(ev2, "{{ a }}", "n", true)).toEqual({ kind: "nodata" });
  });

  it("tolerates a sparse server body", async () => {
    const ev = vi.fn<Evaluate>().mockResolvedValue({ ok: true, data: { ok: true, sources: ["n1"] } as unknown as EvaluateResponse });
    const out = await resolvePreview(ev, "{{ a }}", "n", true);
    expect(out).toMatchObject({ kind: "ok", rendered: "", warnings: [] });
  });
});

describe("previewText", () => {
  it("collapses whitespace and truncates", () => {
    expect(previewText("a\n  b\tc")).toBe("a b c");
    expect(previewText("x".repeat(300), 10)).toBe(`${"x".repeat(10)}…`);
  });
});
