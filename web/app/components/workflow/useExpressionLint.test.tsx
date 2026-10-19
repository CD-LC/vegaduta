import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";

const lint = vi.fn();
vi.mock("../../lib/api/workflowExpressions", async (orig) => {
  const real = await orig<typeof import("../../lib/api/workflowExpressions")>();
  return { ...real, workflowExpressionsApi: { ...real.workflowExpressionsApi, lintExpressions: (...a: unknown[]) => lint(...a) } };
});

import { useExpressionLint } from "./useExpressionLint";

const node = (id: string, config: Record<string, unknown>) => ({ id, type: "http", data: { label: id, config } });

beforeEach(() => {
  vi.useFakeTimers();
  lint.mockReset();
});
afterEach(() => vi.useRealTimers());

describe("useExpressionLint", () => {
  it("shows client quick checks immediately and never calls the server for a graph without expressions", async () => {
    const nodes = [node("a", { url: "https://plain" })];
    const { result } = renderHook(() => useExpressionLint("wf1", nodes, "g1", false));
    await act(async () => {
      vi.advanceTimersByTime(2000);
    });
    expect(result.current.issues).toEqual([]);
    expect(lint).not.toHaveBeenCalled();

    const bad = [node("a", { url: "{{ $json.x " })];
    const r2 = renderHook(() => useExpressionLint("wf1", bad, "g1", false));
    expect(r2.result.current.issues.map((i) => i.code)).toContain("expression-unclosed-braces");
    expect(r2.result.current.issues[0].fix).toEqual({ kind: "open-config", nodeId: "a", field: "url" });
  });

  it("debounces the server lint and merges its diagnostics", async () => {
    lint.mockResolvedValue({
      ok: true,
      data: { diagnostics: [{ nodeId: "a", field: "url", severity: "error", code: "UNKNOWN_FUNCTION", message: "no such fn", start: 3, end: 6 }] },
    });
    const nodes = [node("a", { url: "{{ foo(1) }}" })];
    const { result } = renderHook(() => useExpressionLint("wf1", nodes, "g1", false));
    expect(lint).not.toHaveBeenCalled();
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    expect(lint).toHaveBeenCalledTimes(1);
    expect(lint.mock.calls[0][0]).toBe("wf1");
    expect(lint.mock.calls[0][1]).toEqual([{ nodeId: "a", field: "url", template: "{{ foo(1) }}" }]);
    expect(result.current.issues.some((i) => i.code === "expression-unknown-function" && i.source === "server")).toBe(true);
    expect(result.current.state?.sent["a\u0000url"]).toBe("{{ foo(1) }}");
  });

  it("does not re-request when only positions change, and re-requests when a template changes", async () => {
    lint.mockResolvedValue({ ok: true, data: { diagnostics: [] } });
    let nodes = [node("a", { url: "{{ $json.a }}" })];
    const { rerender } = renderHook(() => useExpressionLint("wf1", nodes, "g1", false));
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    expect(lint).toHaveBeenCalledTimes(1);
    nodes = [{ ...nodes[0] }];
    rerender();
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    expect(lint).toHaveBeenCalledTimes(1);
    nodes = [node("a", { url: "{{ $json.b }}" })];
    rerender();
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    expect(lint).toHaveBeenCalledTimes(2);
  });

  it("re-lints after a save even though no template changed", async () => {
    lint.mockResolvedValue({ ok: true, data: { diagnostics: [] } });
    const nodes = [node("a", { url: "{{ $node[\"Score\"].x }}" })];
    let saved = 0;
    const { rerender } = renderHook(() => useExpressionLint("wf1", nodes, "g1", false, saved));
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    expect(lint).toHaveBeenCalledTimes(1);
    saved = 1;
    rerender();
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    expect(lint).toHaveBeenCalledTimes(2);
  });

  it("turns the server half off for the session when the route is missing", async () => {
    lint.mockResolvedValue({ ok: false, unsupported: true });
    let nodes = [node("a", { url: "{{ $json.a }}" })];
    const { rerender } = renderHook(() => useExpressionLint("wf1", nodes, "g1", false));
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    nodes = [node("a", { url: "{{ $json.ab }}" })];
    rerender();
    await act(async () => {
      vi.advanceTimersByTime(1300);
    });
    expect(lint).toHaveBeenCalledTimes(1);
  });

  it("makes no server call while paused (run observing)", async () => {
    const nodes = [node("a", { url: "{{ $json.a }}" })];
    renderHook(() => useExpressionLint("wf1", nodes, "g1", true));
    await act(async () => {
      vi.advanceTimersByTime(2000);
    });
    expect(lint).not.toHaveBeenCalled();
  });
});
