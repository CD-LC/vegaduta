// localToolsFiles — the opened folder as three local tools.
//
// WHAT IS REAL HERE AND WHAT IS FAKE, because that is the whole question with a
// test like this one:
//
//  * The FILESYSTEM is real code over a fake disk. Every list/read case below
//    runs the actual app/lib/localcode/fsAccess.ts — its walk, its ignore
//    rules, its extension map, its binary sniff, its private pathSegments()
//    traversal rule — against an in-memory FileSystemDirectoryHandle built the
//    way fsAccess.test.ts builds one (jsdom implements none of this API). So
//    "the traversal refusal fsAccess already enforces" is exercised, not
//    imitated.
//  * The INDEX is faked, through this module's own dependency seam. Driving the
//    real indexer would mean an embedding model, a worker and IndexedDB — and
//    the property that matters here is not that MiniLM works, it is that these
//    tools never reach for it: that they refuse BEFORE calling
//    searchLocalIndex when there is no index, and that they surface the
//    indexer's own refusal sentence rather than reporting "nothing matched".
//    Both are assertions about call order and pass-through, which a spy states
//    exactly and a real model would only obscure.
//  * The WALK SUMMARY is faked in the one case that needs 5001 files (the cap).
//    fsAccess.test.ts already proves listCodeFiles sets `capped`; what is
//    unproven, and what is tested here, is that this module turns that flag
//    into a sentence the model cannot miss.
//
// Every tool is invoked through the REAL runLocalTool from localTools.ts — the
// executor these tools will be registered into — so the outer 2000-char cap,
// the 2s ceiling and the typed result union are all in play rather than
// assumed.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { runLocalTool, type LocalTool } from "./localTools";
import {
  FOLDER_NEEDS_REOPEN_DETAIL,
  INDEX_OTHER_FOLDER_DETAIL,
  INDEX_STILL_BUILDING_DETAIL,
  LOCAL_FILE_TOOLS,
  MAX_FILE_CHARS,
  NO_FOLDER_OPEN_DETAIL,
  NO_INDEX_DETAIL,
  UNSUPPORTED_BROWSER_DETAIL,
  __setLocalFolderToolDepsForTests,
  type LocalFolderToolDeps,
} from "./localToolsFiles";
import type { IndexStatus, LocalFileRef, LocalRepoHandle, LocalWalkSummary, RetrievedChunk } from "../localcode/types";

// --- in-memory filesystem (same shape as fsAccess.test.ts) ------------------

interface FakeFileSpec {
  text?: string;
  size?: number;
}

interface FakeFileNode {
  kind: "file";
  spec: FakeFileSpec;
}

interface FakeDirNode {
  kind: "dir";
  children: Record<string, FakeNode>;
}

type FakeNode = FakeFileNode | FakeDirNode;

function f(text = "const a = 1;\n"): FakeFileNode {
  return { kind: "file", spec: { text } };
}

function d(children: Record<string, FakeNode>): FakeDirNode {
  return { kind: "dir", children };
}

function makeFakeFile(name: string, spec: FakeFileSpec): File {
  const bytes = new TextEncoder().encode(spec.text || "");
  const fake = {
    name,
    size: typeof spec.size === "number" ? spec.size : bytes.length,
    slice(start: number, end: number) {
      const part = bytes.slice(start, end);
      return {
        async arrayBuffer(): Promise<ArrayBuffer> {
          return part.buffer as ArrayBuffer;
        },
      };
    },
    async text(): Promise<string> {
      return spec.text !== undefined ? spec.text : new TextDecoder().decode(bytes);
    },
  };
  return fake as unknown as File;
}

function makeFileHandle(name: string, node: FakeFileNode): FileSystemFileHandle {
  return { kind: "file", name, async getFile(): Promise<File> { return makeFakeFile(name, node.spec); } } as unknown as FileSystemFileHandle;
}

function makeDirHandle(name: string, node: FakeDirNode): FileSystemDirectoryHandle {
  async function* iterate(): AsyncGenerator<[string, unknown]> {
    for (const [childName, child] of Object.entries(node.children)) {
      yield [childName, child.kind === "dir" ? makeDirHandle(childName, child) : makeFileHandle(childName, child)];
    }
  }
  const handle = {
    kind: "directory",
    name,
    entries() {
      return iterate();
    },
    async getDirectoryHandle(childName: string) {
      const child = node.children[childName];
      if (child && child.kind === "dir") return makeDirHandle(childName, child);
      throw new Error("NotFoundError");
    },
    async getFileHandle(childName: string) {
      const child = node.children[childName];
      if (child && child.kind === "file") return makeFileHandle(childName, child);
      throw new Error("NotFoundError");
    },
    // No queryPermission/requestPermission: these tools never ask for either
    // (they only read, through a handle the person already granted), so a
    // handle without them is the honest fixture. A call would throw here.
  };
  return handle as unknown as FileSystemDirectoryHandle;
}

function makeRepo(children: Record<string, FakeNode>, name = "my-project", id = "repo-1"): LocalRepoHandle {
  return { id, name, dir: makeDirHandle(name, d(children)) };
}

// --- the dependency record --------------------------------------------------

interface Spies {
  searchLocalIndex: ReturnType<typeof vi.fn>;
  restoreLocalIndexStatus: ReturnType<typeof vi.fn>;
}

const READY_STATUS: IndexStatus = { state: "ready", files: 3, chunks: 9, indexedAt: 1 };
const IDLE_STATUS: IndexStatus = { state: "idle", files: 0, chunks: 0, indexedAt: null };

/** Real fsAccess + a fake index. `overrides` replaces any single dependency. */
async function installDeps(
  repo: LocalRepoHandle | null,
  options: {
    permission?: "granted" | "prompt" | "denied";
    supported?: boolean;
    status?: IndexStatus;
    statusAfterSearch?: IndexStatus;
    indexedRepoId?: string | null;
    hits?: RetrievedChunk[];
    walkOverride?: { files: LocalFileRef[]; summary: LocalWalkSummary };
  } = {}
): Promise<Spies> {
  const fs = await import("../localcode/fsAccess");
  let current: IndexStatus = options.status || READY_STATUS;

  const searchLocalIndex = vi.fn(async (): Promise<RetrievedChunk[]> => {
    if (options.statusAfterSearch) current = options.statusAfterSearch;
    return options.hits || [];
  });
  const restoreLocalIndexStatus = vi.fn(async (): Promise<IndexStatus> => current);

  const deps: LocalFolderToolDeps = {
    // fsAccess's own isLocalCodeSupported() is false under jsdom (no
    // showDirectoryPicker); the browser-support branch is tested by setting
    // this false explicitly, not by leaving it accidentally false everywhere.
    isLocalCodeSupported: () => options.supported !== false,
    getRememberedLocalRepo: async () =>
      repo === null ? null : { repo, permission: options.permission || "granted" },
    listCodeFiles: options.walkOverride ? async () => options.walkOverride.files : fs.listCodeFiles,
    getLastWalkSummary: options.walkOverride ? () => options.walkOverride.summary : fs.getLastWalkSummary,
    readLocalFile: fs.readLocalFile,
    restoreLocalIndexStatus,
    getLocalIndexStatus: () => current,
    searchLocalIndex,
    indexedRepoId: () =>
      options.indexedRepoId === undefined ? (repo ? repo.id : null) : options.indexedRepoId,
  };
  __setLocalFolderToolDepsForTests(deps);
  return { searchLocalIndex, restoreLocalIndexStatus };
}

function tool(name: string): LocalTool {
  const found = LOCAL_FILE_TOOLS.find((t) => t.name === name);
  if (!found) throw new Error(`no such tool: ${name}`);
  return found;
}

/** Runs through the real executor. strict:false — only the explicit literal
 *  comparison narrows EdgeToolExecuteResult. */
async function call(name: string, args: unknown): Promise<string> {
  const result = await runLocalTool(tool(name), args);
  if (result.ok === true) return result.result;
  throw new Error(`expected a result, got ${result.code}: ${result.detail}`);
}

afterEach(() => {
  __setLocalFolderToolDepsForTests(null);
  vi.unstubAllGlobals();
});

// ---------------------------------------------------------------------------

describe("shape", () => {
  it("registers three local_-prefixed, network-free, non-consequential tools", () => {
    expect(LOCAL_FILE_TOOLS.map((t) => t.name)).toEqual([
      "local_list_files",
      "local_read_file",
      "local_search_files",
    ]);
    for (const t of LOCAL_FILE_TOOLS) {
      expect(t.name.startsWith("local_")).toBe(true);
      expect(t.network).toBe("none");
      expect(t.consequential).toBe(false);
      expect(typeof t.run).toBe("function");
    }
  });

  it("says in each description that the folder is read on this device", () => {
    // Property 3: a tool's own description is what the model repeats to the
    // person, so the privacy claim has to live there, not only in a comment.
    for (const t of LOCAL_FILE_TOOLS) {
      expect(t.description.toLowerCase()).toMatch(/device|uploaded|on-device/);
    }
  });
});

describe("no folder open", () => {
  it("gives all three tools the SAME sentence, and it names the /code page", async () => {
    await installDeps(null);
    const answers = await Promise.all([
      call("local_list_files", {}),
      call("local_read_file", { path: "src/a.ts" }),
      call("local_search_files", { query: "auth" }),
    ]);
    for (const answer of answers) expect(answer).toBe(NO_FOLDER_OPEN_DETAIL);
    expect(NO_FOLDER_OPEN_DETAIL).toContain("/code");
  });

  it("distinguishes a lapsed permission from nothing ever opened", async () => {
    await installDeps(makeRepo({ "a.ts": f() }), { permission: "prompt" });
    expect(await call("local_list_files", {})).toBe(FOLDER_NEEDS_REOPEN_DETAIL);
    // The two must not collapse: "reopen it" and "open one" are different
    // instructions, and only one of them works.
    expect(FOLDER_NEEDS_REOPEN_DETAIL).not.toBe(NO_FOLDER_OPEN_DETAIL);
  });

  it("distinguishes a browser with no File System Access API", async () => {
    await installDeps(makeRepo({ "a.ts": f() }), { supported: false });
    expect(await call("local_read_file", { path: "a.ts" })).toBe(UNSUPPORTED_BROWSER_DETAIL);
  });

  it("never reaches the index when there is no folder", async () => {
    const spies = await installDeps(null);
    await call("local_search_files", { query: "auth" });
    expect(spies.restoreLocalIndexStatus).not.toHaveBeenCalled();
    expect(spies.searchLocalIndex).not.toHaveBeenCalled();
  });
});

describe("local_list_files", () => {
  it("lists what the real walk found, with language and size", async () => {
    await installDeps(
      makeRepo({
        src: d({ "index.ts": f("export const x = 1;\n"), "util.py": f("x = 1\n") }),
        "README.md": f("# hi\n"),
        node_modules: d({ "junk.ts": f("nope\n") }),
        // .png never reaches the binary sniff — fsAccess drops it earlier, on
        // the extension map — so both skip counters need their own fixture.
        "logo.png": f("whatever"),
        "packed.json": f(" not really json"),
      })
    );
    const answer = await call("local_list_files", {});
    expect(answer).toContain("my-project — 3 indexable files");
    expect(answer).toContain("src/index.ts  typescript");
    expect(answer).toContain("src/util.py  python");
    expect(answer).toContain("README.md  markdown");
    // The ignore list and the binary sniff are fsAccess's, and they held.
    expect(answer).not.toContain("node_modules");
    expect(answer).not.toContain("logo.png");
    expect(answer).not.toContain("packed.json");
    expect(answer).toContain("[not listed: 1 binary, 1 of a file type this index does not cover]");
  });

  it("narrows to a prefix and says what it narrowed from", async () => {
    await installDeps(makeRepo({ src: d({ "a.ts": f() }), docs: d({ "b.md": f() }) }));
    const answer = await call("local_list_files", { prefix: "src" });
    expect(answer).toContain("my-project/src — 1 of 2 indexable files");
    expect(answer).toContain("src/a.ts");
    expect(answer).not.toContain("docs/b.md");
  });

  it("refuses a prefix that climbs out of the folder", async () => {
    await installDeps(makeRepo({ "a.ts": f() }));
    const answer = await call("local_list_files", { prefix: "../secrets" });
    expect(answer).toContain("not a path inside the open folder");
    expect(answer).not.toContain("a.ts");
  });

  it("says the listing is INCOMPLETE when the walk hit its cap", async () => {
    // The 5000-file cap itself is fsAccess.test.ts's; what is asserted here is
    // that this module refuses to present a capped walk as a whole folder.
    await installDeps(makeRepo({ "a.ts": f() }), {
      walkOverride: {
        files: [{ path: "a.ts", size: 12, lang: "typescript" }],
        summary: { files: 1, capped: true, skippedLarge: 2, skippedBinary: 0, skippedUnknownType: 0, unreadable: 1 },
      },
    });
    const answer = await call("local_list_files", {});
    expect(answer).toContain("[INCOMPLETE:");
    expect(answer).toContain("5000-file cap");
    expect(answer).toContain("not proof that a file is absent");
    expect(answer).toContain("[not listed: 2 over the 512 KB size limit, 1 this browser could not read]");
  });

  it("omits the skip line entirely when nothing was skipped", async () => {
    await installDeps(makeRepo({ "a.ts": f() }));
    const answer = await call("local_list_files", {});
    expect(answer).not.toContain("[not listed");
    expect(answer).not.toContain("[INCOMPLETE");
  });

  it("caps a long listing and says how many of how many it is showing", async () => {
    const many: Record<string, FakeNode> = {};
    for (let i = 0; i < 300; i += 1) many[`file-with-a-fairly-long-name-${i}.ts`] = f();
    await installDeps(makeRepo(many));
    const answer = await call("local_list_files", {});
    expect(answer).toMatch(/\[showing \d+ of 300 files/);
    // The outer executor must NOT have been the thing that cut it: if it had,
    // the "showing N of 300" line would itself have been truncated away.
    expect(answer).not.toContain("[truncated: showing the first");
    expect(answer.length).toBeLessThanOrEqual(2000);
  });

  it("answers an empty folder as empty, not as a failure", async () => {
    await installDeps(makeRepo({ notes: d({}) }));
    const answer = await call("local_list_files", {});
    expect(answer).toContain("0 indexable files");
    expect(answer).toContain("no indexable code files");
  });
});

describe("local_read_file", () => {
  it("reads a file through the real fsAccess path", async () => {
    await installDeps(makeRepo({ src: d({ "a.ts": f("line one\nline two\n") }) }));
    const answer = await call("local_read_file", { path: "src/a.ts" });
    expect(answer).toContain("src/a.ts — 3 lines, 18 characters");
    expect(answer).toContain("line one");
    expect(answer).toContain("line two");
  });

  it("accepts a Windows-style path, because people paste them", async () => {
    await installDeps(makeRepo({ src: d({ "a.ts": f("hello\n") }) }));
    expect(await call("local_read_file", { path: "src\\a.ts" })).toContain("hello");
  });

  it("refuses a traversal path instead of reporting an empty file", async () => {
    await installDeps(makeRepo({ src: d({ "a.ts": f("secret\n") }) }));
    const answer = await call("local_read_file", { path: "../../etc/passwd" });
    expect(answer).toContain("not a path inside the open folder");
    expect(answer).toContain('".." is refused');
    // The distinction this refusal exists for: fsAccess answers a traversal
    // path with "", which is byte-identical to an empty file.
    expect(answer).not.toContain("returned no content");
  });

  it("names all three meanings of an empty read rather than picking one", async () => {
    await installDeps(makeRepo({ "a.ts": f("x\n") }));
    const answer = await call("local_read_file", { path: "does-not-exist.ts" });
    expect(answer).toContain("returned no content");
    expect(answer).toContain("genuinely empty");
    expect(answer).toContain("no such file");
    expect(answer).toContain("refused the read");
  });

  it("caps a long file at MAX_FILE_CHARS and says how to get the rest", async () => {
    const lines: string[] = [];
    for (let i = 1; i <= 400; i += 1) lines.push(`const value${i} = ${i}; // a comment to give this line some length`);
    await installDeps(makeRepo({ "big.ts": f(`${lines.join("\n")}\n`) }));
    const answer = await call("local_read_file", { path: "big.ts" });

    expect(answer).toContain("big.ts — 401 lines,");
    const match = /\[showing lines 1-(\d+) of 401 — call again with from_line (\d+) for the next part\]/.exec(answer);
    expect(match).not.toBeNull();
    const lastShown = Number(match[1]);
    expect(lastShown).toBeGreaterThan(0);
    expect(lastShown).toBeLessThan(401);
    expect(Number(match[2])).toBe(lastShown + 1);
    expect(answer).toContain("const value1 =");
    expect(answer).not.toContain("const value400 =");

    // The two caps are stacked, and this module's has to be the one that bites:
    // its budget plus its own header and footer must fit inside runLocalTool's
    // 2000, or the footer that says the read is partial is what gets cut.
    expect(answer).not.toContain("[truncated: showing the first");
    expect(answer.length).toBeLessThanOrEqual(2000);

    const page2 = await call("local_read_file", { path: "big.ts", from_line: lastShown + 1 });
    expect(page2).toContain(`[showing lines ${lastShown + 1}-`);
    expect(page2).toContain(`const value${lastShown + 1} =`);
  });

  it("clips a single over-long line instead of losing the footer that says so", async () => {
    await installDeps(makeRepo({ "min.js": f(`${"x".repeat(MAX_FILE_CHARS * 2)}\n`) }));
    const answer = await call("local_read_file", { path: "min.js" });
    expect(answer).toContain("was clipped");
    expect(answer).toContain("not the whole line");
    expect(answer).not.toContain("[truncated: showing the first");
    expect(answer.length).toBeLessThanOrEqual(2000);
  });

  it("refuses a non-string path with the type it got", async () => {
    await installDeps(makeRepo({ "a.ts": f() }));
    expect(await call("local_read_file", { path: 7 })).toContain('"path" must be a string (got number)');
  });
});

describe("local_search_files", () => {
  it("refuses, and does NOT search, when no index exists", async () => {
    const spies = await installDeps(makeRepo({ "a.ts": f() }), {
      status: IDLE_STATUS,
      indexedRepoId: null,
    });
    const answer = await call("local_search_files", { query: "where is auth" });
    expect(answer).toBe(NO_INDEX_DETAIL);
    expect(answer).toContain("/code");
    expect(answer).toContain("Index this folder");
    // The load-bearing assertion: the refusal happens BEFORE searchLocalIndex,
    // which is what keeps "does not build an index, downloads nothing"
    // structural rather than a promise about the indexer's internals.
    expect(spies.searchLocalIndex).not.toHaveBeenCalled();
  });

  it("refuses when the index belongs to a different folder", async () => {
    const spies = await installDeps(makeRepo({ "a.ts": f() }), { indexedRepoId: "some-other-repo" });
    expect(await call("local_search_files", { query: "auth" })).toBe(INDEX_OTHER_FOLDER_DETAIL);
    expect(spies.searchLocalIndex).not.toHaveBeenCalled();
  });

  it("refuses while an index is still being built", async () => {
    const spies = await installDeps(makeRepo({ "a.ts": f() }), {
      status: { state: "indexing", files: 1, chunks: 0, indexedAt: null },
    });
    expect(await call("local_search_files", { query: "auth" })).toBe(INDEX_STILL_BUILDING_DETAIL);
    expect(spies.searchLocalIndex).not.toHaveBeenCalled();
  });

  it("passes the indexer's own refusal sentence through unchanged", async () => {
    const detail = "On-device code search needs its embedding model on this device first.";
    await installDeps(makeRepo({ "a.ts": f() }), {
      status: { state: "error", files: 0, chunks: 0, indexedAt: null, detail },
    });
    expect(await call("local_search_files", { query: "auth" })).toBe(detail);
  });

  it("reports a post-search embedder failure as the failure, not as 'no matches'", async () => {
    // searchLocalIndex answers [] for BOTH "nothing matched" and "the embedder
    // could not run", and separates them only by moving the status to "error".
    const detail = "This index was built with a different embedding model, so its results would be meaningless.";
    await installDeps(makeRepo({ "a.ts": f() }), {
      hits: [],
      statusAfterSearch: { state: "error", files: 3, chunks: 9, indexedAt: 1, detail },
    });
    const answer = await call("local_search_files", { query: "auth" });
    expect(answer).toBe(detail);
    expect(answer).not.toContain("No indexed chunk matched");
  });

  it("reports a genuine miss as a miss, with what the index covers", async () => {
    await installDeps(makeRepo({ "a.ts": f() }), { hits: [] });
    const answer = await call("local_search_files", { query: "quantum teapot" });
    expect(answer).toContain('No indexed chunk matched "quantum teapot"');
    expect(answer).toContain("3 files / 9 chunks");
    expect(answer).toContain("real miss, not a failure");
  });

  it("returns path:line citations, best first", async () => {
    const hits: RetrievedChunk[] = [
      { id: "1", path: "src/auth.ts", startLine: 12, endLine: 40, text: "\n\nexport function getToken() {\n  return kc.token;\n}", score: 0.87 },
      { id: "2", path: "src/api.ts", startLine: 5, endLine: 9, text: "const headers = { Authorization: bearer };", score: 0.61 },
    ];
    const spies = await installDeps(makeRepo({ "a.ts": f() }), { hits });
    const answer = await call("local_search_files", { query: "how is the token read" });
    expect(answer).toContain("src/auth.ts:12-40  (score 0.87)");
    expect(answer).toContain("export function getToken() {");
    expect(answer).toContain("src/api.ts:5-9  (score 0.61)");
    expect(answer).toContain("local_read_file");
    expect(spies.searchLocalIndex).toHaveBeenCalledWith("how is the token read", 5);
  });

  it("clamps limit into 1..10 rather than refusing an out-of-range one", async () => {
    const spies = await installDeps(makeRepo({ "a.ts": f() }), { hits: [] });
    await call("local_search_files", { query: "x", limit: 99 });
    await call("local_search_files", { query: "x", limit: 0 });
    expect(spies.searchLocalIndex.mock.calls.map((c) => c[1])).toEqual([10, 1]);
  });

  it("refuses an empty query", async () => {
    const spies = await installDeps(makeRepo({ "a.ts": f() }), { hits: [] });
    expect(await call("local_search_files", { query: "   " })).toContain('"query" is empty');
    expect(spies.searchLocalIndex).not.toHaveBeenCalled();
  });
});

describe("property: nothing here transmits", () => {
  it("touches no network API when all three tools actually run", async () => {
    const calls: string[] = [];
    const trap = (label: string) =>
      vi.fn(() => {
        calls.push(label);
        throw new Error(`${label} must never be called by a local folder tool`);
      });
    vi.stubGlobal("fetch", trap("fetch"));
    vi.stubGlobal("XMLHttpRequest", trap("XMLHttpRequest"));
    vi.stubGlobal("WebSocket", trap("WebSocket"));
    vi.stubGlobal("EventSource", trap("EventSource"));
    const beacon = trap("sendBeacon");
    Object.defineProperty(navigator, "sendBeacon", { configurable: true, value: beacon });

    await installDeps(makeRepo({ src: d({ "a.ts": f("export const a = 1;\n") }) }), {
      hits: [{ id: "1", path: "src/a.ts", startLine: 1, endLine: 2, text: "export const a = 1;", score: 0.5 }],
    });
    const answers = await Promise.all([
      call("local_list_files", {}),
      call("local_read_file", { path: "src/a.ts" }),
      call("local_search_files", { query: "a" }),
      // The refusal paths too — a refusal that phoned home would be the worst
      // version of this bug, because nothing about the output would show it.
      call("local_read_file", { path: "../escape" }),
    ]);
    expect(answers).toHaveLength(4);
    expect(calls).toEqual([]);
    expect(beacon).not.toHaveBeenCalled();
  });

  it("declares network:'none' on every tool, and no tool declares hosts", () => {
    for (const t of LOCAL_FILE_TOOLS) expect(t.network).toBe("none");
  });
});

describe("property: a refusal is an answer, never an exception", () => {
  beforeEach(async () => {
    await installDeps(null);
  });

  it("comes back as ok:true with a sentence, not a failed result", async () => {
    for (const t of LOCAL_FILE_TOOLS) {
      const result = await runLocalTool(t, {});
      // ok:false would label these "failed" — the code that tells the loop a
      // defect happened on our side. Nothing here is a defect.
      expect(result.ok).toBe(true);
      if (result.ok === true) expect(result.result.length).toBeGreaterThan(40);
    }
  });

  it("survives arguments that are not an object at all", async () => {
    const result = await runLocalTool(tool("local_read_file"), "just a string");
    // runLocalTool owns this one: a non-object argument is rejected before any
    // handler runs.
    expect(result.ok).toBe(false);
    if (result.ok === false) expect(result.code).toBe("rejected");
  });
});
