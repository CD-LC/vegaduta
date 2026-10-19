"use client";

import { Users } from "lucide-react";
import TeammateAccessPanel from "../../sharing/TeammateAccessPanel";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";

export default function ShareSection() {
  const { isDraft, t, selectedAgent } = useWorkbench();
  return (
    <>
      {/* Cross-user sharing (core/sharing) - named-teammate grants on
              THIS agent, distinct from ShareAgentPanel's anonymous public
              link (mounted inside AgentPlayground on the right column).
              Only meaningful for a real, already-created agent - a draft
              has no resourceId yet. TeammateAccessPanel itself renders
              nothing for a non-owner (see its own header comment for how
              it decides that without a client-side ownership field), so
              this section silently disappears for anyone the backend's
              own 404-anti-enumeration check doesn't consider the owner. */}
      {isDraft && (
        <WorkbenchSection.SavedOnly
          icon={<Users size={13} />}
          label={t("sharingSectionLabel")}
          cat="var(--cat-assistant)"
          what="teammate sharing needs a saved agent."
        />
      )}
      {!isDraft && (
        /* Merge note (2026-07-29): this section arrived from main while the
               redesign branch was renaming CollapsibleSection's `accent` (raw
               Tailwind pairs) to `cat` (a design token). Git auto-merged both
               sides WITHOUT a conflict and left this call site on the old API -
               caught by tsc, not by the merge. Mapped to --cat-assistant to keep
               the violet it originally had. Note it is the SEVENTH section
               against six category hues, so this one repeats a hue; per the
               design contract that is acceptable because the icon, not the
               colour, is the primary cue (Users here vs Bot on the identity
               section). */
        <WorkbenchSection icon={<Users size={13} />} label={t("sharingSectionLabel")} cat="var(--cat-assistant)">
          <TeammateAccessPanel resourceType="AGENT" resourceId={selectedAgent.id} />
        </WorkbenchSection>
      )}
    </>
  );
}
