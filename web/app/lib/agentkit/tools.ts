// agentkit — tool execution: the half of the loop that touches a host.
//
// Four invariants, in priority order (the first three are the ones
// clients/vscode/src/agent/workspaceTools.ts holds for the native agent — the
// browser agent must not be the weaker of the two):
//   1. NOTHING leaves the granted root. Every model-supplied path goes through
//      paths.confinePath() here, before any host sees it.
//   2. NOTHING mutating happens without the person's say-so. A write is
//      proposed as a diff and applied only on "approve"; a command is proposed
//      as its exact command line. A deny-listed command is never covered by
//      "allow all" (commands.ts), and a blocked one never runs at all.
//   3. NOTHING throws. A tool failure is text the model reads and corrects;
//      an exception here would end a run that was one retry from succeeding.
//   4. Secrets stay out of the window. A path that looks like credentials is
//      read only after an explicit yes for THAT file, and search skips them.
//   5. `.git` is off limits — list, read, edit and write alike. Its hooks and
//      config are text the person's next git command EXECUTES, so a write there
//      is a command run without the command gate; and .git/config can carry a
//      remote URL with a token in it. The native reference agent excludes it too.

import { unifiedDiffResult } from "../localcode/diff";
import type { ContextBudget } from "./budget";
import { classifyCommand } from "./commands";
import { confinePath, isSecretPath, isVcsInternalPath } from "./paths";
import {
  EDIT_FILE,
  LIST_DIR,
  READ_FILE,
  RUN_COMMAND,
  SEARCH_TEXT,
  UPDATE_PLAN,
  WRITE_FILE,
  parsePlanArgs,
  toolNames,
} from "./protocol";
import { MAX_HITS_PER_FILE, describeSlice, sliceFile } from "./slices";
import { truncateToTokens } from "./tokens";
import type {
  AgentHost,
  AgentRunState,
  AgentToolCall,
  ApprovalDecision,
  ApprovalRequest,
  FileSlice,
  HostFailure,
} from "./types";

export const COMMAND_TIMEOUT_MS = 120_000;
const MAX_LIST_ENTRIES = 200;
const MAX_REMEMBERED_HIT_FILES = 40;

/**
 * Same floor, same reason, as lib/localcode/edit.ts MIN_UPDATED_RATIO: a small
 * model asked for a whole file will sometimes emit an opening fragment and
 * stop, and written back that is a silent deletion of someone's work. Restated
 * rather than imported because edit.ts pulls in the whole edge engine and this
 * module is meant to stay testable without it. tools.test.ts pins the value,
 * the boundary either side of it, and (by reading edit.ts as text) that the
 * two constants have not drifted apart.
 */
export const MIN_REWRITE_RATIO = 0.4;

/** Per-run "allow all" switches. Held in memory by the loop and NEVER saved:
 *  they die with the run, and a resumed run asks again. */
export interface RunApprovals {
  edit: boolean;
  command: boolean;
}

export interface ToolContext {
  host: AgentHost;
  state: AgentRunState;
  budget: ContextBudget;
  step: number;
  approvals: RunApprovals;
  approve: (request: ApprovalRequest) => Promise<ApprovalDecision>;
  signal?: AbortSignal;
}

export interface ToolResult {
  observation: string;
  failed: boolean;
  /** Replacement state (plan, working set, hits, epoch…). */
  state: AgentRunState;
  /** The person pressed "stop" inside an approval. */
  stop?: boolean;
}

function str(args: Record<string, unknown>, key: string): string | null {
  const value = args[key];
  return typeof value === "string" && value.length > 0 ? value : null;
}

function int(args: Record<string, unknown>, key: string): number | null {
  const value = args[key];
  if (typeof value === "number" && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === "string" && /^\d+$/.test(value.trim())) return Number.parseInt(value.trim(), 10);
  return null;
}

const VCS_REFUSAL =
  "that path is inside .git, which this agent never reads or changes (its hooks and config are run by git). Work with the project's own files.";

// ---------------------------------------------------------------------------
// Line endings
// ---------------------------------------------------------------------------
// The model only ever sees LF text: slices.ts splits on /\r?\n/ and re-joins
// excerpts with "\n". A Windows checkout (Git for Windows defaults to
// core.autocrlf=true) is CRLF on disk, so a multi-line old_text copied from an
// excerpt can never be found verbatim, and a multi-line new_text would be
// spliced in with bare LF. Both directions are adapted to the FILE's convention.

/** True when most of the file's line breaks are CRLF. */
export function isCrlfFile(text: string): boolean {
  const crlf = text.split("\r\n").length - 1;
  if (crlf === 0) return false;
  const allLf = text.split("\n").length - 1;
  return crlf >= allLf - crlf;
}

/** Every line break as CRLF, without doubling one that already is. */
export function toCrlf(text: string): string {
  return text.replace(/\r\n/g, "\n").replace(/\n/g, "\r\n");
}

function hostFailureText(what: string, failure: HostFailure): string {
  return `${what}: ${failure.detail}`;
}

function withSlice(state: AgentRunState, slice: FileSlice): AgentRunState {
  return { ...state, workingSet: [...state.workingSet.filter((s) => s.path !== slice.path), slice] };
}

async function safeApprove(ctx: ToolContext, request: ApprovalRequest): Promise<ApprovalDecision> {
  try {
    const decision = await ctx.approve(request);
    return decision === "approve" || decision === "approve-all" || decision === "stop" ? decision : "reject";
  } catch {
    // An approval surface that broke has not said yes.
    return "reject";
  }
}

export async function executeTool(call: AgentToolCall, ctx: ToolContext): Promise<ToolResult> {
  const fail = (observation: string, state: AgentRunState = ctx.state): ToolResult => ({
    observation: `Error: ${observation}`,
    failed: true,
    state,
  });
  try {
    const hasTerminal = !!ctx.host.terminal;
    switch (call.name) {
      case LIST_DIR:
        return await listDir(call.args, ctx, fail);
      case READ_FILE:
        return await readFile(call.args, ctx, fail);
      case SEARCH_TEXT:
        return await searchText(call.args, ctx, fail);
      case EDIT_FILE:
        return await editFile(call.args, ctx, fail);
      case WRITE_FILE:
        return await writeFile(call.args, ctx, fail);
      case RUN_COMMAND:
        return await runCommand(call.args, ctx, fail);
      case UPDATE_PLAN: {
        const plan = parsePlanArgs(call.args);
        if (!plan) return fail('update_plan needs "items": a list of {"text", "status"} entries');
        const done = plan.filter((p) => p.status === "done").length;
        return {
          observation: `Plan updated: ${plan.length} step(s), ${done} done.`,
          failed: false,
          state: { ...ctx.state, plan },
        };
      }
      default:
        // Hallucinated tool name — routine for small models; say what exists.
        return fail(`there is no tool named "${call.name}". Available tools: ${toolNames(hasTerminal).join(", ")}`);
    }
  } catch (error) {
    return fail(error instanceof Error && error.message ? error.message : "the tool failed unexpectedly");
  }
}

type Fail = (observation: string, state?: AgentRunState) => ToolResult;

async function listDir(args: Record<string, unknown>, ctx: ToolContext, fail: Fail): Promise<ToolResult> {
  if (!ctx.host.fs) return fail("this host cannot read files");
  const confined = confinePath(str(args, "path") ?? ".");
  if (confined.ok !== true) return fail(`cannot list that path — ${(confined as { reason: string }).reason}`);
  if (isVcsInternalPath(confined.path)) return fail(VCS_REFUSAL);
  const listed = await ctx.host.fs.list(confined.path);
  if (listed.ok !== true) return fail(hostFailureText(`could not list "${confined.path || "."}"`, listed as HostFailure));
  const entries = [...listed.entries].sort((a, b) =>
    a.kind === b.kind ? a.name.localeCompare(b.name) : a.kind === "directory" ? -1 : 1
  );
  if (entries.length === 0) return { observation: `(empty folder: ${confined.path || "."})`, failed: false, state: ctx.state };
  const shown = entries.slice(0, MAX_LIST_ENTRIES).map((e) => (e.kind === "directory" ? `${e.name}/` : e.name));
  const more = entries.length > MAX_LIST_ENTRIES ? `\n… and ${entries.length - MAX_LIST_ENTRIES} more (list a subfolder instead)` : "";
  return { observation: `${confined.path || "."}:\n${shown.join("\n")}${more}`, failed: false, state: ctx.state };
}

async function readFile(args: Record<string, unknown>, ctx: ToolContext, fail: Fail): Promise<ToolResult> {
  if (!ctx.host.fs) return fail("this host cannot read files");
  const confined = confinePath(str(args, "path"));
  if (confined.ok !== true) return fail(`cannot read that path — ${(confined as { reason: string }).reason}`);
  if (confined.segments.length === 0) return fail('read_file needs a file "path", not the project root');
  if (isVcsInternalPath(confined.path)) return fail(VCS_REFUSAL);
  const path = confined.path;

  let state = ctx.state;
  if (isSecretPath(path) && !state.secretReadsAllowed.includes(path)) {
    const decision = await safeApprove(ctx, { kind: "secret-read", path });
    if (decision === "stop") return { observation: "The user stopped the run.", failed: true, state, stop: true };
    // "approve-all" is deliberately read as a yes for THIS file only: there is
    // no such thing as a blanket yes to reading credentials.
    if (decision !== "approve" && decision !== "approve-all") {
      return fail(`${path} looks like it holds secrets and the user did not allow reading it. Work without its contents.`);
    }
    state = { ...state, secretReadsAllowed: [...state.secretReadsAllowed, path] };
  }

  const read = await ctx.host.fs.read(path);
  if (read.ok !== true) return fail(hostFailureText(`could not read ${path}`, read as HostFailure), state);
  if (read.exists !== true) return fail(`no such file: ${path}. Use list_dir or search_text to find the real path.`, state);

  const slice = sliceFile({
    path,
    contents: read.contents,
    capTokens: ctx.budget.perFileCapTokens,
    step: ctx.step,
    startLine: int(args, "start_line"),
    endLine: int(args, "end_line"),
    hitLines: state.recentHits[path],
  });
  return {
    observation: `Opened — see FILE EXCERPTS: ${describeSlice(slice)}`,
    failed: false,
    state: withSlice(state, slice),
  };
}

async function searchText(args: Record<string, unknown>, ctx: ToolContext, fail: Fail): Promise<ToolResult> {
  if (!ctx.host.fs) return fail("this host cannot search files");
  const query = str(args, "query") ?? str(args, "pattern") ?? str(args, "text");
  if (!query) return fail('search_text needs a "query"');
  const include = str(args, "include") ?? undefined;
  const found = await ctx.host.fs.search(query, { isRegex: args.is_regex === true, include, signal: ctx.signal });
  if (found.ok !== true) return fail(hostFailureText("search failed", found as HostFailure));

  // "Narrow it with include" is real advice: the host never opens a file the
  // glob excludes and skips folders outside its literal prefix. It is NOT
  // useful when nothing at all could be scanned, so that case says so instead.
  const stopped = !found.truncated
    ? ""
    : found.filesScanned === 0
      ? `\n(the search stopped before any file could be scanned: ${found.truncatedWhy || "limit reached"}. This is NOT proof the text is absent. Search one folder at a time with "include", e.g. "src/**", or use list_dir and read_file.)`
      : `\n(search stopped early: ${found.truncatedWhy || "limit reached"} — the rest of the project was NOT searched; narrow it with "include", e.g. "src/**")`;
  if (found.hits.length === 0) {
    return {
      observation: `No matches for ${JSON.stringify(query)} in ${found.filesScanned} file(s).${stopped}`,
      failed: false,
      state: ctx.state,
    };
  }

  // Remember WHERE it matched, so a later read_file of a big file opens at the
  // matches instead of at line 1 (slices.ts).
  const recentHits: Record<string, number[]> = { ...ctx.state.recentHits };
  for (const hit of found.hits) {
    const lines = recentHits[hit.path] ? [...recentHits[hit.path]] : [];
    if (!lines.includes(hit.line) && lines.length < MAX_HITS_PER_FILE) lines.push(hit.line);
    // Re-insert so the most recently matched files are last (and survive the cap).
    delete recentHits[hit.path];
    recentHits[hit.path] = lines;
  }
  const keys = Object.keys(recentHits);
  for (const stale of keys.slice(0, Math.max(0, keys.length - MAX_REMEMBERED_HIT_FILES))) delete recentHits[stale];

  const body = found.hits.map((h) => `${h.path}:${h.line}: ${h.text}`).join("\n");
  const observation = truncateToTokens(
    `${found.hits.length} match(es) in ${found.filesScanned} file(s):\n${body}${stopped}`,
    ctx.budget.perObservationCapTokens,
    "head"
  );
  return { observation, failed: false, state: { ...ctx.state, recentHits } };
}

/** 1-based line containing `offset`. */
function lineAt(text: string, offset: number): number {
  return text.slice(0, Math.max(0, offset)).split(/\r?\n/).length;
}

async function editFile(args: Record<string, unknown>, ctx: ToolContext, fail: Fail): Promise<ToolResult> {
  if (!ctx.host.fs) return fail("this host cannot edit files");
  const confined = confinePath(str(args, "path"));
  if (confined.ok !== true) return fail(`cannot edit that path — ${(confined as { reason: string }).reason}`);
  if (confined.segments.length === 0) return fail('edit_file needs a file "path"');
  if (isVcsInternalPath(confined.path)) return fail(VCS_REFUSAL);
  const path = confined.path;
  const oldText = typeof args.old_text === "string" ? args.old_text : null;
  const newText = typeof args.new_text === "string" ? args.new_text : null;
  if (oldText === null || newText === null) return fail('edit_file needs "path", "old_text" and "new_text"');
  if (oldText === "") return fail("old_text must not be empty — use write_file to create a file");
  if (oldText === newText) return fail("old_text and new_text are identical, so there is nothing to change");
  if (isSecretPath(path)) return fail(`${path} looks like it holds secrets; this agent does not edit such files`);

  const read = await ctx.host.fs.read(path);
  if (read.ok !== true) return fail(hostFailureText(`could not read ${path}`, read as HostFailure));
  if (read.exists !== true) return fail(`no such file: ${path}`);
  const original = read.contents;

  // Exact text first; then, for a file with CRLF line breaks, the same snippet
  // with the file's line endings (see "Line endings" above). new_text follows
  // the file's dominant convention so an edit never leaves mixed endings.
  let needle = oldText;
  if (original.indexOf(needle) < 0 && original.includes("\r\n") && oldText.includes("\n")) {
    const adapted = toCrlf(oldText);
    if (original.indexOf(adapted) >= 0) needle = adapted;
  }
  const replacement = isCrlfFile(original) ? toCrlf(newText) : newText;
  if (needle === replacement) return fail("old_text and new_text are identical, so there is nothing to change");

  // Uniqueness is a requirement, not a preference (clients/shared/src/agent/
  // editPlan.ts): picking the first of two matches would make the edit
  // silently position-dependent.
  const offset = original.indexOf(needle);
  if (offset < 0) {
    return fail(
      `old_text was not found in ${path}. It must match the file exactly, including indentation — and must NOT include the line numbers shown in excerpts. Re-read the lines and copy them exactly.`
    );
  }
  if (original.indexOf(needle, offset + needle.length) >= 0) {
    return fail(`old_text occurs more than once in ${path}. Include more surrounding lines so it identifies exactly one place.`);
  }
  const updated = original.slice(0, offset) + replacement + original.slice(offset + needle.length);
  return proposeWrite(ctx, fail, path, original, updated, lineAt(original, offset));
}

async function writeFile(args: Record<string, unknown>, ctx: ToolContext, fail: Fail): Promise<ToolResult> {
  if (!ctx.host.fs) return fail("this host cannot write files");
  const confined = confinePath(str(args, "path"));
  if (confined.ok !== true) return fail(`cannot write that path — ${(confined as { reason: string }).reason}`);
  if (confined.segments.length === 0) return fail('write_file needs a file "path"');
  if (isVcsInternalPath(confined.path)) return fail(VCS_REFUSAL);
  const path = confined.path;
  const given = typeof args.content === "string" ? args.content : typeof args.contents === "string" ? args.contents : null;
  if (given === null) return fail('write_file needs "path" and "content"');
  if (isSecretPath(path)) return fail(`${path} looks like it holds secrets; this agent does not write such files`);

  const read = await ctx.host.fs.read(path);
  if (read.ok !== true) return fail(hostFailureText(`could not check ${path}`, read as HostFailure));
  const original = read.exists === true ? read.contents : null;
  // Overwriting a CRLF file keeps it CRLF: the model writes LF, and a whole-file
  // line-ending flip would bury the real change in the diff the person reviews.
  const content = original !== null && isCrlfFile(original) ? toCrlf(given) : given;
  if (original !== null && original === content) {
    return { observation: `${path} already contains exactly that text — nothing to write.`, failed: false, state: ctx.state };
  }
  if (original !== null && original.length > 0 && content.length < original.length * MIN_REWRITE_RATIO) {
    return fail(
      `refusing to replace ${path} (${original.length} characters) with much shorter text (${content.length}) — that usually means the file was cut off. Change existing files with edit_file instead.`
    );
  }
  if (original === null && !content.trim()) return fail("refusing to create an empty file");
  return proposeWrite(ctx, fail, path, original, content, 1);
}

async function proposeWrite(
  ctx: ToolContext,
  fail: Fail,
  path: string,
  original: string | null,
  updated: string,
  line: number
): Promise<ToolResult> {
  const fs = ctx.host.fs;
  if (!fs) return fail("this host cannot write files");

  if (!ctx.approvals.edit) {
    const rendered = unifiedDiffResult(original ?? "", updated, path);
    const decision = await safeApprove(ctx, {
      kind: "write",
      path,
      original,
      updated,
      diff: rendered.text,
      coarse: rendered.coarse,
    });
    if (decision === "stop") return { observation: "The user stopped the run.", failed: true, state: ctx.state, stop: true };
    if (decision === "reject") {
      return fail(`the user declined the change to ${path}. Do not propose the same change again — reconsider, or finish and explain.`);
    }
    if (decision === "approve-all") ctx.approvals.edit = true;
  }

  // The person may have spent minutes in front of that diff with their editor
  // open on the same folder. What they approved describes `original`; if the
  // file is no longer that, there is nothing honest left to write. Refuse, never
  // merge (lib/localcode/edit.ts applyLocalEdits holds the same line).
  const fresh = await fs.read(path);
  if (fresh.ok !== true) return fail(hostFailureText(`could not re-check ${path} before writing`, fresh as HostFailure));
  const freshText = fresh.exists === true ? fresh.contents : null;
  if (freshText !== original) {
    return fail(`${path} changed on disk while the change waited for approval, so nothing was written. Re-read it and redo the edit.`);
  }

  const wrote = await fs.write(path, updated);
  if (wrote.ok !== true) return fail(hostFailureText(`could not write ${path}`, wrote as HostFailure));

  // Refresh the excerpt around the edit: the model's next step must see the
  // file as it IS, not the pre-edit text still sitting in the working set.
  const slice = sliceFile({
    path,
    contents: updated,
    capTokens: ctx.budget.perFileCapTokens,
    step: ctx.step,
    hitLines: [line],
  });
  const state = withSlice({ ...ctx.state, mutationEpoch: ctx.state.mutationEpoch + 1 }, slice);
  return {
    observation: `${original === null ? "Created" : "Edited"} ${path}${original === null ? "" : ` at line ${line}`}. The excerpt in FILE EXCERPTS now shows the new text.`,
    failed: false,
    state,
  };
}

function tail(text: string, maxChars: number): string {
  return text.length > maxChars ? `… (earlier output cut)\n${text.slice(-maxChars)}` : text;
}

async function runCommand(args: Record<string, unknown>, ctx: ToolContext, fail: Fail): Promise<ToolResult> {
  const terminal = ctx.host.terminal;
  if (!terminal) {
    // Never a pretend result. The model is told it did NOT run.
    return fail(
      `${ctx.host.terminalUnavailableReason || "there is no terminal in this host"} The command was NOT run. Tell the user which command to run instead.`
    );
  }
  const command = str(args, "command");
  if (!command) return fail('run_command needs a "command"');
  const verdict = classifyCommand(command);
  if (verdict.verdict === "blocked") return fail(`refusing to run this command (${verdict.why}). It was NOT run.`);
  const confined = confinePath(str(args, "cwd") ?? ".");
  if (confined.ok !== true) return fail(`cwd is not inside the project — ${(confined as { reason: string }).reason}`);

  const danger = verdict.verdict === "dangerous" ? verdict.why : null;
  // "Allow all" never covers a flagged command: each one is asked for again.
  if (danger !== null || !ctx.approvals.command) {
    const decision = await safeApprove(ctx, { kind: "command", command, cwd: confined.path || ".", danger });
    if (decision === "stop") return { observation: "The user stopped the run.", failed: true, state: ctx.state, stop: true };
    if (decision === "reject") return fail("the user declined to run that command. It was NOT run.");
    if (decision === "approve-all" && danger === null) ctx.approvals.command = true;
  }

  const result = await terminal.run(command, confined.path || ".", COMMAND_TIMEOUT_MS, ctx.signal);
  if (result.ok !== true) return fail(hostFailureText("the command could not be run", result as HostFailure));
  const parts = [`exit code: ${result.exitCode === null ? "unknown (killed)" : result.exitCode}`];
  if (result.stdout.trim()) parts.push(`stdout:\n${tail(result.stdout, 20_000)}`);
  if (result.stderr.trim()) parts.push(`stderr:\n${tail(result.stderr, 20_000)}`);
  if (!result.stdout.trim() && !result.stderr.trim()) parts.push("(no output)");
  // A command may have rewritten files; identical reads afterwards are progress.
  // A non-zero exit is a normal result the model must read, not a tool failure.
  return {
    observation: truncateToTokens(parts.join("\n\n"), ctx.budget.perObservationCapTokens, "tail"),
    failed: false,
    state: { ...ctx.state, mutationEpoch: ctx.state.mutationEpoch + 1 },
  };
}
