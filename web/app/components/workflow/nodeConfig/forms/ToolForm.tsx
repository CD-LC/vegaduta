"use client";

import { useEffect, useState } from "react";
import { api } from "../../../../lib/api";
import Select from "../../../Select";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { errorMessage } from "../../../../lib/errors";
import { ScopedExpressionField } from "../../ExpressionField";
import { type WorkflowNodeConfig, type AgentSummary, type AvailableTool } from "../types";

/**
 * Tool node config - #705. Reuses the same `agents` list already passed
 * down to NodeConfigDrawer, then fetches that agent's live available-tools
 * list (built-ins + whatever MCP servers are enabled) via
 * api.listAvailableTools, exactly the same source AgentsPanel's tool-access
 * editor uses - so this list is never a stale frontend copy of what tools
 * exist. Re-fetches whenever the selected agent changes, and clears any
 * previously-picked toolName on agent change so this can never submit a
 * tool name left over from a different agent's list.
 */

export function ToolNodeConfig({
  agents,
  config,
  setConfig,
}: {
  agents: AgentSummary[] | null;
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const [tools, setTools] = useState<AvailableTool[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    if (!config.agentId) {
      setTools([]);
      return undefined;
    }
    setLoading(true);
    setError("");
    api
      .listAvailableTools(config.agentId)
      .then((list) => {
        if (cancelled) return;
        setTools(Array.isArray(list) ? list : []);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(errorMessage(e, t("loadToolsError")));
        setTools([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [config.agentId, t]);

  return (
    <>
      <div className="field">
        <Label>{t("toolNodeAgentLabel")}</Label>
        <Select
          value={config.agentId || ""}
          onChange={(e) => setConfig({ agentId: e.target.value, toolName: "" })}
          placeholder={t("selectAgentPlaceholder")}
          options={(agents || []).map((a: AgentSummary) => ({ value: a.id, label: a.name }))}
        />
      </div>
      {config.agentId && (
        <div className="field">
          <Label>{t("toolLabel")}</Label>
          <Select
            value={config.toolName || ""}
            onChange={(e) => setConfig({ toolName: e.target.value })}
            placeholder={loading ? t("loadingToolsPlaceholder") : t("selectToolPlaceholder")}
            options={tools.map((tl) => ({ value: tl.name, label: tl.name }))}
          />
          {error && <p className="ax-wf-empty">{error}</p>}
          {!loading && !error && tools.length === 0 && <p className="ax-wf-empty">{t("noToolsHint")}</p>}
        </div>
      )}
      <div className="field">
        <Label>{t("callInputLabel")}</Label>
        <ScopedExpressionField
          field="argsJson"
          multiline
          placeholder={t("callInputPlaceholder")}
          value={config.argsJson || ""}
          onChange={(v) => setConfig({ argsJson: v })}
          ariaLabel={t("callInputLabel")}
        />
      </div>
      <p className="ax-wf-empty">{t("toolCallHint")}</p>
    </>
  );
}
