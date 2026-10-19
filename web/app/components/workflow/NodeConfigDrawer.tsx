"use client";

import { useMemo } from "react";
import type { CSSProperties } from "react";
import { X as CloseIcon } from "lucide-react";
import { nodeDef, nodeLabelKey, nodeDescriptionKey, expressionFieldsOf } from "./nodeDefs";
import { useNodeDefTranslations } from "./useNodeDefTranslations";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import StepTestSlot from "./StepTestSlot";
import PinBanner from "./PinBanner";
import { ExpressionScopeContext } from "./ExpressionField";
import { ExpressionFocusProvider } from "./ExpressionFocus";
import DataPicker from "./DataPicker";
import NodeErrorStrategyEditor from "./NodeErrorStrategyEditor";
import { resolveNodeForm } from "./nodeConfig/registry";
import type { NodeConfigDrawerProps, WorkflowNodeConfig } from "./nodeConfig/types";

// AXON re-theme (visual-consistency pass): shadows the legacy Tier-1/2/3
// tokens the shared .field/.badge/button/input/select/textarea rules (and
// the shared Select component) read from, scoped to this drawer's own
// root element only - no shared selector or globals.css rule is touched,
// so every generic form control rendered inside here just picks up the
// same dark HUD look already used by WorkflowCanvas.tsx's .ax-wf-* rules
// via ordinary CSS variable cascade.
type InspectorStyle = CSSProperties & {
  "--text-muted"?: string;
  "--text-secondary"?: string;
  "--text-primary"?: string;
  "--text-faint"?: string;
  "--surface-card"?: string;
  "--surface-border"?: string;
  "--surface-border-strong"?: string;
  "--surface-hover"?: string;
};

/**
 * Right-hand inspector for whichever node is selected on the canvas.
 * Deliberately dumb: it only ever calls `onChange(nodeId, patch)` with a
 * partial config/label update - WorkflowCanvas owns the actual node array
 * and is the only thing that mutates @xyflow/react state, so this component
 * never has to know about edges, positions, or persistence.
 */
export default function NodeConfigDrawer({
  node,
  workflowId,
  agents,
  collections,
  devices,
  triggerCount,
  onChange,
  onDelete,
  onClose,
}: NodeConfigDrawerProps) {
  const tNodeDefs = useNodeDefTranslations();
  const t = useTranslations("nodeConfigDrawer");
  const nodeIdForScope = node?.id ?? "";
  const scope = useMemo(() => ({ workflowId, nodeId: nodeIdForScope }), [workflowId, nodeIdForScope]);

  if (!node) {
    return (
      <div className="workflow-inspector-empty" style={{ padding: "16px" }}>
        <p className="ax-wf-empty">{t("emptyStateHint")}</p>
      </div>
    );
  }

  const def = nodeDef(node.type);
  const defLabel = tNodeDefs(nodeLabelKey(def.type));
  const defDescription = tNodeDefs(nodeDescriptionKey(def.type));
  const config = node.data.config || {};

  function setConfig(patch: Partial<WorkflowNodeConfig>) {
    onChange(node.id, { config: { ...config, ...patch } });
  }

  return (
    <ExpressionScopeContext.Provider value={scope}>
      <ExpressionFocusProvider>
        <div
          className="workflow-inspector"
          style={
            {
              display: "flex",
              flexDirection: "column",
              height: "100%",
              overflow: "hidden",
              "--text-muted": "var(--ax-mid)",
              "--text-secondary": "var(--ax-mid)",
              "--text-primary": "var(--ax-text)",
              "--text-faint": "var(--ax-dim)",
              "--surface-card": "var(--ax-panel2)",
              "--surface-border": "var(--ax-border)",
              "--surface-border-strong": "var(--ax-border2)",
              "--surface-hover": "var(--ax-chip)",
            } as InspectorStyle
          }
        >
          {/* Pinned head row (node-type badge + close) - outside the scroll
          region so the node's identity stays visible while the config
          scrolls. The X hides itself at >=900px inside the studio shell
          (.wf-node-close in workflow-studio.css): the docked panel head
          already carries the collapse control, so this would be a second
          close affordance stacked on it. It stays for the <900px
          off-canvas drawer, where it's the only way out. */}
          <div className="flex flex-none items-center justify-between gap-2 border-b border-border px-4 py-2.5">
            <Badge variant="outline" className="gap-1.5" style={{ borderColor: `${def.color}66`, color: def.color }}>
              <span className="size-1.5 rounded-full" style={{ background: def.color }} />
              {defLabel}
            </Badge>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="wf-node-close"
              onClick={onClose}
              title={t("close")}
              aria-label={t("close")}
            >
              <CloseIcon size={16} />
            </Button>
          </div>
          <div
            className="workflow-inspector-scroll"
            style={{ flex: 1, minHeight: 0, overflowY: "auto", padding: "12px 16px 16px" }}
          >
            <PinBanner nodeId={node.id} nodeType={node.type} />

            {expressionFieldsOf(node.type).length > 0 && <DataPicker workflowId={workflowId} nodeId={node.id} />}

            <div className="field">
              <Label>{t("labelField")}</Label>
              <Input
                type="text" aria-label={t("labelField")}
                value={node.data.label || ""}
                onChange={(e) => onChange(node.id, { label: e.target.value })}
                placeholder={defLabel}
              />
            </div>

            {!def.supported && (
              <p className="ax-wf-empty">
                {defDescription}
                {t("notYetExecutedSuffix")}
              </p>
            )}

            {(() => {
              const Form = resolveNodeForm(node.type);
              return Form ? (
                <Form
                  nodeId={node.id}
                  nodeType={node.type}
                  workflowId={workflowId}
                  config={config}
                  setConfig={setConfig}
                  agents={agents}
                  collections={collections}
                  devices={devices}
                />
              ) : null;
            })()}

            {(node.type === "agent" || node.type === "output") && (
              <label
                className="row"
                style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}
              >
                <input
                  type="checkbox"
                  checked={!!config.requiresApproval}
                  onChange={(e) => setConfig({ requiresApproval: e.target.checked })}
                />
                {t("requireApprovalLabel")}
              </label>
            )}

            {/* Per-node resilience (Tier-3) - retryAttempts overrides the
             * workflow-level default for THIS node only (0-5, clamped
             * server-side); the failure path itself is the red "error" port on
             * the node's bottom edge - drag it to the fallback node. Trigger
             * and Output are excluded deliberately: a trigger never executes
             * (it IS the entry) and the engine never retries an output node. */}
            {node.type !== "trigger" && node.type !== "output" && (
              <div className="field" style={{ marginTop: "0.5rem" }}>
                <Label>{t("retryAttemptsLabel")}</Label>
                <Input
                  type="number"
                  min={0}
                  max={5}
                  value={config.retryAttempts ?? ""}
                  placeholder={t("retryAttemptsPlaceholder")}
                  onChange={(e) =>
                    setConfig({
                      retryAttempts:
                        e.target.value === "" ? undefined : Math.max(0, Math.min(5, Number(e.target.value))),
                    })
                  }
                />
                <p className="ax-wf-empty">{t("retryAttemptsHint")}</p>
              </div>
            )}

            {/* wfcontrol: onError (fail | continue | branch) + bounded retry with backoff; self-hides for trigger/wait. */}
            <NodeErrorStrategyEditor nodeType={node.type} config={config} setConfig={setConfig} />

            {/* Single-node test run (renders nothing without the canvas's StepTestContext or on an older core). */}
            <StepTestSlot nodeId={node.id} />
          </div>

          <div className="row" style={{ padding: "12px 16px", borderTop: "1px solid var(--ax-border)", flexShrink: 0 }}>
            <Button
              type="button"
              variant="outline"
              onClick={() => onDelete(node.id)}
              disabled={node.type === "trigger" && (triggerCount ?? 1) <= 1}
              className="border-destructive/50 text-destructive hover:bg-destructive/10 hover:text-destructive"
            >
              {t("deleteNode")}
            </Button>
          </div>
          {node.type === "trigger" && (triggerCount ?? 1) <= 1 && (
            <p className="ax-wf-empty" style={{ padding: "0 16px 12px" }}>
              {t("triggerRequiredHint")}
            </p>
          )}
        </div>
      </ExpressionFocusProvider>
    </ExpressionScopeContext.Provider>
  );
}
