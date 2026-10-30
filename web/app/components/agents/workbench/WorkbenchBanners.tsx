"use client";

import { AlertTriangle } from "lucide-react";
import { AGENT_TEMPLATES } from "../../agentTemplates";
import { Notice } from "../AgentWorkbench/Notice";
import CreateAgentChecklist from "../guide/CreateAgentChecklist";
import { useWorkbench } from "./context";
import { useTranslations } from "../../../lib/i18n";

export default function WorkbenchBanners() {
    const tHC = useTranslations("hcComponentsAgentsWorkbenchWorkbenchBanners");
  const {
    tenantSettingsError,
    tenantSettingsNoticeDismissed,
    fetchTenantSettings,
    setTenantSettingsNoticeDismissed,
    atAgentLimit,
    isDraft,
    t,
    agentLimit,
    error,
    success,
    selectedAgent,
    name,
    systemPrompt,
    newAgentMode,
    effectiveMode,
    knowledgeBaseEnabled,
    collections,
    activeTemplateKey,
    agents,
    agentsLoaded,
    busy,
    handleCreate,
    setSystemPrompt,
    setName,
    applyStarterTemplate,
    applyTemplate,
  } = useWorkbench();
  return (
    <>
      {tenantSettingsError && !tenantSettingsNoticeDismissed && (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-lg border px-3 py-2 text-sm"
          style={
            {
              ["--state" as any]: "var(--state-error)",
              color: "var(--state)",
              background: "color-mix(in srgb, var(--state) 11%, transparent)",
              borderColor: "color-mix(in srgb, var(--state) 38%, transparent)",
            } as any
          }
        >
          <AlertTriangle size={15} className="mt-0.5 shrink-0" />
          <span className="min-w-0 flex-1">
            {tHC("s1")}{" "}
            <button
              type="button"
              onClick={() => fetchTenantSettings()}
              className="font-medium underline underline-offset-2"
            >
              Retry
            </button>
          </span>
          <button
            type="button"
            onClick={() => setTenantSettingsNoticeDismissed(true)}
            aria-label="Dismiss"
            className="shrink-0 hover:opacity-80"
          >
            ×
          </button>
        </div>
      )}
      {atAgentLimit && isDraft && <Notice>{t("agentLimitBanner", { limit: agentLimit })}</Notice>}
      {error && <Notice>{error}</Notice>}
      {success && <Notice variant="success">{success}</Notice>}

      {/* "Create your agent" checklist (2026-09-19, founder: "still users get
          lost in creating agents"). Persistent and collapsible, driven by the
          real draft/agent state below - see guide/CreateAgentChecklist.tsx.
          While the agent list is empty it also carries the starting points
          (template / describe in a sentence / sandbox). Props only; every
          setter it reaches is one this form already uses. */}
      <CreateAgentChecklist
        isDraft={isDraft}
        agentId={selectedAgent?.id ?? null}
        name={isDraft ? name : selectedAgent.name}
        systemPrompt={isDraft ? systemPrompt : selectedAgent.systemPrompt || ""}
        mode={isDraft ? newAgentMode : effectiveMode(selectedAgent)}
        knowledgeBaseEnabled={isDraft ? knowledgeBaseEnabled : !!selectedAgent.knowledgeBaseEnabled}
        collectionCount={collections.length}
        allowedCollectionCount={selectedAgent?.allowedKnowledgeCollectionIds?.length ?? 0}
        allowedToolCount={selectedAgent?.allowedToolNames?.length ?? 0}
        templateKey={activeTemplateKey}
        totalAgents={agents.length}
        agentsLoaded={agentsLoaded}
        saving={busy}
        onSave={handleCreate}
        onApplyDraft={(draft) => {
          // Same rule as applyStarterTemplate: a name the person already
          // typed is never overwritten.
          setSystemPrompt(draft.systemPrompt);
          setName((current) => (current.trim() ? current : draft.name));
        }}
        onPickStarter={applyStarterTemplate}
        onPickTemplate={(key) => {
          const template = AGENT_TEMPLATES.find((x) => x.key === key);
          if (template) applyTemplate(template);
        }}
      />
    </>
  );
}
