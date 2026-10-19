"use client";

import { Cpu } from "lucide-react";
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { cn } from "../../../lib/utils";
import { poolLabel } from "../../../lib/personalInstall";
import Select from "../../Select";
import { SetupHint } from "../../setup/SetupLinks";
import { Button } from "../../ui/button";
import IntelligenceSourceControl from "../IntelligenceSourceControl";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";

export default function RoutingSection() {
  const {
    deviceRoutingSummary,
    edgeBrowser,
    onPlatformLlm,
    isFreePlan,
    activeProviders,
    switchByokBusy,
    switchToPoolBusy,
    handleSwitchToByok,
    handleSwitchToPlatformPool,
    setIntelSource,
    isDraft,
    platformLlmInfo,
    selectedAgent,
    locked,
    modelEditing,
    setModelEditing,
    openModelEditor,
    routingPreview,
    edgeRoutingPolicyDraft,
    setEdgeRoutingPolicyDraft,
    edgeRoutingPolicyBusy,
    handleSaveEdgeRoutingPolicy,
    t,
  } = useWorkbench();
  return (
    <>
      {/* Device & routing — its own collapsed section (QA-05, 2026-09-24).
       * IntelligenceSourceControl, the per-agent routing policy and the
       * live routing preview used to sit expanded inside Model &
       * generation, which put the whole on-device model catalogue on
       * first paint. Collapsed by default with a one-line summary in the
       * header; the content and every gate inside are unchanged. The
       * outer edgeEnabled() gate keeps the section itself off for tenants
       * without the flag, exactly as the old wrapper did. */}
      {true && (
        <WorkbenchSection
          icon={<Cpu size={13} />}
          label="Device & routing"
          cat="var(--cat-automation)"
          actions={<WorkbenchSection.Summary>{deviceRoutingSummary}</WorkbenchSection.Summary>}
        >
          <div className="space-y-3">
            {!edgeEnabled() && (
              <p
                aria-disabled="true"
                className="m-0 rounded-md border border-dashed border-border px-3 py-2 text-[12.5px] text-muted-foreground"
              >
                Edge AI is not enabled in this build, so on-device routing and the routing policy below are read-only.
                Set NEXT_PUBLIC_EDGE_AGENTS=1 and rebuild the web app to turn them on.
              </p>
            )}
            <p className="m-0 text-[11px] text-muted-foreground">
              Browser support:{" "}
              {edgeBrowser
                ? `WebGPU ${edgeBrowser.webgpu ? "available" : "not available"}, WebAssembly ${edgeBrowser.wasm ? "available" : "not available"}`
                : "checking…"}
              . Chat on this workspace is served by{" "}
              {onPlatformLlm ? poolLabel("the shared platform pool", "the local model (Ollama) on this machine") : "your own provider key"}.
            </p>
            {/* "What drives this agent's intelligence" 3-way
             * override (platform pool / on-device / own provider). Flag-gated
             * on edgeEnabled() same as every other edge surface; renders
             * nothing when NEXT_PUBLIC_EDGE_AGENTS is unset, leaving the
             * platform-llm note above as the only control (unchanged
             * behavior for tenants without the flag). Shared by both the
             * creation form and the edit form since it sits outside the
             * isDraft ternary, same placement as the onPlatformLlm note. */}
            {edgeEnabled() && (
              <IntelligenceSourceControl
                isFreePlan={isFreePlan}
                onPlatformLlm={onPlatformLlm}
                activeProviderCount={activeProviders.length}
                switchToByokBusy={switchByokBusy}
                switchToPoolBusy={switchToPoolBusy}
                onSwitchToByok={handleSwitchToByok}
                onSwitchToPool={handleSwitchToPlatformPool}
                onSourceSelected={setIntelSource}
                /* A3: hosted-context clarity — the panel shows the model
                 * CURRENTLY serving this agent whenever a hosted source is
                 * selected (the pool's real model on platform LLM, the
                 * agent's own saved model on BYOK), and a Change affordance
                 * that opens the SAME model editor as the "Change model"
                 * button above — only when changing is actually allowed
                 * (saved agent, paid plan, not edit-locked), so the panel
                 * never dangles a control the pencil button would refuse. */
                hostedModelLabel={
                  isDraft
                    ? null
                    : onPlatformLlm
                      ? (platformLlmInfo?.available && platformLlmInfo.model) || null
                      : selectedAgent.model || null
                }
                onChangeHostedModel={
                  !isDraft && !onPlatformLlm && !locked
                    ? () => (modelEditing ? setModelEditing(false) : openModelEditor())
                    : null
                }
                /* Wave 2 fix (audit finding P0): threaded through so the
                 * panel can show an honest divergence note when this
                 * agent's server policy overrides the selection above —
                 * see IntelligenceSourceControl's own prop doc. Both
                 * null for a draft agent (no id, no preview to compare). */
                agentId={isDraft ? null : selectedAgent.id}
                routingPreview={routingPreview}
              />
            )}
            {/* Audit finding P1: the per-agent EDGE_ONLY/HOSTED_ONLY
             * server routing policy had a complete backend
             * (PATCH /api/agents/{id}/edge-routing-policy) but no
             * product surface anywhere to view or set it. Simple
             * labeled-select-plus-save, same minimal pattern the
             * voice/video model editors use for their own busy state —
             * saved agents only (a draft has no id yet to PATCH).
             * Copy corrected 2026-09-19: core only STORES this value and
             * serves it to the browser (EdgeAgentsConfigController); the
             * browser's tierRouter is the only thing that applies it.
             * Workflows, channels, schedules and the chat API never read
             * it, so the words must not say "enforced server-side". */}
            {
              <div className={cn("rounded-md border border-border p-2.5", (!edgeEnabled() || isDraft) && "opacity-60")}>
                <label
                  htmlFor="agent-edge-routing-policy-select"
                  className="mb-1 block text-[11px] font-medium uppercase tracking-wide text-muted-foreground"
                >
                  Agent routing policy
                </label>
                {isDraft ? (
                  <p className="mb-1.5 text-[11px] font-medium text-muted-foreground">
                    Save the agent first to set its routing policy.
                  </p>
                ) : !edgeEnabled() ? (
                  <p className="mb-1.5 text-[11px] font-medium text-muted-foreground">
                    Edge AI is not enabled in this build.
                  </p>
                ) : null}
                <p className="mb-1.5 text-[11px] leading-relaxed text-muted-foreground">
                  Overrides the selection above for this agent only. The setting is saved with the agent, so every
                  browser that opens it reads the same value, but it is the browser that applies it: the server does not
                  check it. Applies to chat in this browser. Workflows, channels and scheduled runs always use the
                  server model. Edge only makes this browser refuse a chat turn instead of sending it to the server
                  model; Hosted only makes this browser skip the on-device model.
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  <Select
                    id="agent-edge-routing-policy-select"
                    name="agent-edge-routing-policy-select"
                    value={edgeRoutingPolicyDraft}
                    onChange={(e) => setEdgeRoutingPolicyDraft(e.target.value)}
                    disabled={isDraft || !edgeEnabled()}
                    options={[
                      { value: "", label: "Platform default (device preference decides)" },
                      { value: "EDGE_ONLY", label: "Edge only — browser chat stays on the device" },
                      { value: "HOSTED_ONLY", label: "Hosted only — browser chat uses the server model" },
                    ]}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={
                      isDraft ||
                      !edgeEnabled() ||
                      edgeRoutingPolicyBusy ||
                      edgeRoutingPolicyDraft === ((selectedAgent && selectedAgent.edgeRoutingPolicy) || "")
                    }
                    onClick={handleSaveEdgeRoutingPolicy}
                  >
                    {edgeRoutingPolicyBusy ? "Saving…" : "Save"}
                  </Button>
                </div>
              </div>
            }
            {/* Part 4 requirement 4 — the live routing PREVIEW ("Your request
             * will be processed by {X}"), distinct from intelSource above
             * (the stored preference): this is routingState.ts's
             * previewRouting() actually resolving device capabilities right
             * now, so it can diverge from what was just selected (e.g.
             * "On-device" picked but no local model is ready yet, so this
             * turn would really fall back to Hosted). Re-runs on every
             * routing-relevant state change via the useEffect above, keyed
             * on intelSource/onPlatformLlm/activeProviders.length. */}
            {edgeEnabled() && routingPreview && (
              <p className="mb-0 text-xs text-muted-foreground">
                {t("routingPreviewLabel", { label: routingPreview.label })}
              </p>
            )}
            <SetupHint targets={["edge", "providers"]} lead="Configure on-device models and provider keys:" />
          </div>
        </WorkbenchSection>
      )}
    </>
  );
}
