// agentkit — the browser host: an AgentHost over the folder the person picked
// on /code through the File System Access API.
//
// It has `fs` and NOTHING else. No terminal, no git, no web: a browser tab
// cannot spawn a process, and this host says so (terminalUnavailableReason)
// instead of pretending. Everything here stays on the device — like the rest of
// lib/localcode there is not a single fetch() in this file.
//
// Built ON lib/localcode/fsAccess, not beside it: the write path
// (writeLocalFileResult, which negotiates the write permission), the ignore
// list, the known-source-extension table and the size limit are all reused.
// Two things are done directly against the directory handle:
//   - READING, because fsAccess.readLocalFile answers "" for a missing file, a
//     failed read and a genuinely empty file alike (it documents that
//     conflation), and an agent has to tell those apart: "no such file" is how
//     write_file knows it is creating one, and a failed read must never look
//     like an empty file that is safe to overwrite.
//   - The SEARCH walk. fsAccess.listCodeFiles opens and sniffs every file in
//     the folder before returning anything and takes no AbortSignal, so a
//     search built on it spent its whole deadline listing a large folder and
//     then reported "0 files" (review finding, 2026-09-19). search() below
//     walks and scans in ONE pass instead: the clock, Stop and the include
//     filter all apply from the first directory entry, a file the include glob
//     excludes is never opened, and each file is opened and sniffed once.
//
// `.git` is refused for read, write and list here as well as in tools.ts: its
// hooks and config are text the person's next git command executes.
//
// Confinement: every path is re-checked with confinePath() here even though the
// loop already did — a host must not rely on its caller for its own boundary.
// Below that, the File System Access API itself is the wall: access is by
// walking handles down from the granted root one segment at a time, there is no
// parent handle and no way to open an OS path. What Chromium does with a
// symlink found inside the folder is Chromium's decision and cannot be observed
// from here, so no claim is made about it.
//
// Browser support: Chromium desktop only. isLocalCodeSupported() is false on
// Firefox and Safari (no showDirectoryPicker), and createBrowserHost() is never
// reached there because there is no folder handle to give it.

import {
  IGNORED_DIRECTORIES,
  MAX_LOCAL_FILE_BYTES,
  languageForPath,
  writeLocalFileResult,
} from "../localcode/fsAccess";
import type { LocalRepoHandle } from "../localcode/types";
import { TERMINAL_UNAVAILABLE_IN_BROWSER } from "./nativeBridge";
import {
  compileIncludeGlob,
  confinePath,
  dirCanMatchPrefix,
  includeLiteralPrefix,
  isSecretPath,
  isVcsInternalPath,
} from "./paths";
import type { AgentHost, DirEntry, HostFailure, HostFs, SearchHit, SearchOutcome } from "./types";

const VCS_REFUSAL = "that is inside .git, which this agent does not read or change";

export const MAX_SEARCH_FILES = 1500;
export const MAX_SEARCH_MATCHES = 60;
/** Whole-scan deadline. The pattern is model-supplied, so "(a+)+$" against a
 *  long line is a reachable stall on the page's main thread. JavaScript cannot
 *  interrupt a regex mid-backtrack, so these are bounds, not a cure: cap what
 *  any one match sees, and check the clock between lines (same mitigation as
 *  clients/vscode workspaceTools.searchText). */
export const SEARCH_DEADLINE_MS = 8_000;
const MAX_MATCH_LINE_CHARS = 2_000;
const MAX_HIT_TEXT_CHARS = 200;
const BINARY_SNIFF_BYTES = 8000;

function failure(reason: HostFailure["reason"], detail: string): HostFailure {
  return { ok: false, reason, detail };
}

function domName(error: unknown): string {
  return error && typeof error === "object" && typeof (error as { name?: unknown }).name === "string"
    ? ((error as { name: string }).name)
    : "";
}

async function resolveDir(root: FileSystemDirectoryHandle, segments: string[]): Promise<FileSystemDirectoryHandle> {
  let dir = root;
  for (const segment of segments) dir = await dir.getDirectoryHandle(segment);
  return dir;
}

/** Size cap → binary sniff → text, on a handle already in hand. Shared by
 *  read() and the search walk so a searched file is opened exactly once. */
async function readFileHandle(handle: FileSystemFileHandle): Promise<{ ok: true; exists: true; contents: string } | HostFailure> {
  try {
    const file = await handle.getFile();
    if (file.size > MAX_LOCAL_FILE_BYTES) {
      return failure("too-large", `the file is ${Math.round(file.size / 1024)} KB — over the ${MAX_LOCAL_FILE_BYTES / 1024} KB limit, and almost certainly generated`);
    }
    const head = new Uint8Array(await file.slice(0, BINARY_SNIFF_BYTES).arrayBuffer());
    for (let i = 0; i < head.length; i += 1) {
      if (head[i] === 0) return failure("binary", "the file is binary, not text");
    }
    return { ok: true, exists: true, contents: await file.text() };
  } catch {
    return failure("unreadable", "the file could not be read (it may be locked by another program)");
  }
}

function createFs(repo: LocalRepoHandle): HostFs {
  // No method here uses `this`, so one destructured off the host still works.
  const fs: HostFs = {
    async list(path) {
      const confined = confinePath(path);
      if (confined.ok !== true) return failure("outside-root", (confined as { reason: string }).reason);
      if (isVcsInternalPath(confined.path)) return failure("invalid", VCS_REFUSAL);
      try {
        const dir = await resolveDir(repo.dir, confined.segments);
        const entries: DirEntry[] = [];
        for await (const [name, handle] of dir.entries()) {
          // Same ignore list the index uses: node_modules and .git are not
          // places a coding task looks, and listing them floods the window.
          if (handle.kind === "directory" && IGNORED_DIRECTORIES.has(name)) continue;
          entries.push({ name, kind: handle.kind === "directory" ? "directory" : "file" });
        }
        return { ok: true, entries };
      } catch (error) {
        const name = domName(error);
        if (name === "NotFoundError" || name === "TypeMismatchError") return failure("not-found", "there is no such folder");
        if (name === "NotAllowedError" || name === "SecurityError") {
          return failure("permission-denied", "the browser no longer has permission to read this folder — reopen it on this page");
        }
        return failure("unreadable", "the folder could not be read");
      }
    },

    async read(path) {
      const confined = confinePath(path);
      if (confined.ok !== true) return failure("outside-root", (confined as { reason: string }).reason);
      if (confined.segments.length === 0) return failure("invalid", "that is the project root, not a file");
      if (isVcsInternalPath(confined.path)) return failure("invalid", VCS_REFUSAL);
      let handle: FileSystemFileHandle;
      try {
        const dir = await resolveDir(repo.dir, confined.segments.slice(0, -1));
        handle = await dir.getFileHandle(confined.segments[confined.segments.length - 1]);
      } catch (error) {
        const name = domName(error);
        // Only a positive "not found" counts as absence. Anything else is a
        // failed read — and a failed read must never be mistaken for a file
        // that does not exist, because that is what licenses creating one.
        if (name === "NotFoundError") return { ok: true, exists: false };
        if (name === "TypeMismatchError") return failure("invalid", "that path is a folder, not a file");
        if (name === "NotAllowedError" || name === "SecurityError") {
          return failure("permission-denied", "the browser no longer has permission to read this folder — reopen it on this page");
        }
        return failure("unreadable", "the file could not be opened");
      }
      return readFileHandle(handle);
    },

    async write(path, contents) {
      const confined = confinePath(path);
      if (confined.ok !== true) return failure("outside-root", (confined as { reason: string }).reason);
      if (confined.segments.length === 0) return failure("invalid", "that is the project root, not a file");
      if (isVcsInternalPath(confined.path)) return failure("invalid", VCS_REFUSAL);
      const result = await writeLocalFileResult(repo, confined.path, contents);
      if (result.ok === true) return { ok: true };
      const reason = (result as { reason: string }).reason;
      if (reason === "write-permission-denied") {
        return failure("permission-denied", "write access to this folder was not granted, so nothing was changed");
      }
      if (reason === "invalid-path") return failure("outside-root", "that path is not inside the folder");
      if (reason === "no-folder-open") return failure("unavailable", "the folder is no longer open");
      if (reason === "cannot-open-file") return failure("failed", "the file could not be opened for writing");
      return failure("failed", reason || "the write failed");
    },

    async search(query, opts) {
      if (typeof query !== "string" || !query) return failure("invalid", "there is nothing to search for");
      let matcher: RegExp;
      try {
        matcher = opts.isRegex
          ? new RegExp(query, "i")
          : new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i");
      } catch {
        return failure("invalid", "that regular expression is not valid");
      }
      const include = compileIncludeGlob(opts.include);
      const prefix = includeLiteralPrefix(opts.include);
      const deadline = Date.now() + SEARCH_DEADLINE_MS;
      const timedOut = `stopped after ${SEARCH_DEADLINE_MS / 1000}s`;

      const hits: SearchHit[] = [];
      let scanned = 0;
      let truncatedWhy = "";
      let skippedFolder = false;

      // Walk and scan in one breadth-first pass (see the header for why this is
      // not listCodeFiles). Everything that can be decided from a NAME — the
      // ignore list, .git, the include glob and its folder prefix, the
      // known-source-extension table, the secret matcher — is decided before a
      // file is opened. Files with no known source extension are not searched,
      // the same rule the /code index uses.
      const queue: Array<{ dir: FileSystemDirectoryHandle; segments: string[] }> = [{ dir: repo.dir, segments: [] }];
      walk: while (queue.length > 0) {
        const current = queue.shift();
        if (!current) break;
        try {
          for await (const [name, handle] of current.dir.entries()) {
            if (opts.signal?.aborted) return failure("aborted", "the search was stopped");
            if (Date.now() > deadline) {
              truncatedWhy = timedOut;
              break walk;
            }
            const segments = [...current.segments, name];
            if (handle.kind === "directory") {
              if (IGNORED_DIRECTORIES.has(name) || name.toLowerCase() === ".git") continue;
              if (!dirCanMatchPrefix(segments, prefix)) continue;
              queue.push({ dir: handle as FileSystemDirectoryHandle, segments });
              continue;
            }
            const path = segments.join("/");
            if (!languageForPath(path) || isSecretPath(path)) continue;
            if (include && !include.test(path)) continue;
            if (scanned >= MAX_SEARCH_FILES) {
              truncatedWhy = `stopped after ${MAX_SEARCH_FILES} files`;
              break walk;
            }
            const read = await readFileHandle(handle as FileSystemFileHandle);
            // Too large, binary or locked: not searchable, and not a reason to
            // fail the whole search.
            if (read.ok !== true) continue;
            scanned += 1;
            const lines = read.contents.split(/\r?\n/);
            for (let index = 0; index < lines.length && hits.length < MAX_SEARCH_MATCHES; index += 1) {
              if ((index & 0xff) === 0 && Date.now() > deadline) {
                truncatedWhy = timedOut;
                break;
              }
              if (matcher.test(lines[index].slice(0, MAX_MATCH_LINE_CHARS))) {
                hits.push({ path, line: index + 1, text: lines[index].trim().slice(0, MAX_HIT_TEXT_CHARS) });
              }
            }
            // Hitting the cap INSIDE a file must be reported too — otherwise a
            // scan that stopped in the last file it opened reads as complete.
            // This over-reports when the total is exactly the cap; that is the
            // safe direction (the model is told to narrow a search that was in
            // fact whole), the opposite is "absent" claimed over a partial scan.
            if (!truncatedWhy && hits.length >= MAX_SEARCH_MATCHES) truncatedWhy = `stopped at ${MAX_SEARCH_MATCHES} matches`;
            if (truncatedWhy) break walk;
          }
        } catch (error) {
          const name = domName(error);
          // A revoked grant at the ROOT means nothing can be searched at all —
          // say that, rather than report a clean "no matches in 0 files".
          if (current.segments.length === 0 && (name === "NotAllowedError" || name === "SecurityError")) {
            return failure("permission-denied", "the browser no longer has permission to read this folder — reopen it on this page");
          }
          // Otherwise one folder went stale mid-walk (deleted, renamed). The
          // result is no longer a whole scan, and must say so.
          skippedFolder = true;
        }
      }
      if (!truncatedWhy && skippedFolder) truncatedWhy = "a folder could not be read, so the scan is incomplete";
      const outcome: SearchOutcome = { ok: true, hits, filesScanned: scanned, truncated: truncatedWhy !== "" };
      if (truncatedWhy) outcome.truncatedWhy = truncatedWhy;
      return outcome;
    },
  };
  return fs;
}

export function createBrowserHost(repo: LocalRepoHandle): AgentHost {
  return {
    kind: "browser",
    rootName: repo.name,
    fs: createFs(repo),
    terminalUnavailableReason: TERMINAL_UNAVAILABLE_IN_BROWSER,
  };
}
