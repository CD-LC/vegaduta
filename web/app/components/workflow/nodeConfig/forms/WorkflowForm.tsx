"use client";

import { useEffect, useState } from "react";
import { api } from "../../../../lib/api";
import Select from "../../../Select";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { errorMessage } from "../../../../lib/errors";
import { type WorkflowNodeConfig, type WorkflowSummary } from "../types";

/**
 * Trigger Workflow node config - #846 workflow-triggers-workflow. Fetches
 * the tenant's own workflow list the same way the /workflows list page
 * does (api.listWorkflows), then filters out this canvas's own workflow id
 * client-side so a self-reference can never even be picked from the
 * dropdown - a UX-level belt to go with the server-side braces
 * (OrchestratorService#validate's save-time check, and
 * WorkflowNodeExecutor's runtime call-stack check for indirect cycles).
 */
export function WorkflowTriggerNodeConfig({
  workflowId,
  config,
  setConfig,
}: {
  workflowId: string;
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError("");
    api
      .listWorkflows()
      .then((list) => {
        if (cancelled) return;
        setWorkflows(Array.isArray(list) ? list : []);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(errorMessage(e, t("loadWorkflowsError")));
        setWorkflows([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [t]);

  const options = workflows.filter((w) => w.id !== workflowId).map((w) => ({ value: w.id, label: w.name }));

  return (
    <>
      <div className="field">
        <Label>{t("workflowToTriggerLabel")}</Label>
        <Select
          value={config.targetWorkflowId || ""}
          onChange={(e) => setConfig({ targetWorkflowId: e.target.value })}
          placeholder={loading ? t("loadingWorkflowsPlaceholder") : t("selectWorkflowPlaceholder")}
          options={options}
        />
        {error && <p className="ax-wf-empty">{error}</p>}
        {!loading && !error && options.length === 0 && <p className="ax-wf-empty">{t("noOtherWorkflowsHint")}</p>}
      </div>
      <p className="ax-wf-empty">{t("workflowTriggerHint")}</p>
    </>
  );
}

// Mirrors WorkflowNodeExecutor#executeCodeNode's operation switch exactly -
// a fixed library of safe, built-in transforms, not arbitrary code (see
// WorkflowNodeType's javadoc for why sandboxed code execution was ruled out).
// Only structural values live here (plain data, not a component) - labels
// are resolved via useTranslations inside CodeNodeConfig below, same pattern
// as nodeDefs.js's key-builder helpers.
