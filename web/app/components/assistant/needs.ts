import { CONNECT_ITEMS, connectState, type ConnectItem, type StatusInput } from "../../connect/connectCatalog";

// "Connect first": what a request needs that may not be connected yet. Pure, no React and no network.
// The platform only gives an agent a service's tools once that service is connected (Google tools appear
// after Google is linked, channel tools after a channel is), so an agent cannot reliably tell the user
// "WhatsApp is not connected". The surface therefore looks at the message itself, checks the same
// connection status the /connect page shows, and asks the person to connect before the turn starts; it
// also hands the agent the same facts so a request it receives is never answered by guessing.

export type ConnectId = ConnectItem["id"];

/** A thing the request needs; met when ANY of `options` is connected (email can be a mailbox or Google). */
export interface Need {
  key: string;
  options: ConnectId[];
}

interface Rule {
  key: string;
  options: ConnectId[];
  pattern: RegExp;
}

const RULES: readonly Rule[] = [
  { key: "whatsapp", options: ["whatsapp"], pattern: /\bwhats\s?app\b/i },
  { key: "telegram", options: ["telegram"], pattern: /\btelegram\b/i },
  { key: "slack", options: ["slack"], pattern: /\bslack\b/i },
  { key: "sms", options: ["sms"], pattern: /\b(sms|text messages?)\b/i },
  { key: "email", options: ["email", "google"], pattern: /\b(e-?mails?|inbox|gmail)\b/i },
  {
    key: "google",
    options: ["google"],
    pattern: /\b(calendar|google (drive|docs?|sheets?)|g-?drive|spreadsheets?)\b/i,
  },
];

/**
 * Asking ABOUT a service ("what is WhatsApp") needs nothing; asking the assistant to DO something with it
 * does. Without an action word nothing is gated, so the check errs towards letting the turn run.
 */
const ACTION =
  /\b(read|get|check|show|send|reply|summari[sz]e|find|search|list|fetch|add|book|schedule|put|create|forward|draft|write|look|pull|export|upload|share|clear|delete|move|cancel|remind|monitor|watch|count|tell)\b/i;

export function detectNeeds(message: string): Need[] {
  if (!ACTION.test(message)) return [];
  return RULES.filter((r) => r.pattern.test(message)).map(({ key, options }) => ({ key, options }));
}

function stateOf(id: ConnectId, status: StatusInput) {
  const item = CONNECT_ITEMS.find((i) => i.id === id);
  return item ? connectState(item, status) : "unknown";
}

/** A need is unmet only when every option is known to be NOT connected; unknown never blocks a turn. */
export function isUnmet(need: Need, status: StatusInput): boolean {
  const states = need.options.map((id) => stateOf(id, status));
  return states.length > 0 && states.every((s) => s === "not-connected");
}

export function unmetNeeds(needs: readonly Need[], status: StatusInput): Need[] {
  return needs.filter((n) => isUnmet(n, status));
}

export function connectHref(id: ConnectId): string {
  return CONNECT_ITEMS.find((i) => i.id === id)?.href ?? "/connect";
}

// ---- what the agent is told ----------------------------------------------------------------------

const AGENT_LABELS: Partial<Record<ConnectId, string>> = {
  telegram: "Telegram",
  whatsapp: "WhatsApp",
  email: "Email",
  google: "Google (Calendar, Gmail, Drive, Sheets)",
  slack: "Slack",
  sms: "SMS",
};

const CONTEXT_OPEN = "<app_context>";
const CONTEXT_CLOSE = "</app_context>";
const CONTEXT_BLOCK = /^<app_context>[\s\S]*?<\/app_context>\s*/;

/** The connection facts as one short block, or null when nothing is known or everything is connected. */
export function connectionsContext(status: StatusInput): string | null {
  const connected: string[] = [];
  const missing: string[] = [];
  for (const item of CONNECT_ITEMS) {
    const label = AGENT_LABELS[item.id];
    if (!label) continue;
    const s = connectState(item, status);
    if (s === "connected") connected.push(label);
    else if (s === "not-connected") missing.push(label);
  }
  if (missing.length === 0) return null;
  return [
    CONTEXT_OPEN,
    "Added by the app, not typed by the user.",
    `Connected: ${connected.length ? connected.join(", ") : "none"}.`,
    `Not connected: ${missing.join(", ")}.`,
    "If the request needs a service that is not connected, do not guess and do not invent results: tell the user which service to connect (the Connect page, /connect, does it) and wait. Use what is connected and every other tool you have.",
    CONTEXT_CLOSE,
  ].join("\n");
}

/** What the model is told about the folder the person attached (null: none attached). */
export function folderContextLines(folder: string | null): string[] {
  if (!folder) return [];
  return [
    `Attached folder: "${folder}" in the user's projects folder. Do this work there: pass folder "${folder}" to local_shell and local_shell_read, and start every path for workspace_list, workspace_read, workspace_write and workspace_edit with "${folder}/".`,
    "Keep going step by step until the task is done, but the moment a tool call is waiting for the user's approval, stop and wait; when you are asked to continue after an approval, pick up where you stopped.",
  ];
}

/**
 * What the model is told about files the person attached in the box and the app saved in the projects
 * folder: where each one is, and which tools find things in them. Paths are the app's own (the server
 * chose them), never the person's text.
 */
export function attachedFilesContextLines(files: ReadonlyArray<{ workspacePath?: string; workspaceTextPath?: string | null }>): string[] {
  const saved = files.filter((f): f is { workspacePath: string; workspaceTextPath?: string | null } => !!f.workspacePath);
  if (saved.length === 0) return [];
  const list = saved.map((f) => `- "${f.workspacePath}"${f.workspaceTextPath ? ` (its text is in "${f.workspaceTextPath}")` : ""}`);
  return [
    "The user attached these files to this message; they are saved in the projects folder:",
    ...list,
    "Look inside them with workspace_search (find words or passages; pass a folder as path, or leave it empty for everything) and workspace_read (read a file, or part of it with offset and limit). Never say you cannot see an attachment: search or read it. For a file you cannot read as text (a scan, a spreadsheet), say so plainly.",
  ];
}

/**
 * The facts the app knows for certain about an approved (or rejected) tool call, for the "carry on"
 * turn that follows it: the status and, for a command, its exit code. Never the output itself - that
 * is tool data (core wraps it as untrusted), and copying it into the user's message would hand
 * whatever a build log or a file says the user's authority.
 */
export function approvalOutcomeLines(outcome: { kind: string; detail: string | null }): string[] {
  if (outcome.kind === "rejected") return ["The user rejected the tool call you asked for."];
  const exit = /exit_code\\?"\s*:\s*(-?\d+)/.exec(outcome.detail ?? "")?.[1];
  const timedOut = /timed_out\\?"\s*:\s*true/.test(outcome.detail ?? "");
  const facts = [
    outcome.kind === "failed"
      ? "The tool call you asked for was approved but failed."
      : "The tool call you asked for was approved and ran.",
  ];
  if (exit !== undefined) facts.push(`Exit code: ${exit}${timedOut ? " (stopped at its time limit)" : ""}.`);
  facts.push(
    "A note with its output is in this conversation's history: read it, then continue (fix what failed, or go on to the next step)."
  );
  return facts;
}

/** The connection facts and the attached folder as one app_context block, or null when there is nothing to say. */
export function assistantContext(status: StatusInput, folder: string | null, extraLines: string[] = []): string | null {
  const connections = connectionsContext(status);
  const folderLines = [...folderContextLines(folder), ...extraLines];
  if (folderLines.length === 0) return connections;
  const inner = connections
    ? connections
        .slice(CONTEXT_OPEN.length, connections.length - CONTEXT_CLOSE.length)
        .trim()
        .split("\n")
    : ["Added by the app, not typed by the user."];
  return [CONTEXT_OPEN, ...inner, ...folderLines, CONTEXT_CLOSE].join("\n");
}

export function withAppContext(message: string, context: string | null): string {
  return context ? `${context}\n\n${message}` : message;
}

/** A stored transcript shows what the person typed, not the block the app added. */
export function stripAppContext(content: string): string {
  return content.replace(CONTEXT_BLOCK, "");
}
