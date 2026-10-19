// agentkit — every user-facing sentence of the agent panel, in one place.
//
// Plain English strings rather than t("…") keys, on purpose (2026-09-19):
// web/messages/*.json is edited by several sessions at once and en.json is
// fragile, and a t() call for a key that does not exist renders the raw key
// path to the person. When these are moved into the message catalogue, this
// file is the complete list to move.
//
// NO-FICTION RULE. Every sentence here describes code that exists in
// lib/agentkit. In particular nothing says or implies that commands can be run
// from this page — they cannot — and nothing names a model, a speed or a
// benchmark.

import type { RunStatus } from "./types";

export const AGENT_COPY = {
  title: "Give the on-device agent a task",
  body:
    "Describe a change or a question that takes several steps. The agent plans, then searches, reads and edits files in the folder you opened — one step at a time, on the model running in this browser. It never writes a file until you have seen the diff and approved it.",
  taskPlaceholder: "e.g. Find where the retry delay is set and make it configurable through an environment variable.",
  start: "Start",
  stop: "Stop",
  continueRun: "Continue",
  discardRun: "Discard this run",
  newTask: "New task",
  needFolder: "Open a folder above first — the agent works inside it and nowhere else.",
  checkingModel: "Checking for an on-device model…",
  recheckModel: "Check again",
  runningLabel: "Running…",
  savedRunTitle: "An unfinished run was found for this folder",
  savedRunBody:
    "It was saved on this device only. Continuing rebuilds the model's context from the saved plan and steps, and the model takes the interrupted step again. A change that was waiting for your approval was not saved — the model may propose it again, propose something different, or nothing. Nothing is written without your approval either way.",
  terminalTitle: "What this agent cannot do here",
  terminalBody:
    "A browser tab cannot run terminal commands, so this agent cannot run your tests, your build or git. It will tell you which command to run instead of claiming a result. To have an agent run commands on your machine (each one with your approval), open the project in the VegaDūta extension for VS Code and use “Start a Coding Task”, or use the VegaDūta JetBrains plugin.",
  planTitle: "Plan",
  planEmpty: "No plan yet. The agent is asked to write one first on multi-step tasks; a small model sometimes skips it.",
  stepsTitle: "Steps",
  approvalTitle: "Waiting for your approval",
  approvalNone: "Nothing is waiting for you.",
  approveWriteHint: "Review the diff. Applying asks the browser for permission to edit files in this folder the first time.",
  coarseDiff: "This file pair was too large for a line-by-line diff, so the whole file is shown as replaced. Read it with extra care before applying.",
  newFile: "This creates a new file.",
  secretTitle: "The agent wants to read a file that looks like it holds secrets",
  secretBody:
    "Its contents would be placed in the on-device model's context. They stay in this browser, and are not kept in the saved run. Allow only if the task really needs it.",
  allowOnce: "Allow for this file",
  deny: "Don't allow",
  commandTitle: "The agent wants to run a command",
  commandFlagged: "This command was flagged as risky:",
  runCommand: "Run it",
  dontRun: "Don't run",
  meterTitle: "Context window",
  meterBody: "What the model can see on the current step. Small on-device models have small windows, so older material is pushed out in a fixed order: oldest tool output first, then old steps are summarised, then the least recently used file excerpts.",
  meterEmpty: "Shown once a run starts.",
  meterReserved: "held back for the model's reply and a safety margin",
  evictedTitle: "Pushed out to make room",
  evictedNone: "Nothing has been pushed out yet.",
  filesInWindow: "File excerpts in the window",
  answerTitle: "Result",
  estimateNote: "Token counts are estimates (about 3.5 characters per token) — the browser engines expose no tokenizer.",
} as const;

export function statusLine(status: RunStatus, step: number): string {
  switch (status) {
    case "running":
      return step > 0 ? `Working — step ${step}` : "Starting…";
    case "awaiting-approval":
      return "Paused — waiting for your approval";
    case "finished":
      return "Finished";
    case "stopped":
      return "Stopped";
    case "stalled":
      return "Stopped — the model was going in circles";
    case "step-budget":
      return "Paused — step limit reached";
    case "token-budget":
      return "Paused — token limit reached";
    case "window-too-small":
      return "Can't run — context window too small";
    case "failed":
      return "Stopped — something went wrong";
    default:
      return "";
  }
}
