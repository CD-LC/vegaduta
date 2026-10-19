import { describe, expect, it } from "vitest";
import { CommandHistory, HISTORY_CAP, type CanvasGraph } from "./commands";

const g = (...ids: string[]): CanvasGraph => ({ nodes: ids.map((id) => ({ id })), edges: [] });

describe("CommandHistory", () => {
  it("undoes and redoes a recorded edit", () => {
    const h = new CommandHistory();
    const a = g("a");
    const b = g("a", "b");
    h.commit(a);
    expect(h.undo(b)).toBe(a);
    expect(h.canRedo).toBe(true);
    expect(h.redo(a)).toBe(b);
    expect(h.canUndo).toBe(true);
  });

  it("returns null when empty", () => {
    const h = new CommandHistory();
    expect(h.undo(g())).toBeNull();
    expect(h.redo(g())).toBeNull();
  });

  it("clears redo on a new edit", () => {
    const h = new CommandHistory();
    h.commit(g("a"));
    h.undo(g("a", "b"));
    h.commit(g("a"));
    expect(h.canRedo).toBe(false);
  });

  it("coalesces a burst into one step", () => {
    const h = new CommandHistory();
    expect(h.commit(g("a"), "type", 500, 1000)).toBe(true);
    expect(h.commit(g("a"), "type", 500, 1200)).toBe(false);
    expect(h.commit(g("a"), "type", 500, 1600)).toBe(false);
    expect(h.past).toHaveLength(1);
    expect(h.commit(g("a"), "type", 500, 2500)).toBe(true);
    expect(h.past).toHaveLength(2);
  });

  it("caps the stack and resets", () => {
    const h = new CommandHistory();
    for (let i = 0; i < HISTORY_CAP + 5; i++) h.commit(g("a"));
    expect(h.past).toHaveLength(HISTORY_CAP);
    h.reset();
    expect(h.canUndo).toBe(false);
  });
});
