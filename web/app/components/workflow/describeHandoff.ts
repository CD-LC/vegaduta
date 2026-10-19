// "Describe it, automate it" handoff between the Workflows list page and the
// canvas (nlflow W3 writes, W4 reads).
//
// The composer on /workflows creates a DRAFT workflow, then needs to tell the
// canvas at /workflows/<id> "build this sentence as soon as you mount". The
// prompt travels in sessionStorage, NOT in the URL: a description can name
// customers, amounts or phone numbers, and a query string ends up in browser
// history, server logs and Referer headers. sessionStorage is per-tab and
// gone when the tab closes, which is exactly the lifetime the handoff needs.
//
// Pure module: no React, no runtime imports. Every function takes an optional
// storage argument so the logic is testable without a DOM, and every storage
// touch is guarded - Safari private mode and blocked site data make the
// accessor itself throw. A failed write must never block the navigation; the
// canvas simply opens without an auto-build and the describe bar still works.

/** sessionStorage key prefix; the workflow id is appended. */
export const DESCRIBE_HANDOFF_PREFIX = "vd.wf.describe.";

/** Upper bound on what the composer accepts and what the handoff stores. */
export const DESCRIBE_PROMPT_MAX_CHARS = 2000;

/** How many leading words of the prompt become the workflow name. */
export const DESCRIBE_NAME_WORDS = 6;

/**
 * workflow_definition.name is VARCHAR(200) (V20); stay far inside it so six
 * very long words cannot produce a 400/500 on create.
 */
export const DESCRIBE_NAME_MAX_CHARS = 80;

type HandoffStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function defaultStorage(): HandoffStorage | null {
  try {
    if (typeof window === "undefined") return null;
    return window.sessionStorage ?? null;
  } catch {
    return null;
  }
}

function keyFor(workflowId: string): string {
  return `${DESCRIBE_HANDOFF_PREFIX}${workflowId}`;
}

/** Trim, collapse all whitespace runs to single spaces, cap the length. */
export function normalizeDescribePrompt(raw: unknown): string {
  if (typeof raw !== "string") return "";
  return raw.replace(/\s+/g, " ").trim().slice(0, DESCRIBE_PROMPT_MAX_CHARS);
}

/**
 * Workflow name from the first few words of the prompt, e.g.
 * "every monday pull unpaid invoices and whatsapp a reminder" ->
 * "Every monday pull unpaid invoices and". Returns "" when the prompt has no
 * usable words, so the caller can substitute its translated fallback name.
 */
export function deriveWorkflowName(prompt: unknown, maxWords: number = DESCRIBE_NAME_WORDS): string {
  const words = normalizeDescribePrompt(prompt).split(" ").filter(Boolean);
  if (words.length === 0) return "";
  let name = words.slice(0, Math.max(1, maxWords)).join(" ");
  if (name.length > DESCRIBE_NAME_MAX_CHARS) {
    name = name.slice(0, DESCRIBE_NAME_MAX_CHARS).trimEnd();
  }
  // A name that ends mid-sentence on "," or ":" reads like a typo in the list.
  name = name.replace(/[\s.,;:!?\-]+$/u, "");
  if (!name) return "";
  return name.charAt(0).toUpperCase() + name.slice(1);
}

/** Store the prompt for the canvas of {@code workflowId}. False = not stored. */
export function writeDescribeHandoff(
  workflowId: string,
  prompt: string,
  storage: HandoffStorage | null = defaultStorage(),
): boolean {
  const value = normalizeDescribePrompt(prompt);
  if (!workflowId || !value || !storage) return false;
  try {
    storage.setItem(keyFor(workflowId), value);
    return true;
  } catch {
    return false;
  }
}

/** The pending prompt for this workflow, or null. Does not clear it. */
export function readDescribeHandoff(
  workflowId: string,
  storage: HandoffStorage | null = defaultStorage(),
): string | null {
  if (!workflowId || !storage) return null;
  try {
    const value = normalizeDescribePrompt(storage.getItem(keyFor(workflowId)));
    return value || null;
  } catch {
    return null;
  }
}

export function clearDescribeHandoff(
  workflowId: string,
  storage: HandoffStorage | null = defaultStorage(),
): void {
  if (!workflowId || !storage) return;
  try {
    storage.removeItem(keyFor(workflowId));
  } catch {
    // Nothing to do: the entry dies with the tab anyway.
  }
}

/**
 * Read and clear in one step - what the canvas wants, so a reload or a
 * back/forward visit never rebuilds (and overwrites) the graph a second time.
 */
export function takeDescribeHandoff(
  workflowId: string,
  storage: HandoffStorage | null = defaultStorage(),
): string | null {
  const value = readDescribeHandoff(workflowId, storage);
  clearDescribeHandoff(workflowId, storage);
  return value;
}
