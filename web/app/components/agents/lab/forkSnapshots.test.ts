// Fork snapshots: the only copy of a rewound conversation tail. Newest five,
// 24 hours, large fields stripped, and never a throw when the browser refuses
// storage - the snapshot is then held in memory, and the caller is told.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  FORK_SNAPSHOT_LIMIT,
  FORK_SNAPSHOT_TTL_MS,
  clearMemorySnapshots,
  deleteSnapshot,
  loadSnapshots,
  pruneSnapshots,
  saveSnapshot,
  type ForkSnapshot,
  type ForkSnapshotDraft,
} from "./forkSnapshots";
import type { LabTurn } from "./types";

const AGENT = "agent-1";
const KEY = `agentic:lab:forks:${AGENT}`;
const T0 = 1_790_000_000_000;

function draft(overrides: Partial<ForkSnapshotDraft> = {}): ForkSnapshotDraft {
  return {
    sessionId: "session-1",
    forkUserIndex: 2,
    originalMessage: "What are your hours?",
    sentMessage: "What are your weekend hours?",
    originalTail: [
      { role: "user", content: "What are your hours?" },
      { role: "assistant", content: "9 to 7.", ref: "ref-1", steps: [{ type: "run_finished", id: 1, origin: "server" }] },
    ],
    ...overrides,
  };
}

beforeEach(() => {
  window.localStorage.clear();
  clearMemorySnapshots();
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe("forkSnapshots", () => {
  it("stores a snapshot under the agent's key and reads it back", () => {
    const { snapshot: saved, persisted } = saveSnapshot(AGENT, draft(), T0);
    expect(persisted).toBe(true);
    expect(saved.id).toMatch(/^f/);
    expect(saved.createdAt).toBe(T0);
    expect(JSON.parse(window.localStorage.getItem(KEY) ?? "[]")).toHaveLength(1);
    expect(loadSnapshots(AGENT, T0)).toEqual([saved]);
    // Another agent's snapshots are a different key.
    expect(loadSnapshots("agent-2", T0)).toEqual([]);
  });

  it("keeps the newest five, newest first", () => {
    const ids: string[] = [];
    for (let i = 0; i < 7; i++) ids.push(saveSnapshot(AGENT, draft({ originalMessage: `m${i}` }), T0 + i).snapshot.id);
    const loaded = loadSnapshots(AGENT, T0 + 10);
    expect(FORK_SNAPSHOT_LIMIT).toBe(5);
    expect(loaded.map((s) => s.id)).toEqual(ids.slice(2).reverse());
    expect(loaded.map((s) => s.originalMessage)).toEqual(["m6", "m5", "m4", "m3", "m2"]);
  });

  it("drops snapshots older than 24 hours, on load and on the next save", () => {
    const old = saveSnapshot(AGENT, draft({ originalMessage: "old" }), T0).snapshot;
    expect(loadSnapshots(AGENT, T0 + FORK_SNAPSHOT_TTL_MS)).toHaveLength(1);
    expect(loadSnapshots(AGENT, T0 + FORK_SNAPSHOT_TTL_MS + 1)).toEqual([]);

    const fresh = saveSnapshot(AGENT, draft({ originalMessage: "fresh" }), T0 + FORK_SNAPSHOT_TTL_MS + 1).snapshot;
    const stored = JSON.parse(window.localStorage.getItem(KEY) ?? "[]") as { id: string }[];
    expect(stored.map((s) => s.id)).toEqual([fresh.id]);
    expect(stored.some((s) => s.id === old.id)).toBe(false);
  });

  it("strips frames, attachment and trace from the stored turns and leaves the caller's turns alone", () => {
    const heavy = {
      role: "user",
      content: "Look at this",
      attachment: { name: "photo.png", dataUrl: "data:image/png;base64,AAAA" },
      frames: ["data:image/jpeg;base64,BBBB"],
      trace: [{ tool: "x" }],
    } as unknown as LabTurn;
    const reply: LabTurn = { role: "assistant", content: "Seen.", ref: "ref-1", metrics: { elapsedMs: 10, toolCalls: 0 } };
    const saved = saveSnapshot(AGENT, draft({ originalTail: [heavy, reply] }), T0).snapshot;
    expect(saved.originalTail[0]).toEqual({ role: "user", content: "Look at this" });
    expect(saved.originalTail[1]).toEqual(reply);
    const raw = window.localStorage.getItem(KEY) ?? "";
    expect(raw).not.toContain("base64");
    expect(raw).not.toContain("trace");
    expect(heavy.attachment).toBeTruthy();
    expect(heavy.frames).toHaveLength(1);
  });

  it("deletes one snapshot and removes the key with the last one", () => {
    const a = saveSnapshot(AGENT, draft(), T0).snapshot;
    const b = saveSnapshot(AGENT, draft(), T0 + 1).snapshot;
    deleteSnapshot(AGENT, a.id, T0 + 2);
    expect(loadSnapshots(AGENT, T0 + 2).map((s) => s.id)).toEqual([b.id]);
    deleteSnapshot(AGENT, b.id, T0 + 2);
    expect(window.localStorage.getItem(KEY)).toBeNull();
    // Unknown id: nothing to do, nothing thrown.
    expect(() => deleteSnapshot(AGENT, "nope")).not.toThrow();
  });

  it("ignores stored data that is not a list of snapshots", () => {
    window.localStorage.setItem(KEY, "{not json");
    expect(loadSnapshots(AGENT, T0)).toEqual([]);
    window.localStorage.setItem(KEY, JSON.stringify({ id: "x" }));
    expect(loadSnapshots(AGENT, T0)).toEqual([]);
    const good: ForkSnapshot = { id: "f1", sessionId: "s", createdAt: T0, forkUserIndex: 0, originalMessage: "a", sentMessage: "a", originalTail: [] };
    window.localStorage.setItem(KEY, JSON.stringify([null, 3, { id: "bad" }, good]));
    expect(loadSnapshots(AGENT, T0)).toEqual([good]);
  });

  function blockStorage() {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
      throw new Error("blocked");
    });
  }

  it("never throws when storage throws, still returns the snapshot, and says it was not stored", () => {
    blockStorage();
    expect(loadSnapshots(AGENT, T0)).toEqual([]);
    const { snapshot: saved, persisted } = saveSnapshot(AGENT, draft(), T0);
    expect(persisted).toBe(false);
    expect(saved).toMatchObject({ sessionId: "session-1", createdAt: T0, forkUserIndex: 2 });
    expect(() => deleteSnapshot(AGENT, saved.id)).not.toThrow();
  });

  it("a snapshot storage refused is still there on the next load (a remounted panel), until it is deleted", () => {
    blockStorage();
    const first = saveSnapshot(AGENT, draft({ originalMessage: "first" }), T0).snapshot;
    const second = saveSnapshot(AGENT, draft({ originalMessage: "second" }), T0 + 1).snapshot;
    // Nothing reached storage, yet a fresh load - what RunGraphPanel does
    // when it mounts again after a tab switch - returns both, newest first.
    expect(loadSnapshots(AGENT, T0 + 2).map((s) => s.id)).toEqual([second.id, first.id]);
    expect(loadSnapshots("agent-2", T0 + 2)).toEqual([]);
    // Held snapshots expire and are capped like stored ones.
    expect(loadSnapshots(AGENT, T0 + FORK_SNAPSHOT_TTL_MS + 1).map((s) => s.id)).toEqual([second.id]);
    deleteSnapshot(AGENT, second.id, T0 + 2);
    expect(loadSnapshots(AGENT, T0 + 2).map((s) => s.id)).toEqual([first.id]);
    deleteSnapshot(AGENT, first.id, T0 + 2);
    expect(loadSnapshots(AGENT, T0 + 2)).toEqual([]);
  });

  it("lists held and stored snapshots together once storage works again", () => {
    const stored = saveSnapshot(AGENT, draft({ originalMessage: "stored" }), T0).snapshot;
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    const held = saveSnapshot(AGENT, draft({ originalMessage: "held" }), T0 + 1);
    expect(held.persisted).toBe(false);
    setItem.mockRestore();
    expect(loadSnapshots(AGENT, T0 + 2).map((s) => s.originalMessage)).toEqual(["held", "stored"]);
    // Deleting the stored one leaves the held one alone, and the other way round.
    deleteSnapshot(AGENT, stored.id, T0 + 2);
    expect(loadSnapshots(AGENT, T0 + 2).map((s) => s.originalMessage)).toEqual(["held"]);
    expect(window.localStorage.getItem(KEY)).toBeNull();
  });

  it("over quota, retries with the newest snapshot alone", () => {
    saveSnapshot(AGENT, draft({ originalMessage: "first" }), T0);
    const real = Storage.prototype.setItem;
    let calls = 0;
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(function (this: Storage, key: string, value: string) {
      calls += 1;
      if (calls === 1) throw new Error("quota");
      real.call(this, key, value);
    });
    const second = saveSnapshot(AGENT, draft({ originalMessage: "second" }), T0 + 1);
    expect(calls).toBe(2);
    expect(second.persisted).toBe(true);
    expect(loadSnapshots(AGENT, T0 + 2).map((s) => s.id)).toEqual([second.snapshot.id]);
  });

  it("pruneSnapshots sorts, expires and caps without touching its input", () => {
    const list = [1, 3, 2].map((n) => ({ ...saveSnapshot("other", draft(), T0 + n).snapshot, id: `s${n}` }));
    const before = list.map((s) => s.id);
    expect(pruneSnapshots(list, T0 + 5).map((s) => s.id)).toEqual(["s3", "s2", "s1"]);
    expect(pruneSnapshots(list, T0 + FORK_SNAPSHOT_TTL_MS + 2).map((s) => s.id)).toEqual(["s3", "s2"]);
    expect(list.map((s) => s.id)).toEqual(before);
  });
});
