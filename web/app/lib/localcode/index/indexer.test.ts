// Tests for the local-code indexer.
//
// Three groups, because the module has three genuinely different risk surfaces:
//
//   1. "localcode/indexer" — status, incremental re-indexing, abort, the model
//      stamp. Driven through __setIndexerOverridesForTests, so no worker is
//      constructed and no model is downloaded.
//   2. "localcode/indexer — the no-download gate" — the real embedder path with
//      a FAKE Worker and a FAKE Cache API. This is where the feature's central
//      promise lives ("nothing downloads unless you click"), and it used to be
//      untestable because the gate read a localStorage flag; now it reads the
//      Cache API, which is a thing a test can fake. In the style of the sibling
//      fsAccess.test.ts / edge/webmcp.test.ts, every test here builds the
//      browser surface it needs and asserts what the module DOES with it.
//   3. "localcode/indexer — the IndexedDB store" — jsdom has no IndexedDB, so
//      this group stands up a small fake one. Read the honesty note above that
//      group before trusting it: it pins the store's CONTRACT, not Chrome's
//      real transaction semantics.
//
// Everything that touches the network is stubbed to throw, and two tests assert
// nothing on the build or search path calls it at all.
//
// One file per group: this file holds group 1; indexer.gate.test.ts group 2 and
// indexer.store.test.ts group 3. Shared fakes live in indexer.testkit.ts.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmbedSpy, REPO, createFileAccess, SOURCES, loadIndexer, poisonFetch } from "./indexer.testkit";

describe("localcode/indexer", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    poisonFetch();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("starts idle and reports nothing indexed", async () => {
    const mod = await loadIndexer(createEmbedSpy(), createFileAccess({}));
    expect(mod.getLocalIndexStatus()).toEqual({ state: "idle", files: 0, chunks: 0, indexedAt: null });
  });

  it("moves idle → indexing → ready and reports files done against the total", async () => {
    const embed = createEmbedSpy();
    const mod = await loadIndexer(embed, createFileAccess({ ...SOURCES }));
    const seen: any[] = [];
    const final = await mod.buildLocalIndex(REPO, (s: any) => seen.push(s));

    expect(final.state).toBe("ready");
    expect(final.files).toBe(3);
    expect(final.chunks).toBe(3);
    expect(typeof final.indexedAt).toBe("number");
    expect(mod.getLocalIndexStatus()).toEqual(final);

    expect(seen[0].state).toBe("indexing");
    expect(seen[seen.length - 1].state).toBe("ready");
    const indexing = seen.filter((s: any) => s.state === "indexing");
    expect(indexing.length).toBeGreaterThanOrEqual(3);
    // files-indexed never goes backwards, and the scan total is in the detail.
    let previous = -1;
    for (const step of indexing) {
      expect(step.files).toBeGreaterThanOrEqual(previous);
      previous = step.files;
    }
    expect(indexing[indexing.length - 1].detail).toBe("Scanned 3 of 3 files");
    expect(final.detail).toBe("Indexed 3 files");
    expect(embed.calls.length).toBe(3);
  });

  it("counts only the files it actually indexed, and says how many it skipped", async () => {
    // One file too big to embed, one that fails to read. Both move the scan
    // counter; neither may show up in the final "Indexed N files".
    const files = {
      ...SOURCES,
      "dist/bundle.js": "x".repeat(64),
      "src/vanished.ts": "export const gone = 1;\n",
    };
    const access = createFileAccess(files, { "dist/bundle.js": 5_000_000 });
    // Still listed by the walk, gone by the time it is read — the exact race
    // the build has to survive without ending.
    const read = access.readLocalFile.bind(access);
    access.readLocalFile = async (repo: any, path: string): Promise<string> => {
      if (path === "src/vanished.ts") throw new Error("NotFoundError");
      return read(repo, path);
    };
    const embed = createEmbedSpy();
    const mod = await loadIndexer(embed, access);

    const final = await mod.buildLocalIndex(REPO, () => {});
    expect(final.state).toBe("ready");
    expect(final.files).toBe(3);
    expect(final.chunks).toBe(3);
    expect(final.detail).toBe("Indexed 3 files (2 skipped)");
    // The oversized file was never read, let alone embedded.
    expect(embed.calls.flat().join("\n")).not.toContain("xxxx");
  });

  it("re-embeds only the files whose content changed", async () => {
    const embed = createEmbedSpy();
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(embed, access);
    await mod.buildLocalIndex(REPO, () => {});
    expect(embed.calls.length).toBe(3);

    // Nothing changed: a second build must embed nothing at all.
    embed.reset();
    const unchanged = await mod.buildLocalIndex(REPO, () => {});
    expect(embed.calls.length).toBe(0);
    expect(unchanged.state).toBe("ready");
    expect(unchanged.files).toBe(3);
    expect(unchanged.chunks).toBe(3);

    // One file changed: only that file is re-embedded.
    embed.reset();
    access.files["src/beta.ts"] = 'export function beta() {\n  return "beta handler v2";\n}\n';
    const after = await mod.buildLocalIndex(REPO, () => {});
    expect(embed.calls.length).toBe(1);
    expect(embed.calls[0].join("\n")).toContain("beta handler v2");
    expect(after.files).toBe(3);
    expect(after.chunks).toBe(3);
  });

  it("re-embeds everything when the embedding model changed under the index", async () => {
    const first = createEmbedSpy();
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(first, access, "model-a@8");
    await mod.buildLocalIndex(REPO, () => {});
    expect(first.calls.length).toBe(3);

    // Same bytes, different embedder. Reusing those vectors would mix two
    // vector spaces in one index and score them against each other.
    const second = createEmbedSpy();
    mod.__setIndexerOverridesForTests({ embed: second, files: access, embedSignature: "model-b@8" } as any);
    const after = await mod.buildLocalIndex(REPO, () => {});
    expect(second.calls.length).toBe(3);
    expect(after.state).toBe("ready");
    expect(after.files).toBe(3);
    expect(after.chunks).toBe(3);
  });

  it("refuses to search an index built by a different embedding model", async () => {
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(createEmbedSpy(), access, "model-a@8");
    await mod.buildLocalIndex(REPO, () => {});
    expect((await mod.searchLocalIndex("alpha")).length).toBeGreaterThan(0);

    mod.__setIndexerOverridesForTests({ embed: createEmbedSpy(), files: access, embedSignature: "model-b@8" } as any);
    // Confident nonsense is the failure mode being prevented here: without the
    // stamp this returns a ranked list scored across two vector spaces.
    expect(await mod.searchLocalIndex("alpha")).toEqual([]);
    expect(mod.getLocalIndexStatus().state).toBe("error");
    expect(mod.getLocalIndexStatus().detail).toBe(mod.MODEL_CHANGED_DETAIL);
  });

  it("prunes files that left the repo", async () => {
    const embed = createEmbedSpy();
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(embed, access);
    await mod.buildLocalIndex(REPO, () => {});

    delete access.files["src/gamma.ts"];
    const after = await mod.buildLocalIndex(REPO, () => {});
    expect(after.state).toBe("ready");
    expect(after.files).toBe(2);
    expect(after.chunks).toBe(2);

    const hits = await mod.searchLocalIndex("gamma", 10);
    expect(hits.some((h: any) => h.path === "src/gamma.ts")).toBe(false);
  });

  it("prunes a file that is still there but can no longer be indexed", async () => {
    // The gap this covers: a file skipped for size or lost to a read error is
    // NOT a file that left the repo, so it used to keep the record and chunks
    // an earlier build wrote — stale text, stale line numbers, and still
    // counted inside "Indexed N files" beside the "(skipped)" note that
    // contradicted it.
    const sizes: Record<string, number> = {};
    const access = createFileAccess({ ...SOURCES }, sizes);
    const mod = await loadIndexer(createEmbedSpy(), access);
    const first = await mod.buildLocalIndex(REPO, () => {});
    expect(first.files).toBe(3);
    expect(first.chunks).toBe(3);

    // Same path, same content, now past the size cap.
    sizes["src/gamma.ts"] = 5_000_000;
    const afterSize = await mod.buildLocalIndex(REPO, () => {});
    expect(afterSize.state).toBe("ready");
    expect(afterSize.files).toBe(2);
    expect(afterSize.chunks).toBe(2);
    expect(afterSize.detail).toBe("Indexed 2 files (1 skipped)");
    expect((await mod.searchLocalIndex("gamma", 10)).some((h: any) => h.path === "src/gamma.ts")).toBe(false);

    // ...and the same for a file that is listed but throws when read.
    const read = access.readLocalFile.bind(access);
    access.readLocalFile = async (repo: any, path: string): Promise<string> => {
      if (path === "src/beta.ts") throw new Error("NotReadableError");
      return read(repo, path);
    };
    const afterRead = await mod.buildLocalIndex(REPO, () => {});
    expect(afterRead.files).toBe(1);
    expect(afterRead.chunks).toBe(1);
    expect(afterRead.detail).toBe("Indexed 1 files (2 skipped)");
    expect((await mod.searchLocalIndex("beta", 10)).some((h: any) => h.path === "src/beta.ts")).toBe(false);
  });

  it("does not prune the files an aborted run never reached", async () => {
    // "Not seen" means "not indexable" only when the walk ran to the end. An
    // abort stops it early, so everything after the cancellation point is
    // simply unvisited and must keep the index it already had.
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(createEmbedSpy(), access);
    expect((await mod.buildLocalIndex(REPO, () => {})).files).toBe(3);

    const controller = new AbortController();
    const cancelled = await mod.buildLocalIndex(
      REPO,
      (s: any) => {
        if (s.state === "indexing" && s.files >= 1) controller.abort();
      },
      controller.signal
    );
    expect(cancelled.state).toBe("ready");
    expect(cancelled.files).toBe(3);
    expect(cancelled.chunks).toBe(3);
  });

  it("stops early on an aborted signal and keeps what it already indexed", async () => {
    const embed = createEmbedSpy();
    const mod = await loadIndexer(embed, createFileAccess({ ...SOURCES }));
    const controller = new AbortController();
    const final = await mod.buildLocalIndex(
      REPO,
      (s: any) => {
        // Cancel the moment the first file is done — the next loop iteration
        // sees the aborted signal.
        if (s.state === "indexing" && s.files >= 1) controller.abort();
      },
      controller.signal
    );

    expect(final.state).toBe("ready");
    expect(final.files).toBe(1);
    expect(final.chunks).toBe(1);
    expect(final.detail).toContain("Cancelled");
    expect(embed.calls.length).toBe(1);
  });

  it("leaves no orphan chunks when the abort lands mid-file", async () => {
    // A file big enough to need several embedding batches, cancelled from
    // inside the FIRST batch's embed call — so that batch still finishes and
    // writes its chunks, and the loop then sees the aborted signal before the
    // second batch starts (expect(batches).toBe(1) below pins that). The chunks
    // the first batch already wrote must go with the file record: a chunk
    // nothing accounts for is a chunk search can still return and the status
    // can never count.
    const big: string[] = [];
    for (let i = 1; i <= 400; i++) big.push(`  const zeta${i} = ${i};`);
    const access = createFileAccess({ "src/big.ts": big.join("\n") });
    const controller = new AbortController();
    let batches = 0;
    const embed = createEmbedSpy(() => {
      batches++;
      if (batches === 1) controller.abort();
    });
    const mod = await loadIndexer(embed, access);

    const final = await mod.buildLocalIndex(REPO, () => {}, controller.signal);
    expect(batches).toBe(1);
    // Nothing survived, so the honest terminal state is idle, not a "ready"
    // index of orphans.
    expect(final.state).toBe("idle");
    expect(final.files).toBe(0);
    expect(final.chunks).toBe(0);
    expect(await mod.searchLocalIndex("zeta", 50)).toEqual([]);
  });

  it("reports idle, not ready, when an abort lands before anything was indexed", async () => {
    const embed = createEmbedSpy();
    const mod = await loadIndexer(embed, createFileAccess({ ...SOURCES }));
    const controller = new AbortController();
    controller.abort();
    const final = await mod.buildLocalIndex(REPO, () => {}, controller.signal);
    expect(final.state).toBe("idle");
    expect(final.chunks).toBe(0);
    expect(embed.calls.length).toBe(0);
  });

  it("drops the in-memory vectors when a build fails part-way", async () => {
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(createEmbedSpy(), access);
    await mod.buildLocalIndex(REPO, () => {});
    expect((await mod.searchLocalIndex("alpha")).length).toBeGreaterThan(0);

    // Second build: alpha.ts changed, so its old chunks are deleted, and then
    // the embedder dies. A stale vector cache would keep scoring the deleted
    // rows and silently return fewer hits than asked for.
    access.files["src/alpha.ts"] = 'export function alpha() {\n  return "alpha handler v2";\n}\n';
    const failing = (async () => {
      throw new Error("the embedder fell over");
    }) as any;
    mod.__setIndexerOverridesForTests({ embed: failing, files: access } as any);
    const failed = await mod.buildLocalIndex(REPO, () => {});
    expect(failed.state).toBe("error");

    mod.__setIndexerOverridesForTests({ embed: createEmbedSpy(), files: access } as any);
    const hits = await mod.searchLocalIndex("alpha", 10);
    // The cache was rebuilt from the store, so every id it scored still exists.
    expect(hits.every((h: any) => h.text.length > 0)).toBe(true);
    expect(hits.some((h: any) => h.path === "src/alpha.ts")).toBe(false);
  });

  it("ranks search hits by cosine similarity and honours k", async () => {
    const embed = createEmbedSpy();
    const mod = await loadIndexer(embed, createFileAccess({ ...SOURCES }));
    await mod.buildLocalIndex(REPO, () => {});

    const hits = await mod.searchLocalIndex("where is the beta handler", 2);
    expect(hits).toHaveLength(2);
    expect(hits[0].path).toBe("src/beta.ts");
    expect(hits[0].score).toBeGreaterThan(hits[1].score);
    // A citation must carry a real, non-empty span of the file it names.
    expect(hits[0].startLine).toBe(1);
    expect(hits[0].endLine).toBeGreaterThanOrEqual(hits[0].startLine);
    expect(hits[0].text).toContain("beta handler");
  });

  it("returns nothing for an empty query or an empty index", async () => {
    const mod = await loadIndexer(createEmbedSpy(), createFileAccess({ ...SOURCES }));
    expect(await mod.searchLocalIndex("alpha")).toEqual([]);
    await mod.buildLocalIndex(REPO, () => {});
    expect(await mod.searchLocalIndex("   ")).toEqual([]);
  });

  it("never touches the network while indexing or searching", async () => {
    const spy = poisonFetch();
    const mod = await loadIndexer(createEmbedSpy(), createFileAccess({ ...SOURCES }));
    await mod.buildLocalIndex(REPO, () => {});
    await mod.searchLocalIndex("alpha");
    expect(spy).not.toHaveBeenCalled();
  });

  it("clearLocalIndex wipes the index and resets the status", async () => {
    const mod = await loadIndexer(createEmbedSpy(), createFileAccess({ ...SOURCES }));
    await mod.buildLocalIndex(REPO, () => {});
    expect((await mod.searchLocalIndex("alpha")).length).toBeGreaterThan(0);

    await mod.clearLocalIndex();
    expect(mod.getLocalIndexStatus()).toEqual({ state: "idle", files: 0, chunks: 0, indexedAt: null });
    expect(await mod.searchLocalIndex("alpha")).toEqual([]);
  });

  it("says so plainly when on-device AI is switched off for the deployment", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "";
    const mod = await import("./indexer");
    mod.__setIndexerOverridesForTests({ files: createFileAccess({ ...SOURCES }) } as any);
    const final = await mod.buildLocalIndex(REPO, () => {});
    expect(final.state).toBe("error");
    expect(final.detail).toBe(mod.EDGE_OFF_DETAIL);
  });

  it("restores a stored index's status without re-indexing", async () => {
    const embed = createEmbedSpy();
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(embed, access);
    await mod.buildLocalIndex(REPO, () => {});
    // restoreLocalIndexStatus is a no-op once a build has run in this tab...
    expect((await mod.restoreLocalIndexStatus()).state).toBe("ready");
    // ...and the stored active-repo id is what a reloaded tab would key off.
    expect(localStorage.getItem("localcode.index.activeRepo")).toBe(REPO.id);
  });

  it("refuses to run two builds at once", async () => {
    const embed = createEmbedSpy();
    const mod = await loadIndexer(embed, createFileAccess({ ...SOURCES }));
    const first = mod.buildLocalIndex(REPO, () => {});
    const second = await mod.buildLocalIndex(REPO, () => {});
    expect(second.state).toBe("error");
    expect(second.detail).toContain("already running");
    expect((await first).state).toBe("ready");
  });
});
