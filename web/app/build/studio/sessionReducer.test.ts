import { describe, expect, it } from "vitest";
import { initialUiState, uiReducer } from "./sessionReducer";

describe("uiReducer", () => {
  it("sets a value and keeps the state object when nothing changes", () => {
    const a = uiReducer(initialUiState, { type: "set", key: "device", value: "tablet" });
    expect(a.device).toBe("tablet");
    expect(uiReducer(a, { type: "set", key: "device", value: "tablet" })).toBe(a);
  });

  it("applies functional updaters to the previous value", () => {
    const el = { path: [0], tag: "p", text: "hi", html: "", color: "", background: "", fontSize: "" };
    const a = uiReducer(initialUiState, { type: "set", key: "selected", value: el });
    const b = uiReducer(a, { type: "set", key: "selected", value: (s) => (s ? { ...s, text: "yo" } : s) });
    expect(b.selected?.text).toBe("yo");
    expect(uiReducer(initialUiState, { type: "set", key: "selected", value: (s) => (s ? { ...s, text: "x" } : s) })).toBe(initialUiState);
  });

  it("select opens the element panel", () => {
    const el = { path: [1], tag: "h1", text: "t", html: "", color: "", background: "", fontSize: "" };
    const s = uiReducer(initialUiState, { type: "select", element: el });
    expect(s.panel).toBe("element");
    expect(s.selected).toBe(el);
  });

  it("toggles scope ask and diff independently", () => {
    let s = uiReducer(initialUiState, { type: "set", key: "scopeAsk", value: "rebuild all" });
    s = uiReducer(s, { type: "set", key: "diffFrom", value: 2 });
    expect(s.scopeAsk).toBe("rebuild all");
    expect(s.diffFrom).toBe(2);
    s = uiReducer(s, { type: "set", key: "scopeAsk", value: null });
    expect(s.scopeAsk).toBeNull();
    expect(s.diffFrom).toBe(2);
  });
});
