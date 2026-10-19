"use client";

import { History } from "lucide-react";
import AgentVersionsCard from "../AgentVersionsCard";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";

export default function VersionsSection() {
  const { isDraft, t, selectedAgent, locked, onChanged } = useWorkbench();
  return (
    <>
      {/* Configuration versions (AgentVersionController, V212 + V260 eval
       * gate) - snapshots are taken server-side on every save; the card
       * lists them with label (staging/production) + restore actions.
       * Draft-gated like the sharing section above: no agent id, no
       * /versions to call. onRestored -> onChanged so a restore re-reads
       * the agent the same way every other save does. */}
      {isDraft && (
        <WorkbenchSection.SavedOnly
          icon={<History size={13} />}
          label={t("versionsSectionLabel")}
          cat="var(--cat-analysis)"
          what="configuration versions are snapshotted on save."
        />
      )}
      {!isDraft && selectedAgent && (
        <WorkbenchSection
          icon={<History size={13} />}
          label={t("versionsSectionLabel")}
          cat="var(--cat-analysis)"
          tourId="workbench-versions"
        >
          <AgentVersionsCard
            agentId={selectedAgent.id}
            locked={!!locked}
            editLockedTooltip={t("editLockedTooltip")}
            onRestored={onChanged}
          />
        </WorkbenchSection>
      )}
    </>
  );
}
