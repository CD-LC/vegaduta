"use client";

import { ShieldCheck } from "lucide-react";
import { cn } from "../../../lib/utils";
import McpExposureField from "../../McpExposureField";
import AgentBrowserAutomation from "../AgentBrowserAutomation";
import AgentGuardrailsCard from "../AgentGuardrailsCard";
import AgentHooksCard from "../AgentHooksCard";
import AgentSafetyControls from "../AgentSafetyControls";
import AgentSkillsCard from "../AgentSkillsCard";
import AgentSpecialistsCard from "../AgentSpecialistsCard";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";

export default function AutonomySection() {
  const {
    isDraft,
    behaviourSummary,
    t,
    reasoningDepth,
    locked,
    reasoningDepthBusy,
    handleSetReasoningDepth,
    autonomyTier,
    autonomyTierBusy,
    handleSetAutonomyTier,
    selectedAgent,
  } = useWorkbench();
  return (
    <>
      {/* Reasoning & autonomy — reasoning depth, autonomy tier, guardrails,
       * specialist roles and skills scope. Saved agents only (every card
       * PATCHes by id), collapsed by default with the two live-patched
       * choices echoed in the header (QA-05). */}
      {isDraft ? (
        <WorkbenchSection.SavedOnly
          icon={<ShieldCheck size={13} />}
          label="Reasoning & autonomy"
          cat="var(--cat-analysis)"
          what="reasoning depth, autonomy, guardrails, specialists and skills apply to a saved agent."
        />
      ) : (
        <WorkbenchSection
          icon={<ShieldCheck size={13} />}
          label="Reasoning & autonomy"
          cat="var(--cat-analysis)"
          actions={<WorkbenchSection.Summary>{behaviourSummary}</WorkbenchSection.Summary>}
        >
          {/* Reasoning depth - FAST/PLANNED/CAREFUL/RIGOROUS, existing
           * agents only (the PATCH endpoint needs a saved id; a draft is
           * created on the backend FAST default and can be deepened right
           * after saving). Same explicitly-labeled-pill idiom as the
           * Simple/Manual tuning toggle below, widened to a 2-column card
           * grid so each mode carries its one-line description inline -
           * "what does Rigorous actually do" shouldn't require a docs
           * round-trip. Live-patched on click via
           * handleSetReasoningDepth, no separate Save.
           *
           * Direct feedback (2026-08-12): a locked FREE-tier agent past
           * its 24h grace window used to let every card sit fully
           * clickable, so a click just threw the same "editing is a
           * Premium feature" error/toast on every single try - the same
           * jarring-error experience every OTHER post-creation control
           * here (model/voice/video/TTS provider, fallback, cost
           * routing) had already fixed via the shared `locked` +
           * editLockedTooltip idiom. Wired in the same way here instead
           * of inventing a new pattern. */}
          {!isDraft && (
            <div className="mb-4">
              <div className="mb-1 text-xs text-muted-foreground">{t("reasoningDepthLabel")}</div>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {["FAST", "PLANNED", "CAREFUL", "RIGOROUS"].map((depth) => (
                  <button
                    key={depth}
                    type="button"
                    data-testid={`reasoning-depth-${depth.toLowerCase()}`}
                    aria-pressed={reasoningDepth === depth}
                    title={locked ? t("editLockedTooltip") : undefined}
                    className={cn(
                      "rounded-lg border px-3 py-2 text-left transition-colors",
                      reasoningDepth === depth
                        ? "border-primary bg-primary/10"
                        : "border-border bg-card hover:bg-accent",
                      (reasoningDepthBusy || locked) && "pointer-events-none opacity-50"
                    )}
                    disabled={reasoningDepthBusy || locked}
                    onClick={() => handleSetReasoningDepth(depth)}
                  >
                    <span
                      className={cn(
                        "block text-sm font-medium",
                        reasoningDepth === depth ? "text-foreground" : "text-muted-foreground"
                      )}
                    >
                      {t(`reasoningDepth${depth}`)}
                    </span>
                    <span className="mt-0.5 block text-xs text-muted-foreground">
                      {t(`reasoningDepth${depth}Hint`)}
                    </span>
                  </button>
                ))}
              </div>
              {/* Cost/latency trade-off is stated up front, next to the
                    control, not buried in docs - deeper modes multiply LLM
                    calls (plan/verify passes) and buffer the reply until
                    verification finishes, so live token streaming is off. */}
              <p className="mt-2 text-xs text-muted-foreground">{t("reasoningDepthCostNote")}</p>
            </div>
          )}

          {/* #815 Governance - autonomy tier. Same card-grid shape and the
           * same `locked` + editLockedTooltip idiom as reasoning depth
           * above, because it is the same kind of control: a
           * live-patch-on-click setting with no separate Save. Every word
           * of the descriptions below is backed by code that runs -
           * AgentAutonomyTier drives effectiveMaxStepsFor() and
           * approvalGateModeFor() in AgentToolProvider, nothing else. */}
          {!isDraft && (
            <div className="mb-4">
              <div className="mb-1 text-xs text-muted-foreground">Autonomy</div>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                {[
                  {
                    tier: "SUPERVISED",
                    label: "Supervised",
                    hint: "Every tool call waits for a human to approve it, whatever the workspace policy says. Tighter step ceiling.",
                  },
                  {
                    tier: "SEMI_AUTONOMOUS",
                    label: "Semi-autonomous",
                    hint: "Follows the workspace's approval policy for each action type. The default for every agent.",
                  },
                  {
                    tier: "FULLY_AUTONOMOUS",
                    label: "Fully autonomous",
                    hint: "Never waits for approval, and gets a higher step ceiling. It will act without asking.",
                  },
                ].map(({ tier, label, hint }) => (
                  <button
                    key={tier}
                    type="button"
                    data-testid={`autonomy-tier-${tier.toLowerCase()}`}
                    aria-pressed={autonomyTier === tier}
                    title={locked ? t("editLockedTooltip") : undefined}
                    className={cn(
                      "rounded-lg border px-3 py-2 text-left transition-colors",
                      autonomyTier === tier ? "border-primary bg-primary/10" : "border-border bg-card hover:bg-accent",
                      (autonomyTierBusy || locked) && "pointer-events-none opacity-50"
                    )}
                    disabled={autonomyTierBusy || locked}
                    onClick={() => handleSetAutonomyTier(tier)}
                  >
                    <span
                      className={cn(
                        "block text-sm font-medium",
                        autonomyTier === tier ? "text-foreground" : "text-muted-foreground"
                      )}
                    >
                      {label}
                    </span>
                    <span className="mt-0.5 block text-xs text-muted-foreground">{hint}</span>
                  </button>
                ))}
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                Approval requests land in the workspace&apos;s approvals queue, and a tool call that is waiting is not
                run - the agent is told so and carries on with the rest of the turn.
              </p>
            </div>
          )}

          {!isDraft && selectedAgent && (
            <AgentSafetyControls agentId={selectedAgent.id} locked={!!locked} lockedTooltip={t("editLockedTooltip")} />
          )}

          {!isDraft && selectedAgent && <McpExposureField kind="agents" resourceId={selectedAgent.id} />}

          {/* Per-agent content guardrails. Draft agents have no id to PATCH
           * against yet, hence the same !isDraft gate every other
           * post-creation control here uses. */}
          {!isDraft && selectedAgent && (
            <AgentGuardrailsCard
              agentId={selectedAgent.id}
              locked={!!locked}
              editLockedTooltip={t("editLockedTooltip")}
            />
          )}

          {/* Crew handoff: specialist roles (core /api/agents/{id}/specialties). */}
          {!isDraft && selectedAgent && (
            <AgentSpecialistsCard
              agentId={selectedAgent.id}
              locked={!!locked}
              editLockedTooltip={t("editLockedTooltip")}
            />
          )}

          {/* Agent Skills scope (core /api/agents/{id}/skills). Same !isDraft gate
           * and the same locked + editLockedTooltip obligation as the card above. */}
          {!isDraft && selectedAgent && (
            <AgentSkillsCard agentId={selectedAgent.id} locked={!!locked} editLockedTooltip={t("editLockedTooltip")} />
          )}

          {/* Agent lifecycle hooks (core /api/agents/{id}/hooks). Same !isDraft gate and the same
           * locked + editLockedTooltip obligation as the cards above; self-hides on an older core. */}
          {!isDraft && selectedAgent && (
            <AgentHooksCard agentId={selectedAgent.id} locked={!!locked} editLockedTooltip={t("editLockedTooltip")} />
          )}

          {/* Browser automation: saved logins + recorded tasks (core /api/browser/*). Renders nothing
           * until a deployment switches the feature on, so this card is invisible by default. */}
          {!isDraft && selectedAgent && (
            <AgentBrowserAutomation
              agentId={selectedAgent.id}
              locked={!!locked}
              editLockedTooltip={t("editLockedTooltip")}
            />
          )}
        </WorkbenchSection>
      )}
    </>
  );
}
