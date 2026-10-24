"use client";

import AgentRunBudgetField from "./AgentRunBudgetField";
import AgentApprovalModeCard from "./AgentApprovalModeCard";
import AgentToolPoliciesCard from "./AgentToolPoliciesCard";

/**
 * The per-agent safety controls that sit under the autonomy selector in the workbench: the spending cap per run,
 * the tool argument policies and the plain-language note on when this agent's tool use waits for a person.
 * A single mount point, so the workbench (a very large file) gains no lines when another optional per-agent
 * control joins them. Each control hides itself when the deployment does not offer it.
 */
export default function AgentSafetyControls({
  agentId,
  locked = false,
  lockedTooltip,
}: {
  agentId: string | null | undefined;
  /** The agent is edit-locked (free-tier lock); the controls are read-only. */
  locked?: boolean;
  lockedTooltip?: string;
}) {
  return (
    <>
      <AgentRunBudgetField agentId={agentId} locked={locked} lockedTooltip={lockedTooltip} />
      <AgentToolPoliciesCard agentId={agentId} locked={locked} lockedTooltip={lockedTooltip} />
      <AgentApprovalModeCard agentId={agentId} />
    </>
  );
}
