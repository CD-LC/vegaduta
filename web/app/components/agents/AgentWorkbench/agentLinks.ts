import { CONTROL_AREAS, type ControlAreaId } from "../../controls/controlAreas";
import { turnTimelineHref } from "../ViewTurnLink";

// Pure model for the links from an agent's "Behavior & limits" tab to the pages that hold the rest of what shapes
// that one agent: its prompts, its approvals and its turn timeline. Each link opens the target page already scoped
// to the agent. Who may use the target page comes from CONTROL_AREAS (one list of scopes), so a link that would only
// answer 403 is not offered. No React and no i18n: the label keys live under the "agentBehavior" namespace.

/** The prompt registry opened at agent scope for one agent (the page validates the id and falls back to the workspace). */
export function agentPromptsHref(agentId: string): string {
  return `/admin/instructions?agent=${encodeURIComponent(agentId)}`;
}

/** The approvals queue filtered to one agent. */
export function agentApprovalsHref(agentId: string): string {
  return `/admin/approvals?agent=${encodeURIComponent(agentId)}`;
}

/**
 * The turn timeline with the agent filled in. The journal lists turns per chat session, so the page still asks for a
 * session id (or a turn id) before it shows anything; the agent id is what is carried over.
 */
export function agentTurnsHref(agentId: string): string {
  return turnTimelineHref(agentId, null);
}

export interface AgentRelatedLink {
  id: "prompts" | "approvals" | "turns";
  href: string;
  /** Message keys under "agentBehavior": visible text and the longer accessible name (which carries the agent name). */
  labelKey: string;
  ariaKey: string;
  /** Scope that the target page requires; undefined = every member. */
  perm?: string;
}

const AREA_FOR: Record<AgentRelatedLink["id"], ControlAreaId> = {
  prompts: "prompts",
  approvals: "approvals",
  turns: "turns",
};

const permOf = (id: ControlAreaId) => CONTROL_AREAS.find((a) => a.id === id)?.perm;

/** The three agent-scoped links, in display order. */
export function agentRelatedLinks(agentId: string): AgentRelatedLink[] {
  return [
    { id: "prompts", href: agentPromptsHref(agentId), labelKey: "links.prompts", ariaKey: "links.promptsAria", perm: permOf(AREA_FOR.prompts) },
    { id: "approvals", href: agentApprovalsHref(agentId), labelKey: "links.approvals", ariaKey: "links.approvalsAria", perm: permOf(AREA_FOR.approvals) },
    { id: "turns", href: agentTurnsHref(agentId), labelKey: "links.turns", ariaKey: "links.turnsAria", perm: permOf(AREA_FOR.turns) },
  ];
}

/** Keeps the links this person may open. Without a known permission set (no auth context) nothing is hidden. */
export function reachableAgentLinks(
  links: AgentRelatedLink[],
  can: { isAdmin: boolean; hasPermission: (name: string) => boolean } | null | undefined
): AgentRelatedLink[] {
  if (!can) return links;
  return links.filter((l) => !l.perm || can.isAdmin || can.hasPermission(l.perm));
}

/**
 * The link to open one agent from the agents index. A request for a place inside the agent that arrived without an
 * agent (/agents?section=behavior&focus=<setting>, from the "Where do I control...?" page) rides along, so choosing the
 * agent lands on the right tab and setting instead of dropping the request.
 */
export function openAgentHref(agentId: string, wanted: { section?: string | null; focus?: string | null } = {}): string {
  const qs = new URLSearchParams({ agentId });
  if (wanted.section) qs.set("section", wanted.section);
  if (wanted.focus) qs.set("focus", wanted.focus);
  return `/agents?${qs.toString()}`;
}
