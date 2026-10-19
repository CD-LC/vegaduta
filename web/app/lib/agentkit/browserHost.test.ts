// agentkit/browserHost + persist — the two modules that touch a browser API.
//
// jsdom has no File System Access API, so (in the style of
// localcode/fsAccess.test.ts) the host is exercised against an in-memory
// directory handle. The cases are the judgement calls: a MISSING file and a
// FAILED read are different answers; nothing resolves outside the root; secrets
// are not searched; a truncated search says so; and the host has no terminal.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { LocalRepoHandle } from "../localcode/types";
import { SEARCH_DEADLINE_MS, createBrowserHost } from "./browserHost";
import { newRunState } from "./loop";
import { probeNativeBridge, nativeBridgeTerminal } from "./nativeBridge";
import { MAX_SAVED_CHARS, clearRun, loadRun, saveRun } from "./persist";

type Node =
  | { kind: "file"; text: string; size?: number; bytes?: number[]; throwOnRead?: boolean }
  | { kind: "dir"; children: Record<string, Node>; throwOnList?: string };

/** Every file the host OPENED (getFile), by name — opening is the expensive
 *  part of a search on a real disk, so what is never opened is what is pinned. */
let opened: string[] = [];
/** Called on every getFile, so a test can move the clock or abort mid-walk. */
let onOpen: ((name: string) => void) | null = null;

function file(text: string, extra: Partial<Extract<Node, { kind: "file" }>> = {}): Node {
  return { kind: "file", text, ...extra };
}
function dir(children: Record<string, Node>): Node {
  return { kind: "dir", children };
}

function domError(name: string): Error {
  const error = new Error(name);
  error.name = name;
  return error;
}

function fileHandle(name: string, node: Extract<Node, { kind: "file" }>, log: string[]) {
  return {
    kind: "file",
    name,
    async getFile() {
      opened.push(name);
      onOpen?.(name);
      if (node.throwOnRead) throw domError("NotReadableError");
      const bytes = node.bytes ? Uint8Array.from(node.bytes) : new TextEncoder().encode(node.text);
      return {
        size: node.size ?? bytes.length,
        slice: (a: number, b: number) => ({ arrayBuffer: async () => bytes.slice(a, b).buffer }),
        text: async () => node.text,
      };
    },
    async createWritable() {
      return {
        async write(contents: string) {
          node.text = contents;
          log.push(`write:${name}`);
        },
        async close() {},
      };
    },
  };
}

function dirHandle(name: string, node: Extract<Node, { kind: "dir" }>, log: string[], opts: { denyAll?: boolean } = {}): any {
  return {
    kind: "directory",
    name,
    async queryPermission() {
      return "granted";
    },
    async requestPermission() {
      return "granted";
    },
    async getDirectoryHandle(child: string, options?: { create?: boolean }) {
      if (opts.denyAll) throw domError("NotAllowedError");
      let next = node.children[child];
      if (!next && options?.create) next = node.children[child] = dir({});
      if (!next) throw domError("NotFoundError");
      if (next.kind !== "dir") throw domError("TypeMismatchError");
      return dirHandle(child, next, log, opts);
    },
    async getFileHandle(child: string, options?: { create?: boolean }) {
      if (opts.denyAll) throw domError("NotAllowedError");
      let next = node.children[child];
      if (!next && options?.create) next = node.children[child] = file("");
      if (!next) throw domError("NotFoundError");
      if (next.kind !== "file") throw domError("TypeMismatchError");
      return fileHandle(child, next, log);
    },
    entries() {
      const list = Object.entries(node.children);
      return {
        async *[Symbol.asyncIterator]() {
          if (node.throwOnList) throw domError(node.throwOnList);
          for (const [childName, child] of list) {
            yield [childName, child.kind === "dir" ? dirHandle(childName, child, log, opts) : fileHandle(childName, child, log)];
          }
        },
      };
    },
  };
}

function repoOf(tree: Record<string, Node>, opts: { denyAll?: boolean } = {}) {
  const log: string[] = [];
  const root = dir(tree) as Extract<Node, { kind: "dir" }>;
  const repo = { id: "repo-1", name: "my-project", dir: dirHandle("my-project", root, log, opts) } as unknown as LocalRepoHandle;
  return { repo, root, log };
}

describe("agentkit/browserHost", () => {
  beforeEach(() => {
    opened = [];
    onOpen = null;
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("has files and NO terminal, git or web — and says why", () => {
    const host = createBrowserHost(repoOf({}).repo);
    expect(host.kind).toBe("browser");
    expect(host.rootName).toBe("my-project");
    expect(host.fs).toBeDefined();
    expect(host.terminal).toBeUndefined();
    expect(host.git).toBeUndefined();
    expect(host.web).toBeUndefined();
    expect(host.terminalUnavailableReason).toContain("cannot run commands");
    // The bridge is honestly absent, not a stub that pretends.
    expect(probeNativeBridge().available).toBe(false);
    expect(nativeBridgeTerminal()).toBeUndefined();
  });

  it("lists a folder without node_modules/.git, and reports a missing one", async () => {
    const { repo } = repoOf({ src: dir({ "a.ts": file("x") }), node_modules: dir({}), ".git": dir({}), "README.md": file("# hi") });
    const fs = createBrowserHost(repo).fs!;
    const listed = await fs.list("");
    expect(listed.ok).toBe(true);
    expect((listed as { entries: Array<{ name: string }> }).entries.map((e) => e.name).sort()).toEqual(["README.md", "src"]);
    expect(await fs.list("nope")).toMatchObject({ ok: false, reason: "not-found" });
  });

  it("tells a MISSING file from a FAILED read from an EMPTY file", async () => {
    const { repo } = repoOf({ "empty.ts": file(""), "locked.ts": file("x", { throwOnRead: true }), src: dir({}) });
    const fs = createBrowserHost(repo).fs!;
    expect(await fs.read("empty.ts")).toEqual({ ok: true, exists: true, contents: "" });
    expect(await fs.read("missing.ts")).toEqual({ ok: true, exists: false });
    expect(await fs.read("nodir/missing.ts")).toEqual({ ok: true, exists: false });
    expect(await fs.read("locked.ts")).toMatchObject({ ok: false, reason: "unreadable" });
    expect(await fs.read("src")).toMatchObject({ ok: false, reason: "invalid" });
  });

  it("reports a revoked permission as such — never as 'file does not exist'", async () => {
    const { repo } = repoOf({ "a.ts": file("x") }, { denyAll: true });
    const fs = createBrowserHost(repo).fs!;
    expect(await fs.read("a.ts")).toMatchObject({ ok: false, reason: "permission-denied" });
    expect(await fs.list("src")).toMatchObject({ ok: false, reason: "permission-denied" });
  });

  it("refuses binary and oversized files", async () => {
    const { repo } = repoOf({ "blob.json": file("", { bytes: [123, 0, 125] }), "bundle.js": file("x", { size: 600 * 1024 }) });
    const fs = createBrowserHost(repo).fs!;
    expect(await fs.read("blob.json")).toMatchObject({ ok: false, reason: "binary" });
    expect(await fs.read("bundle.js")).toMatchObject({ ok: false, reason: "too-large" });
  });

  it("re-checks confinement itself, for read, list and write", async () => {
    const { repo, log } = repoOf({ "a.ts": file("x") });
    const fs = createBrowserHost(repo).fs!;
    for (const path of ["../a.ts", "/etc/passwd", "C:\\x", "src/../../y"]) {
      expect(await fs.read(path)).toMatchObject({ ok: false, reason: "outside-root" });
      expect(await fs.list(path)).toMatchObject({ ok: false, reason: "outside-root" });
      expect(await fs.write(path, "pwn")).toMatchObject({ ok: false, reason: "outside-root" });
    }
    expect(log).toEqual([]);
  });

  it("writes through fsAccess, creating folders for a new file", async () => {
    const { repo, root, log } = repoOf({ "a.ts": file("old") });
    const fs = createBrowserHost(repo).fs!;
    expect(await fs.write("a.ts", "new")).toEqual({ ok: true });
    expect(await fs.write("src/lib/b.ts", "export {};")).toEqual({ ok: true });
    expect((root.children["a.ts"] as { text: string }).text).toBe("new");
    expect(await fs.read("src/lib/b.ts")).toEqual({ ok: true, exists: true, contents: "export {};" });
    expect(log).toEqual(["write:a.ts", "write:b.ts"]);
  });

  it("searches source files case-insensitively, honours include, and skips secrets and ignored folders", async () => {
    const { repo } = repoOf({
      src: dir({ "retry.ts": file("const RETRY_DELAY = 500;\nexport const x = retry_delay;\n"), "other.py": file("RETRY_DELAY = 1\n") }),
      node_modules: dir({ "dep.js": file("RETRY_DELAY") }),
      "secrets.json": file('{"RETRY_DELAY": "sk-live"}'),
    });
    const fs = createBrowserHost(repo).fs!;
    const all = await fs.search("retry_delay", {});
    expect(all.ok).toBe(true);
    const hits = (all as { hits: Array<{ path: string; line: number }> }).hits;
    expect(hits.map((h) => `${h.path}:${h.line}`).sort()).toEqual(["src/other.py:1", "src/retry.ts:1", "src/retry.ts:2"]);

    const onlyTs = await fs.search("RETRY_DELAY", { include: "**/*.ts" });
    expect((onlyTs as { hits: Array<{ path: string }> }).hits.every((h) => h.path.endsWith(".ts"))).toBe(true);
  });

  it("treats a literal query literally, accepts a regex, and rejects a broken one", async () => {
    const { repo } = repoOf({ "a.ts": file("price = a.b(c)\nprice = axb(c)\n") });
    const fs = createBrowserHost(repo).fs!;
    expect(((await fs.search("a.b(c)", {})) as { hits: unknown[] }).hits).toHaveLength(1);
    expect(((await fs.search("a.b\\(c\\)", { isRegex: true })) as { hits: unknown[] }).hits).toHaveLength(2);
    expect(await fs.search("(unclosed", { isRegex: true })).toMatchObject({ ok: false, reason: "invalid" });
    expect(await fs.search("", {})).toMatchObject({ ok: false, reason: "invalid" });
  });

  it("SAYS when a search stopped early instead of reporting a clean result", async () => {
    const many = Array.from({ length: 200 }, () => "needle").join("\n");
    const { repo } = repoOf({ "a.ts": file(many) });
    const out = await createBrowserHost(repo).fs!.search("needle", {});
    expect(out).toMatchObject({ ok: true, truncated: true });
    expect((out as { truncatedWhy: string }).truncatedWhy).toContain("matches");
  });

  it("stops a search when the run is aborted", async () => {
    const { repo } = repoOf({ "a.ts": file("needle") });
    const controller = new AbortController();
    controller.abort();
    expect(await createBrowserHost(repo).fs!.search("needle", { signal: controller.signal })).toMatchObject({ ok: false, reason: "aborted" });
  });

  it("stops MID-WALK on abort, without opening the rest of the folder", async () => {
    const children: Record<string, Node> = {};
    for (let i = 0; i < 50; i += 1) children[`f${i}.ts`] = file("needle");
    const { repo } = repoOf(children);
    const controller = new AbortController();
    onOpen = () => {
      if (opened.length === 3) controller.abort();
    };
    expect(await createBrowserHost(repo).fs!.search("needle", { signal: controller.signal })).toMatchObject({ ok: false, reason: "aborted" });
    expect(opened.length).toBe(3);
  });

  it("never opens a file the include glob excludes, and never enters a folder outside its prefix", async () => {
    const { repo } = repoOf({
      src: dir({ "a.ts": file("needle"), "b.py": file("needle"), deep: dir({ "c.ts": file("needle") }) }),
      docs: dir({ "d.ts": file("needle"), "e.md": file("needle") }),
      "root.ts": file("needle"),
      "logo.png": file("needle"),
    });
    const fs = createBrowserHost(repo).fs!;

    const out = await fs.search("needle", { include: "src/**/*.ts" });
    expect((out as { hits: Array<{ path: string }> }).hits.map((h) => h.path).sort()).toEqual(["src/a.ts", "src/deep/c.ts"]);
    expect((out as { filesScanned: number }).filesScanned).toBe(2);
    expect(opened.sort()).toEqual(["a.ts", "c.ts"]);

    // Each searched file is opened exactly once (no second sniff), and a file
    // with no known source extension is not opened at all.
    opened = [];
    await fs.search("needle", {});
    expect(opened.sort()).toEqual(["a.ts", "b.py", "c.ts", "d.ts", "e.md", "root.ts"]);
  });

  it("spends its deadline on SCANNING: a slow folder yields the files it got to, marked partial", async () => {
    const children: Record<string, Node> = {};
    for (let i = 0; i < 20; i += 1) children[`f${i}.ts`] = file(`needle ${i}`);
    const { repo } = repoOf(children);
    let now = 1_000_000;
    vi.spyOn(Date, "now").mockImplementation(() => now);
    // Every file costs a fifth of the deadline to open.
    onOpen = () => {
      now += SEARCH_DEADLINE_MS / 5;
    };
    const out = (await createBrowserHost(repo).fs!.search("needle", {})) as SearchOutcomeLike;
    expect(out.ok).toBe(true);
    expect(out.truncated).toBe(true);
    expect(out.truncatedWhy).toContain("8s");
    // The old walk-then-scan reported 0 files here; this one reports real work.
    expect(out.filesScanned).toBeGreaterThanOrEqual(4);
    expect(out.filesScanned).toBeLessThan(20);
    expect(out.hits.length).toBeGreaterThanOrEqual(out.filesScanned - 1);
  });

  it("reports a revoked folder grant as such instead of 'no matches in 0 files'", async () => {
    const { repo, root } = repoOf({ "a.ts": file("needle") });
    root.throwOnList = "NotAllowedError";
    expect(await createBrowserHost(repo).fs!.search("needle", {})).toMatchObject({ ok: false, reason: "permission-denied" });
  });

  it("marks the scan incomplete when a subfolder went stale, and still searches the rest", async () => {
    const gone = dir({ "x.ts": file("needle") }) as Extract<Node, { kind: "dir" }>;
    gone.throwOnList = "NotFoundError";
    const { repo } = repoOf({ gone, src: dir({ "a.ts": file("needle") }) });
    const out = (await createBrowserHost(repo).fs!.search("needle", {})) as SearchOutcomeLike;
    expect(out.hits.map((h) => h.path)).toEqual(["src/a.ts"]);
    expect(out.truncated).toBe(true);
    expect(out.truncatedWhy).toContain("incomplete");
  });

  it("refuses .git itself — read, list and write — and never searches it", async () => {
    const { repo, log } = repoOf({
      ".git": dir({ config: file("[remote]\n url = https://user:token@example.com\n"), hooks: dir({ "pre-commit.sh": file("needle") }) }),
      "a.ts": file("needle"),
    });
    const fs = createBrowserHost(repo).fs!;
    expect(await fs.read(".git/config")).toMatchObject({ ok: false, reason: "invalid" });
    expect(await fs.list(".git/hooks")).toMatchObject({ ok: false, reason: "invalid" });
    expect(await fs.write(".git/hooks/pre-commit", "#!/bin/sh\n")).toMatchObject({ ok: false, reason: "invalid" });
    expect(log).toEqual([]);
    const out = (await fs.search("needle", {})) as SearchOutcomeLike;
    expect(out.hits.map((h) => h.path)).toEqual(["a.ts"]);
  });
});

type SearchOutcomeLike = { ok: boolean; hits: Array<{ path: string }>; filesScanned: number; truncated: boolean; truncatedWhy?: string };

describe("agentkit/persist", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it("round-trips a run, keyed by folder", () => {
    const state = { ...newRunState("do the thing"), status: "step-budget" as const, stepsUsed: 4, plan: [{ text: "a", status: "done" as const }] };
    expect(saveRun("repo-1", state)).toBe(true);
    expect(loadRun("repo-1")).toMatchObject({ task: "do the thing", stepsUsed: 4, status: "step-budget", plan: [{ text: "a", status: "done" }] });
    expect(loadRun("repo-2")).toBeNull();
    clearRun("repo-1");
    expect(loadRun("repo-1")).toBeNull();
  });

  it("keeps an excerpt's real line ranges across a reload, and ignores garbage ones", () => {
    const slice = { path: "a.ts", startLine: 14, endLine: 506, totalLines: 600, text: "14\tx", touchedStep: 2 };
    const ranges = [
      { start: 14, end: 26 },
      { start: 494, end: 506 },
    ];
    saveRun("repo-1", { ...newRunState("t"), status: "stopped", workingSet: [{ ...slice, ranges }] });
    expect(loadRun("repo-1")?.workingSet[0].ranges).toEqual(ranges);

    saveRun("repo-1", { ...newRunState("t"), status: "stopped", workingSet: [{ ...slice, ranges: [{ start: 9, end: 2 }, null, "x"] as never }] });
    expect(loadRun("repo-1")?.workingSet[0].ranges).toBeUndefined();
  });

  it("drops an oversized state rather than filling the origin's storage", () => {
    const huge = { ...newRunState("t"), summary: ["x".repeat(MAX_SAVED_CHARS + 10)] };
    expect(saveRun("repo-1", { ...newRunState("small"), status: "stopped" })).toBe(true);
    expect(saveRun("repo-1", huge)).toBe(false);
    expect(loadRun("repo-1")).toBeNull();
  });

  it("reads corrupt or foreign JSON as 'no saved run'", () => {
    localStorage.setItem("vegaduta.agentkit.run.repo-1", "{not json");
    expect(loadRun("repo-1")).toBeNull();
    localStorage.setItem("vegaduta.agentkit.run.repo-1", JSON.stringify({ version: 1, task: "" }));
    expect(loadRun("repo-1")).toBeNull();
  });

  it("survives storage that throws (blocked, full, private mode)", () => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = () => {
      throw new DOMException("full", "QuotaExceededError");
    };
    try {
      expect(saveRun("repo-1", newRunState("t"))).toBe(false);
    } finally {
      Storage.prototype.setItem = original;
    }
    expect(saveRun("", newRunState("t"))).toBe(false);
  });
});
