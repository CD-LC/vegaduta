// Why "attach a data file for analysis" is not on offer for this agent.
//
// Core puts an optional sentence on the agent response at
//   modalities.sandboxDataUnavailableReason
// (AgentModalitiesView). The key is OMITTED unless the server's
// app.sandbox.data-analysis.enabled flag is on AND sandboxData is false, so on
// a default deployment - and against an older core - everything here yields
// null and no UI renders.
//
// The sentence is shown verbatim. It is only inspected to pick where the
// "fix it" link goes: the agent's own Capabilities section (Code execution is
// off for this agent), the workspace's Providers page (no E2B connected), or
// nowhere at all (a temporary failure, or a sentence that names neither -
// there is nothing for the person to go and fix).
// Pure and DOM-free so it is unit-testable.

/** DOM id of the workbench's Capabilities section (CollapsibleSection anchorId). */
export const CAPABILITIES_ANCHOR_ID = "agent-capabilities-section";

export type SandboxDataHintTarget = "capabilities" | "providers" | "none";

export type SandboxDataHintModel = {
  /** Server sentence, trimmed. Display as-is. */
  reason: string;
  target: SandboxDataHintTarget;
  /** Where the link navigates when the target is not already on this page; null = no link. */
  href: string | null;
};

/**
 * The server's reason, or null when there is none to show: no modalities
 * block, key absent/blank/non-string, or sandboxData actually true (a reason
 * next to an available sandbox would be a contradiction - trust the boolean).
 */
export function readSandboxDataUnavailableReason(agent: any): string | null {
  const m = agent?.modalities;
  if (!m || m.sandboxData === true) return null;
  const raw = m.sandboxDataUnavailableReason;
  if (typeof raw !== "string") return null;
  const reason = raw.trim();
  return reason.length > 0 ? reason : null;
}

/**
 * "temporarily" = nothing to fix, no link (the workspace's E2B may well be
 * connected already). "Code execution" = the per-agent toggle. "E2B" =
 * workspace setup on the Providers page. Anything else: no link rather than
 * a guess.
 */
export function sandboxDataHintTarget(reason: string): SandboxDataHintTarget {
  if (/temporarily/i.test(reason)) return "none";
  if (/code execution/i.test(reason)) return "capabilities";
  if (/\bE2B\b/i.test(reason)) return "providers";
  return "none";
}

export function sandboxDataHint(agent: any): SandboxDataHintModel | null {
  const reason = readSandboxDataUnavailableReason(agent);
  if (!reason) return null;
  const target = sandboxDataHintTarget(reason);
  if (target === "none") return { reason, target, href: null };
  if (target === "providers") return { reason, target, href: "/providers" };
  const id = typeof agent?.id === "string" && agent.id ? agent.id : null;
  return {
    reason,
    target,
    // /agents reads ?agentId= to pre-select the agent (app/agents/page.tsx).
    href: id ? `/agents?agentId=${encodeURIComponent(id)}` : "/agents",
  };
}

// ---- dismissal memory -------------------------------------------------
// The hint sits under the composer of every agent it applies to, on both
// /playground and the workbench. Dismissing it is remembered for this browser
// tab (sessionStorage) per agent + reason, so it does not come back on every
// reload or agent switch - but a DIFFERENT reason for the same agent shows.

const DISMISSED_PREFIX = "vd_sandbox_hint_dismissed_v1:";

type HintStore = Pick<Storage, "getItem" | "setItem">;

function tabStore(): HintStore | null {
  try {
    return typeof window !== "undefined" ? window.sessionStorage : null;
  } catch {
    // Storage blocked (privacy mode / sandboxed frame).
    return null;
  }
}

export function sandboxHintDismissKey(agentId: string | null | undefined, reason: string): string {
  return `${DISMISSED_PREFIX}${agentId || "-"}:${reason}`;
}

export function isSandboxHintDismissed(
  agentId: string | null | undefined,
  reason: string,
  store: HintStore | null = tabStore()
): boolean {
  try {
    return store?.getItem(sandboxHintDismissKey(agentId, reason)) === "1";
  } catch {
    return false;
  }
}

export function rememberSandboxHintDismissed(
  agentId: string | null | undefined,
  reason: string,
  store: HintStore | null = tabStore()
): void {
  try {
    store?.setItem(sandboxHintDismissKey(agentId, reason), "1");
  } catch {
    // Quota / blocked storage: the dismissal still holds for this mount.
  }
}
