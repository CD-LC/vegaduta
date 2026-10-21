import { beforeEach, describe, expect, it } from "vitest";
import {
  deleteThread,
  getThread,
  groupThreads,
  listThreads,
  mergeServerThread,
  mergeServerThreads,
  patchThread,
  recordThreadActivity,
  resetThreadIndexForTests,
  sortThreads,
  threadScope,
  threadTitle,
  transcriptToTurns,
} from "./threadIndex";

// jsdom has no IndexedDB, so these exercise the in-memory fallback and the pure helpers; the IndexedDB
// branch mirrors lib/api/persistentCache.ts, which has the same shape.
beforeEach(() => resetThreadIndexForTests());

describe("threadTitle / threadScope", () => {
  it("uses the first non-empty line, cut at a word", () => {
    expect(threadTitle("\n\n  book me a dentist next week\nand a haircut")).toBe("book me a dentist next week");
    expect(threadTitle("")).toBe("New thread");
    const long = threadTitle("please find the cheapest flight from Bengaluru to Lisbon in the second week of November");
    expect(long.endsWith("…")).toBe(true);
    expect(long.length).toBeLessThanOrEqual(61);
  });

  it("scopes rows by user id with a local fallback", () => {
    expect(threadScope("kc-user-1")).toBe("kc-user-1");
    expect(threadScope("  ")).toBe("local");
    expect(threadScope(null)).toBe("local");
  });
});

describe("index rows", () => {
  it("creates a row on first activity and bumps it afterwards", async () => {
    const row = await recordThreadActivity("u1", "s1", "a1", "first question\nmore", 1_000);
    expect(row).toMatchObject({
      id: "s1",
      scope: "u1",
      agentId: "a1",
      title: "first question",
      lastAt: 1_000,
      pinned: false,
    });
    const bumped = await recordThreadActivity("u1", "s1", "a1", "ignored", 5_000);
    expect(bumped.title).toBe("first question");
    expect(bumped.lastAt).toBe(5_000);
    // An older timestamp never moves a thread back in time.
    expect((await recordThreadActivity("u1", "s1", "a1", "x", 2_000)).lastAt).toBe(5_000);
  });

  it("lists only the scope's rows, pinned first then newest", async () => {
    await recordThreadActivity("u1", "old", "a1", "old", 1_000);
    await recordThreadActivity("u1", "new", "a1", "new", 9_000);
    await recordThreadActivity("u2", "other", "a1", "other", 50_000);
    await patchThread("u1", "old", { pinned: true });
    expect((await listThreads("u1")).map((r) => r.id)).toEqual(["old", "new"]);
    expect((await listThreads("u2")).map((r) => r.id)).toEqual(["other"]);
  });

  it("patches, reads and deletes", async () => {
    await recordThreadActivity("u1", "s1", "a1", "hello", 1_000);
    expect(await patchThread("u1", "s1", { title: "renamed" })).toMatchObject({ title: "renamed" });
    expect(await patchThread("u1", "missing", { title: "x" })).toBeNull();
    expect((await getThread("u1", "s1"))?.title).toBe("renamed");
    await deleteThread("u1", "s1");
    expect(await getThread("u1", "s1")).toBeNull();
  });
});

describe("mergeServerThreads (GET /api/chat/threads into the local cache)", () => {
  const server = (sessionId: string, updatedAt: string, title: string | null = "from the server", agentId = "a1") => ({
    sessionId,
    agentId,
    title,
    createdAt: "2026-10-17T09:00:00Z",
    updatedAt,
  });

  it("adds a session this browser has never seen, titled by the server's first user line", async () => {
    const written = await mergeServerThreads("u1", [
      server("new", "2026-10-17T10:00:00Z", "book me a dentist\nnext week"),
    ]);
    expect(written).toBe(1);
    const row = await getThread("u1", "new");
    expect(row).toMatchObject({
      id: "new",
      scope: "u1",
      agentId: "a1",
      title: "book me a dentist",
      lastAt: Date.parse("2026-10-17T10:00:00Z"),
      createdAt: Date.parse("2026-10-17T09:00:00Z"),
      pinned: false,
    });
  });

  it("keeps the local title and pin, and moves lastAt forward only", async () => {
    await recordThreadActivity("u1", "s1", "a1", "my first line", 5_000);
    await patchThread("u1", "s1", { title: "renamed here", pinned: true });

    // Server behind the browser: nothing to write.
    expect(await mergeServerThreads("u1", [server("s1", "1970-01-01T00:00:01Z")])).toBe(0);
    expect(await getThread("u1", "s1")).toMatchObject({ title: "renamed here", pinned: true, lastAt: 5_000 });

    // Server ahead (another device chatted): activity catches up, the rest stays local.
    expect(await mergeServerThreads("u1", [server("s1", "2026-10-17T10:00:00Z")])).toBe(1);
    expect(await getThread("u1", "s1")).toMatchObject({
      title: "renamed here",
      pinned: true,
      lastAt: Date.parse("2026-10-17T10:00:00Z"),
    });
  });

  it("falls back to the default label for a session without a user message and skips junk rows", async () => {
    const written = await mergeServerThreads("u1", [
      server("empty", "2026-10-17T10:00:00Z", null),
      { sessionId: "", agentId: "a1", title: "x", createdAt: "", updatedAt: "" },
    ]);
    expect(written).toBe(1);
    expect((await getThread("u1", "empty"))?.title).toBe("New thread");
    expect((await listThreads("u1")).map((r) => r.id)).toEqual(["empty"]);
  });

  it("is pure per row: mergeServerThread returns null when nothing would change", () => {
    const local = { id: "s", scope: "u", agentId: "a1", title: "t", lastAt: 9_000, createdAt: 1_000, pinned: false };
    expect(mergeServerThread(local, server("s", "1970-01-01T00:00:01Z"), "u")).toBeNull();
    expect(mergeServerThread(local, server("s", "1970-01-01T00:00:01Z", "x", "a2"), "u")).toMatchObject({
      agentId: "a2",
      lastAt: 9_000,
    });
  });
});

describe("sortThreads / groupThreads", () => {
  const now = new Date(2026, 9, 17, 15, 0, 0).getTime();
  const at = (daysAgo: number, hour = 10) => {
    const d = new Date(2026, 9, 17 - daysAgo, hour, 0, 0);
    return d.getTime();
  };
  const rows = [
    { id: "t", title: "today", agentId: "a", lastAt: at(0), pinned: false },
    { id: "y", title: "yesterday", agentId: "a", lastAt: at(1), pinned: false },
    { id: "w", title: "this week", agentId: "a", lastAt: at(4), pinned: false },
    { id: "e", title: "earlier", agentId: "a", lastAt: at(20), pinned: false },
    { id: "p", title: "pinned old", agentId: "a", lastAt: at(30), pinned: true },
  ];

  it("sorts pinned first, then by activity", () => {
    expect(sortThreads(rows).map((r) => r.id)).toEqual(["p", "t", "y", "w", "e"]);
  });

  it("groups by day", () => {
    expect(groupThreads(rows, now).map((g) => [g.key, g.rows.map((r) => r.id)])).toEqual([
      ["pinned", ["p"]],
      ["today", ["t"]],
      ["yesterday", ["y"]],
      ["week", ["w"]],
      ["earlier", ["e"]],
    ]);
    expect(groupThreads([], now)).toEqual([]);
  });
});

describe("transcriptToTurns", () => {
  it("maps roles, timestamps and failed replies and skips system entries", () => {
    const turns = transcriptToTurns(
      {
        sessionId: "s1",
        messages: [
          { role: "system", content: "hidden" },
          { role: "user", content: "hi", timestamp: "2026-10-17T10:00:00Z" },
          { role: "assistant", content: "hello", timestamp: "2026-10-17T10:00:02Z", status: "ok", error: false },
          { role: "assistant", content: "The reply failed.", status: "failed", error: true },
        ],
      },
      "s1"
    );
    expect(turns.map((t) => t.role)).toEqual(["user", "assistant", "assistant"]);
    expect(turns[0]).toMatchObject({ id: "s1:1", content: "hi", at: Date.parse("2026-10-17T10:00:00Z") });
    expect(turns[1].failed).toBeUndefined();
    expect(turns[2]).toMatchObject({ failed: true, at: 0 });
    expect(transcriptToTurns(null, "s1")).toEqual([]);
  });
});
