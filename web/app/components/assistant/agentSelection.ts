// Which agent the Assistant talks to (moved from app/playground/selection.ts when the Playground
// folded into /assistant, 2026-10-18; the rules are the Playground's, kept because they were
// hard-won: prod signed-in pass 2026-10-05, bug 10).
//
// The selection lives in the URL (?agent=<id>, so a link is shareable and a reload is lossless)
// with localStorage as the fallback for a bare /assistant visit. Both are read for the FIRST
// render, so the right agent is already selected when the agent list arrives and the resume check
// runs against it - never against a placeholder. An empty selection means the person's own
// Assistant agent (useAssistantAgent), which is also what every old /playground, /jobs and
// /quick-task link without an agent lands on.

export const AGENT_PARAM = "agent";
/** Same key the Playground wrote, so an upgrade keeps the agent the person last used. */
export const AGENT_STORAGE_KEY = "playground.selectedAgent";

export function readStoredAgentId(): string {
  try {
    return window.localStorage.getItem(AGENT_STORAGE_KEY) || "";
  } catch {
    return ""; // storage blocked (private mode, policy) - the URL still works
  }
}

export function storeAgentId(agentId: string): void {
  try {
    if (agentId) window.localStorage.setItem(AGENT_STORAGE_KEY, agentId);
    else window.localStorage.removeItem(AGENT_STORAGE_KEY);
  } catch {
    // Best-effort.
  }
}

/** The id to select before the agent list has loaded: URL first, then the
 * last agent used in this browser. Empty when neither is known. */
export function initialAgentId(queryId: string | null | undefined): string {
  return queryId || readStoredAgentId();
}

/**
 * The id to select once the agent list is known. Precedence: the URL's agent,
 * the current selection (which carries the stored id on first load), then
 * `fallbackId` (the Assistant agent) or the first agent. An id that is not in
 * the list (deleted agent, another workspace's link) is never kept.
 */
export function resolveAgentId(
  agents: ReadonlyArray<{ id: string }>,
  queryId: string | null | undefined,
  currentId: string | null | undefined,
  fallbackId: string | null | undefined = null
): string {
  const has = (id: string | null | undefined): id is string => !!id && agents.some((a) => a.id === id);
  if (has(queryId)) return queryId;
  if (has(currentId)) return currentId;
  if (has(fallbackId)) return fallbackId;
  return agents[0]?.id || "";
}

/** `href` with ?agent= set to `agentId` (removed when empty); every other
 * query parameter and the hash are preserved. Returns path + search + hash. */
export function withAgentParam(href: string, agentId: string): string {
  const url = new URL(href, "http://x");
  if (agentId) url.searchParams.set(AGENT_PARAM, agentId);
  else url.searchParams.delete(AGENT_PARAM);
  return url.pathname + url.search + url.hash;
}

/** Remember the selection: localStorage + the address bar (no navigation,
 * no scroll jump, no new history entry). */
export function persistAgentSelection(agentId: string): void {
  storeAgentId(agentId);
  try {
    const next = withAgentParam(window.location.href, agentId);
    const current = window.location.pathname + window.location.search + window.location.hash;
    if (next !== current) window.history.replaceState(window.history.state, "", next);
  } catch {
    // Best-effort - the in-memory selection is unaffected.
  }
}

/** The /assistant URL an old route redirects to, carrying the agent and the mode it implied. */
export function assistantHref(opts: {
  agentId?: string | null;
  mode?: string | null;
  prompt?: string | null;
  /** Which old route sent the person, for the one-line note the pane shows. */
  from?: "playground" | "jobs" | "quickTask" | null;
}): string {
  const params = new URLSearchParams();
  if (opts.agentId) params.set(AGENT_PARAM, opts.agentId);
  if (opts.mode) params.set("mode", opts.mode);
  if (opts.prompt) params.set("prompt", opts.prompt.slice(0, 600));
  if (opts.from) params.set("from", opts.from);
  const query = params.toString();
  return query ? `/assistant?${query}` : "/assistant";
}
