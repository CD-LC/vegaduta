// Effective access level for one agent (AgentResponse.accessLevel: EDITOR / VIEWER / RUNNER, nullable).
// The server enforces it regardless; this only disables controls the caller cannot use, instead of
// letting them fail with a toast. A missing or null level (older core, owner) means editable.
export type AgentAccessLevel = "EDITOR" | "VIEWER" | "RUNNER";

type WithAccess = { accessLevel?: string | null; editable?: boolean } | null | undefined;

/** True when write actions (edit, delete, publish) are available. */
export function canEditAgent(agent: WithAccess): boolean {
  const level = agent?.accessLevel;
  return level == null || level === "EDITOR";
}

/** True when the agent's configuration may be read (duplicate copies it). RUNNER may only run it. */
export function canViewAgentConfig(agent: WithAccess): boolean {
  const level = agent?.accessLevel;
  return level == null || level === "EDITOR" || level === "VIEWER";
}
