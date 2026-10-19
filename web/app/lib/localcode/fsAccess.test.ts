// localcode/fsAccess — the File System Access layer, exercised against an
// in-memory filesystem.
//
// jsdom implements none of this API: no showDirectoryPicker, no
// FileSystemDirectoryHandle, no indexedDB. So, in the style of the sibling
// edge/webmcp.test.ts, every test builds a fake browser surface — a fake
// filesystem AND a fake IndexedDB — and asserts on what the module DOES with
// it. (An earlier revision stubbed only the filesystem, which left every
// persistence path running through its "no indexedDB" no-op branch: the
// store/restore round trip and the stale-handle forget were reviewed by eye and
// never once executed.) That is the honest shape of the test here too —
// this module's entire job is turning a hostile, permission-gated, partly
// missing browser API into a small set of values that never throw, and the
// things worth pinning are exactly the judgement calls: what gets skipped, what
// gets counted, and which permission mode is asked for when.
//
// resetModules + a fresh dynamic import per test because the walk summary is
// module state; a leaked summary between cases would make the cap assertions
// pass for the wrong reason.
//
// The fakes live in fsAccess.testkit.ts. Split by area: this file covers
// browser support, picking and walking; fsAccess.io.test.ts covers reading,
// writing, permissions and persistence.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  type FakeNode,
  f,
  d,
  makeRepo,
  installDirectoryPicker,
  removeDirectoryPicker,
  importFsAccess,
} from "./fsAccess.testkit";
import type { LocalRepoHandle } from "./types";

beforeEach(() => {
  vi.resetModules();
  removeDirectoryPicker();
});

afterEach(() => {
  // Unstub first: the SSR test leaves `window` undefined, and the picker
  // cleanup needs a real window to delete from.
  vi.unstubAllGlobals();
  removeDirectoryPicker();
});

describe("localcode/fsAccess — browser support", () => {
  it("reports unsupported where showDirectoryPicker is absent (Firefox, Safari, and jsdom)", async () => {
    const { isLocalCodeSupported } = await importFsAccess();

    expect(isLocalCodeSupported()).toBe(false);
  });

  it("reports unsupported during SSR, where there is no window at all", async () => {
    vi.stubGlobal("window", undefined);
    const { isLocalCodeSupported } = await importFsAccess();

    expect(isLocalCodeSupported()).toBe(false);
  });

  it("reports supported once the Chromium picker exists", async () => {
    installDirectoryPicker(async () => null);
    const { isLocalCodeSupported } = await importFsAccess();

    expect(isLocalCodeSupported()).toBe(true);
  });

  it("picking is a null, not a throw, on an unsupported browser", async () => {
    const { pickLocalRepo } = await importFsAccess();

    await expect(pickLocalRepo()).resolves.toBeNull();
  });
});

describe("localcode/fsAccess — picking a folder", () => {
  it("asks for READ only — write is negotiated later, at apply time", async () => {
    const { repo } = makeRepo({ "a.ts": f() });
    const picker = installDirectoryPicker(async () => repo.dir);
    const { pickLocalRepo } = await importFsAccess();

    const picked = await pickLocalRepo();

    expect(picked?.name).toBe("my-project");
    expect(picker.options).toEqual([{ id: "vegaduta-localcode", mode: "read" }]);
  });

  it("a cancelled picker is a null, not an error — Escape is the common case", async () => {
    installDirectoryPicker(async () => {
      throw new Error("AbortError: The user aborted a request.");
    });
    const { pickLocalRepo } = await importFsAccess();

    await expect(pickLocalRepo()).resolves.toBeNull();
  });
});

describe("localcode/fsAccess — walking the folder", () => {
  it("skips every ignored directory, however deep", async () => {
    const { repo } = makeRepo({
      "index.ts": f(),
      node_modules: d({ "left-pad.js": f() }),
      ".git": d({ "config.ts": f() }),
      dist: d({ "bundle.js": f() }),
      build: d({ "out.js": f() }),
      ".next": d({ "page.js": f() }),
      target: d({ "Main.java": f() }),
      out: d({ "gen.ts": f() }),
      coverage: d({ "lcov.json": f() }),
      ".venv": d({ "site.py": f() }),
      __pycache__: d({ "mod.py": f() }),
      src: d({ nested: d({ node_modules: d({ "dep.ts": f() }), "deep.ts": f() }) }),
    });
    const { listCodeFiles } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files.map((file) => file.path).sort()).toEqual(["index.ts", "src/nested/deep.ts"]);
  });

  it("keeps .github — the spec's ignore list is a list, not 'every dotted folder'", async () => {
    const { repo } = makeRepo({ ".github": d({ workflows: d({ "ci.yml": f() }) }) });
    const { listCodeFiles } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files.map((file) => file.path)).toEqual([".github/workflows/ci.yml"]);
  });

  it("labels files by language, and drops extensions it does not index", async () => {
    const { repo } = makeRepo({
      "a.tsx": f(),
      "b.py": f(),
      "c.kt": f(),
      "d.hpp": f(),
      "e.yml": f(),
      "logo.png": f(),
      Makefile: f(),
      ".gitignore": f(),
    });
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files.map((file) => `${file.path}:${file.lang}`)).toEqual([
      "a.tsx:typescript",
      "b.py:python",
      "c.kt:kotlin",
      "d.hpp:cpp",
      "e.yml:yaml",
    ]);
    expect(getLastWalkSummary().skippedUnknownType).toBe(3);
  });

  it("skips anything over 512 KB and says so in the summary", async () => {
    const { repo } = makeRepo({
      "small.ts": f({ text: "ok" }),
      "bundle.js": f({ text: "generated", size: 512 * 1024 + 1 }),
      "borderline.ts": f({ text: "still fine", size: 512 * 1024 }),
    });
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files.map((file) => file.path)).toEqual(["small.ts", "borderline.ts"]);
    expect(getLastWalkSummary().skippedLarge).toBe(1);
  });

  it("skips a file that looks binary despite a source extension", async () => {
    const { repo } = makeRepo({
      // A .json that is really a packed blob, and a UTF-16 .ts — both would
      // chunk into garbage and poison retrieval.
      "blob.json": f({ bytes: [0x7b, 0x00, 0x01, 0x02] }),
      "utf16.ts": f({ bytes: [0x63, 0x00, 0x6f, 0x00] }),
      "plain.ts": f({ bytes: [0x63, 0x6f, 0x6e, 0x73, 0x74] }),
    });
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files.map((file) => file.path)).toEqual(["plain.ts"]);
    expect(getLastWalkSummary().skippedBinary).toBe(2);
  });

  it("caps at 5000 files and REPORTS the cap — a silent truncation reads as 'indexed everything'", async () => {
    const children: Record<string, FakeNode> = {};
    for (let i = 0; i < 5100; i += 1) children[`file${i}.ts`] = f({ text: "x" });
    const { repo } = makeRepo(children);
    const { listCodeFiles, getLastWalkSummary, MAX_LOCAL_FILES } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(MAX_LOCAL_FILES).toBe(5000);
    expect(files).toHaveLength(5000);
    expect(getLastWalkSummary().capped).toBe(true);
  });

  it("does not claim a cap when the repo happens to land exactly on the limit", async () => {
    const children: Record<string, FakeNode> = {};
    for (let i = 0; i < 5000; i += 1) children[`file${i}.ts`] = f({ text: "x" });
    const { repo } = makeRepo(children);
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files).toHaveLength(5000);
    expect(getLastWalkSummary().capped).toBe(false);
  });

  it("does not claim a cap when everything past the limit was going to be skipped anyway", async () => {
    // The entry handed over after the budget runs out is very often node_modules
    // or an image. Breaking on it and calling that a truncation would put an
    // "incomplete index" banner on a repo that was in fact walked in full.
    const children: Record<string, FakeNode> = {};
    for (let i = 0; i < 5000; i += 1) children[`file${i}.ts`] = f({ text: "x" });
    children["logo.png"] = f();
    children.node_modules = d({ "left-pad.js": f() });
    children[".gitignore"] = f();
    const { repo } = makeRepo(children);
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files).toHaveLength(5000);
    expect(getLastWalkSummary().capped).toBe(false);
  });

  it("does claim a cap when an indexable file is behind the skippable ones", async () => {
    const children: Record<string, FakeNode> = {};
    for (let i = 0; i < 5000; i += 1) children[`file${i}.ts`] = f({ text: "x" });
    children["logo.png"] = f();
    children["dropped.ts"] = f();
    const { repo } = makeRepo(children);
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files.some((file) => file.path === "dropped.ts")).toBe(false);
    expect(getLastWalkSummary().capped).toBe(true);
  });

  it("still claims a cap for an unopened folder, even though it might be empty", async () => {
    // The optimistic half of the rule: we cannot know what is in a folder we
    // never opened, and over-reporting is the safe direction.
    const children: Record<string, FakeNode> = {};
    for (let i = 0; i < 5000; i += 1) children[`file${i}.ts`] = f({ text: "x" });
    children.src = d({});
    const { repo } = makeRepo(children);
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    await listCodeFiles(repo);

    expect(getLastWalkSummary().capped).toBe(true);
  });

  it("counts a file whose first bytes will not read as unreadable, not as binary", async () => {
    // Both outcomes refuse to index; only one of them is a fact about the file's
    // contents, and a summary that calls an I/O failure "binary" sends the
    // reader hunting for a packed blob that was never there.
    const { repo } = makeRepo({ "gone.ts": f({ headThrows: true }), "ok.ts": f() });
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files.map((file) => file.path)).toEqual(["ok.ts"]);
    expect(getLastWalkSummary().unreadable).toBe(1);
    expect(getLastWalkSummary().skippedBinary).toBe(0);
  });

  it("a folder that cannot be enumerated is counted, not thrown", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { throwOnEntries: true });
    const { listCodeFiles, getLastWalkSummary } = await importFsAccess();

    const files = await listCodeFiles(repo);

    expect(files).toEqual([]);
    expect(getLastWalkSummary().unreadable).toBe(1);
  });

  it("walking a repo with no handle at all returns an empty list", async () => {
    const { listCodeFiles } = await importFsAccess();

    await expect(listCodeFiles(null as unknown as LocalRepoHandle)).resolves.toEqual([]);
  });
});
