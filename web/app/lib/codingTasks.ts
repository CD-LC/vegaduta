// Coding tasks (core com.agenticai.core.agents.coding, /api/coding-tasks): types plus the pure helpers the /coding page
// uses - status tones, the diff line classifier, the form's validation and the elapsed-time label. No React, no fetch,
// no clock of its own, so every rule is unit-tested (codingTasks.test.ts).
//
// The server is the source of truth: it re-validates every input (allowlisted test runner, public https git URL or a
// sanitised archive, clamped budget) and it - not this page - decides whether a change may be applied: the harness
// blocks on the user's approval of the exact diff (bound by diffHash), and nothing here can answer that gate for the
// user.

export type CodingTaskStatus =
  | "QUEUED"
  | "RUNNING"
  | "AWAITING_APPROVAL"
  | "APPROVED"
  | "REJECTED"
  | "FAILED"
  | "CANCELLED"
  | "ROLLED_BACK";

export const CODING_TASK_STATUSES: readonly CodingTaskStatus[] = [
  "QUEUED",
  "RUNNING",
  "AWAITING_APPROVAL",
  "APPROVED",
  "REJECTED",
  "FAILED",
  "CANCELLED",
  "ROLLED_BACK",
];

export interface CodingBudget {
  maxSteps: number;
  maxWallSeconds: number;
  maxTokens: number;
  maxFixIterations: number;
}

/** GET /api/coding-tasks/capabilities - readable even while the feature is off. */
export interface CodingTasksConfig {
  enabled: boolean;
  /** false with a `reason` (PLAN = upgrade needed, SETUP = no sandbox key/template yet) when enabled but unusable. */
  allowed: boolean;
  reason: "PLAN" | "SETUP" | null;
  runner: string;
  testRunners: string[];
  defaults: CodingBudget;
  caps: CodingBudget;
  maxArchiveBytes: number;
  approvalTimeoutSeconds: number;
  rollbackWindowSeconds: number;
  networkEnabled: boolean;
}

export interface CodingTaskEvent {
  atMs: number;
  type: string;
  text: string;
}

export interface CodingTestRun {
  iteration: number;
  exitCode: number;
  outputTail: string;
}

export interface CodingTask {
  taskId: string;
  agentId: string;
  status: CodingTaskStatus;
  outcome: string | null;
  phase: string;
  instruction: string;
  testCommand: string;
  sourceKind: "GIT" | "ARCHIVE";
  repoUrl: string | null;
  runner: string;
  budget: CodingBudget;
  usage: { steps: number; tokens: number; elapsedMs: number };
  events: CodingTaskEvent[];
  tests: CodingTestRun[];
  review: { approved: boolean; notes: string } | null;
  diffAvailable: boolean;
  diffHash: string | null;
  diffFiles: number;
  testFilesTouched: boolean;
  message: string | null;
  cancelRequested: boolean;
  createdAtMs: number;
  updatedAtMs: number;
}

export interface CodingTaskDiff {
  taskId: string;
  diff: string;
  diffHash: string;
  truncated: boolean;
}

export function isTerminalStatus(status: string): boolean {
  return (
    status === "APPROVED" ||
    status === "REJECTED" ||
    status === "FAILED" ||
    status === "CANCELLED" ||
    status === "ROLLED_BACK"
  );
}

/** Which of the user's actions make sense for a task in this status (the server enforces the same rules). */
export function allowedActions(task: Pick<CodingTask, "status" | "diffAvailable" | "diffHash">): {
  approve: boolean;
  reject: boolean;
  cancel: boolean;
  rollback: boolean;
  patch: boolean;
} {
  const awaiting = task.status === "AWAITING_APPROVAL" && task.diffAvailable && !!task.diffHash;
  return {
    approve: awaiting,
    reject: task.status === "AWAITING_APPROVAL",
    cancel: !isTerminalStatus(task.status),
    rollback: task.status === "APPROVED",
    patch: (task.status === "APPROVED" || task.status === "AWAITING_APPROVAL") && task.diffAvailable,
  };
}

export type StatusTone = "success" | "warning" | "error" | "info" | "idle";

export function statusTone(status: string): StatusTone {
  switch (status) {
    case "APPROVED":
      return "success";
    case "AWAITING_APPROVAL":
      return "warning";
    case "FAILED":
      return "error";
    case "RUNNING":
    case "QUEUED":
      return "info";
    default:
      return "idle";
  }
}

export type DiffLineKind = "add" | "del" | "hunk" | "file" | "meta" | "ctx";

export interface DiffLine {
  kind: DiffLineKind;
  text: string;
}

/** Classifies a unified diff line by line for colouring. Pure and total: any text yields lines. */
export function classifyDiff(diff: string): DiffLine[] {
  if (!diff) return [];
  return diff.split("\n").map((raw): DiffLine => {
    const text = raw.replace(/\r$/, "");
    if (text.startsWith("diff --git")) return { kind: "file", text };
    if (text.startsWith("@@")) return { kind: "hunk", text };
    if (text.startsWith("+++") || text.startsWith("---") || text.startsWith("index ") || text.startsWith("new file") || text.startsWith("deleted file") || text.startsWith("similarity ") || text.startsWith("rename ")) {
      return { kind: "meta", text };
    }
    if (text.startsWith("+")) return { kind: "add", text };
    if (text.startsWith("-")) return { kind: "del", text };
    return { kind: "ctx", text };
  });
}

export function diffStats(diff: string): { added: number; removed: number } {
  let added = 0;
  let removed = 0;
  for (const line of classifyDiff(diff)) {
    if (line.kind === "add") added += 1;
    else if (line.kind === "del") removed += 1;
  }
  return { added, removed };
}

export type SourceKind = "git" | "archive";

export interface CodingTaskDraft {
  agentId: string;
  instruction: string;
  source: SourceKind;
  repoUrl: string;
  archive: File | null;
  testRunner: string;
  testArgs: string;
  maxMinutes: string;
}

/** The test command as the server will see it: runner plus the optional arguments, single-spaced. */
export function testCommandOf(draft: Pick<CodingTaskDraft, "testRunner" | "testArgs">): string {
  return `${draft.testRunner} ${draft.testArgs}`.trim().replace(/\s+/g, " ");
}

/** A public https git URL (the server also resolves the host and refuses private ranges). */
export function looksLikePublicGitUrl(url: string): boolean {
  const u = url.trim();
  if (!/^https:\/\/[a-z0-9.-]+\.[a-z]{2,}(\/[A-Za-z0-9._~-]+){1,6}$/i.test(u)) return false;
  return !u.includes("..");
}

export type DraftProblem =
  | "problem_agentRequired"
  | "problem_instructionRequired"
  | "problem_instructionTooLong"
  | "problem_repoUrl"
  | "problem_archiveRequired"
  | "problem_archiveTooLarge"
  | "problem_testRunner"
  | "problem_testArgs"
  | "problem_minutes";

/** First problem with the draft, or null when it can be sent. The server re-validates all of it. */
export function validateDraft(
  draft: CodingTaskDraft,
  config: Pick<CodingTasksConfig, "testRunners" | "maxArchiveBytes" | "caps">,
  maxInstructionChars = 4000
): DraftProblem | null {
  if (!draft.agentId) return "problem_agentRequired";
  const instruction = draft.instruction.trim();
  if (!instruction) return "problem_instructionRequired";
  if (instruction.length > maxInstructionChars) return "problem_instructionTooLong";
  if (draft.source === "git") {
    if (!looksLikePublicGitUrl(draft.repoUrl)) return "problem_repoUrl";
  } else {
    if (!draft.archive) return "problem_archiveRequired";
    if (draft.archive.size > config.maxArchiveBytes) return "problem_archiveTooLarge";
  }
  if (!config.testRunners.includes(draft.testRunner)) return "problem_testRunner";
  // Mirrors the server's token rule: plain words, flags and relative paths only - no shell syntax at all.
  if (draft.testArgs.trim() && !/^[A-Za-z0-9_./:=,@+\- ]+$/.test(draft.testArgs.trim())) return "problem_testArgs";
  if (draft.maxMinutes.trim()) {
    const m = Number(draft.maxMinutes);
    if (!Number.isFinite(m) || m < 1 || m * 60 > config.caps.maxWallSeconds) return "problem_minutes";
  }
  return null;
}

/** FormData for POST /api/coding-tasks. Budget fields are optional; the server clamps them to its caps. */
export function buildStartForm(draft: CodingTaskDraft): FormData {
  const form = new FormData();
  form.append("agentId", draft.agentId);
  form.append("instruction", draft.instruction.trim());
  form.append("testCommand", testCommandOf(draft));
  if (draft.source === "git") form.append("repoUrl", draft.repoUrl.trim());
  else if (draft.archive) form.append("archive", draft.archive);
  if (draft.maxMinutes.trim()) form.append("maxWallSeconds", String(Math.round(Number(draft.maxMinutes) * 60)));
  return form;
}

/** "1m 05s" / "42s" from milliseconds. */
export function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return m > 0 ? `${m}m ${String(s).padStart(2, "0")}s` : `${s}s`;
}
