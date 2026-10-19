// Pure helpers behind canvas comment threads (comments.ts) plus the
// classification in the api/workflows.ts comment wrappers. apiFetch is
// mocked at the seam - no network.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
vi.mock("../../lib/api/core", () => ({
  apiFetch: apiFetchMock,
  apiFetchOnce: apiFetchMock,
  friendlyStatusFallback: (s: number) => `status ${s}`,
  toJson: async (res: Response) => {
    if (!res.ok) {
      const err = new Error("toasted") as Error & { status: number };
      err.status = res.status;
      throw err;
    }
    if (res.status === 204) return null;
    const t = await res.text();
    return t ? JSON.parse(t) : null;
  },
  toJsonQuietOn: (quiet: number[]) => async (res: Response) => {
    if (!res.ok) {
      let body: { error?: string } = {};
      try {
        body = await res.json();
      } catch {
        /* none */
      }
      const err = new Error(body.error || (quiet.includes(res.status) ? "quiet" : "toasted")) as Error & { status: number };
      err.status = res.status;
      throw err;
    }
    if (res.status === 204) return null;
    const t = await res.text();
    return t ? JSON.parse(t) : null;
  },
  jsonBody: (body: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }),
}));
vi.mock("./stepTest", () => ({ testWorkflowNode: vi.fn() }));

import {
  activeMention,
  canvasPins,
  editMentionIds,
  extractMentionIds,
  mentionLabels,
  resolveTargetId,
  filterPeople,
  filterThreads,
  insertComment,
  insertMention,
  isEdited,
  mergePending,
  normalizeThreads,
  optimisticComment,
  patchComment,
  personLabel,
  relativeTime,
  removeComment,
  replaceComment,
  restoreThread,
  setThreadResolved,
  splitMentions,
  threadCounts,
  threadIdOf,
  threadNumbers,
  validateBody,
  type CommentView,
} from "./comments";
import {
  createWorkflowComment,
  deleteWorkflowComment,
  listWorkflowComments,
  updateWorkflowComment,
} from "../../lib/api/workflows";

const T0 = "2026-09-29T10:00:00Z";

function c(over: Partial<CommentView> & { id: string }): CommentView {
  return {
    parentId: null,
    nodeId: null,
    anchor: null,
    body: "hi",
    mentions: [],
    author: { id: "u1", displayName: "Ada Lovelace" },
    resolved: false,
    resolvedBy: null,
    resolvedAt: null,
    deleted: false,
    orphaned: false,
    canEdit: true,
    canResolve: true,
    createdAt: T0,
    updatedAt: T0,
    replies: [],
    ...over,
  };
}

function res(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : typeof body === "string" ? body : JSON.stringify(body), { status });
}

describe("normalizeThreads", () => {
  it("parses the nested server shape and sorts replies", () => {
    const out = normalizeThreads([
      {
        id: "r1", parentId: null, nodeId: "n1", anchor: null, body: "root",
        mentions: [{ id: "u2", displayName: null }], author: { id: "u1", displayName: "Ada" },
        resolved: false, deleted: false, orphaned: false, canEdit: true, canResolve: true,
        createdAt: T0, updatedAt: T0,
        replies: [
          { id: "b", parentId: "r1", body: "second", author: { id: "u2" }, createdAt: "2026-09-29T10:02:00Z" },
          { id: "a", parentId: "r1", body: "first", author: { id: "u2" }, createdAt: "2026-09-29T10:01:00Z" },
        ],
      },
      { id: "p1", anchor: { x: 10, y: 20 }, body: "free", author: { id: "u1" }, createdAt: "2026-09-29T09:00:00Z" },
      "junk",
      { nope: true },
    ]);
    expect(out.map((t) => t.id)).toEqual(["p1", "r1"]);
    expect(out[1].replies.map((r) => r.id)).toEqual(["a", "b"]);
    expect(out[1].mentions).toEqual([{ id: "u2", displayName: null }]);
    expect(out[0].anchor).toEqual({ x: 10, y: 20 });
  });

  it("folds a flat list and drops fully deleted roots", () => {
    const out = normalizeThreads([
      { id: "r", createdAt: T0, author: { id: "u" } },
      { id: "x", parentId: "r", createdAt: T0, author: { id: "u" } },
      { id: "gone", deleted: true, body: null, createdAt: T0, author: { id: "u" } },
      { id: "orphan-reply", parentId: "missing", createdAt: T0, author: { id: "u" } },
    ]);
    expect(out.map((t) => t.id)).toEqual(["r"]);
    expect(out[0].replies.map((r) => r.id)).toEqual(["x"]);
  });

  it("returns [] for non-arrays", () => {
    expect(normalizeThreads(null)).toEqual([]);
    expect(normalizeThreads({ id: "x" })).toEqual([]);
  });
});

describe("derivations", () => {
  const threads = [
    c({ id: "1", nodeId: "n1" }),
    c({ id: "2", nodeId: "n1", resolved: true }),
    c({ id: "3", anchor: { x: 1, y: 2 } }),
    c({ id: "4", nodeId: "n2", replies: [c({ id: "4a", parentId: "4", nodeId: "n2" })] }),
    c({ id: "5", anchor: { x: 5, y: 6 }, resolved: true }),
  ];

  it("threadCounts counts node roots only", () => {
    expect(threadCounts(threads)).toEqual({ n1: { open: 1, resolved: 1 }, n2: { open: 1, resolved: 0 } });
  });

  it("canvasPins keeps free-anchored roots with their global number", () => {
    expect(canvasPins(threads).map((p) => [p.thread.id, p.number])).toEqual([["3", 3], ["5", 5]]);
    expect(threadNumbers(threads).get("4")).toBe(4);
  });

  it("filterThreads and threadIdOf", () => {
    expect(filterThreads(threads, "open").map((t) => t.id)).toEqual(["1", "3", "4"]);
    expect(filterThreads(threads, "resolved").map((t) => t.id)).toEqual(["2", "5"]);
    expect(filterThreads(threads, "all")).toHaveLength(5);
    expect(threadIdOf(threads, "4a")).toBe("4");
    expect(threadIdOf(threads, "3")).toBe("3");
    expect(threadIdOf(threads, "zzz")).toBeNull();
  });
});

describe("personLabel / isEdited / relativeTime / validateBody", () => {
  it("falls back to a short id when displayName is missing", () => {
    expect(personLabel({ id: "abcdef12-3456", displayName: null })).toBe("user abcdef");
    expect(personLabel({ id: "x", displayName: "  Ada " })).toBe("Ada");
  });

  it("edited ignores resolve bumps on a root", () => {
    expect(isEdited(c({ id: "a" }))).toBe(false);
    expect(isEdited(c({ id: "a", updatedAt: "2026-09-29T10:05:00Z" }))).toBe(true);
    expect(isEdited(c({ id: "a", updatedAt: "2026-09-29T10:05:00Z", resolvedAt: "2026-09-29T10:05:00.300Z", resolved: true }))).toBe(false);
    expect(isEdited(c({ id: "a", updatedAt: "2026-09-29T10:05:00Z", deleted: true }))).toBe(false);
  });

  it("relativeTime buckets", () => {
    const now = Date.parse(T0);
    expect(relativeTime(T0, now + 10_000)).toBe("just now");
    expect(relativeTime(T0, now + 5 * 60_000)).toBe("5m ago");
    expect(relativeTime(T0, now + 3 * 3_600_000)).toBe("3h ago");
    expect(relativeTime(T0, now + 2 * 86_400_000)).toBe("2d ago");
    expect(relativeTime(T0, now + 30 * 86_400_000)).not.toMatch(/ago/);
    expect(relativeTime(null, now)).toBe("");
    expect(relativeTime("garbage", now)).toBe("");
  });

  it("validateBody mirrors core limits", () => {
    expect(validateBody("   ")).toMatch(/empty/);
    expect(validateBody("x".repeat(4001))).toMatch(/4000/);
    expect(validateBody("  ok  ")).toBeNull();
  });
});

describe("mentions", () => {
  const ada = { id: "u-ada", displayName: "Ada Lovelace" };
  const alan = { id: "u-alan", displayName: "Alan Turing" };
  const grace = { id: "u-grace", displayName: "Grace Hopper" };

  it("activeMention finds the @query at the caret", () => {
    expect(activeMention("hey @Ad", 7)).toEqual({ start: 4, query: "Ad" });
    expect(activeMention("@", 1)).toEqual({ start: 0, query: "" });
    expect(activeMention("hey @Ada Lo", 11)).toEqual({ start: 4, query: "Ada Lo" });
    expect(activeMention("mail a@b.com", 12)).toBeNull();
    expect(activeMention("hey @Ada\nnext", 13)).toBeNull();
    expect(activeMention("no at here", 5)).toBeNull();
  });

  it("filterPeople ranks prefix matches first", () => {
    // "Grace" only contains "a" mid-word, so it ranks after the prefix hits.
    expect(filterPeople([grace, alan, ada], "a").map((p) => p.id)).toEqual(["u-ada", "u-alan", "u-grace"]);
    expect(filterPeople([grace, alan, ada], "tur").map((p) => p.id)).toEqual(["u-alan"]);
    expect(filterPeople([grace, alan, ada], "hop").map((p) => p.id)).toEqual(["u-grace"]);
    expect(filterPeople([grace, alan, ada], "")).toHaveLength(3);
  });

  it("insertMention replaces the query and places the caret after it", () => {
    const r = insertMention("hey @Ad please", 4, 7, ada);
    expect(r.text).toBe("hey @Ada Lovelace please");
    expect(r.text.slice(0, r.caret)).toBe("hey @Ada Lovelace ");
  });

  it("extractMentionIds only returns picked people still in the text", () => {
    expect(extractMentionIds("cc @Ada Lovelace and @Alan Turing", [ada, alan, grace])).toEqual(["u-ada", "u-alan"]);
    // Typed by hand, never picked: not a mention.
    expect(extractMentionIds("cc @Grace Hopper", [ada])).toEqual([]);
    // Picked, then the token was deleted.
    expect(extractMentionIds("cc nobody", [ada])).toEqual([]);
    // Token must be whole.
    expect(extractMentionIds("cc @Ada Lovelaces", [ada])).toEqual([]);
    expect(extractMentionIds("@Ada Lovelace @Ada Lovelace", [ada, ada])).toEqual(["u-ada"]);
  });

  it("caps at 20 mentions", () => {
    const many = Array.from({ length: 25 }, (_, i) => ({ id: `u${i}`, displayName: `P${i}x` }));
    const text = many.map((p) => `@${p.displayName}`).join(" ");
    expect(extractMentionIds(text, many)).toHaveLength(20);
  });

  it("splitMentions segments known mentions, including short-id fallbacks", () => {
    const noName: { id: string; displayName: string | null } = { id: "abcdef99", displayName: null };
    expect(splitMentions("hi @Ada Lovelace and @user abcdef!", [ada, noName])).toEqual([
      { text: "hi " },
      { text: "@Ada Lovelace", mention: ada },
      { text: " and " },
      { text: "@user abcdef", mention: noName },
      { text: "!" },
    ]);
    expect(splitMentions("plain", [])).toEqual([{ text: "plain" }]);
    expect(splitMentions("", [ada])).toEqual([]);
  });
});

describe("edit mentions / resolve target", () => {
  const ada = { id: "u-ada", labels: mentionLabels("u-ada", ["Ada Lovelace", "Ada L"]) };
  const ghost = { id: "u-ghost-1234", labels: mentionLabels("u-ghost-1234", [null]) };

  it("lists every label variant, including the short-id fallback", () => {
    expect(mentionLabels("u-ada", ["Ada Lovelace", "Ada L", "Ada Lovelace"])).toEqual(["Ada Lovelace", "Ada L", "user uada"]);
  });

  it("leaves mentions out when nothing was picked or removed", () => {
    expect(editMentionIds({ before: "hi @Ada Lovelace", after: "hello @Ada Lovelace", original: [ada], pickedIds: [], pickedDuringEdit: false })).toBeNull();
    // A mention whose token can't be matched is never dropped by a body edit.
    expect(editMentionIds({ before: "hi @Grace", after: "hello @Grace", original: [ghost], pickedIds: [], pickedDuringEdit: false })).toBeNull();
  });

  it("drops a removed token (matched under either name) and keeps unverifiable ones", () => {
    expect(editMentionIds({ before: "hi @Ada L and @Grace", after: "hi and @Grace", original: [ada, ghost], pickedIds: [], pickedDuringEdit: false }))
      .toEqual(["u-ghost-1234"]);
  });

  it("adds new picks to the carried-over list", () => {
    expect(editMentionIds({ before: "hi @Grace", after: "hi @Grace @Alan", original: [ghost], pickedIds: ["u-alan"], pickedDuringEdit: true }))
      .toEqual(["u-ghost-1234", "u-alan"]);
  });

  it("routes resolve through a live reply when the root is deleted", () => {
    expect(resolveTargetId(c({ id: "r" }))).toBe("r");
    const blanked = c({
      id: "r",
      deleted: true,
      body: null,
      replies: [c({ id: "temp-x", parentId: "r", pending: true }), c({ id: "gone", parentId: "r", deleted: true }), c({ id: "live", parentId: "r" })],
    });
    expect(resolveTargetId(blanked)).toBe("live");
    expect(resolveTargetId({ ...blanked, replies: [] })).toBeNull();
  });
});

describe("optimistic reducers", () => {
  const base = [c({ id: "r1", nodeId: "n1", replies: [c({ id: "a", parentId: "r1" })] }), c({ id: "r2" })];

  it("insertComment appends roots and replies without mutating", () => {
    const snapshot = JSON.stringify(base);
    const withReply = insertComment(base, c({ id: "b", parentId: "r1" }));
    expect(withReply[0].replies.map((r) => r.id)).toEqual(["a", "b"]);
    const withRoot = insertComment(base, c({ id: "r3" }));
    expect(withRoot.map((t) => t.id)).toEqual(["r1", "r2", "r3"]);
    expect(JSON.stringify(base)).toBe(snapshot);
  });

  it("replaceComment swaps temp ids and keeps a root's local replies on the PATCH shape", () => {
    const temp = optimisticComment({ body: "x", mentions: [], author: { id: "me", displayName: "Me" }, root: base[0] });
    expect(temp.pending).toBe(true);
    expect(temp.parentId).toBe("r1");
    const s1 = insertComment(base, temp);
    const s2 = replaceComment(s1, temp.id, c({ id: "real", parentId: "r1" }));
    expect(s2[0].replies.map((r) => r.id)).toEqual(["a", "real"]);
    const s3 = replaceComment(s2, "r1", c({ id: "r1", body: "edited", replies: [] }));
    expect(s3[0].body).toBe("edited");
    expect(s3[0].replies).toHaveLength(2);
  });

  it("patchComment merges fields", () => {
    expect(patchComment(base, "a", { body: "new" })[0].replies[0].body).toBe("new");
    expect(patchComment(base, "missing", { body: "new" })).toBe(base);
  });

  it("setThreadResolved mirrors on replies and clears on reopen", () => {
    const r = setThreadResolved(base, "r1", true, "u9", T0);
    expect(r[0].resolved).toBe(true);
    expect(r[0].replies[0].resolvedBy).toBe("u9");
    const o = setThreadResolved(r, "r1", false, "u9", T0);
    expect(o[0].resolvedBy).toBeNull();
    expect(o[0].replies[0].resolved).toBe(false);
  });

  it("removeComment mirrors core delete semantics", () => {
    // Root with replies: blanked.
    const blanked = removeComment(base, "r1");
    expect(blanked[0]).toMatchObject({ id: "r1", deleted: true, body: null });
    // Last reply of a blanked root: the thread goes.
    expect(removeComment(blanked, "a").map((t) => t.id)).toEqual(["r2"]);
    // Root without replies: gone.
    expect(removeComment(base, "r2").map((t) => t.id)).toEqual(["r1"]);
    // Reply: gone.
    expect(removeComment(base, "a")[0].replies).toHaveLength(0);
  });

  it("restoreThread puts a removed thread back at its index", () => {
    const removed = removeComment(base, "r2");
    expect(restoreThread(removed, base[1], 1).map((t) => t.id)).toEqual(["r1", "r2"]);
    const blanked = removeComment(base, "r1");
    expect(restoreThread(blanked, base[0], 0)[0].deleted).toBe(false);
  });

  it("mergePending keeps in-flight replies across a refresh", () => {
    const temp = optimisticComment({ body: "x", mentions: [], author: { id: "me", displayName: null }, root: base[0] });
    const local = insertComment(base, temp);
    const merged = mergePending([c({ id: "r1", replies: [] })], local);
    expect(merged[0].replies.map((r) => r.id)).toEqual([temp.id]);
    // Thread gone on the server: its pending reply goes too.
    expect(mergePending([], local)).toEqual([]);
  });
});

describe("api wrappers", () => {
  beforeEach(() => apiFetchMock.mockReset());

  it("list normalizes, and flags 404/405 as unsupported", async () => {
    apiFetchMock.mockResolvedValueOnce(res(200, [{ id: "r", createdAt: T0, author: { id: "u" } }]));
    const ok = await listWorkflowComments("wf");
    expect(ok.ok && ok.data.map((t) => t.id)).toEqual(["r"]);
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/wf/comments");

    apiFetchMock.mockResolvedValueOnce(res(404));
    expect(await listWorkflowComments("wf")).toEqual({ ok: false, unsupported: true });
    apiFetchMock.mockResolvedValueOnce(res(405));
    expect(await listWorkflowComments("wf")).toEqual({ ok: false, unsupported: true });
    apiFetchMock.mockResolvedValueOnce(res(500));
    expect(await listWorkflowComments("wf")).toEqual({ ok: false, unsupported: false });
    apiFetchMock.mockResolvedValueOnce(res(200, "<html>"));
    expect(await listWorkflowComments("wf")).toEqual({ ok: false, unsupported: true });
    apiFetchMock.mockRejectedValueOnce(new Error("offline"));
    expect(await listWorkflowComments("wf")).toEqual({ ok: false, unsupported: false });
  });

  it("create posts the body and returns the view", async () => {
    apiFetchMock.mockResolvedValueOnce(res(201, { id: "new", parentId: "r", createdAt: T0, author: { id: "u" } }));
    const r = await createWorkflowComment("wf", { body: "hi", parentId: "r", mentions: ["u2"] });
    expect(r.ok && r.data.id).toBe("new");
    const [path, opts] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/workflows/wf/comments");
    expect(opts.method).toBe("POST");
    expect(JSON.parse(opts.body)).toEqual({ body: "hi", parentId: "r", mentions: ["u2"] });
  });

  it("update reports 403 inline with the server message", async () => {
    apiFetchMock.mockResolvedValueOnce(res(403, { error: "Only the author can edit a comment." }));
    const r = await updateWorkflowComment("wf", "c1", { body: "x" });
    expect(r).toEqual({ ok: false, unsupported: false, status: 403, message: "Only the author can edit a comment." });
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/workflows/wf/comments/c1");
    expect(apiFetchMock.mock.calls[0][1].method).toBe("PATCH");
  });

  it("delete handles 204 and flags 405 as unsupported", async () => {
    apiFetchMock.mockResolvedValueOnce(res(204));
    expect(await deleteWorkflowComment("wf", "c1")).toEqual({ ok: true, data: true });
    apiFetchMock.mockResolvedValueOnce(res(405));
    const r = await deleteWorkflowComment("wf", "c1");
    expect(r.ok).toBe(false);
    expect(r.unsupported).toBe(true);
    apiFetchMock.mockRejectedValueOnce(new Error("offline"));
    const off = await deleteWorkflowComment("wf", "c1");
    expect(off.ok === false && off.status).toBeNull();
  });
});
