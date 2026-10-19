"use client";

import Select from "../../../Select";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { ScopedExpressionField } from "../../ExpressionField";
import { type WorkflowNodeConfig } from "../types";

const CRM_LEAD_ACTIONS = ["upsert", "stage", "activity"] as const;
const CRM_LEAD_STAGES = ["NEW", "CONTACTED", "REPLIED", "QUALIFIED", "PROPOSAL", "WON", "LOST"] as const;
const CRM_LEAD_ACTIVITY_TYPES = ["NOTE", "EMAIL_SENT", "EMAIL_REPLY", "CALL", "TASK"] as const;

export function CrmLeadNodeConfig({
  config,
  setConfig,
}: {
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("crmLeadNode");
  const action = config.action || "upsert";
  const text = (key: keyof WorkflowNodeConfig, labelKey: string, placeholder?: string) => (
    <div className="field">
      <Label>{t(labelKey)}</Label>
      <ScopedExpressionField
        field={String(key)}
        value={(config[key] as string | undefined) || ""}
        placeholder={placeholder}
        onChange={(v) => setConfig({ [key]: v } as Partial<WorkflowNodeConfig>)}
        ariaLabel={t(labelKey)}
      />
    </div>
  );
  const stageOptions = [
    ...(action === "upsert" ? [{ value: "", label: t("stageKeep") }] : []),
    ...CRM_LEAD_STAGES.map((s) => ({ value: s, label: s })),
  ];

  return (
    <>
      <p className="ax-wf-empty">{t("hint")}</p>

      <div className="field">
        <Label>{t("actionLabel")}</Label>
        <Select
          value={action}
          onChange={(e: any) => setConfig({ action: e.target.value })}
          options={CRM_LEAD_ACTIONS.map((a) => ({ value: a, label: t(`action_${a}`) }))}
        />
      </div>

      {action !== "upsert" && text("leadId", "leadIdLabel", t("leadIdPlaceholder"))}
      {text("email", "emailLabel", "{{email}}")}

      {action === "upsert" && (
        <>
          {text("name", "nameLabel", "{{name}}")}
          {text("phone", "phoneLabel")}
          {text("company", "companyLabel")}
          {text("title", "titleLabel")}
          {text("source", "sourceLabel", "workflow")}
          {text("score", "scoreLabel")}
          {text("value", "valueLabel")}
          {text("owner", "ownerLabel")}
          {text("tags", "tagsLabel", t("tagsPlaceholder"))}
          {text("notes", "notesLabel")}
        </>
      )}

      {action !== "activity" && (
        <div className="field">
          <Label>{t("stageLabel")}</Label>
          <Select
            value={config.stage || ""}
            onChange={(e: any) => setConfig({ stage: e.target.value })}
            options={stageOptions}
          />
        </div>
      )}
      {action === "stage" && text("note", "noteLabel")}

      {action === "activity" && (
        <>
          <div className="field">
            <Label>{t("activityTypeLabel")}</Label>
            <Select
              value={config.activityType || "NOTE"}
              onChange={(e: any) => setConfig({ activityType: e.target.value })}
              options={CRM_LEAD_ACTIVITY_TYPES.map((a) => ({ value: a, label: a }))}
            />
          </div>
          <div className="field">
            <Label>{t("activityBodyLabel")}</Label>
            <ScopedExpressionField
              field="activityBody"
              multiline
              placeholder={t("activityBodyPlaceholder")}
              value={config.activityBody || ""}
              onChange={(v) => setConfig({ activityBody: v })}
              ariaLabel={t("activityBodyLabel")}
            />
          </div>
        </>
      )}
    </>
  );
}
