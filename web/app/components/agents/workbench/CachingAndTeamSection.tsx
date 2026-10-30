"use client";

import { Database, Users } from "lucide-react";
import AgentAdvancedPanels from "../AgentAdvancedPanels";
import AgentTeamSwarmCard from "../AgentTeamSwarmCard";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";
import { useTranslations } from "../../../lib/i18n";

export default function CachingAndTeamSection() {
    const tHC = useTranslations("hcComponentsAgentsWorkbenchCachingAndTeamSection");
  const { isDraft, cachingSummary, selectedAgent, locked, t, onChanged } = useWorkbench();
  return (
    <>
      {/* Caching, sharing & knowledge - always open; saved agents only. */}
      {isDraft ? (
        <WorkbenchSection.SavedOnly
          icon={<Database size={13} />}
          label={tHC("s1")}
          cat="var(--cat-data)"
          what="response caching, memory sharing, collaborators and the knowledge-base toggle are saved on the agent."
        />
      ) : (
        <WorkbenchSection
          icon={<Database size={13} />}
          label={tHC("s1")}
          cat="var(--cat-data)"
          actions={<WorkbenchSection.Summary>{cachingSummary}</WorkbenchSection.Summary>}
        >
          <AgentAdvancedPanels
            agent={selectedAgent}
            disabled={!!locked}
            disabledReason={locked ? t("editLockedTooltip") : undefined}
            onSaved={() => onChanged()}
          />
        </WorkbenchSection>
      )}

      {/* Team / Swarm: 2+ collaborators = parallel delegation; presets. Saved agents only. */}
      {isDraft ? (
        <WorkbenchSection.SavedOnly
          icon={<Users size={13} />}
          label={t("teamSwarmTitle")}
          cat="var(--cat-assistant)"
          what="a team is built from a saved agent and its collaborators."
        />
      ) : (
        <WorkbenchSection
          icon={<Users size={13} />}
          label={t("teamSwarmTitle")}
          cat="var(--cat-assistant)"
          actions={
            <WorkbenchSection.Summary>
              {(selectedAgent.collaboratorAgentIds?.length ?? 0) >= 2
                ? t("teamSwarmActive", { count: selectedAgent.collaboratorAgentIds.length })
                : t("teamSwarmNotTeam")}
            </WorkbenchSection.Summary>
          }
        >
          <AgentTeamSwarmCard
            agent={selectedAgent}
            locked={!!locked}
            editLockedTooltip={t("editLockedTooltip")}
            onSaved={() => onChanged()}
          />
        </WorkbenchSection>
      )}
    </>
  );
}
