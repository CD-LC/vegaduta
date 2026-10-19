// The folder the person opened on /code, exposed to the
// on-device model as three local tools.
//
// localTools.ts (phase 1) gave the tier-1 loop four PURE-COMPUTE tools: they
// take a string and return a string and touch nothing. These three are the
// first local tools with a subject — the directory handle the person picked on
// /code. They read it; they never write it. app/lib/localcode/ already owns
// every hard part (fsAccess.ts walks and reads, index/indexer.ts searches the
// on-device vector index), so this module is a thin, honest adapter: resolve
// the folder, call the existing function, and turn its answer — including its
// refusals and its partial-ness — into a sentence a model will repeat
// accurately to the person.
//
// WHY A SEPARATE MODULE. localTools.ts must stay importable-by-nothing: its own
// test reads its source and fails on any value import, which is how "no spend,
// no download, no silent network" is proven there rather than promised. These
// tools cannot meet that bar (they exist to call fsAccess and the indexer), so
// they live here and are registered into that array by one line at the merge —
// see the registry note at the bottom of this file.
//
// ---------------------------------------------------------------------------
// THE FOUR PROPERTIES, AND HOW THIS MODULE HOLDS EACH
// ---------------------------------------------------------------------------
//
// 1. NO SPEND. Nothing here reaches a billable platform endpoint. The two
//    modules this one imports are the same two the /code page already runs
//    entirely signed-out: fsAccess.ts contains no fetch() at all (its own
//    header says so and its source bears it out), and indexer.ts's only
//    network is the model-download path, which is unreachable from here —
//    see (2). This module itself calls no API client: it does not import
//    app/lib/api, toolBridge, or anything that holds a bearer token, and it
//    has no notion of a tenant, a plan or a quota.
//
// 2. NO DOWNLOAD. local_search_files calls indexer.searchLocalIndex(), and
//    that function CANNOT acquire the embedding weights: its embed path goes
//    through the indexer's resolveEmbed(), which throws
//    EMBEDDER_NOT_READY_DETAIL unless isLocalEmbedderReady() — a real Cache
//    API check, not a localStorage marker — already answered true. The only
//    function in that module permitted to move bytes is
//    installLocalEmbedder(), and nothing here calls it, by name or indirectly.
//    The complete set of foreign calls the three handlers make is the
//    LocalFolderToolDeps record below and nothing else: isLocalCodeSupported,
//    getRememberedLocalRepo, listCodeFiles, getLastWalkSummary, readLocalFile,
//    restoreLocalIndexStatus, getLocalIndexStatus, searchLocalIndex, and one
//    localStorage READ (see INDEXED_REPO_STORAGE_KEY). The structural weights
//    guard in tier1.ts (getTier1RunModel returning null unless the weights are
//    cached) is untouched and unreferenced.
//
// 3. NO SILENT NETWORK. All three declare `network: "none"`, and that claim is
//    about transmission: no handler has an output channel other than its
//    return value. localToolsFiles.test.ts runs all three with throwing stubs
//    over fetch, XMLHttpRequest, WebSocket, EventSource and sendBeacon and
//    asserts none is touched. What that test exercises is this module's code
//    plus injected fakes, so it proves this module transmits nothing; the
//    no-fetch fact about fsAccess.ts and the structural download gate in
//    indexer.ts are stated above from those files' own source and their own
//    tests, not re-proved here.
//
// 4. HONEST DEGRADATION. Six distinguishable "can't" states, each with its own
//    sentence: this browser has no File System Access API; nothing has ever
//    been opened; a folder is remembered but the tab's access to it lapsed;
//    no index exists; the index belongs to a different folder; the embedder
//    isn't installed. None of them is an empty list — an empty list is the
//    answer to "this folder has no code files", and it must not double as the
//    answer to "I couldn't look".
//
// ---------------------------------------------------------------------------
// REFUSALS ARE RETURNED, NOT THROWN
// ---------------------------------------------------------------------------
// localTools.ts models a refusal as its private LocalToolRefusal class, which
// runLocalTool maps to code "rejected". That class is not exported, so this
// module cannot construct one, and a plain thrown Error would come back as
// "failed" — the code that tells the loop "a defect on our side, do not retry".
// For "no folder is open" that label would be a lie: the tool worked perfectly
// and the answer is "I can't, because X". So every refusal here is a normal
// returned string. It reaches the model, and through it the person, which is
// the whole requirement; what it costs is the rejected/failed distinction,
// which for these six states would not have changed any caller's behaviour.

//
// Split: localToolsFilesAccess.ts (dependency seam + cached walk) and
// localToolsFilesFormat.ts (pure text helpers); this file owns the refusal
// sentences, folder resolution, the three handlers and the tool registry.

import type { LocalTool } from "./localTools";
import type { IndexStatus, LocalRepoHandle, RememberedLocalRepo } from "../localcode/types";
import { getDeps, walkFolder, type LocalFolderToolDeps } from "./localToolsFilesAccess";
import {
  firstMeaningfulLine,
  formatAge,
  formatBytes,
  joinWithinBudget,
  normalizeRepoPath,
  readCountArg,
  readStringArg,
  skipLine,
} from "./localToolsFilesFormat";
export {
  WALK_CACHE_TTL_MS,
  __setLocalFolderToolDepsForTests,
  type LocalFolderToolDeps,
} from "./localToolsFilesAccess";

// ---------------------------------------------------------------------------
// Budgets
//
// runLocalTool caps EVERY result at localTools.ts's MAX_RESULT_CHARS (2000,
// read from that file on 2026-09-12) and replaces the tail with its own generic
// marker. That marker names a character count and nothing else — so anything
// this module needs the model to actually see (the walk's `capped` flag, the
// skipped counts, "showing 30 of 412 files", "showing lines 1-40 of 900") has
// to be INSIDE the surviving 2000, not after it. Hence the budgets below: each
// bounds only the bulky part of a result and leaves the rest of the 2000 for
// the header and footer lines this module writes itself.
//
// Why 2000 is the right ceiling to design against is localTools.ts's own
// derivation, not restated here: on the fallback context window a tier-1 prompt
// gets ~2744 tokens for system block, tool schemas, history and the current
// turn, and 2000 chars estimates to ~572 of them.
//
// These are NOT imported from localTools.ts on purpose. That module is about to
// import THIS one (the registry merge), and importing a value back would make
// the cycle real; it also keeps this file off the list of things that break
// when a module several sessions are editing moves a constant. If
// MAX_RESULT_CHARS changes, re-derive these by hand.
// ---------------------------------------------------------------------------

/** Longest run of file lines local_list_files emits before it stops and says
 *  how many it is showing. ~500 chars of the 2000 are left for the folder
 *  header, the truncation line, the partial-walk warning and the skip counts —
 *  four lines that are the difference between a partial listing the model
 *  knows is partial and one it will treat as the whole folder. */
export const MAX_LIST_CHARS = 1500;

/** Longest slice of file BODY local_read_file emits. Lower than MAX_LIST_CHARS
 *  because a file is quoted verbatim and the header ("path — 900 lines, 31 KB")
 *  plus the resume line ("showing lines 1-40 of 900; call again with from_line
 *  41") are what make a partial read usable instead of misleading. */
export const MAX_FILE_CHARS = 1400;

/** Longest run of search hits. Each hit is a citation line plus one snippet
 *  line, so this holds several hits with room for the header and the
 *  "showing N of M" line. */
export const MAX_SEARCH_CHARS = 1500;

/** Default and maximum hits returned by local_search_files. The indexer's own
 *  default top-k is 8; 5 is used here because eight citations plus snippets do
 *  not fit MAX_SEARCH_CHARS without truncating the last ones mid-word, and a
 *  citation the model cannot read is worse than one it never saw. */
const DEFAULT_SEARCH_HITS = 5;
const MAX_SEARCH_HITS = 10;

/** Longest path accepted as an argument. Generous against any real repo path
 *  and small enough that a malformed argument is refused before any disk work
 *  begins — the same "bound the work before it starts" posture localTools.ts
 *  takes with MAX_EXPRESSION_CHARS. */
const MAX_PATH_CHARS = 400;

/** Longest query accepted by local_search_files. The query is embedded by the
 *  on-device model into a single vector; a novel-length query is not a better
 *  query, it is a slower one. */
const MAX_QUERY_CHARS = 500;

// ---------------------------------------------------------------------------
// The six refusal sentences
//
// Exported so a test (and any future UI) can compare identity rather than match
// prose — the same posture indexer.ts takes with EMBEDDER_NOT_READY_DETAIL.
// Each names WHAT is wrong and WHAT to do, because a model will repeat these
// verbatim to someone who cannot see this code.
// ---------------------------------------------------------------------------

/** The one sentence all three tools share when nothing has been opened. */
export const NO_FOLDER_OPEN_DETAIL =
  "No folder is open on this device, so there is nothing to read. Open one on the /code page — click “Open a folder” and pick a directory — then ask again. The folder is read inside this browser tab only; nothing is uploaded.";

/** Remembered, but this tab's access to it has lapsed. A browser only grants
 *  file access inside a click, and a tool call is not one — so this is
 *  genuinely un-fixable from here and the sentence has to say so. */
export const FOLDER_NEEDS_REOPEN_DETAIL =
  "A folder is remembered but this tab's permission to read it has lapsed — browsers drop folder access on reload. Only a click can restore it: open the /code page and choose “Reopen”, then ask again.";

/** No File System Access API at all (Firefox, Safari). */
export const UNSUPPORTED_BROWSER_DETAIL =
  "This browser cannot open a folder from your device — reading one needs the File System Access API, which desktop Chromium browsers (Chrome, Edge) have and Firefox and Safari do not. Nothing is broken; there is simply no folder for these tools to read here.";

/** Nothing indexed yet. Named for the action, not the console — the index
 *  button sits on the same page the folder was opened from. */
export const NO_INDEX_DETAIL =
  "There is no on-device search index for this folder yet, and this tool will not build one (indexing reads every file and can take a while, so it stays an explicit action). Open the folder on the /code page and choose “Index this folder”, then ask again. Meanwhile local_list_files and local_read_file work without an index.";

/** An index exists, but for a different folder than the one now open. */
export const INDEX_OTHER_FOLDER_DETAIL =
  "The on-device search index was built for a different folder than the one currently open, so searching it would cite files that are not in front of you. Re-index the open folder on the /code page, then ask again.";

/** Indexing is running right now, on the /code page. */
export const INDEX_STILL_BUILDING_DETAIL =
  "The on-device search index for this folder is still being built. Wait for the /code page to finish indexing, then ask again.";

// ---------------------------------------------------------------------------
// Resolving "the opened folder"
//
// There is no module-level "current repo": LocalCodePanel holds the handle in
// React state, and this module is not a React consumer. fsAccess's IndexedDB
// record is the only process-wide answer to "which folder is open", and it is
// an honest one — pickLocalRepo() writes it at pick time and reopenLocalRepo()
// re-grants it, so after either the record IS the open folder with permission
// "granted".
//
// The gap this leaves is named rather than hidden: a record whose permission
// reads "prompt" or "denied" is a folder the person opened in a PREVIOUS page
// load and has not reopened in this one. fsAccess is explicit that reading
// through such a handle yields empty strings for every file (see its
// readLocalFile doc comment), so treating it as open would report a repo full
// of empty files — a wrong answer that looks exactly like a right one. It
// refuses instead, with FOLDER_NEEDS_REOPEN_DETAIL.
// ---------------------------------------------------------------------------

type FolderResolution = { ok: true; repo: LocalRepoHandle } | { ok: false; detail: string };

async function resolveOpenFolder(d: LocalFolderToolDeps): Promise<FolderResolution> {
  if (d.isLocalCodeSupported() !== true) {
    return { ok: false, detail: UNSUPPORTED_BROWSER_DETAIL };
  }
  let remembered: RememberedLocalRepo | null = null;
  try {
    remembered = await d.getRememberedLocalRepo();
  } catch {
    // getRememberedLocalRepo is documented never to throw; if a future change
    // makes it, "nothing is open" is the safe reading, not a stack trace.
    remembered = null;
  }
  if (!remembered || !remembered.repo || !remembered.repo.dir) {
    return { ok: false, detail: NO_FOLDER_OPEN_DETAIL };
  }
  // strict:false — only the explicit literal comparison narrows here.
  if (remembered.permission !== "granted") {
    return { ok: false, detail: FOLDER_NEEDS_REOPEN_DETAIL };
  }
  return { ok: true, repo: remembered.repo };
}

// ---------------------------------------------------------------------------
// local_list_files
// ---------------------------------------------------------------------------

async function runListFiles(args: Record<string, unknown>): Promise<string> {
  const d = await getDeps();
  const folder = await resolveOpenFolder(d);
  if (folder.ok !== true) return folder.detail;

  let prefix = "";
  if (args.prefix !== undefined && args.prefix !== null) {
    const arg = readStringArg(args, "prefix", MAX_PATH_CHARS);
    if (arg.ok !== true) return arg.detail;
    const normalized = normalizeRepoPath(arg.value);
    if (normalized === null) {
      return `"${arg.value}" is not a path inside the open folder ("..") — paths here are relative to the folder that was opened.`;
    }
    prefix = normalized;
  }

  const walk = await walkFolder(d, folder.repo);
  const all = walk.files;
  const matching = prefix
    ? all.filter((file) => file.path === prefix || file.path.startsWith(`${prefix}/`))
    : all;

  const header = prefix
    ? `${folder.repo.name}/${prefix} — ${matching.length} of ${all.length} indexable files`
    : `${folder.repo.name} — ${all.length} indexable files`;

  const lines: string[] = [];
  const footer: string[] = [];

  if (matching.length === 0) {
    lines.push(
      prefix
        ? `(no indexable files under "${prefix}")`
        : "(no indexable code files in this folder — fsAccess indexes source and config files by extension, so a folder of documents or binaries lists as empty)"
    );
  } else {
    const body = joinWithinBudget(
      matching.map((file) => `${file.path}  ${file.lang}  ${formatBytes(file.size)}`),
      MAX_LIST_CHARS
    );
    lines.push(body.text);
    if (body.kept < matching.length) {
      footer.push(
        `[showing ${body.kept} of ${matching.length} files — call again with "prefix" to narrow to a subfolder]`
      );
    }
  }

  // The partial-walk warning goes LAST-but-one so it survives any outer
  // truncation ahead of the skip counts: "this list is incomplete" is the one
  // fact the model must not lose.
  if (walk.summary.capped === true) {
    footer.push(
      "[INCOMPLETE: the walk stopped at its 5000-file cap, so files were left out of this folder entirely. Treat this list as partial — it is not proof that a file is absent.]"
    );
  }
  const skipped = skipLine(walk.summary);
  if (skipped) footer.push(skipped);

  const age = Date.now() - walk.at;
  if (age >= 1000) footer.push(`[listing taken ${formatAge(age)} ago; ask again for a fresh walk]`);

  return [header, ...lines, ...footer].join("\n");
}

// ---------------------------------------------------------------------------
// local_read_file
// ---------------------------------------------------------------------------

async function runReadFile(args: Record<string, unknown>): Promise<string> {
  const d = await getDeps();
  const folder = await resolveOpenFolder(d);
  if (folder.ok !== true) return folder.detail;

  const arg = readStringArg(args, "path", MAX_PATH_CHARS);
  if (arg.ok !== true) return arg.detail;
  const path = normalizeRepoPath(arg.value);
  if (path === null) {
    return `"${arg.value}" is not a path inside the open folder. Paths are relative to the folder that was opened, and ".." is refused — these tools can only read inside the directory the person picked.`;
  }

  const contents = await d.readLocalFile(folder.repo, path);
  if (contents === "") {
    // readLocalFile's no-throw contract collapses three outcomes into "", and
    // it is honest to say which three rather than pick one.
    return `"${path}" returned no content. That means one of three things and this tool cannot tell them apart: the file is genuinely empty, there is no such file in the open folder, or the browser refused the read. Use local_list_files to see what is actually there.`;
  }

  const allLines = contents.split("\n");
  const from = readCountArg(args, "from_line", 1, allLines.length);
  const slice = allLines.slice(from - 1);
  const body = joinWithinBudget(slice, MAX_FILE_CHARS);
  const lastLine = from + body.kept - 1;

  // Characters, not bytes: `contents` is a decoded JS string, so its .length is
  // UTF-16 units and calling that "KB" would be wrong for any file with
  // non-ASCII in it. formatBytes is for LocalFileRef.size, which really is a
  // byte count from File.size.
  const header = `${path} — ${allLines.length} lines, ${contents.length} characters`;
  const out = [header, body.text];
  if (body.clipped === true) {
    out.push(
      `[line ${from} is longer than this tool returns in one call and was clipped — it is not the whole line]`
    );
  }
  if (from > 1 || lastLine < allLines.length) {
    out.push(
      lastLine < allLines.length
        ? `[showing lines ${from}-${lastLine} of ${allLines.length} — call again with from_line ${lastLine + 1} for the next part]`
        : `[showing lines ${from}-${lastLine} of ${allLines.length}]`
    );
  }
  return out.join("\n");
}

// ---------------------------------------------------------------------------
// local_search_files
// ---------------------------------------------------------------------------

/** Index states this tool can act on, and the sentence for each that it can't.
 *  Kept apart from the handler so the order of the checks is readable: which
 *  folder, then whether an index exists, then whether it is THIS folder's. */
function indexRefusal(status: IndexStatus, indexedRepoId: string | null, repoId: string): string | null {
  if (indexedRepoId === null) return NO_INDEX_DETAIL;
  if (indexedRepoId !== repoId) return INDEX_OTHER_FOLDER_DETAIL;
  if (status.state === "indexing") return INDEX_STILL_BUILDING_DETAIL;
  if (status.state === "idle") return NO_INDEX_DETAIL;
  if (status.state === "error") {
    // The indexer's own display-safe sentence — EMBEDDER_NOT_READY_DETAIL,
    // MODEL_CHANGED_DETAIL, EDGE_OFF_DETAIL and the rest all arrive here. It is
    // written for a person, so it is passed through rather than paraphrased.
    return status.detail || NO_INDEX_DETAIL;
  }
  return null;
}

async function runSearchFiles(args: Record<string, unknown>): Promise<string> {
  const d = await getDeps();
  const folder = await resolveOpenFolder(d);
  if (folder.ok !== true) return folder.detail;

  const arg = readStringArg(args, "query", MAX_QUERY_CHARS);
  if (arg.ok !== true) return arg.detail;
  const limit = readCountArg(args, "limit", DEFAULT_SEARCH_HITS, MAX_SEARCH_HITS);

  // restoreLocalIndexStatus is the cheap question: it reads the stored index's
  // file records and never loads a model (its own doc comment says so, and it
  // is why the model-signature check happens later, at the first search). This
  // is the call that lets the refusal below happen BEFORE searchLocalIndex is
  // reached, which is what keeps "will not build an index" structural rather
  // than a promise.
  const status = await d.restoreLocalIndexStatus();
  const refusal = indexRefusal(status, d.indexedRepoId(), folder.repo.id);
  if (refusal !== null) return refusal;

  const hits = await d.searchLocalIndex(arg.value, limit);
  if (hits.length === 0) {
    // searchLocalIndex returns [] for "nothing matched" AND for "the embedder
    // could not run", and distinguishes them only by moving the status to
    // "error" with a display-safe detail. Reading the status back afterwards is
    // the documented way to tell those apart — without it, a missing embedding
    // model would be reported as "your code contains nothing relevant".
    const after = d.getLocalIndexStatus();
    if (after.state === "error") return after.detail || NO_INDEX_DETAIL;
    return `No indexed chunk matched "${arg.value}". The index covers ${status.files} files / ${status.chunks} chunks of this folder, so this is a real miss, not a failure — try different wording, or local_list_files and local_read_file to look directly.`;
  }

  const lines = hits.map((hit) => {
    const snippet = firstMeaningfulLine(hit.text);
    return `${hit.path}:${hit.startLine}-${hit.endLine}  (score ${hit.score.toFixed(2)})${snippet ? `\n    ${snippet}` : ""}`;
  });
  const body = joinWithinBudget(lines, MAX_SEARCH_CHARS);
  const out = [`${hits.length} match(es) in ${folder.repo.name}, best first:`, body.text];
  if (body.kept < hits.length) out.push(`[showing ${body.kept} of ${hits.length} matches]`);
  out.push("[read a citation with local_read_file to see the surrounding code]");
  return out.join("\n");
}

// ---------------------------------------------------------------------------
// The tools
//
// Schemas stay one line per property for the reason localTools.ts's registry
// comment gives: every advertised tool's whole schema is pasted into the tier-1
// system prompt out of the same budget the conversation lives in.
//
// consequential:false on all three. The field means "writes to the person's
// disk, clipboard or storage" (LocalTool's own doc comment), and none of these
// does: fsAccess's write path (writeLocalFileResult, ensureWritePermission) is
// not imported here, and read permission is never even requested — only read,
// and only through handles the person already granted. local_search_files is
// included in that despite doing the most work: it warms the embedding worker
// and reads IndexedDB, which costs CPU and memory but changes nothing the
// person owns.
// ---------------------------------------------------------------------------

export const LOCAL_FILE_TOOLS: LocalTool[] = [
  {
    name: "local_list_files",
    description:
      "List the code files in the folder the person opened on this device. Reads the folder in this browser tab only; nothing is uploaded.",
    inputSchema: {
      type: "object",
      properties: {
        prefix: { type: "string", description: 'Optional subfolder, e.g. "src/lib"; omit for the whole folder' },
      },
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: runListFiles,
  },
  {
    name: "local_read_file",
    description:
      "Read one file from the folder the person opened on this device, by its path relative to that folder. Long files come back in parts.",
    inputSchema: {
      type: "object",
      properties: {
        path: { type: "string", description: 'Relative path, e.g. "src/lib/auth.ts"' },
        from_line: { type: "number", description: "1-based first line to return; default 1" },
      },
      required: ["path"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: runReadFile,
  },
  {
    name: "local_search_files",
    description:
      "Search the opened folder by meaning, using the on-device index built on the /code page, and return path:line citations. Does not build the index and downloads nothing.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "What to look for, in plain words" },
        limit: { type: "number", description: "Max matches, 1-10; default 5" },
      },
      required: ["query"],
      additionalProperties: false,
    },
    network: "none",
    consequential: false,
    run: runSearchFiles,
  },
];

// ---------------------------------------------------------------------------
// REGISTRY MERGE (applied by hand, NOT from this file)
//
// localTools.ts owns the advertised array and two tracks would collide on it,
// so the one-line edit belongs in the merge, not here:
//
//   const LOCAL_TOOLS: LocalTool[] = [ …the four phase-1 tools…, ...LOCAL_FILE_TOOLS ];
//
// plus the import it needs. That import is a VALUE import, and localTools.ts's
// own test currently asserts every import line in that file starts with
// "import type " — so the merge cannot land green without also widening that
// assertion. The exact edits are in this change's needsOtherFiles; this note is
// here so whoever applies the merge finds out from the file rather than from a
// red test.
// ---------------------------------------------------------------------------
