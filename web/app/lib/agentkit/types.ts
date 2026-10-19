// agentkit — the shared vocabulary for the on-device coding agent.
//
// WHY THIS EXISTS (2026-09-19). The ask was "WebLLM should run terminal
// commands, search folders and do everything a coding copilot does". The first
// half of that sentence is not something a browser tab can do: a web page has
// no way to spawn a process, and no amount of framework changes that. What a
// tab CAN do is read, search and (with a second permission) write a folder the
// person granted through the File System Access API, and run a local model.
//
// So the design splits on exactly that line. The agent loop, the context-window
// management and the safety gates in this directory are host-agnostic; WHERE a
// tool runs is an `AgentHost`. The browser host (browserHost.ts) has `fs` and
// nothing else. A native host has `terminal` too. The loop asks the host what
// it can do and only ever offers the model the tools that are really there —
// it never fakes a terminal, because an agent that says "tests pass" over a
// command that never ran is worse than one that says "I can't run that here".
//
// What was checked in clients/ before writing this (2026-09-19): the VS Code
// extension (clients/vscode/src/agent/workspaceTools.ts) and the JetBrains
// plugin (…/ide/agent/ProjectToolExecutor.kt) both run real commands — but
// IN-PROCESS, for their own agent loop. Neither exposes a localhost HTTP or
// WebSocket endpoint, a postMessage channel or any other bridge a web-app
// tab could call. The only 127.0.0.1 servers in clients/ are model runtimes
// (llama-server, Ollama, LM Studio): they complete text, they do not execute
// tools. The "18 offline tools" of clients 0.4.0 are in-webview text utilities
// (base64, regex, timestamps…), not a tool protocol. So there is no bridge to
// attach to today, and nativeBridge.ts is an interface plus an honest
// "unavailable", not an implementation.
//
// The tool names below deliberately match clients/shared/src/agent/
// codingTools.ts (list_dir, read_file, search_text, write_file, edit_file,
// run_command) so the day a bridge exists, the same six names mean the same six
// things on both sides of it.
//
// Same no-throw convention as lib/localcode and clients/shared/agent: nothing
// in an AgentHost throws at the loop. Every failure is a typed `{ ok: false }`.

// ---------------------------------------------------------------------------
// Host capabilities
// ---------------------------------------------------------------------------

export type HostFailureReason =
  | "outside-root" // the path tried to leave the granted folder
  | "not-found"
  | "unreadable" // exists, but the read failed (lock, revoked permission, stale handle)
  | "too-large"
  | "binary"
  | "permission-denied"
  | "unavailable" // this host does not have the capability at all
  | "invalid"
  | "aborted"
  | "failed";

export interface HostFailure {
  ok: false;
  reason: HostFailureReason;
  /** Plain-language detail. Safe to show a person AND to feed back to the
   *  model — never a stack trace. */
  detail: string;
}

export interface DirEntry {
  name: string;
  kind: "file" | "directory";
}

export interface SearchHit {
  path: string;
  /** 1-based. */
  line: number;
  /** The matching line, trimmed and capped. */
  text: string;
}

export interface SearchOutcome {
  ok: true;
  hits: SearchHit[];
  filesScanned: number;
  /** True when the scan stopped early (match cap, file cap or deadline). The
   *  tool result must SAY so: a truncated scan reported as a clean "no
   *  matches" is read by the model as proof the string is absent. */
  truncated: boolean;
  truncatedWhy?: string;
}

/**
 * Files within ONE granted root. Every `path` is root-relative and
 * "/"-separated. Implementations MUST confine: reject "..", absolute paths and
 * drive letters lexically (paths.ts does this and the loop calls it before the
 * host ever sees a path), and a NATIVE implementation must additionally
 * real-path the result against the root, because a repo can commit a symlink
 * that points outside it (clients/vscode/src/agent/containment.ts is the
 * reference). The browser host has no OS paths to resolve — see browserHost.ts
 * for what that does and does not guarantee.
 */
export interface HostFs {
  list(path: string): Promise<{ ok: true; entries: DirEntry[] } | HostFailure>;
  /** `exists: false` is a SUCCESS, not a failure: "there is no such file" is
   *  the answer write_file needs in order to create one, and it must not be
   *  confusable with "the read failed" (the conflation lib/localcode/
   *  fsAccess.readLocalFile documents as its own sharp edge). */
  read(path: string): Promise<{ ok: true; exists: true; contents: string } | { ok: true; exists: false } | HostFailure>;
  write(path: string, contents: string): Promise<{ ok: true } | HostFailure>;
  search(
    query: string,
    opts: { isRegex?: boolean; include?: string; signal?: AbortSignal }
  ): Promise<SearchOutcome | HostFailure>;
}

export interface TerminalResult {
  ok: true;
  stdout: string;
  stderr: string;
  /** null when the process was killed before it could report one. */
  exitCode: number | null;
}

export interface HostTerminal {
  run(command: string, cwd: string, timeoutMs: number, signal?: AbortSignal): Promise<TerminalResult | HostFailure>;
}

/** Declared for the interface's completeness; NO host in this repo implements
 *  them today and the loop offers the model no git or web tool. They exist so
 *  a native host can grow into them without reshaping AgentHost. */
export interface HostGit {
  status(): Promise<{ ok: true; text: string } | HostFailure>;
  diff(path?: string): Promise<{ ok: true; text: string } | HostFailure>;
}
export interface HostWeb {
  fetchText(url: string, signal?: AbortSignal): Promise<{ ok: true; text: string } | HostFailure>;
}

export interface AgentHost {
  /** "browser" | "native-bridge" … — for the UI and for logs. */
  kind: string;
  /** What the person sees as the project name (the folder's own name; a
   *  browser never learns the absolute path). */
  rootName: string;
  fs?: HostFs;
  terminal?: HostTerminal;
  git?: HostGit;
  web?: HostWeb;
  /** When `terminal` is absent: the sentence that tells a person (and the
   *  model) why, and what to do instead. */
  terminalUnavailableReason?: string;
}

// ---------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------

export type ModelFailureReason = "no-model" | "too-large" | "aborted" | "failed" | "empty";

export type ModelReply = { ok: true; text: string } | { ok: false; reason: ModelFailureReason; detail: string };

/**
 * One stateless round trip: a system prompt and ONE user message in, text out.
 * Stateless on purpose — the whole point of agentkit's context manager is that
 * IT decides what is in the window each step, so a model adapter that kept its
 * own conversation history would be a second, invisible window fighting the
 * first. Never throws.
 */
export interface AgentModel {
  id: string;
  /** The model's real context window in tokens (from the engine's own record —
   *  see edgeModel.ts — never a guess). */
  contextWindow: number;
  /** How many PROMPT tokens the engine will actually accept, when the adapter
   *  knows (tier 1 refuses up front past its own budget). Falls back to
   *  budget.ts's arithmetic when absent. */
  promptBudget?: number;
  /** The delimiter this model is asked to wrap a tool call in. Defaults to
   *  protocol.ts AGENT_CALL_TAG; the tier-1 adapter asks for "tool_call" (see
   *  edgeModel.ts for why it can). */
  callTag?: string;
  complete(system: string, user: string, signal?: AbortSignal): Promise<ModelReply>;
}

// ---------------------------------------------------------------------------
// Tool calls, plan, run state
// ---------------------------------------------------------------------------

export interface AgentToolCall {
  name: string;
  args: Record<string, unknown>;
}

export type PlanStatus = "todo" | "doing" | "done";

export interface PlanItem {
  text: string;
  status: PlanStatus;
}

/** One completed step, as the context manager stores it. `observation` is what
 *  the tool said; it is the first thing evicted under pressure. */
export interface AgentTurn {
  step: number;
  call: AgentToolCall | null;
  /** Prose the model wrote alongside / instead of a call (capped). */
  note: string;
  observation: string;
  /** Set once the observation text was dropped to make room. The stub line
   *  that replaces it is derived, not stored. */
  observationEvicted: boolean;
  failed: boolean;
}

/** A range of a file currently held in the window. Deduplicated by path: a
 *  second read of the same file REPLACES the first instead of holding two
 *  copies, which is the single biggest saving on a 4k window. */
export interface FileSlice {
  path: string;
  /** 1-based, inclusive. */
  startLine: number;
  endLine: number;
  totalLines: number;
  /** The line ranges really present in `text`, in order. More than one when the
   *  excerpt is built around search hits: startLine/endLine are then only the
   *  outer bounds, with gaps between. Absent on runs saved before 2026-09-19,
   *  which reads as one continuous range. */
  ranges?: Array<{ start: number; end: number }>;
  /** Already line-numbered text. */
  text: string;
  touchedStep: number;
}

export type RunStatus =
  | "idle"
  | "running"
  | "awaiting-approval"
  | "finished"
  | "stopped" // the person pressed Stop
  | "stalled" // loop detection ended it
  | "step-budget"
  | "token-budget"
  | "window-too-small"
  | "failed";

export interface AgentRunState {
  version: 1;
  task: string;
  plan: PlanItem[];
  turns: AgentTurn[];
  /** Turns folded away by compaction, one line each, oldest first. */
  summary: string[];
  /** How many summary lines were themselves dropped for room. Only the count
   *  survives, so the model can be told "there was more". */
  summaryDropped: number;
  workingSet: FileSlice[];
  /** Line numbers the most recent searches matched, per path (capped). A later
   *  read_file of a file too big for its cap is answered with the ranges
   *  around THESE lines instead of the file's first screenful — see
   *  slices.ts. */
  recentHits: Record<string, number[]>;
  stepsUsed: number;
  /** Estimated tokens spent (prompt + reply) across all model calls. */
  tokensUsed: number;
  /** Bumped on every applied write. Part of the stall fingerprint so that
   *  read → edit → read-again is progress, not a loop. */
  mutationEpoch: number;
  /** Fingerprints of the most recent calls (capped), for loop detection. */
  recentFingerprints: string[];
  /** Consecutive calls refused as repeats. Two ends the run as "stalled". */
  repeatCount: number;
  malformedStreak: number;
  status: RunStatus;
  /** Final prose, or the plain-language reason the run ended. */
  answer: string;
  /** Paths the person said yes to reading despite looking like secrets.
   *  Per run, per file — never a stored setting. */
  secretReadsAllowed: string[];
}

// ---------------------------------------------------------------------------
// Approvals
// ---------------------------------------------------------------------------

export type ApprovalRequest =
  | {
      kind: "write";
      path: string;
      /** null = the file does not exist yet. */
      original: string | null;
      updated: string;
      diff: string;
      coarse: boolean;
    }
  | {
      kind: "command";
      command: string;
      cwd: string;
      /** Set when the deny-list flagged it. A flagged command can be approved
       *  by a person looking at it, but is NEVER covered by "allow all". */
      danger: string | null;
    }
  | { kind: "secret-read"; path: string };

export type ApprovalDecision = "approve" | "approve-all" | "reject" | "stop";

export type AgentEvent =
  | { type: "state"; state: AgentRunState }
  | { type: "context"; report: import("./context").ContextReport }
  | { type: "model-start"; step: number }
  | { type: "tool-start"; step: number; call: AgentToolCall }
  | { type: "tool-end"; step: number; call: AgentToolCall; failed: boolean };
