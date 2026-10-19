/**
 * Command pattern for canvas undo/redo. Every edit is a Command with do/undo
 * over an immutable {nodes, edges} graph. Graph arrays are updated immutably
 * everywhere, so a stored reference IS a snapshot (no deep copies).
 */
export type CanvasGraph = { nodes: any[]; edges: any[] };

export interface Command {
  readonly label: string;
  /** Apply the edit (redo): returns the graph after it. */
  do(current: CanvasGraph): CanvasGraph;
  /** Revert the edit: returns the graph before it. */
  undo(current: CanvasGraph): CanvasGraph;
}

/**
 * A command recorded BEFORE its mutation runs: it knows the graph it started
 * from; the graph after is captured the first time it is undone.
 */
export class SnapshotCommand implements Command {
  private after: CanvasGraph | null = null;
  constructor(
    readonly label: string,
    private readonly before: CanvasGraph
  ) {}
  do(current: CanvasGraph): CanvasGraph {
    return this.after ?? current;
  }
  undo(current: CanvasGraph): CanvasGraph {
    this.after = current;
    return this.before;
  }
}

export const HISTORY_CAP = 50;

export class CommandHistory {
  past: Command[] = [];
  future: Command[] = [];
  private lastRecordAt = 0;

  constructor(private readonly cap: number = HISTORY_CAP) {}

  /** Record a command about to run. Clears redo. */
  record(cmd: Command): void {
    this.past.push(cmd);
    if (this.past.length > this.cap) this.past.shift();
    this.future = [];
  }

  /**
   * Record the CURRENT graph as the "before" of an upcoming edit. A positive
   * `coalesceMs` merges a burst (typing) into one step; every call inside the
   * window extends it. Returns whether a command was recorded.
   */
  commit(current: CanvasGraph, label = "edit", coalesceMs = 0, now: number = Date.now()): boolean {
    if (coalesceMs > 0 && now - this.lastRecordAt < coalesceMs) {
      this.lastRecordAt = now;
      return false;
    }
    this.lastRecordAt = now;
    this.record(new SnapshotCommand(label, current));
    return true;
  }

  /** Returns the graph to show, or null when there is nothing to undo. */
  undo(current: CanvasGraph): CanvasGraph | null {
    const cmd = this.past.pop();
    if (!cmd) return null;
    const next = cmd.undo(current);
    this.future.push(cmd);
    return next;
  }

  redo(current: CanvasGraph): CanvasGraph | null {
    const cmd = this.future.pop();
    if (!cmd) return null;
    const next = cmd.do(current);
    this.past.push(cmd);
    return next;
  }

  reset(): void {
    this.past = [];
    this.future = [];
    this.lastRecordAt = 0;
  }

  get canUndo(): boolean {
    return this.past.length > 0;
  }
  get canRedo(): boolean {
    return this.future.length > 0;
  }
}
