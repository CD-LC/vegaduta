"use client";

import { useState } from "react";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { TEXTAREA_CLASS } from "../shared";
import { type WorkflowNodeConfig } from "../types";

/**
 * Sub-Flow node (Tier-3) - the whole embedded body graph lives in
 * `config.body` as raw {nodes, edges} JSON (the same wire shape the outer
 * graph saves). A nested canvas editor is deliberately out of scope - the
 * drawer's job is a tolerant JSON textbox with a parse-check, same honest
 * "author the advanced thing as JSON" posture argsJson already set for
 * the Tool node.
 */
export function SubFlowNodeConfig({
  config,
  setConfig,
}: {
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const bodyGraph = typeof config.body === "object" && config.body !== null ? config.body : { nodes: [], edges: [] };
  // Local draft so an in-progress (temporarily invalid) JSON edit doesn't
  // get clobbered by the round-trip through config on every keystroke.
  const [bodyDraft, setBodyDraft] = useState(() => JSON.stringify(bodyGraph, null, 2));
  const [bodyError, setBodyError] = useState("");

  function commitBody(raw: string) {
    setBodyDraft(raw);
    try {
      const parsed = JSON.parse(raw);
      if (
        parsed == null ||
        typeof parsed !== "object" ||
        Array.isArray(parsed) ||
        !Array.isArray(parsed.nodes) ||
        !Array.isArray(parsed.edges)
      ) {
        setBodyError(t("subFlowBodyShapeError"));
        return;
      }
      setBodyError("");
      setConfig({ body: parsed });
    } catch {
      setBodyError(t("subFlowBodyParseError"));
    }
  }

  return (
    <>
      <p className="ax-wf-empty">{t("subFlowHint")}</p>
      <div className="field">
        <Label>{t("loopListFieldLabel")}</Label>
        <Input
          type="text"
          value={config.itemsField || ""}
          placeholder={t("loopListFieldPlaceholder")}
          onChange={(e) => setConfig({ itemsField: e.target.value })}
        />
      </div>
      <div className="field">
        <Label>{t("maxIterationsLabel")}</Label>
        <Input
          type="number"
          min={1}
          max={100}
          value={config.maxIterations ?? 25}
          onChange={(e) => setConfig({ maxIterations: Number(e.target.value) })}
        />
      </div>
      <div className="field">
        <Label>{t("subFlowEntryLabel")}</Label>
        <Input
          type="text"
          value={config.entryNodeId || ""}
          placeholder={t("subFlowEntryPlaceholder")}
          onChange={(e) => setConfig({ entryNodeId: e.target.value })}
        />
        <p className="ax-wf-empty">{t("subFlowEntryHint")}</p>
      </div>
      <div className="field">
        <Label>{t("subFlowBodyLabel")}</Label>
        <textarea
          rows={8}
          className={TEXTAREA_CLASS}
          style={{ fontFamily: "monospace", fontSize: "0.78rem" }}
          value={bodyDraft}
          onChange={(e) => commitBody(e.target.value)}
          spellCheck={false}
        />
        {bodyError ? (
          <p className="ax-wf-empty" style={{ color: "var(--ax-red, #dc2626)" }}>
            {bodyError}
          </p>
        ) : (
          <p className="ax-wf-empty">{t("subFlowBodyHint")}</p>
        )}
      </div>
    </>
  );
}

// "CRM Lead" node (crm_lead) - form for WorkflowCrmLeadNodeHandler's config.
// Strings live in the self-contained "crmLeadNode" en.json namespace. Every
// text field is templated server-side ({{input}} / {{field}}), so the inputs
// are plain strings, not validated here.
