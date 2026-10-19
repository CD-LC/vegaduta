"use client";

import { useState } from "react";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { ScopedExpressionField } from "../../ExpressionField";
import { TEXTAREA_CLASS } from "../shared";
import { type WorkflowNodeConfig, type AgentSummary, type CollectionSummary, type AgentInputMapping } from "../types";

/**
 * Workflow -> Agent structured parameter passing. Hand-built form over a
 * known, fixed field set (query/context_data/knowledge_collection_ids on the
 * way in; a single output-field picker on the way out) rather than a generic
 * JSON-Schema-driven form renderer - the shape AgentSchemaService generates
 * server-side is small and fixed, so a generic builder would be
 * over-engineering for it. Collapsed by default and writes nothing until a
 * workflow author actually opens it - an untouched Agent node's config stays
 * exactly {agentId} as it always has, so every workflow saved before this
 * feature existed keeps behaving identically (see
 * WorkflowNodeExecutor#executeAgentNode's own backward-compat javadoc for the
 * server-side half of this guarantee). approval_required is NOT handled
 * here - it's the existing "Require approval" checkbox rendered just above
 * this component for the agent node type, which already writes to this
 * node's top-level requiresApproval config key.
 */
export function AgentStructuredInputConfig({
  agent,
  collections,
  config,
  setConfig,
}: {
  agent: AgentSummary | undefined;
  collections: CollectionSummary[] | null;
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const mapping = config.inputMapping || {};
  const [open, setOpen] = useState(Object.keys(mapping).length > 0);

  function setMapping(patch: Partial<AgentInputMapping>) {
    setConfig({ inputMapping: { ...mapping, ...patch } });
  }

  const allowedCollectionIds = agent?.allowedKnowledgeCollectionIds || [];
  const knowledgeCapable = !!agent?.knowledgeBaseEnabled || allowedCollectionIds.length > 0;
  const candidateCollections =
    allowedCollectionIds.length > 0
      ? (collections || []).filter((c: CollectionSummary) => allowedCollectionIds.includes(c.id))
      : collections || [];
  const selectedCollectionIds = Array.isArray(mapping.knowledge_collection_ids) ? mapping.knowledge_collection_ids : [];

  return (
    <div className="field" style={{ marginTop: "0.5rem" }}>
      <Button type="button" variant="ghost" size="sm" style={{ paddingLeft: 0 }} onClick={() => setOpen((v) => !v)}>
        {open ? t("structuredInputHide") : t("structuredInputShow")}
      </Button>
      {open && (
        <div style={{ marginTop: "0.4rem", display: "flex", flexDirection: "column", gap: "0.6rem" }}>
          <div className="field">
            <Label>{t("structuredQueryLabel")}</Label>
            <ScopedExpressionField
              field="inputMapping.query"
              value={mapping.query ?? "{{input}}"}
              onChange={(v) => setMapping({ query: v })}
              placeholder="{{input}}"
              ariaLabel={t("structuredQueryLabel")}
            />
            <p className="ax-wf-empty">{t("structuredQueryHint")}</p>
          </div>
          <div className="field">
            <Label>{t("structuredContextDataLabel")}</Label>
            <textarea
              rows={3}
              className={TEXTAREA_CLASS}
              placeholder={t("structuredContextDataPlaceholder")}
              value={mapping.context_data || ""}
              onChange={(e) => setMapping({ context_data: e.target.value })}
            />
          </div>
          {knowledgeCapable && candidateCollections.length > 0 && (
            <div className="field">
              <Label>{t("structuredKnowledgeCollectionsLabel")}</Label>
              <select
                multiple
                className={TEXTAREA_CLASS}
                value={selectedCollectionIds}
                onChange={(e) =>
                  setMapping({ knowledge_collection_ids: Array.from(e.target.selectedOptions).map((o) => o.value) })
                }
              >
                {candidateCollections.map((c: CollectionSummary) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="field">
            <Label>{t("structuredOutputFieldLabel")}</Label>
            <Select
              value={config.outputMapping || "result_text"}
              onChange={(e) => setConfig({ outputMapping: e.target.value })}
              options={[
                { value: "result_text", label: t("structuredOutputResultText") },
                { value: "confidence", label: t("structuredOutputConfidence") },
                { value: "sources", label: t("structuredOutputSources") },
                { value: "tool_calls", label: t("structuredOutputToolCalls") },
                { value: "metadata", label: t("structuredOutputMetadata") },
              ]}
            />
            <p className="ax-wf-empty">{t("structuredOutputHint")}</p>
          </div>
        </div>
      )}
    </div>
  );
}

