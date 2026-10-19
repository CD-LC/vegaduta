// Browser-local code assistant — the File System Access layer.
//
// This is the only module in localcode/ that touches the person's disk, and it
// is the boundary the rest of the feature is allowed to assume: paths in, text
// out, nothing crosses the network. There is not a single fetch() in this file
// and there must never be one — the free, signed-out, zero-quota promise of the
// feature is enforced here or nowhere.
//
// Two shapes of rule run through everything below:
//
//  1. Nothing throws at the caller. The File System Access API rejects for a
//     dozen unremarkable reasons — the person hit Cancel, the folder moved, the
//     tab lost its permission on reload, a file is locked by an editor — and
//     none of those is an exception in the product sense. Every exported
//     function answers with a typed empty/false/null value instead, so the UI
//     can render an honest state rather than an error boundary. Callers that do
//     need the reason get it from writeLocalFileResult()/getLastWalkSummary().
//
//  2. Permission is asked for at the last possible moment. pickLocalRepo()
//     requests "read" only; "readwrite" is requested by ensureWritePermission()
//     at the instant an edit is about to be applied. Asking for write at pick
//     time would be one extra, scarier Chrome prompt for a feature most people
//     will only ever read with, and that prompt's wording is exactly what makes
//     people back out.
//
// Browser reality: showDirectoryPicker() is Chromium-only and secure-context
// only. Firefox and Safari have no equivalent (Safari's OPFS is a sandboxed
// private filesystem, not the user's repo), so isLocalCodeSupported() is false
// there and every entry point degrades to a null/empty answer rather than
// pretending.

import type {
  DirectoryPickerWindow,
  LocalFileRef,
  LocalFsPermission,
  LocalRepoHandle,
  LocalWalkSummary,
  LocalWriteResult,
  PermissionCapableHandle,
  RememberedLocalRepo,
} from "./types";

/** Hard cap on the walk. A repo with more source files than this is past the
 *  point where a browser-side index is useful anyway, and an uncapped walk over
 *  a monorepo checkout locks the tab for minutes with no way out. */
export const MAX_LOCAL_FILES = 5000;

/** 512 KB. Above this a "source file" is almost always generated — a bundle, a
 *  lockfile, a checked-in minified vendor blob — and it would eat the index
 *  budget while contributing nothing a person would ask a question about. */
export const MAX_LOCAL_FILE_BYTES = 512 * 1024;

/** Exactly the set the feature spec names. Deliberately NOT "every dotted
 *  directory": .github holds workflow YAML that people do ask questions about,
 *  and a blanket dot-dir skip would silently drop it. */
export const IGNORED_DIRECTORIES: ReadonlySet<string> = new Set([
  "node_modules",
  ".git",
  "dist",
  "build",
  ".next",
  "target",
  "out",
  "coverage",
  ".venv",
  "__pycache__",
]);

/** git's own binary heuristic: a NUL byte anywhere in the first 8000 bytes.
 *  Cheap, no false positives on real source, and it catches what the extension
 *  allowlist cannot — a .json that is actually a packed blob, a .sql dump with
 *  binary columns, a UTF-16 file whose every other byte is NUL (which would
 *  otherwise chunk into garbage and poison retrieval). */
const BINARY_SNIFF_BYTES = 8000;

/** Extension -> language. The value is a LANGUAGE, not a file type: .tsx maps
 *  to "typescript" and .jsx to "javascript" so downstream chunking and
 *  prompting never have to know about four spellings of the same thing.
 *
 *  .mjs/.cjs/.mts/.cts go past the spec's list on purpose — a Next.js repo
 *  keeps next.config.mjs and half its tooling config in those, and omitting
 *  them would skip exactly the files people ask "how is this configured?" about. */
const LANGUAGE_BY_EXTENSION: Readonly<Record<string, string>> = {
  ts: "typescript",
  tsx: "typescript",
  mts: "typescript",
  cts: "typescript",
  js: "javascript",
  jsx: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  py: "python",
  java: "java",
  kt: "kotlin",
  go: "go",
  rs: "rust",
  rb: "ruby",
  php: "php",
  cs: "csharp",
  c: "c",
  h: "c",
  cpp: "cpp",
  hpp: "cpp",
  swift: "swift",
  scala: "scala",
  sql: "sql",
  sh: "shell",
  yaml: "yaml",
  yml: "yaml",
  json: "json",
  md: "markdown",
};

const DB_NAME = "vegaduta-localcode";
const DB_VERSION = 1;
const HANDLE_STORE = "handles";
const HANDLE_KEY = "repo";

/** Chromium keys its "last folder you picked" memory off this, so the picker
 *  reopens where the person left off instead of in their home directory. */
const PICKER_ID = "vegaduta-localcode";

function emptyWalkSummary(): LocalWalkSummary {
  return {
    files: 0,
    capped: false,
    skippedLarge: 0,
    skippedBinary: 0,
    skippedUnknownType: 0,
    unreadable: 0,
  };
}

let lastWalk: LocalWalkSummary = emptyWalkSummary();

/** What the most recent listCodeFiles() walk actually did. The UI must surface
 *  `capped` — see the LocalWalkSummary doc comment for why silence is worse
 *  than a visibly partial index. */
export function getLastWalkSummary(): LocalWalkSummary {
  return { ...lastWalk };
}

/** True only where the real picker exists: Chromium, secure context, top-level
 *  or same-origin frame. Safe to call during SSR (Next renders this tree on the
 *  server first) and on Firefox/Safari, where it is simply false. */
export function isLocalCodeSupported(): boolean {
  try {
    if (typeof window === "undefined") return false;
    return typeof (window as unknown as DirectoryPickerWindow).showDirectoryPicker === "function";
  } catch {
    // A cross-origin iframe can make even the property read throw. "Not
    // supported" is the honest answer there too.
    return false;
  }
}

function newRepoId(): string {
  try {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
  } catch {
    // Fall through — this id only has to be unique within one browser profile.
  }
  return `repo-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Show the directory picker. MUST be called from inside a user gesture (a
 * click) — Chromium rejects otherwise, and that rejection arrives here as a
 * plain null.
 *
 * Returns null for every non-success, cancel very much included: a person
 * hitting Escape is the most common outcome of this call and is not an error to
 * report anywhere.
 */
export async function pickLocalRepo(): Promise<LocalRepoHandle | null> {
  if (!isLocalCodeSupported()) return null;
  try {
    const picker = (window as unknown as DirectoryPickerWindow).showDirectoryPicker;
    if (typeof picker !== "function") return null;
    // Read-only at pick time; write is negotiated later by ensureWritePermission().
    const dir = await picker.call(window, { id: PICKER_ID, mode: "read" });
    if (!dir) return null;
    const repo: LocalRepoHandle = { id: newRepoId(), name: dir.name || "folder", dir };
    await rememberLocalRepo(repo);
    return repo;
  } catch {
    return null;
  }
}

function extensionOf(path: string): string {
  const slash = path.lastIndexOf("/");
  const name = slash >= 0 ? path.slice(slash + 1) : path;
  const dot = name.lastIndexOf(".");
  // dot === 0 is a dotfile with no extension (".gitignore"), not an extension.
  if (dot <= 0) return "";
  return name.slice(dot + 1).toLowerCase();
}

/** The language for a path, or "" when we do not index this kind of file.
 *  Extensionless files (Makefile, Dockerfile) fall out here: the index exists
 *  for code questions, and admitting every extensionless file is how a walk
 *  ends up full of LICENSE and CHANGELOG. */
export function languageForPath(path: string): string {
  const ext = extensionOf(path);
  if (!ext) return "";
  return LANGUAGE_BY_EXTENSION[ext] || "";
}

/** Three answers, not two. A head read that FAILS and a head read that finds
 *  NUL bytes both end in "do not index this" — sampling zero bytes is no basis
 *  for chunking a file — but they are different facts about the repo, and
 *  filing an I/O failure under skippedBinary sends whoever reads the summary
 *  hunting for a packed blob that was never there. */
type ContentKind = "text" | "binary" | "unreadable";

async function classifyContents(file: File): Promise<ContentKind> {
  try {
    const head = await file.slice(0, BINARY_SNIFF_BYTES).arrayBuffer();
    const bytes = new Uint8Array(head);
    for (let i = 0; i < bytes.length; i += 1) {
      if (bytes[i] === 0) return "binary";
    }
    return "text";
  } catch {
    return "unreadable";
  }
}

/** Could this entry still have added a file to the index? Answered WITHOUT
 *  touching the disk: it exists only to decide whether stopping here is a
 *  truncation worth telling the person about, and paying a getFile() per
 *  remaining entry once the budget is already spent would defeat the cap.
 *
 *  Deliberately optimistic about directories: a non-ignored folder MIGHT hold
 *  indexable files, so it counts as "more was dropped" even when it turns out
 *  to be empty. Over-reporting `capped` is the safe direction — the opposite
 *  mistake is claiming a complete index over a partial one, which is how a
 *  wrong answer becomes a trusted wrong answer. */
function couldStillIndex(name: string, kind: string, path: string): boolean {
  if (kind === "directory") return !IGNORED_DIRECTORIES.has(name);
  return languageForPath(path) !== "";
}

/**
 * Walk the picked folder and return every file worth indexing.
 *
 * Breadth-first through an explicit queue rather than recursion: a deeply
 * nested tree is exactly the input that blows a recursive walk's stack, and the
 * queue also makes the 5000-file cap one check in one loop instead of a flag
 * threaded through every return.
 *
 * Never throws. A folder we cannot enumerate (permission revoked mid-walk, a
 * deleted directory) is counted in the summary and skipped; the rest of the
 * walk still produces a usable index.
 */
export async function listCodeFiles(repo: LocalRepoHandle): Promise<LocalFileRef[]> {
  const summary = emptyWalkSummary();
  const files: LocalFileRef[] = [];
  if (!repo || !repo.dir) {
    lastWalk = summary;
    return files;
  }

  const queue: Array<{ dir: FileSystemDirectoryHandle; prefix: string }> = [{ dir: repo.dir, prefix: "" }];
  try {
    while (queue.length > 0 && files.length < MAX_LOCAL_FILES) {
      const current = queue.shift();
      if (!current) break;

      let entries: AsyncIterable<[string, FileSystemDirectoryHandle | FileSystemFileHandle]>;
      try {
        entries = current.dir.entries();
      } catch {
        summary.unreadable += 1;
        continue;
      }

      try {
        for await (const [name, handle] of entries) {
          const path = current.prefix ? `${current.prefix}/${name}` : name;

          if (files.length >= MAX_LOCAL_FILES) {
            // Budget spent. Keep scanning the rest of THIS directory — cheaply,
            // no disk I/O — instead of breaking on the first entry handed to us:
            // that entry is very often node_modules or a .png, and calling it a
            // truncation puts an "incomplete index" banner on a repo that was in
            // fact walked in full. Break the moment we see something that really
            // would have been indexed.
            if (couldStillIndex(name, handle.kind, path)) {
              summary.capped = true;
              break;
            }
            continue;
          }

          if (handle.kind === "directory") {
            if (IGNORED_DIRECTORIES.has(name)) continue;
            queue.push({ dir: handle, prefix: path });
            continue;
          }

          const lang = languageForPath(path);
          if (!lang) {
            summary.skippedUnknownType += 1;
            continue;
          }

          let file: File;
          try {
            file = await handle.getFile();
          } catch {
            summary.unreadable += 1;
            continue;
          }

          if (file.size > MAX_LOCAL_FILE_BYTES) {
            summary.skippedLarge += 1;
            continue;
          }
          const kind = await classifyContents(file);
          if (kind === "binary") {
            summary.skippedBinary += 1;
            continue;
          }
          if (kind === "unreadable") {
            // Could not even sample the head. Refusing to index is still the
            // right call; it is the counter that changes, not the decision.
            summary.unreadable += 1;
            continue;
          }

          files.push({ path, size: file.size, lang });
        }
      } catch {
        // Iteration itself failed part-way (the handle went stale). Keep what
        // we have; a partial index is useful, and it is reported as partial.
        summary.unreadable += 1;
      }
    }

    // Same optimistic rule as couldStillIndex(): the queue only ever holds
    // non-ignored directories (ignored ones are never pushed), so anything left
    // in it is a folder we never opened and may have dropped files from.
    if (files.length >= MAX_LOCAL_FILES && queue.length > 0) summary.capped = true;
  } catch {
    summary.unreadable += 1;
  }

  summary.files = files.length;
  lastWalk = summary;
  return files;
}

/** Split a relative path into segments, or null if it tries to leave the picked
 *  folder. ".." is rejected rather than resolved: the whole safety story here is
 *  "only the folder they chose", and a traversal segment is the one input that
 *  would quietly widen it. Backslashes are accepted on the way in because
 *  Windows callers paste them, but paths stay "/" everywhere after. */
function pathSegments(path: string): string[] | null {
  if (typeof path !== "string" || path.length === 0) return null;
  const parts = path.split(/[\\/]+/);
  const segments: string[] = [];
  for (const part of parts) {
    if (part === "" || part === ".") continue;
    if (part === "..") return null;
    segments.push(part);
  }
  return segments.length > 0 ? segments : null;
}

async function resolveFileHandle(
  repo: LocalRepoHandle,
  path: string,
  create: boolean,
): Promise<FileSystemFileHandle | null> {
  if (!repo || !repo.dir) return null;
  const segments = pathSegments(path);
  if (!segments) return null;
  try {
    let dir = repo.dir;
    for (let i = 0; i < segments.length - 1; i += 1) {
      dir = await dir.getDirectoryHandle(segments[i], { create });
    }
    return await dir.getFileHandle(segments[segments.length - 1], { create });
  } catch {
    return null;
  }
}

/**
 * Read one file as text. Returns "" for anything that did not work — missing
 * file, revoked permission, a path that tried to escape the folder.
 *
 * That does conflate "empty file" with "could not read", and that is the
 * deliberate price of the no-throw contract: both consumers of this (the
 * chunker and the diff preview) treat the two identically anyway — nothing to
 * index, nothing to show.
 *
 * The conflation has one sharp edge the UI owns, not this module: neither this
 * nor listCodeFiles() ever asks for READ permission, because doing so outside a
 * click cannot prompt and would only ever return "prompt". A handle restored
 * from IndexedDB after a reload therefore reads as a repo full of empty files
 * until reopenLocalRepo() has been driven from a real click. The panel must
 * call it before indexing; there is no way to detect the difference from here.
 */
export async function readLocalFile(repo: LocalRepoHandle, path: string): Promise<string> {
  const handle = await resolveFileHandle(repo, path, false);
  if (!handle) return "";
  try {
    const file = await handle.getFile();
    return await file.text();
  } catch {
    return "";
  }
}

/**
 * Write one file, reporting what happened. Prefer this over writeLocalFile()
 * anywhere the outcome matters — applyLocalEdits() needs a reason per path for
 * its `failed` list, and the contract's void-returning writeLocalFile() has
 * nowhere to put one.
 *
 * Narrow the result with the explicit literal (`result.ok === true`): this
 * project compiles with "strict": false, where truthiness does not narrow a
 * discriminated union.
 */
export async function writeLocalFileResult(
  repo: LocalRepoHandle,
  path: string,
  contents: string,
): Promise<LocalWriteResult> {
  if (!repo || !repo.dir) return { ok: false, reason: "no-folder-open" };
  const segments = pathSegments(path);
  if (!segments) return { ok: false, reason: "invalid-path" };

  const granted = await ensureWritePermission(repo);
  if (!granted) return { ok: false, reason: "write-permission-denied" };

  // create:true on the intermediate directories too, so an edit that adds a new
  // file in a new folder works. It does mean a typo'd path silently mints
  // directories — acceptable only because every path reaching here came from a
  // walk of this same folder or from a diff the person just reviewed.
  const handle = await resolveFileHandle(repo, path, true);
  if (!handle) return { ok: false, reason: "cannot-open-file" };

  try {
    const writable = await handle.createWritable();
    await writable.write(contents);
    await writable.close();
    return { ok: true };
  } catch (error) {
    const reason = error instanceof Error && error.message ? error.message : "write-failed";
    return { ok: false, reason };
  }
}

/** Contract-shaped write. Swallows the outcome by design; use
 *  writeLocalFileResult() when the caller has to report per-file failure. */
export async function writeLocalFile(repo: LocalRepoHandle, path: string, contents: string): Promise<void> {
  await writeLocalFileResult(repo, path, contents);
}

async function ensurePermission(
  dir: FileSystemDirectoryHandle | null | undefined,
  mode: "read" | "readwrite",
): Promise<boolean> {
  if (!dir) return false;
  const handle = dir as unknown as PermissionCapableHandle;
  const canQuery = typeof handle.queryPermission === "function";
  const canRequest = typeof handle.requestPermission === "function";
  // No permission API at all means this is not Chromium's implementation and we
  // have no way to ask. Report "allowed" rather than blocking the feature: the
  // read or write itself is the real gate and reports its own failure.
  if (!canQuery && !canRequest) return true;

  try {
    if (canQuery) {
      const state = await handle.queryPermission({ mode });
      if (state === "granted") return true;
      // A denial is sticky for the origin: re-requesting resolves "denied"
      // without ever showing a prompt, so asking again would only look like a
      // hang to someone waiting for a dialog that never appears.
      if (state === "denied") return false;
    }
    if (!canRequest) return false;
    return (await handle.requestPermission({ mode })) === "granted";
  } catch {
    return false;
  }
}

/**
 * Ask for write access. Call this ONLY at the moment an edit is about to be
 * applied — never at pick time. Chromium shows a distinctly more alarming
 * prompt for editing files, and paying that cost up front for a feature people
 * mostly read with is how a first-run flow loses them.
 *
 * Must run inside a user gesture, because requestPermission() needs one.
 */
export async function ensureWritePermission(repo: LocalRepoHandle): Promise<boolean> {
  return ensurePermission(repo ? repo.dir : null, "readwrite");
}

// --- remembering the folder across reloads ---------------------------------
//
// Handles are structured-cloneable, so IndexedDB (not localStorage — a handle
// is not JSON) can hold one across a reload. The PERMISSION does not survive;
// only the handle does. So a reload can offer "reopen <name>" and re-request
// read access from inside that click. Handles also simply go stale — the folder
// was renamed, moved or deleted, the profile was cleared — and that is an
// ordinary outcome to swallow, never an error to surface.

function openHandleDb(): Promise<IDBDatabase | null> {
  return new Promise<IDBDatabase | null>((resolve) => {
    try {
      // jsdom and SSR both have no indexedDB; every persistence path below then
      // becomes a no-op and the feature still works, just without "reopen".
      if (typeof indexedDB === "undefined" || indexedDB === null) {
        resolve(null);
        return;
      }
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        try {
          if (!request.result.objectStoreNames.contains(HANDLE_STORE)) {
            request.result.createObjectStore(HANDLE_STORE);
          }
        } catch {
          // Leave it; the get/put below then resolves to null/no-op.
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(null);
      request.onblocked = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

function closeQuietly(db: IDBDatabase): void {
  try {
    db.close();
  } catch {
    // Already closing.
  }
}

function isRepoRecord(value: unknown): value is LocalRepoHandle {
  if (!value || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  return typeof record.id === "string" && typeof record.name === "string" && !!record.dir;
}

/** Persist the picked folder so a reload can offer to reopen it. Best-effort:
 *  losing this costs one extra click, never correctness. */
export async function rememberLocalRepo(repo: LocalRepoHandle): Promise<void> {
  if (!repo || !repo.dir) return;
  const db = await openHandleDb();
  if (!db) return;
  await new Promise<void>((resolve) => {
    try {
      const tx = db.transaction(HANDLE_STORE, "readwrite");
      tx.objectStore(HANDLE_STORE).put({ id: repo.id, name: repo.name, dir: repo.dir }, HANDLE_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
      tx.onabort = () => resolve();
    } catch {
      resolve();
    }
  });
  closeQuietly(db);
}

/** The remembered folder and our current standing with it, or null if there is
 *  nothing to reopen. Does NOT prompt — queryPermission needs no user gesture,
 *  which is what makes this safe to call on mount. */
export async function getRememberedLocalRepo(): Promise<RememberedLocalRepo | null> {
  const db = await openHandleDb();
  if (!db) return null;
  const record = await new Promise<unknown>((resolve) => {
    try {
      const tx = db.transaction(HANDLE_STORE, "readonly");
      const request = tx.objectStore(HANDLE_STORE).get(HANDLE_KEY);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  closeQuietly(db);
  if (!isRepoRecord(record)) return null;

  let permission: LocalFsPermission = "prompt";
  try {
    const handle = record.dir as unknown as PermissionCapableHandle;
    if (typeof handle.queryPermission === "function") {
      permission = await handle.queryPermission({ mode: "read" });
    }
  } catch {
    // Stale handle. "prompt" is the right answer: let the person try, and let
    // reopenLocalRepo() forget it when the folder really is gone.
    permission = "prompt";
  }
  return { repo: record, permission };
}

/**
 * Reopen the remembered folder. Call from inside a click — it may prompt.
 * Returns null when there is nothing remembered, when read access is refused,
 * or when the handle has gone stale, and forgets a stale handle on the way out
 * so the UI stops offering a folder that no longer exists.
 */
export async function reopenLocalRepo(): Promise<LocalRepoHandle | null> {
  const remembered = await getRememberedLocalRepo();
  if (!remembered) return null;
  const granted = await ensurePermission(remembered.repo.dir, "read");
  if (!granted) return null;
  try {
    // Cheapest possible liveness probe: open a fresh iterator and pull once. A
    // renamed or deleted folder throws NotFoundError here rather than half way
    // through a walk the person is already watching a progress bar for.
    await remembered.repo.dir.entries().next();
  } catch {
    await forgetLocalRepo();
    return null;
  }
  return remembered.repo;
}

/** Drop the remembered handle (the person picked a different folder, or asked
 *  us to forget this one). Best-effort, never throws. */
export async function forgetLocalRepo(): Promise<void> {
  const db = await openHandleDb();
  if (!db) return;
  await new Promise<void>((resolve) => {
    try {
      const tx = db.transaction(HANDLE_STORE, "readwrite");
      tx.objectStore(HANDLE_STORE).delete(HANDLE_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
      tx.onabort = () => resolve();
    } catch {
      resolve();
    }
  });
  closeQuietly(db);
}
