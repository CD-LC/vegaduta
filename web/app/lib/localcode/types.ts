// Browser-local code assistant — the shared vocabulary for web/app/lib/localcode.
//
// Everything described here is data that MUST stay inside the tab. The file
// contents come off the person's own disk through the File System Access API,
// and no module under localcode/ is ever allowed to put any of it on the wire.
// That rule shows up first in the types: there is deliberately no request
// envelope, no tenant id, no trace id and no correlation id anywhere in this
// file. If a field would only ever make sense to a server, it does not belong
// here — adding one is the cheapest way to quietly break the only promise this
// feature makes ("your code never leaves the browser").
//
// The non-standard DOM declarations at the bottom exist because the File System
// Access API is Chromium-only: TypeScript's lib.dom ships the handle interfaces
// (FileSystemDirectoryHandle, FileSystemFileHandle) but NOT showDirectoryPicker()
// and NOT the queryPermission/requestPermission methods that live on every
// handle. They are declared structurally instead of through `declare global`
// precisely because several sessions build on this module in parallel in this
// shared worktree — two competing `interface Window` augmentations would be a
// duplicate-identifier error in whichever file lost the race, in a file neither
// author owns.

/** A folder the person picked, plus the live handle we walk and read through.
 *  `id` is ours (the browser gives handles no stable identity); `name` is the
 *  folder's own name — the browser never reveals the absolute path, by design. */
export interface LocalRepoHandle {
  id: string;
  name: string;
  dir: FileSystemDirectoryHandle;
}

/** One indexable source file. `path` is always relative to the picked folder
 *  and always "/"-separated, so it is safe to show, to key a map on, and to
 *  hand back to readLocalFile()/writeLocalFile() verbatim. */
export interface LocalFileRef {
  path: string;
  size: number;
  lang: string;
}

export interface LocalChunk {
  id: string;
  path: string;
  startLine: number;
  endLine: number;
  text: string;
}

export interface RetrievedChunk extends LocalChunk {
  score: number;
}

export interface IndexStatus {
  state: "idle" | "indexing" | "ready" | "error";
  files: number;
  chunks: number;
  indexedAt: number | null;
  detail?: string;
}

/** Mirrors the platform PermissionState. Spelled out here so callers can hold a
 *  permission value without importing anything DOM-shaped. */
export type LocalFsPermission = "granted" | "prompt" | "denied";

/** A handle restored from IndexedDB after a reload, together with what the
 *  browser currently thinks of our access to it. "prompt" is the normal case:
 *  permission does not survive a reload, so the UI has to offer a button and
 *  call reopenLocalRepo() from inside that click. */
export interface RememberedLocalRepo {
  repo: LocalRepoHandle;
  permission: LocalFsPermission;
}

/** What listCodeFiles() left behind, retrievable via getLastWalkSummary().
 *  It exists because listCodeFiles' contract return type is a bare array with
 *  nowhere to say "there was more" — and a silent truncation at the 5000-file
 *  cap would read to the person as "it indexed my whole repo", which is the one
 *  misreading that turns a wrong answer into a trusted wrong answer. */
export interface LocalWalkSummary {
  files: number;
  /** "We stopped early and something indexable may have been left behind."
   *  Deliberately biased: the walk cannot know whether an unopened folder held
   *  source without opening it, so a non-ignored folder it never reached counts
   *  as a truncation even if it was empty. It errs toward saying the index is
   *  partial, never toward claiming it is complete. */
  capped: boolean;
  skippedLarge: number;
  skippedBinary: number;
  skippedUnknownType: number;
  /** Enumerations and reads that FAILED — a folder that vanished mid-walk, a
   *  file whose first bytes could not be sampled. Distinct from skippedBinary,
   *  which means we read the file and decided against it. */
  unreadable: number;
}

/** Result shape for the write path. Note the explicit-literal narrowing
 *  (`result.ok === true`): this project compiles with "strict": false, where a
 *  bare `if (result.ok)` does not narrow a discriminated union. */
export type LocalWriteResult = { ok: true } | { ok: false; reason: string };

// --- non-standard File System Access surface (Chromium-only) ---------------

export interface FileSystemPermissionDescriptor {
  mode?: "read" | "readwrite";
}

/** queryPermission/requestPermission are on every handle in Chromium and on no
 *  handle anywhere else, so both are optional and every call site must check. */
export interface PermissionCapableHandle {
  queryPermission?(descriptor?: FileSystemPermissionDescriptor): Promise<LocalFsPermission>;
  requestPermission?(descriptor?: FileSystemPermissionDescriptor): Promise<LocalFsPermission>;
}

export interface DirectoryPickerOptions {
  /** Chromium remembers a starting directory per id, so re-picking lands the
   *  person back where they were instead of in Documents every time. */
  id?: string;
  mode?: "read" | "readwrite";
  startIn?: string;
}

export interface DirectoryPickerWindow {
  showDirectoryPicker?(options?: DirectoryPickerOptions): Promise<FileSystemDirectoryHandle>;
}
