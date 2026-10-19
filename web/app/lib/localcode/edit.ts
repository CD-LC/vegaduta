// Local-code: propose an edit with the on-device model, then write it back.
//
// This is the only module in the local-code feature that can DESTROY
// something. Everything else reads, indexes and answers; this one opens a
// writable file handle on a folder the person picked and overwrites source
// they may not have committed. So the posture here is deliberately paranoid,
// and every guard below exists because of a specific realistic failure, not
// out of general caution:
//
//   1. A small on-device model asked for "the full updated file" will
//      sometimes emit an opening fragment of it and stop. Written back, that
//      is a silent deletion of someone's work with no undo and no git staging
//      area involved. Hence MIN_UPDATED_RATIO - a proposal that shrank the
//      file past the floor is refused outright rather than shown as a diff
//      someone might approve at a glance.
//   2. The person's editor is open on the same folder while they review the
//      diff. Between propose and apply they may save. Writing anyway would
//      discard their edit and look like a tool bug. Hence the re-read and
//      exact compare against `original` in applyLocalEdits - stale means
//      refuse, never merge, never "probably fine".
//   3. A model download started by an "edit" button would be an unasked-for
//      transfer on someone's connection. Hence readyTier() below, which is
//      codegen.ts's eligibleTier with tier 0's "downloadable" case removed
//      (see its comment).
//
// Privacy contract (binding, and the reason this file imports no api.ts):
// file contents, the instruction, the model's reply - none of it may reach
// the network, and no call from here may carry any of them.
//
// Stated as a direction rather than as "nothing on this path touches the
// network", because the latter is not true and a promise this feature is sold
// on should not be approximate. Two things reachable from here do talk to the
// network, and neither carries anything of the person's:
//   - readyTier() calls getTier1RunModel(), which can fetch the edge model
//     MANIFEST - a public, unauthenticated list of downloadable models,
//     skipped entirely when nothing has ever been downloaded.
//   - a tier-1 run goes through createTier1Session(), which pulls in the
//     WebLLM engine and loads weights through that library's own loader.
// Both move code and model files toward the device. What must never travel in
// the other direction is the person's source. This module builds no request
// body from file contents — grep it for fetch and for the read helpers and you
// will find the source only ever reaching a local model session. That is a
// statement about THIS file; it is not a guarantee about anything a future
// caller might hand those contents to.
//
// Unlike edge/codegen.ts, which sends its on-device draft to the server's
// LLM-free validate endpoint before trusting it, this module cannot borrow
// that safety net: the payload would be the person's private source. The
// ratio/empty guards below are the local substitute, and they are the reason
// the guards are refusals rather than warnings.

import {
  detectCapabilities,
  edgeEnabled,
  getEdgeOptIn,
  getEdgeRoutingPreference,
  getStoredModelOverride,
} from "../edge/capabilities";
import { createTier0Session, tier0Availability } from "../edge/tier0";
import { createTier1Session, getTier1RunModel } from "../edge/tier1";
// writeLocalFileResult, NOT the void-returning writeLocalFile: the contract
// shape swallows cannot-open-file, a revoked write grant and every exception
// out of createWritable()/close(), which would make `applied` below a list of
// files we HOPE were written. See the write in applyLocalEdits.
import { ensureWritePermission, readLocalFile, writeLocalFileResult } from "./fsAccess";
import { unifiedDiffResult } from "./diff";
import type { LocalRepoHandle } from "./types";

export interface ProposedEdit {
  path: string;
  original: string;
  updated: string;
  diff: string;
  /** The diff is a whole-file replacement rather than a line-level one,
   * because the pair was too large for the exact LCS (diff.ts MAX_LCS_CELLS).
   * Still correct, just unreadable - the surface should say so instead of
   * presenting the whole file as removed-then-re-added and calling it a
   * reviewed change. */
  coarse: boolean;
}

export interface ApplyResult {
  applied: string[];
  failed: Array<{ path: string; reason: string }>;
}

/**
 * Refuse any "edit" that leaves the file under 40% of its original length.
 * Not a tuned number: it is low enough that a refactor deleting half a file
 * still goes through (half is 0.5, above the floor), and high enough to catch
 * the real failure mode, which is not a modest shrink - it is a model emitting
 * an opening fragment and stopping. Exported so that any surface explaining
 * the rule quotes the value the code enforces instead of restating it.
 */
export const MIN_UPDATED_RATIO = 0.4;

/**
 * Hard ceiling on what we will hand a local model, in characters.
 *
 * The binding constraint is not this file's size on its own: the prompt
 * carries the whole file AND the reply is required to be a complete copy of
 * it, so both have to fit an on-device context window at once. Past some size
 * the model stops failing loudly and simply truncates, which is exactly the
 * outcome guard #1 exists to catch. This constant is where we refuse up front
 * instead. It is a chosen limit, not a measured property of any model - if
 * it needs to move, move it against a model you actually ran, not against an
 * estimate of how many tokens a character is.
 */
export const MAX_EDITABLE_CHARS = 24_000;

/**
 * Why an empty read is a refusal rather than "a new file".
 *
 * readLocalFile() returns "" for a missing file, a path that tried to escape
 * the folder, a revoked permission, a stale handle AND a genuinely empty
 * file - fsAccess documents that conflation as the price of its no-throw
 * contract. For a chunker that conflation is harmless. Here it is not:
 *
 *   - at propose time, original === "" makes the truncation guard vacuous
 *     (nothing can be below 40% of zero), so a mistyped path in the free-text
 *     box turns into a wholly hallucinated file the person is invited to
 *     approve - and writeLocalFileResult creates missing directories, so
 *     approving it mints the path.
 *   - at apply time, a read that fails twice compares "" === "" and passes
 *     the stale check, writing invented contents over a file that exists but
 *     could not be read.
 *
 * This was re-checked against fsAccess rather than assumed: its whole read
 * surface is readLocalFile() (string) and listCodeFiles() (a walk of the whole
 * folder, which reports unreadable entries only as a count in the walk summary
 * and cannot answer "did THIS path read"). Nothing there separates the two
 * cases, so this path still fails closed on both. The cost is that a genuinely
 * 0-byte file cannot be edited by the local model - a real, if small, loss:
 * there is no content to preserve, and typing into it directly works.
 *
 * The fix belongs in fsAccess, not here: a result-shaped read alongside
 * readLocalFile - `{ ok: true; contents: string } | { ok: false; reason }`,
 * mirroring writeLocalFileResult - would make "read succeeded, the file is
 * empty" distinguishable from "could not read", and both guards below could
 * then key off ok instead of off "". Until that exists, do not relax either
 * guard: "" is the only failure signal this module gets.
 */
export const UNREADABLE_REASON =
  "Couldn't read that file - it may be missing, outside the folder you picked, or no longer readable. Nothing was written.";

// ---------------------------------------------------------------------------
// Repo binding
//
// proposeLocalEdit's signature (fixed by the module contract) takes a path
// but no repo handle, while readLocalFile needs one. Rather than guess at a
// global in fsAccess, the surface registers the picked repo here once and
// callers that already hold the handle can pass it per call. No repo bound =>
// no proposal (null), never a read against the wrong folder.
// ---------------------------------------------------------------------------

let boundRepo: LocalRepoHandle | null = null;

/** Bind the repo proposeLocalEdit reads through. Call once after
 * pickLocalRepo(); pass null when the person disconnects the folder. */
export function setLocalEditRepo(repo: LocalRepoHandle | null): void {
  boundRepo = repo;
}

export function getLocalEditRepo(): LocalRepoHandle | null {
  return boundRepo;
}

// ---------------------------------------------------------------------------
// Model readiness - warm/ready only, never a download
// ---------------------------------------------------------------------------

/**
 * The tier that would run an edit RIGHT NOW without downloading anything, or
 * null. Mirrors edge/codegen.ts's eligibleTier - same opt-in, routing
 * preference and hosted-override gates, so on-device editing obeys the same
 * stated preferences as on-device chat - with ONE deliberate divergence:
 * codegen accepts tier0Available(), which is true for "downloadable" because
 * createTier0Session is allowed to kick off Chrome's own model download with
 * visible progress. This feature may not: an editor button that silently
 * starts a model download is exactly the behaviour the local-code feature
 * promises never to have. So only "available" - weights already on the device
 * - counts here. Never rejects.
 */
async function readyTier(): Promise<0 | 1 | null> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") return null;
    if (getStoredModelOverride() === "hosted") return null;
    if (getEdgeRoutingPreference() === "hosted-only") return null;
    if (!getEdgeOptIn()) return null;
    const caps = await detectCapabilities();
    // getTier1RunModel is ready-only: it returns a model solely when its
    // weights are already cached on this device, so preferring tier 1 here
    // cannot start a download. It is not silent on the network — the same call
    // can fetch the public model manifest (see this file's header) — so do not
    // read this as "no request leaves the tab".
    if (await getTier1RunModel(caps)) return 1;
    if ((await tier0Availability()) === "available") return 0;
    return null;
  } catch {
    return null;
  }
}

export interface LocalEditReadiness {
  ready: boolean;
  tier: 0 | 1 | null;
  /** Plain-English reason, for a UI that must tell someone what to do next
   * ("nothing is downloaded - open the Edge console and get a model") rather
   * than grey a button out with no explanation. Never a raw error string. */
  detail: string;
}

/** Cheap check for whether an "edit with local AI" affordance should be live.
 * Runs no generation and starts no download. Never rejects. */
export async function localEditReadiness(): Promise<LocalEditReadiness> {
  const tier = await readyTier();
  if (tier !== null) {
    return {
      ready: true,
      tier,
      detail: tier === 1 ? "A downloaded on-device model is ready." : "Your browser's built-in model is ready.",
    };
  }
  if (!edgeEnabled() || typeof window === "undefined") {
    return { ready: false, tier: null, detail: "On-device mode is off for this deployment." };
  }
  if (getStoredModelOverride() === "hosted" || getEdgeRoutingPreference() === "hosted-only" || !getEdgeOptIn()) {
    return {
      ready: false,
      tier: null,
      detail: "You're set to use platform models. Local code editing runs only on-device - switch on on-device mode to use it.",
    };
  }
  return {
    ready: false,
    tier: null,
    detail:
      "No on-device model is downloaded yet. Open the Edge console and download a model - this feature will not start a download on its own.",
  };
}

// ---------------------------------------------------------------------------
// Prompting
// ---------------------------------------------------------------------------

function systemPrompt(): string {
  return (
    "You edit exactly one source file. Output the COMPLETE updated file and " +
    "nothing else: no markdown fence, no explanation, no diff, no line " +
    "numbers. Preserve every part of the file the instruction does not ask " +
    "you to change, including imports, comments, blank lines and " +
    "indentation. If you cannot apply the instruction, output the file " +
    "exactly as given."
  );
}

function userPrompt(instruction: string, path: string, original: string): string {
  return [
    `File: ${path}`,
    `Instruction: ${instruction.trim()}`,
    "",
    "Current contents:",
    original,
  ].join("\n");
}

/** Strip a leading/trailing ``` or ```lang fence. Local models add one
 * despite being told not to - same fence-strip edge/codegen.ts carries, kept
 * private to each module rather than shared, because the two differ in what
 * they do afterwards and a shared helper would invite "fixing" one for the
 * other's benefit. */
function stripFence(text: string): string {
  let out = text.trim();
  if (out.startsWith("```")) {
    out = out
      .replace(/^```[a-zA-Z0-9+#._-]*\s*\n?/, "")
      .replace(/\n?```\s*$/, "")
      .trim();
  }
  return out;
}

/**
 * Give back the final newline that stripFence's trim() just ate. Nearly
 * every source file on disk ends with one; without this, a fence-wrapped
 * reply writes back a file whose only other change is a missing EOF newline,
 * which shows up in the person's `git diff` as a spurious "\ No newline at
 * end of file" on the last line - noise this tool has no business creating.
 * Only ever ADDS one, and only when the original had one: a file that
 * deliberately ends without a newline keeps that property. The mirror case
 * needs no code - stripFence() trims unconditionally, so `updated` reaches
 * here without a trailing newline in every branch, and a no-newline original
 * therefore stays a no-newline file.
 */
function restoreTrailingNewline(original: string, updated: string): string {
  if (!updated) return updated;
  if (original.endsWith("\n") && !updated.endsWith("\n")) return updated + "\n";
  return updated;
}

/**
 * The truncation guard. True when `updated` is a plausible rewrite of
 * `original` rather than a fragment of it. See MIN_UPDATED_RATIO for why the
 * length ratio is the test that matters; exported so it can be asserted
 * directly instead of only through a mocked model.
 *
 * The empty-original branch stays true because a ratio against zero says
 * nothing either way - but it is no longer load-bearing and no longer a hole,
 * because both callers refuse an empty `original` outright BEFORE they get
 * here (see UNREADABLE_REASON, and the guard order in applyLocalEdits). Do not
 * reintroduce a caller that relies on this function alone to police an empty
 * original, and do not move either caller's empty check back below its ratio
 * check: this function would then answer for a case it cannot judge, and in
 * the both-sides-empty case it answers "truncated" to a read failure.
 */
export function isPlausibleRewrite(original: string, updated: string): boolean {
  if (!updated || !updated.trim()) return false;
  if (original.length === 0) return true;
  return updated.length >= original.length * MIN_UPDATED_RATIO;
}

async function runOneShot(tier: 0 | 1, system: string, user: string, signal?: AbortSignal): Promise<string> {
  if (tier === 1) {
    const caps = await detectCapabilities();
    const session = await createTier1Session(caps, { systemPrompt: system });
    try {
      return await session.prompt(user, undefined, signal);
    } finally {
      session.destroy();
    }
  }
  const session = await createTier0Session(system);
  try {
    return await session.prompt(user, undefined, signal);
  } finally {
    session.destroy();
  }
}

// ---------------------------------------------------------------------------
// Propose
// ---------------------------------------------------------------------------

/**
 * Ask the on-device model to rewrite one file, and return the proposal with a
 * reviewable diff. Resolves null - never a guess, never a partial - when
 * anything is off: no repo bound, no model ready on this device, the file
 * could not be read or is empty (see UNREADABLE_REASON), the file is too
 * large to round-trip through a small local context, the model errored or was
 * aborted, or the reply failed the truncation guard.
 *
 * Null is intentionally the single failure channel here. A caller that needs
 * to explain WHY nothing happened should ask localEditReadiness() - that keeps
 * the reason strings in one place instead of leaking model/plumbing errors,
 * which on this path may contain fragments of the person's own source, into
 * toast copy.
 *
 * A no-op proposal (model returned the file unchanged) resolves with
 * `diff: ""` rather than null, so the surface can say "no changes needed"
 * instead of the same "couldn't do it" as a real failure. "" now means
 * byte-identical and nothing weaker: a reply differing only in the
 * end-of-file newline renders a real hunk (diff.ts NO_EOL_MARKER) rather than
 * an empty diff that a surface would read as "nothing to write".
 *
 * Never throws.
 */
export async function proposeLocalEdit(
  instruction: string,
  path: string,
  opts?: { signal?: AbortSignal; repo?: LocalRepoHandle }
): Promise<ProposedEdit | null> {
  try {
    if (!instruction || !instruction.trim()) return null;
    if (!path || !path.trim()) return null;
    const repo = opts?.repo ?? boundRepo;
    if (!repo) return null;
    if (opts?.signal?.aborted) return null;

    const tier = await readyTier();
    if (tier === null) return null;

    // No try/catch: readLocalFile's contract is that it never throws, it
    // returns "". Wrapping it in one would look like the failure was handled
    // while the actual failure signal - the empty string on the next line -
    // went unchecked, which is exactly how the hole below got in.
    const original = await readLocalFile(repo, path);
    if (original === "") return null; // see UNREADABLE_REASON
    // Bound BEFORE prompting: past this, "the model truncated" is the
    // expected outcome, not an anomaly worth showing someone.
    if (original.length > MAX_EDITABLE_CHARS) return null;

    let raw: string;
    try {
      if (opts?.signal?.aborted) return null;
      raw = await runOneShot(tier, systemPrompt(), userPrompt(instruction, path, original), opts?.signal);
    } catch {
      // Tier0Error/Tier1Error (including code "aborted") and anything else
      // the session throws land in the same place: no proposal. Unlike
      // tierRouter, there is no hosted leg to fall through to here - that is
      // the feature, not a gap.
      return null;
    }

    const updated = restoreTrailingNewline(original, stripFence(raw));
    if (!isPlausibleRewrite(original, updated)) return null;

    const rendered = unifiedDiffResult(original, updated, path);
    return { path, original, updated, diff: rendered.text, coarse: rendered.coarse };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Apply
// ---------------------------------------------------------------------------

const STALE_REASON =
  "The file changed on disk since this edit was proposed - refusing to overwrite. Re-run the edit against the current file.";

/**
 * Write approved proposals back to the picked folder.
 *
 * Order matters and is part of the contract: permission first (one prompt for
 * the whole batch, not one per file - writeLocalFileResult re-checks the grant
 * per file, but a grant already given answers from queryPermission without a
 * dialog), then per file a fresh read and an EXACT compare against the
 * `original` captured at proposal time. Anything other than byte-identical is
 * a refusal - including the case where the read itself fails, and including a
 * file that was edited and then edited back, which we cannot distinguish from
 * "unchanged" and do not need to.
 *
 * The per-file guards are ordered so that each failure reports its own cause,
 * cheapest and most specific first: malformed shape, then an empty `original`
 * (which no later check can judge, because it makes both the ratio and the
 * disk compare vacuous), then the truncation ratio, then the re-read.
 *
 * `applied` means the bytes reached the disk, not that we asked for them to:
 * the write goes through writeLocalFileResult and a failure lands in `failed`
 * with its reason. The void-returning writeLocalFile cannot be used here at
 * any price - it reports a refused, locked or unopenable file as success, and
 * the surface then tells the person their edit was saved.
 *
 * Two proposals for the same path in one batch: the first wins, the second
 * reads what the first just wrote, sees stale, and is refused. That is the
 * correct outcome - the second was computed against contents that no longer
 * exist - and it falls out of the same check rather than needing its own.
 *
 * Never throws. Every failure is reported per-file with a reason a person can
 * act on; a caller that gets an exception from here cannot tell which files
 * were already written, which is precisely the ambiguity this promises to
 * avoid.
 */
export async function applyLocalEdits(repo: LocalRepoHandle, edits: ProposedEdit[]): Promise<ApplyResult> {
  const applied: string[] = [];
  const failed: Array<{ path: string; reason: string }> = [];
  const list = Array.isArray(edits) ? edits : [];
  if (list.length === 0) return { applied, failed };

  let granted = false;
  try {
    granted = await ensureWritePermission(repo);
  } catch (err) {
    granted = false;
    void err;
  }
  if (!granted) {
    for (const edit of list) {
      failed.push({
        path: edit?.path ?? "(unknown)",
        reason: "Write access to this folder was not granted, so nothing was changed.",
      });
    }
    return { applied, failed };
  }

  for (const edit of list) {
    const path = edit?.path ?? "(unknown)";
    try {
      if (!edit || typeof edit.updated !== "string" || typeof edit.original !== "string") {
        failed.push({ path, reason: "Malformed proposal - nothing was written." });
        continue;
      }
      // Empty `original` FIRST, and the order is the point: this proposal
      // cannot be verified against disk at all, because readLocalFile reports
      // an unreadable file as "" too, so the exact compare below would pass on
      // a failed read and write invented contents over a file we never saw.
      // proposeLocalEdit already refuses these; this catches a hand-built or
      // restored proposal.
      //
      // Running it before the truncation guard keeps each failure reporting
      // its own cause. The other order sends the empty-original-and-empty-
      // updated proposal - an unreadable file, start to finish - out under
      // "looks truncated compared to the file on disk", pointing at a length
      // comparison against a file we never read.
      if (edit.original === "") {
        failed.push({ path, reason: UNREADABLE_REASON });
        continue;
      }

      // Re-run the same guard the proposal already passed. A proposal can be
      // held in UI state across a re-render, a route change, or a restored
      // session; it is cheap to re-check and the consequence of not checking
      // is an unrecoverable overwrite.
      if (!isPlausibleRewrite(edit.original, edit.updated)) {
        failed.push({
          path,
          reason: "The proposed contents look truncated compared to the file on disk - refusing to write.",
        });
        continue;
      }

      // Again no try/catch: readLocalFile returns "" instead of throwing, so
      // an empty result here IS the read failure and has to be read as one.
      const current = await readLocalFile(repo, path);
      if (current === "") {
        // Distinct from STALE_REASON on purpose. "Someone edited it" sends a
        // person to their editor to look at a change that isn't there; the
        // real cause is a deleted/moved/unreadable file.
        failed.push({ path, reason: UNREADABLE_REASON });
        continue;
      }
      if (current !== edit.original) {
        failed.push({ path, reason: STALE_REASON });
        continue;
      }

      const write = await writeLocalFileResult(repo, path, edit.updated);
      // "strict": false - the explicit literal is what narrows the union.
      if (write.ok === true) {
        applied.push(path);
      } else {
        failed.push({ path, reason: writeFailureReason(write.reason) });
      }
    } catch (err) {
      failed.push({ path, reason: describe(err) });
    }
  }

  return { applied, failed };
}

/**
 * Turn writeLocalFileResult's reason into something a person can act on.
 * The known values are fsAccess's own slugs, which are fine in a log and
 * useless in a toast; anything else is already a DOMException message
 * (NoModificationAllowedError, a Windows file lock, a full disk) and is
 * passed through, because a real message beats a generic one we invented.
 */
function writeFailureReason(reason: string): string {
  switch (reason) {
    case "no-folder-open":
      return "The folder is no longer open, so nothing was written.";
    case "invalid-path":
      return "That path is not inside the folder you picked - nothing was written.";
    case "write-permission-denied":
      return "Write access to this folder was withdrawn before this file was written.";
    case "cannot-open-file":
      return "Couldn't open this file for writing, so nothing was written.";
    default:
      return `Couldn't write this file: ${reason || "unknown error"}. Nothing was changed in it.`;
  }
}

function describe(err: unknown): string {
  if (err instanceof Error && err.message) return err.message;
  const text = String(err ?? "");
  return text || "Unknown error.";
}
