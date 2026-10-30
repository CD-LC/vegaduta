"use client";

import { Wrench } from "lucide-react";
import { cn } from "../../../lib/utils";
import { SetupHint } from "../../setup/SetupLinks";
import AgentTrifectaWarning from "../AgentTrifectaWarning";
import { SUB_TEXT } from "../AgentWorkbench/styles";
import { ToolsGrid } from "../AgentWorkbench/ToolsGrid";
import { GUIDE_ANCHORS } from "../guide/agentChecklist";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";
import { useTranslations } from "../../../lib/i18n";

export default function ToolsSection() {
  const tHC = useTranslations("hcComponentsAgentsWorkbenchToolsSection");
  const {
    t,
    isDraft,
    newAgentMode,
    setNewAgentMode,
    toggleMode,
    modeBusy,
    effectiveMode,
    selectedAgent,
    trifectaRefreshKey,
    autonomyTier,
    handleSetAutonomyTier,
    autonomyTierBusy,
    locked,
    trifectaBlock,
    toolsResolved,
    toolBusyName,
    toggleTool,
  } = useWorkbench();
  return (
    <>
      {/* Tools / MCP — collapsible; the auto-select/manual toggle moves to
              the top of the section body. */}
      <WorkbenchSection
        icon={<Wrench size={13} />}
        label={t("toolsMcpLabel")}
        cat="var(--cat-automation)"
        className="flex-1"
        anchorId={GUIDE_ANCHORS.tools}
        tourId="workbench-tools"
      >
        <SetupHint
          targets={["tools", "mcp", "secrets", "integrations"]}
          lead="Connect tools and credentials:"
          className="mb-2"
        />
        <div className="mb-3 flex flex-wrap items-center gap-2">
          {isDraft ? (
            <div className="grid min-w-[260px] grid-cols-2 gap-2">
              <button
                type="button"
                className={cn(
                  "rounded-lg border px-3 py-2 text-xs font-medium transition-colors",
                  newAgentMode === "SIMPLE"
                    ? "border-primary bg-primary/10 text-foreground"
                    : "border-border bg-card text-muted-foreground hover:bg-accent"
                )}
                onClick={() => setNewAgentMode("SIMPLE")}
              >
                {t("autoSelect")}
              </button>
              <button
                type="button"
                className={cn(
                  "rounded-lg border px-3 py-2 text-xs font-medium transition-colors",
                  newAgentMode === "ADVANCED"
                    ? "border-primary bg-primary/10 text-foreground"
                    : "border-border bg-card text-muted-foreground hover:bg-accent"
                )}
                onClick={() => setNewAgentMode("ADVANCED")}
              >
                {t("manualLabel")}
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="flex items-center gap-2 text-xs font-medium text-muted-foreground disabled:opacity-50"
              onClick={toggleMode}
              disabled={modeBusy}
            >
              {t("autoSelectToggle")}
              <span
                className={`ax-toggle ax-toggle-lg ax-toggle-violet${effectiveMode(selectedAgent) === "SIMPLE" ? " ax-toggle-on" : ""}`}
              />
            </button>
          )}
        </div>

        {isDraft && (
          <p className={SUB_TEXT}>
            {newAgentMode === "SIMPLE" ? t("toolsAutoSelectDraftHint") : t("toolsManualDraftHint")}
          </p>
        )}

        {!isDraft && selectedAgent && (
          <AgentTrifectaWarning
            agentId={selectedAgent.id}
            refreshKey={`${trifectaRefreshKey}|${effectiveMode(selectedAgent)}|${(selectedAgent.allowedToolNames || []).join(",")}`}
            approvalRequired={autonomyTier === "SUPERVISED"}
            onRequireApproval={() => handleSetAutonomyTier("SUPERVISED")}
            requireApprovalDisabled={autonomyTierBusy || locked}
            blockedMessage={trifectaBlock && trifectaBlock.agentId === selectedAgent.id ? trifectaBlock.message : null}
          />
        )}

        {!isDraft && effectiveMode(selectedAgent) === "SIMPLE" && (
          <p className={SUB_TEXT}>
            {t("toolsAutoSelectLiveHint")} {tHC("s1")}
          </p>
        )}

        {!isDraft && effectiveMode(selectedAgent) === "SIMPLE" && (
          <div {...({ inert: true } as Record<string, unknown>)} className="pointer-events-none opacity-60">
            <ToolsGrid
              agentId={selectedAgent.id}
              resolved={toolsResolved}
              selected={new Set(selectedAgent.allowedToolNames || [])}
              busyName={toolBusyName}
              onToggle={() => {}}
            />
          </div>
        )}

        {!isDraft && effectiveMode(selectedAgent) === "ADVANCED" && (
          <ToolsGrid
            agentId={selectedAgent.id}
            resolved={toolsResolved}
            selected={new Set(selectedAgent.allowedToolNames || [])}
            busyName={toolBusyName}
            onToggle={toggleTool}
          />
        )}
      </WorkbenchSection>
    </>
  );
}
