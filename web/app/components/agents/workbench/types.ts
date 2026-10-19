import type { WorkbenchAgent, WorkbenchCollection, WorkbenchProvider } from "../AgentWorkbench/types";

export type WorkbenchProps = {
  agents: WorkbenchAgent[];
  // Optional, and not passed by agents/page.tsx yet: false = "the agent list
  // has not loaded (or failed to)", so `agents` being [] proves nothing and
  // the checklist holds back its starting-points block. Undefined = unknown,
  // which the checklist handles by making no claim about the agent count.
  agentsLoaded?: boolean;
  providers: WorkbenchProvider[];
  collections: WorkbenchCollection[];
  selectedAgentId: string | null;
  // null = "clear the selection / start a draft" - see startDraft().
  onSelect: (agentId: string | null) => void;
  onChanged: () => void;
  // /agents/templates deep-links back here as /agents?template=<key>.
  initialTemplateKey?: string | null;
  // /agents/quality's "Agent Lab" topic link deep-links here as
  // /agents?agentId=<id>&lab=open - the overlay is a session-bound surface
  // inside the playground, so it can only be opened through it.
  initialLabOpen?: boolean;
  startInDraft?: boolean; // open on a blank new-agent draft instead of auto-selecting the latest agent
};
