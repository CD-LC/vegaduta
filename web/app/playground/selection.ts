// Which agent the Playground shows (prod signed-in pass 2026-10-05, bug 10).
//
// The page only ever READ ?agent=; picking an agent in the dropdown changed
// component state and nothing else. So a reload - including the reload in the
// middle of a streaming answer that the resumable-stream feature exists for -
// came back on the first agent in the list, and the resumed reply stayed
// invisible until the person reselected the agent they had been talking to.
//
// The selection now lives in the URL (?agent=<id>, so a link is shareable and
// a reload is lossless) with localStorage as the fallback for a bare
// /playground visit. Both are read for the FIRST render, so the right agent
// is already selected when the agent list arrives and ChatPanel's resume
// check runs against it - never against a placeholder first agent.

export const PLAYGROUND_AGENT_PARAM = "agent";
export const PLAYGROUND_AGENT_STORAGE_KEY = "playground.selectedAgent";

export function readStoredAgentId(): string {
  try {
    return window.localStorage.getItem(PLAYGROUND_AGENT_STORAGE_KEY) || "";
  } catch {
    return ""; // storage blocked (private mode, policy) - the URL still works
  }
}

export function storeAgentId(agentId: string): void {
  try {
    if (agentId) window.localStorage.setItem(PLAYGROUND_AGENT_STORAGE_KEY, agentId);
    else window.localStorage.removeItem(PLAYGROUND_AGENT_STORAGE_KEY);
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
 * the current selection (which carries the stored id on first load), then the
 * first agent. An id that is not in the list (deleted agent, another
 * workspace's link) is never kept.
 */
export function resolveAgentId(
  agents: ReadonlyArray<{ id: string }>,
  queryId: string | null | undefined,
  currentId: string | null | undefined
): string {
  const has = (id: string | null | undefined): id is string => !!id && agents.some((a) => a.id === id);
  if (has(queryId)) return queryId;
  if (has(currentId)) return currentId;
  return agents[0]?.id || "";
}

/** `href` with ?agent= set to `agentId` (removed when empty); every other
 * query parameter and the hash are preserved. Returns path + search + hash. */
export function withAgentParam(href: string, agentId: string): string {
  const url = new URL(href, "http://x");
  if (agentId) url.searchParams.set(PLAYGROUND_AGENT_PARAM, agentId);
  else url.searchParams.delete(PLAYGROUND_AGENT_PARAM);
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
