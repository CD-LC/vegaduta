"use client";

import { Plus, Trash2 } from "lucide-react";
import { type HumanInputField } from "../../nodeDefs";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { TEXTAREA_CLASS } from "../shared";
import { type WorkflowNodeConfig } from "../types";

/**
 * Human Input node (Tier-3) - the structured mid-run form: `message` shown
 * to the human plus the `fields` schema the run snapshots into its
 * workflow_input_request row (key/label/type/required + options for
 * "select"). An empty fields list is legal and means a pure pause/
 * acknowledge step - the engine says so in WorkflowNodeType.HUMAN_INPUT's
 * javadoc, and the drawer mirrors it rather than inventing a stricter rule.
 */
export function HumanInputNodeConfig({
  config,
  setConfig,
}: {
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const fields = config.fields || [];

  function patchField(index: number, patch: Partial<HumanInputField>) {
    setConfig({ fields: fields.map((f, i) => (i === index ? { ...f, ...patch } : f)) });
  }

  function addField() {
    setConfig({
      fields: [...fields, { key: `field${fields.length + 1}`, label: "", type: "text", required: false }],
    });
  }

  function removeField(index: number) {
    setConfig({ fields: fields.filter((_, i) => i !== index) });
  }

  return (
    <>
      <p className="ax-wf-empty">{t("humanInputPauseHint")}</p>
      <div className="field">
        <Label>{t("instructionsForApproverLabel")}</Label>
        <textarea
          rows={3}
          className={TEXTAREA_CLASS}
          placeholder={t("humanInputMessagePlaceholder")}
          value={config.message || ""}
          onChange={(e) => setConfig({ message: e.target.value })}
        />
      </div>

      <div className="field">
        <Label>{t("humanInputFieldsLabel")}</Label>
        {fields.map((field, i) => (
          <div
            key={i}
            style={{
              border: "1px solid var(--surface-border)",
              borderRadius: "0.5rem",
              padding: "0.5rem",
              marginBottom: "0.5rem",
              display: "flex",
              flexDirection: "column",
              gap: "0.35rem",
            }}
          >
            <div className="row" style={{ gap: "0.4rem" }}>
              <Input
                type="text"
                value={field.key}
                placeholder={t("humanInputFieldKeyPlaceholder")}
                onChange={(e) => patchField(i, { key: e.target.value })}
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                onClick={() => removeField(i)}
                aria-label={t("humanInputRemoveField")}
              >
                <Trash2 size={14} />
              </Button>
            </div>
            <Input
              type="text"
              value={field.label}
              placeholder={t("humanInputFieldLabelPlaceholder")}
              onChange={(e) => patchField(i, { label: e.target.value })}
            />
            <div className="row" style={{ gap: "0.4rem", alignItems: "center" }}>
              <Select
                value={field.type || "text"}
                onChange={(e) => patchField(i, { type: e.target.value as HumanInputField["type"] })}
                options={[
                  { value: "text", label: t("humanInputTypeText") },
                  { value: "number", label: t("humanInputTypeNumber") },
                  { value: "select", label: t("humanInputTypeSelect") },
                  { value: "boolean", label: t("humanInputTypeBoolean") },
                ]}
              />
              <label
                className="row"
                style={{ gap: "0.3rem", fontSize: "0.8rem", color: "var(--text-secondary)", whiteSpace: "nowrap" }}
              >
                <input
                  type="checkbox"
                  checked={!!field.required}
                  onChange={(e) => patchField(i, { required: e.target.checked })}
                />
                {t("humanInputRequiredLabel")}
              </label>
            </div>
            {field.type === "select" && (
              <Input
                type="text"
                value={(field.options || []).join(", ")}
                placeholder={t("humanInputOptionsPlaceholder")}
                onChange={(e) =>
                  patchField(i, {
                    options: e.target.value
                      .split(",")
                      .map((o) => o.trim())
                      .filter(Boolean),
                  })
                }
              />
            )}
          </div>
        ))}
        <Button type="button" variant="outline" size="sm" onClick={addField}>
          <Plus size={14} /> {t("humanInputAddField")}
        </Button>
        <p className="ax-wf-empty">{t("humanInputFieldsHint")}</p>
      </div>
    </>
  );
}

// Keypad characters Twilio's DTMF detection can report - mirrors the
// [0-9*#] set VoiceDtmfMappingService already validates against.
