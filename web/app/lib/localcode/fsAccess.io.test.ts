// localcode/fsAccess — reading, write permission, writing, remembering the
// folder and surviving a reload, against the in-memory filesystem and fake
// indexedDB in fsAccess.testkit.ts. See fsAccess.test.ts's header for the
// approach (resetModules + a fresh import per test).

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  type FakeFileNode,
  type FakeDirNode,
  f,
  d,
  makeRepo,
  installDirectoryPicker,
  removeDirectoryPicker,
  installFakeIndexedDb,
  importFsAccess,
} from "./fsAccess.testkit";

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

describe("localcode/fsAccess — reading", () => {
  it("resolves a nested path relative to the picked folder", async () => {
    const { repo } = makeRepo({ src: d({ app: d({ "main.ts": f({ text: "export const x = 1;" }) }) }) });
    const { readLocalFile } = await importFsAccess();

    await expect(readLocalFile(repo, "src/app/main.ts")).resolves.toBe("export const x = 1;");
  });

  it("refuses a path that tries to climb out of the picked folder", async () => {
    const { repo } = makeRepo({ "a.ts": f({ text: "inside" }) });
    const { readLocalFile } = await importFsAccess();

    await expect(readLocalFile(repo, "../../etc/passwd")).resolves.toBe("");
    await expect(readLocalFile(repo, "src/../../a.ts")).resolves.toBe("");
  });

  it("a missing file is an empty string, not a rejection", async () => {
    const { repo } = makeRepo({ "a.ts": f() });
    const { readLocalFile } = await importFsAccess();

    await expect(readLocalFile(repo, "nope.ts")).resolves.toBe("");
  });
});

describe("localcode/fsAccess — write permission", () => {
  it("asks for readwrite, and does not prompt when it is already granted", async () => {
    const { repo, calls } = makeRepo({ "a.ts": f() }, { permission: { query: "granted", request: "granted" } });
    const { ensureWritePermission } = await importFsAccess();

    await expect(ensureWritePermission(repo)).resolves.toBe(true);
    expect(calls).toEqual([{ method: "query", mode: "readwrite" }]);
  });

  it("prompts when the answer is 'prompt', and honours the grant", async () => {
    const { repo, calls } = makeRepo({ "a.ts": f() }, { permission: { query: "prompt", request: "granted" } });
    const { ensureWritePermission } = await importFsAccess();

    await expect(ensureWritePermission(repo)).resolves.toBe(true);
    expect(calls.map((call) => call.method)).toEqual(["query", "request"]);
  });

  it("does not re-ask after a denial — Chromium answers 'denied' without ever showing a dialog", async () => {
    const { repo, calls } = makeRepo({ "a.ts": f() }, { permission: { query: "denied", request: "granted" } });
    const { ensureWritePermission } = await importFsAccess();

    await expect(ensureWritePermission(repo)).resolves.toBe(false);
    expect(calls.map((call) => call.method)).toEqual(["query"]);
  });

  it("a handle with no permission API is not treated as a denial — the write itself is the gate", async () => {
    const { repo } = makeRepo({ "a.ts": f() });
    const { ensureWritePermission } = await importFsAccess();

    await expect(ensureWritePermission(repo)).resolves.toBe(true);
  });
});

describe("localcode/fsAccess — writing", () => {
  it("writes through, once write permission is granted", async () => {
    const { repo, root } = makeRepo(
      { src: d({ "main.ts": f({ text: "old" }) }) },
      { permission: { query: "granted", request: "granted" } }
    );
    const { writeLocalFileResult } = await importFsAccess();

    const result = await writeLocalFileResult(repo, "src/main.ts", "new");

    expect(result.ok === true).toBe(true);
    expect((root.children.src as FakeDirNode).children["main.ts"]).toEqual({
      kind: "file",
      spec: { text: "new", bytes: undefined, size: undefined },
    });
  });

  it("reports a denial instead of silently losing the edit", async () => {
    const { repo, root } = makeRepo({ "main.ts": f({ text: "old" }) }, { permission: { query: "denied" } });
    const { writeLocalFileResult } = await importFsAccess();

    const result = await writeLocalFileResult(repo, "main.ts", "new");

    expect(result).toEqual({ ok: false, reason: "write-permission-denied" });
    expect((root.children["main.ts"] as FakeFileNode).spec.text).toBe("old");
  });

  it("refuses a traversing path before it ever asks for permission", async () => {
    const { repo, calls } = makeRepo({ "a.ts": f() }, { permission: { query: "granted", request: "granted" } });
    const { writeLocalFileResult } = await importFsAccess();

    const result = await writeLocalFileResult(repo, "../outside.ts", "x");

    expect(result).toEqual({ ok: false, reason: "invalid-path" });
    expect(calls).toEqual([]);
  });

  it("the contract-shaped writeLocalFile swallows failure rather than throwing", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { permission: { query: "denied" } });
    const { writeLocalFile } = await importFsAccess();

    await expect(writeLocalFile(repo, "a.ts", "x")).resolves.toBeUndefined();
  });
});

describe("localcode/fsAccess — remembering the folder", () => {
  // jsdom has no indexedDB, which is exactly the degraded case a private window
  // or a storage-blocked profile hits: "reopen" is unavailable, and every other
  // part of the feature still works.
  it("persistence degrades to a no-op where indexedDB is unavailable", async () => {
    const { repo } = makeRepo({ "a.ts": f() });
    const { rememberLocalRepo, getRememberedLocalRepo, forgetLocalRepo, reopenLocalRepo } = await importFsAccess();

    await expect(rememberLocalRepo(repo)).resolves.toBeUndefined();
    await expect(getRememberedLocalRepo()).resolves.toBeNull();
    await expect(reopenLocalRepo()).resolves.toBeNull();
    await expect(forgetLocalRepo()).resolves.toBeUndefined();
  });

  it("picking still succeeds when the handle cannot be persisted", async () => {
    const { repo } = makeRepo({ "a.ts": f() });
    installDirectoryPicker(async () => repo.dir);
    const { pickLocalRepo } = await importFsAccess();

    const picked = await pickLocalRepo();

    expect(picked?.name).toBe("my-project");
    expect(picked?.id).toBeTruthy();
  });
});

describe("localcode/fsAccess — surviving a reload (against a stubbed indexedDB)", () => {
  it("stores the handle and hands it back with the browser's current standing", async () => {
    const { repo, calls } = makeRepo({ "a.ts": f() }, { permission: { query: "granted" } });
    installFakeIndexedDb();
    const { rememberLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    await rememberLocalRepo(repo);
    const remembered = await getRememberedLocalRepo();

    expect(remembered?.repo.id).toBe("repo-1");
    expect(remembered?.repo.name).toBe("my-project");
    expect(remembered?.repo.dir).toBe(repo.dir);
    expect(remembered?.permission).toBe("granted");
    // Probed for READ and never prompted: this runs on mount, where there is no
    // user gesture and requestPermission would only resolve "prompt" anyway.
    expect(calls).toEqual([{ method: "query", mode: "read" }]);
  });

  it("pickLocalRepo persists the folder it just picked", async () => {
    const { repo } = makeRepo({ "a.ts": f() });
    installDirectoryPicker(async () => repo.dir);
    installFakeIndexedDb();
    const { pickLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    const picked = await pickLocalRepo();
    const remembered = await getRememberedLocalRepo();

    expect(picked?.id).toBeTruthy();
    expect(remembered?.repo.id).toBe(picked?.id);
  });

  it("answers 'prompt' when a restored handle cannot say — including when asking it throws", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { permission: { query: "granted" }, throwOnQueryPermission: true });
    installFakeIndexedDb();
    const { rememberLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    await rememberLocalRepo(repo);

    // A throwing probe means the folder is probably gone. "prompt" lets the
    // person try; reopenLocalRepo() is what decides it is really dead.
    expect((await getRememberedLocalRepo())?.permission).toBe("prompt");
  });

  it("reopens a live folder, asking for read and not readwrite", async () => {
    const { repo, calls } = makeRepo({ "a.ts": f() }, { permission: { query: "prompt", request: "granted" } });
    installFakeIndexedDb();
    const { rememberLocalRepo, reopenLocalRepo } = await importFsAccess();

    await rememberLocalRepo(repo);
    const reopened = await reopenLocalRepo();

    expect(reopened?.dir).toBe(repo.dir);
    // Reopening is not consent to edit: nothing here asks for readwrite.
    expect(calls.map((call) => `${call.method}:${call.mode}`)).toEqual(["query:read", "query:read", "request:read"]);
  });

  it("forgets a stale handle on reopen, so the UI stops offering a folder that is gone", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { permission: { query: "granted" }, throwOnEntries: true });
    const { stores } = installFakeIndexedDb();
    const { rememberLocalRepo, reopenLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    await rememberLocalRepo(repo);
    // Pinned before the act, so this cannot pass by nothing ever being stored —
    // which is exactly how this test passed under jsdom's missing indexedDB.
    expect(await getRememberedLocalRepo()).not.toBeNull();

    expect(await reopenLocalRepo()).toBeNull();
    expect(stores.get("handles").has("repo")).toBe(false);
    expect(await getRememberedLocalRepo()).toBeNull();
  });

  it("KEEPS the handle when reopen is merely refused — a denial is not a dead folder", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { permission: { query: "denied", request: "granted" } });
    installFakeIndexedDb();
    const { rememberLocalRepo, reopenLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    await rememberLocalRepo(repo);

    expect(await reopenLocalRepo()).toBeNull();
    // Still on offer: the person can change their mind, and dropping the handle
    // would cost them the folder picker all over again.
    expect((await getRememberedLocalRepo())?.repo.id).toBe("repo-1");
  });

  it("forgetLocalRepo clears the record", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { permission: { query: "granted" } });
    installFakeIndexedDb();
    const { rememberLocalRepo, forgetLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    await rememberLocalRepo(repo);
    await forgetLocalRepo();

    expect(await getRememberedLocalRepo()).toBeNull();
  });

  it("ignores a stored row that is not a repo handle", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { permission: { query: "granted" } });
    const { stores } = installFakeIndexedDb();
    const { rememberLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    await rememberLocalRepo(repo);
    // A half-written row, or a row from an older shape of this feature.
    stores.get("handles").set("repo", { id: 1, name: null });

    expect(await getRememberedLocalRepo()).toBeNull();
  });

  it("an indexedDB that will not open degrades to the same no-op as having none", async () => {
    const { repo } = makeRepo({ "a.ts": f() });
    installFakeIndexedDb({ failOpen: true });
    const { rememberLocalRepo, getRememberedLocalRepo, reopenLocalRepo, forgetLocalRepo } = await importFsAccess();

    await expect(rememberLocalRepo(repo)).resolves.toBeUndefined();
    await expect(getRememberedLocalRepo()).resolves.toBeNull();
    await expect(reopenLocalRepo()).resolves.toBeNull();
    await expect(forgetLocalRepo()).resolves.toBeUndefined();
  });

  it("a write that aborts (quota, a closing db) is swallowed, not thrown", async () => {
    const { repo } = makeRepo({ "a.ts": f() }, { permission: { query: "granted" } });
    installFakeIndexedDb({ failWrites: true });
    const { rememberLocalRepo, getRememberedLocalRepo } = await importFsAccess();

    await expect(rememberLocalRepo(repo)).resolves.toBeUndefined();
    // Nothing to reopen, and no error anywhere: losing this costs one click.
    expect(await getRememberedLocalRepo()).toBeNull();
  });
});
