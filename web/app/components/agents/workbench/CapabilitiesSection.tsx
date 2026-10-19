"use client";

import { Zap } from "lucide-react";
import { CAPABILITIES_ANCHOR_ID } from "../../../lib/sandboxDataHint";
import { AgentCapabilities } from "../AgentWorkbench/AgentCapabilities";
import { SUB_TEXT } from "../AgentWorkbench/styles";
import CodeIntelligenceSection from "../CodeIntelligenceSection";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";

export default function CapabilitiesSection() {
  const {
    t,
    selectedAgent,
    locked,
    onChanged,
    setSuccess,
    setError,
    isDraft,
    isFreePlan,
    codeIntelToolsAvailable,
    fetchCodeIntelTools,
    editingCodeIntelRepos,
    setEditingCodeIntelRepos,
    codeIntelRepos,
    codeIntelRepoDraft,
    setCodeIntelRepoDraft,
    toggleCodeIntelRepoDraft,
    saveCodeIntelRepoAccess,
    codeIntelReposBusy,
  } = useWorkbench();
  return (
    <>
      {/* Capabilities - the generic, catalog-driven replacement for the
       * retired provider-branded sections (the Sarvam "Translation"
       * tools, the E2B "Sandbox" toggle, the GROK-only "xAI server
       * tools" section, and the code-workspace toggle that used to live
       * in Code Intelligence's header). AgentCapabilities renders
       * whatever GET /api/agents/{id}/capabilities returns for this
       * agent - labels, descriptions, availability and applicability
       * (e.g. GROK-only entries) are all resolved server-side - so a new
       * provider or platform capability needs zero UI here. */}
      <WorkbenchSection
        icon={<Zap size={13} />}
        label={t("capabilitiesLabel")}
        cat="var(--cat-creative)"
        anchorId={CAPABILITIES_ANCHOR_ID}
      >
        <p className={SUB_TEXT}>{t("capabilitiesExplainer")}</p>
        <div className="mt-3">
          <AgentCapabilities
            agentId={selectedAgent?.id ?? null}
            locked={!!locked || selectedAgent?.editable === false}
            onChanged={onChanged}
            onError={(message) => {
              setSuccess(null);
              setError(message);
            }}
          />
        </div>
      </WorkbenchSection>

      {/* Code Intelligence - the code-workspace capability's detail
       * config (the per-agent repo allow-list, PATCH
       * /{agentId}/code-intel-repo-access, migration V103). The on/off
       * opt-in itself moved to the generic Capabilities section above;
       * this section only manages WHICH repos an enabled agent may touch
       * - deny-all until at least one id is granted. The 5 read-only
       * tools (search_code/code_callers/code_callees/code_impact/
       * code_graph_stats) need no per-agent action at all - see
       * AgentToolProvider#resolveCodeIntelTools's javadoc. */}
      <CodeIntelligenceSection
        t={t}
        isDraft={isDraft}
        locked={!!locked}
        isFreePlan={isFreePlan}
        agent={selectedAgent}
        codeIntelToolsAvailable={codeIntelToolsAvailable}
        fetchCodeIntelTools={fetchCodeIntelTools}
        editingRepos={editingCodeIntelRepos}
        setEditingRepos={setEditingCodeIntelRepos}
        repos={codeIntelRepos}
        repoDraft={codeIntelRepoDraft}
        setRepoDraft={setCodeIntelRepoDraft}
        toggleRepoDraft={toggleCodeIntelRepoDraft}
        saveRepoAccess={saveCodeIntelRepoAccess}
        reposBusy={codeIntelReposBusy}
      />
    </>
  );
}
